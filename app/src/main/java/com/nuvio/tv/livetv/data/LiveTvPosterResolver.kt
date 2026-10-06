package com.nuvio.tv.livetv.data

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.poster.CustomPosterScreen
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.model.supportsExtra
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.ui.LiveTvSearchBridge
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finds the poster Nuvio would show for a program, by searching the user's own addon catalogs
 * (the same catalogs Nuvio's search uses) for the program title. Uses the Home poster settings,
 * so custom posters match what the home screen shows. Only an exact or very close title match is
 * used; a wrong poster is worse than none.
 */
@Singleton
class LiveTvPosterResolver @Inject constructor(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
    private val store: LiveTvPosterStore
) {
    private val cache = boundedCache<String, Result>(4_000)
    private val mutex = Mutex()

    data class Result(val poster: String?)

    /** Whether a program is a movie or a series, and how sure we are. */
    enum class Kind { MOVIE, SERIES }
    /**
     * What kind of title the guide suggests. [strong]: only that kind is searched. Otherwise
     * both are, and [weight] is how much the suggested kind counts when picking between
     * same-named titles (a show on a 24/7 channel counts for a lot more than a guess from length).
     */
    data class TypeHint(val kind: Kind, val strong: Boolean, val weight: Double = 15.0)

    /** What the guide says about the program, used to tell same-named titles apart. */
    data class Clues(
        /** When known (On Demand titles with a TMDB id), the match with this IMDb id wins. */
        val imdbId: String? = null,
        val year: Int? = null,
        val people: List<String> = emptyList(),
        val description: String? = null
    )

    private val matchCache = boundedCache<String, Hit>(3_000)
    @Volatile private var pausedUntil = 0L
    @Volatile private var failuresInARow = 0
    private val noMatch = boundedSet<String>(3_000)

    /**
     * On Demand: finds a provider title in your own addon catalogs, so it opens on Nuvio's
     * details page with your metadata and posters. Null if none of your catalogs has it.
     */
    suspend fun matchFor(title: String, series: Boolean, year: Int?, imdbId: String? = null, urgent: Boolean = false): Hit? {
        // If addons keep failing (rate limits, offline), pause lookups for a minute.
        if (System.currentTimeMillis() < pausedUntil) return null
        val query = LiveTvSearchBridge.cleanTitle(title).ifBlank { title.trim() }
        if (query.length < 2) return null
        val key = normalize(query) + "|" + series + "|" + (year ?: "") + "|" + (imdbId ?: "")
        matchCache[key]?.let { return it }
        if (key in noMatch) return null
        // On Demand has its own small queue, so browsing movies never holds up the guide's posters.
        // Opening a title ([urgent]) doesn't wait behind poster lookups in the queue.
        return (if (urgent) urgentPermits else vodPermits).withPermit {
            val attempt = Attempt()
            val hit = runCatching {
                lookup(query, TypeHint(if (series) Kind.SERIES else Kind.MOVIE, strong = true), Clues(imdbId = imdbId, year = year), attempt)
            }.getOrNull()
            if (hit != null) matchCache[key] = hit else if (attempt.reached) noMatch += key
            if (hit == null && !attempt.reached) {
                if (++failuresInARow >= 5) { pausedUntil = System.currentTimeMillis() + 60_000; failuresInARow = 0 }
            } else failuresInARow = 0
            hit
        }
    }

    /**
     * "Test poster lookup" in settings: what the lookup sees for [title], in plain words, to
     * track down why a poster isn't showing.
     */
    suspend fun diagnose(title: String): String {
        val query = LiveTvSearchBridge.cleanTitle(title).ifBlank { title.trim() }
        val addons = withTimeoutOrNull(8_000) {
            addonRepository.getInstalledAddons().first { it.enabledAddons().isNotEmpty() }
        }?.enabledAddons() ?: return "No addons are installed and enabled, so there's nothing to search."
        val targets = searchTargets(addons)
        if (targets.isEmpty()) {
            return "None of your ${addons.size} addons has a searchable movie or series catalog. Posters come from addon search (Cinemeta, TMDB, AIOMetadata and similar)."
        }
        val lines = ArrayList<String>()
        lines += "Searching \"$query\" in ${targets.size} catalogs (showing up to $MAX_CATALOGS per type):"
        val results = kotlinx.coroutines.coroutineScope {
            targets.take(MAX_CATALOGS * 2).map { (addon, catalog) ->
                async {
                    val r = withTimeoutOrNull(6_000) {
                        catalogRepository.getCatalog(
                            addonBaseUrl = addon.baseUrl, addonId = addon.id, addonName = addon.displayName,
                            catalogId = catalog.id, catalogName = catalog.name, type = catalog.apiType,
                            extraArgs = mapOf("search" to query), posterScreen = CustomPosterScreen.HOME
                        ).first { it !is NetworkResult.Loading }
                    }
                    Triple(addon, catalog, r)
                }
            }.map { it.await() }
        }
        val wanted = normalize(query)
        results.forEach { (addon, catalog, r) ->
            val text = when (r) {
                null -> "timed out"
                is NetworkResult.Error -> "error: ${r.message}"
                is NetworkResult.Success -> {
                    val items = r.data.items
                    val exact = items.firstOrNull { normalize(it.name) == wanted }
                    "${items.size} results" + when {
                        exact == null -> ", no exact title match"
                        exact.poster.isNullOrBlank() -> ", match \"${exact.name}\" but it has no poster"
                        else -> ", match \"${exact.name}\" with a poster ✓"
                    }
                }
                else -> "no answer"
            }
            lines += "• ${addon.displayName} / ${catalog.name} (${catalog.apiType}): $text"
        }
        return lines.joinToString("\n")
    }

    /** Tracks whether a lookup actually reached any catalog (so failures aren't remembered). */
    private class Attempt { @Volatile var reached = false }

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private val inFlight = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<String?>>()
    // A few lookups at once, so one slow title doesn't hold up every other poster.
    private val permits = kotlinx.coroutines.sync.Semaphore(3)
    private val vodPermits = kotlinx.coroutines.sync.Semaphore(2)
    private val requestPermits = kotlinx.coroutines.sync.Semaphore(2)
    private val urgentPermits = kotlinx.coroutines.sync.Semaphore(2)

    suspend fun posterFor(programTitle: String, hint: TypeHint? = null, clues: Clues = Clues()): String? {
        // "Programming" and the like aren't shows: the channel logo is the right picture.
        if (LiveTvRepository.isPlaceholderTitle(programTitle)) return null
        val query = LiveTvSearchBridge.cleanTitle(programTitle).ifBlank { programTitle.trim() }
        if (query.length < 2) return null
        // One lookup per show (not per episode): the key is the title, kind and year.
        val key = normalize(query) + "|" + (hint?.kind ?: "any") + "|" + (hint?.weight?.toInt() ?: 0) + "|" + (clues.year ?: "")
        cache[key]?.let { return it.poster }
        // Remembered from an earlier session: no search needed.
        withContext(kotlinx.coroutines.Dispatchers.IO) { store.get(key) }?.let { saved ->
            val poster = saved.ifEmpty { null }
            cache[key] = Result(poster)
            return poster
        }
        // One lookup per title at a time. Started only after it's registered, so a finished
        // lookup can never be left behind and answer "no poster" forever.
        val fresh = scope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            permits.withPermit { resolve(programTitle, query, hint, clues, key) }
        }
        val job = inFlight.putIfAbsent(key, fresh) ?: fresh.also { d ->
            d.invokeOnCompletion { inFlight.remove(key, d) }
            d.start()
        }
        if (job !== fresh) fresh.cancel()
        return runCatching { job.await() }.getOrNull()
    }

    private suspend fun resolve(programTitle: String, query: String, hint: TypeHint?, clues: Clues, key: String): String? {
        cache[key]?.let { return it.poster }
        if (System.currentTimeMillis() < pausedUntil) return null
        val attempt = Attempt()
        // Titles like "The Game - The Trey Wiggs Taps Back Episode" glue the show and episode
        // together. For series, try the show name first (that's what catalogs know); otherwise
        // the whole title first. Only one extra lookup, and only when the title has a separator.
        val shows = showNameCandidates(programTitle, hint).filter { normalize(it) != normalize(query) }.take(1)
        val attempts = buildList {
            if (hint?.kind == Kind.SERIES) shows.forEach { add(it to true) }
            add(query to false)
            if (hint?.kind != Kind.SERIES) shows.forEach { add(it to true) }
            // 24/7 channels often put a brand in front ("OnePlay Paw Patrol"): if the full name
            // finds nothing, try it without the first word, then the first two.
            if (hint?.weight == SHOW_247_WEIGHT) {
                val words = query.split(' ').filter { it.isNotBlank() }
                for (drop in 1..2) {
                    if (words.size - drop >= 1) {
                        val rest = words.drop(drop).joinToString(" ")
                        if (rest.length >= 3) add(rest to true)
                    }
                }
            }
        }
        var poster: String? = null
        for ((q, isShowName) in attempts) {
            poster = try {
                // For a show name, the guide's year belongs to the episode, so leave it out.
                if (isShowName) lookup(q, hint?.copy(strong = false), clues.copy(year = null), attempt)?.meta?.poster
                else lookup(q, hint, clues, attempt)?.meta?.poster
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (poster != null) break
        }
        // Only remember "no poster" when the catalogs actually answered. If addons hadn't
        // loaded yet or the network failed, try again next time instead of showing a logo forever.
        if (poster != null || attempt.reached) {
            cache[key] = Result(poster)
            store.put(key, poster) // remembered across restarts
        }
        // Addons not answering (rate limits, offline): back off for a minute.
        if (poster == null && !attempt.reached) {
            if (++failuresInARow >= 5) { pausedUntil = System.currentTimeMillis() + 60_000; failuresInARow = 0 }
        } else failuresInARow = 0
        return poster
    }

    private suspend fun lookup(query: String, hint: TypeHint?, clues: Clues, attempt: Attempt): Hit? {
        // Wait for the installed addons to load (the first value can be an empty placeholder).
        val addons = withTimeoutOrNull(8_000) {
            addonRepository.getInstalledAddons().first { it.enabledAddons().isNotEmpty() }
        }?.enabledAddons() ?: return null
        val all = searchTargets(addons)
        if (hint == null) return lookupIn(query, all.take(MAX_CATALOGS), clues, attempt)
        val preferredType = if (hint.kind == Kind.MOVIE) "movie" else "series"
        val preferred = all.filter { it.second.apiType == preferredType }.take(MAX_CATALOGS)
        // Strong hint (episode number, or the guide says "Movie"): only that kind. That's how a
        // sitcom never ends up with a movie poster.
        if (hint.strong) return lookupIn(query, preferred, clues, attempt)
        // Weak hint (just the show's length): look at both kinds and pick the likeliest. A
        // two-hour reality episode ("Bachelor in Paradise") used to stop at the first movie with
        // the same name (a 1961 film) without ever looking at the show.
        val others = all.filter { it.second.apiType != preferredType }.take(MAX_CATALOGS)
        return lookupIn(query, preferred + others, clues, attempt, preferredType, hint.weight)
    }

    /**
     * Collects every result with the right title, then picks the best one: same release year
     * wins (Total Recall 1990 vs 2012), then shared cast/directors, then how much the
     * descriptions have in common. If the guide gives a year and no result is within a year of
     * it, nothing is shown rather than a poster for the wrong film.
     */
    private suspend fun lookupIn(
        query: String,
        targets: List<Pair<Addon, CatalogDescriptor>>,
        clues: Clues,
        attempt: Attempt
,
        /** A weak preference for "movie" or "series" (from the show's length). */
        preferredType: String? = null,
        preferredWeight: Double = 15.0
    ): Hit? {
        val wanted = normalize(query)
        val exact = LinkedHashMap<String, Hit>()
        // Where each title came in its catalog's search results (addons list the best known /
        // most popular first): used to break ties between same-named titles.
        val rankOf = HashMap<String, Int>()
        var closeMatch: Hit? = null

        // Ask every catalog at once (not one after another), so a slow addon costs a few seconds
        // instead of holding up the poster while each catalog is tried in turn.
        val results = kotlinx.coroutines.coroutineScope {
            targets.map { (addon, catalog) ->
                async {
                    // At most two poster searches hit your addons at any moment, so Nuvio's own
                    // searches and catalogs are never crowded out (or rate-limited) by posters.
                    requestPermits.withPermit {
                        withTimeoutOrNull(6_000) {
                            catalogRepository.getCatalog(
                                addonBaseUrl = addon.baseUrl,
                                addonId = addon.id,
                                addonName = addon.displayName,
                                catalogId = catalog.id,
                                catalogName = catalog.name,
                                type = catalog.apiType,
                                extraArgs = mapOf("search" to query),
                                posterScreen = CustomPosterScreen.HOME
                            ).first { it !is NetworkResult.Loading }
                        }
                    }
                }
            }.map { it.await() }
        }

        for ((i, result) in results.withIndex()) {
            if (result is NetworkResult.Success) attempt.reached = true
            val (addon, catalog) = targets[i]
            val items = (result as? NetworkResult.Success)?.data?.items.orEmpty()
            items.withIndex()
                .filter { (_, it) -> normalize(it.name) == wanted && !it.poster.isNullOrBlank() }
                .forEach { (idx, it) ->
                    val id = it.imdbId ?: it.id
                    exact.putIfAbsent(id, Hit(it, addon.baseUrl, catalog.apiType))
                    rankOf[id] = minOf(rankOf[id] ?: Int.MAX_VALUE, idx)
                }
            if (closeMatch == null) {
                closeMatch = items.take(5).firstOrNull { item ->
                    val n = normalize(item.name)
                    !item.poster.isNullOrBlank() && n.isNotEmpty() &&
                        (n.startsWith(wanted) || wanted.startsWith(n)) &&
                        minOf(n.length, wanted.length) * 10 >= maxOf(n.length, wanted.length) * 7
                }?.let { Hit(it, addon.baseUrl, catalog.apiType) }
            }
        }

        if (exact.isNotEmpty()) {
            // Year, cast and description pick the best of several same-named titles; they never
            // throw away the only match (guides' years are often the airing, not the release).
            return exact.entries.maxByOrNull { (id, hit) ->
                score(hit.meta, clues) + popularity(hit, clues, rankOf[id] ?: 10, preferredType, preferredWeight)
            }!!.value
        }
        val close = closeMatch ?: return null
        if (clues.year != null) {
            val y = yearOf(close.meta)
            if (y != null && kotlin.math.abs(y - clues.year) > 1) return null
        }
        return close
    }

    /**
     * Tie-breakers between same-named titles when the guide gives no year: the kind the show's
     * length suggests, how high the title came in the search results (addons list the best
     * known first), how recent it is (TV guides are mostly current shows and recent films), and
     * whether it's rated. Small next to a matching year, cast or ID, which still decide first.
     */
    private fun popularity(hit: Hit, clues: Clues, rank: Int, preferredType: String?, preferredWeight: Double = 15.0): Double {
        var bonus = 0.0
        if (preferredType != null && hit.type == preferredType) bonus += preferredWeight
        bonus += (8 - rank).coerceAtLeast(0) * 5.0
        if (clues.year == null) {
            val y = yearOf(hit.meta)
            val thisYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            if (y != null) {
                if (y >= thisYear - 25) bonus += 30.0
                else if (y < thisYear - 45) bonus -= 15.0
            }
            // A show that's still running ("2014-") is very likely what's on.
            if (hit.type == "series" && (hit.meta.releaseInfo ?: "").trim().endsWith("-")) bonus += 20.0
        }
        hit.meta.imdbRating?.let { if (it >= 6f) bonus += 10.0 }
        return bonus
    }

    private val aboutCache = boundedCache<String, Hit>(500)
    private val aboutMisses = boundedSet<String>(500)

    /**
     * What a title is (description, poster, year, rating) by its name alone, show or movie: for
     * 24/7 channels, which play one show (or one actor's films) around the clock with no guide.
     */
    suspend fun aboutTitle(name: String): MetaPreview? {
        val key = normalize(name)
        if (key.length < 2 || key in aboutMisses) return null
        aboutCache[key]?.let { return it.meta }
        val hit = requestPermitsAware {
            val hint = TypeHint(Kind.SERIES, strong = false, weight = SHOW_247_WEIGHT)
            lookup(name, hint, Clues(), Attempt())
                // A brand in front ("OnePlay Paw Patrol"): without the first word, then two.
                ?: name.split(' ').filter { it.isNotBlank() }.let { w ->
                    (1..2).asSequence()
                        .filter { w.size - it >= 1 && w.drop(it).joinToString(" ").length >= 3 }
                        .mapNotNull { lookup(w.drop(it).joinToString(" "), hint, Clues(), Attempt()) }
                        .firstOrNull()
                }
        }
        if (hit == null) aboutMisses += key else aboutCache[key] = hit
        return hit?.meta
    }

    private suspend fun <T> requestPermitsAware(block: suspend () -> T): T? =
        runCatching { withTimeoutOrNull(20_000) { block() } }.getOrNull()

    /** A match in one of your addon catalogs: its details page and its poster. */
    data class Hit(val meta: MetaPreview, val addonBaseUrl: String, val type: String)

    private fun score(item: MetaPreview, clues: Clues): Double {
        var score = 0.0
        // The same IMDb id is a certain match.
        if (clues.imdbId != null && (item.imdbId == clues.imdbId || item.id == clues.imdbId)) score += 1_000.0
        val y = yearOf(item)
        if (clues.year != null && y != null) {
            val diff = kotlin.math.abs(y - clues.year)
            score += when (diff) { 0 -> 100.0; 1 -> 80.0; else -> -100.0 }
        }
        if (clues.people.isNotEmpty()) {
            val names = clues.people.map { it.lowercase() }
            val hay = (item.director + item.writer).joinToString(" ").lowercase() + " " + item.description.orEmpty().lowercase()
            score += 30.0 * names.count { n -> n.substringAfterLast(' ').length > 2 && hay.contains(n.substringAfterLast(' ')) }
        }
        val a = words(clues.description)
        val b = words(item.description)
        if (a.isNotEmpty() && b.isNotEmpty()) {
            score += 60.0 * a.intersect(b).size / minOf(a.size, b.size)
        }
        return score
    }

    private fun yearOf(item: MetaPreview): Int? =
        Regex("""(19|20)\d{2}""").find(item.releaseInfo ?: item.released ?: "")?.value?.toIntOrNull()

    private val stopWords = setOf(
        "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "with", "his", "her", "their", "is",
        "are", "was", "who", "that", "this", "from", "by", "as", "at", "into", "after", "when", "while"
    )

    private fun words(text: String?): Set<String> =
        text.orEmpty().lowercase().split(Regex("""[^a-z0-9]+"""))
            .filter { it.length > 3 && it !in stopWords }
            .toSet()

    /** Movie and series catalogs that need nothing but a search term, movies first. */
    private fun searchTargets(addons: List<Addon>): List<Pair<Addon, CatalogDescriptor>> =
        addons.flatMap { addon ->
            addon.catalogs
                .filter { c ->
                    c.supportsExtra("search") &&
                        c.extra.none { it.isRequired && !it.name.equals("search", ignoreCase = true) } &&
                        (c.apiType == "movie" || c.apiType == "series")
                }
                .map { addon to it }
        }

    private fun normalize(s: String): String =
        s.lowercase().replace("&", "and").filter { it.isLetterOrDigit() }

    companion object {
        /** Catalogs searched per kind (movies / series) for one title. */
        private const val MAX_CATALOGS = 3

        /** " - ", " – ", " — " or " | " between a show name and an episode title. */
        private val SHOW_EPISODE_SEPARATOR = Regex("""\s+[-–—|]\s+""")

        /**
         * Possible show names hidden in a combined title, best first. "Show - Episode" always
         * counts; "Show: Episode" only for series, because plenty of films use a colon
         * ("Star Wars: A New Hope").
         */
        fun showNameCandidates(title: String, hint: TypeHint?): List<String> {
            val cleaned = LiveTvSearchBridge.cleanTitle(title).ifBlank { title.trim() }
            val out = LinkedHashSet<String>()
            SHOW_EPISODE_SEPARATOR.split(cleaned).firstOrNull()?.trim()?.let { if (it.length >= 2 && it != cleaned) out += it }
            if (hint?.kind == Kind.SERIES && cleaned.contains(": ")) {
                cleaned.substringBefore(": ").trim().let { if (it.length >= 2) out += it }
            }
            return out.toList()
        }

        /**
         * What to search for in Nuvio ("Find & stream"): for a series with a combined
         * "Show - Episode" title, just the show name, so search finds the show.
         */
        fun searchTitleFor(program: EpgProgram, channel: LiveChannel?): String {
            val hint = typeHint(program, channel)
            if (hint?.kind == Kind.SERIES) showNameCandidates(program.title, hint).firstOrNull()?.let { return it }
            return program.title
        }
        private val movieWords = Regex("""\b(movie|movies|film|films|cinema|feature)\b""", RegexOption.IGNORE_CASE)
        private val seriesWords = Regex(
            """\b(series|sitcom|episode|episodes|soap|soap opera|talk show|reality|news|game show|drama series|comedy series)\b""",
            RegexOption.IGNORE_CASE
        )

        /**
         * Works out movie vs series from the guide: an episode number or the guide's category is a
         * strong signal; failing that, the running time (under about an hour is almost always a
         * series episode, over 80 minutes usually a movie) and the channel's group are weak ones.
         */
        private val TWENTY_FOUR_SEVEN = Regex("""(?i)\b24\s*[/\\|\-x]?\s*7\b""")

        /** A 24/7 channel (by its name or its group). */
        fun is247(channel: LiveChannel): Boolean =
            TWENTY_FOUR_SEVEN.containsMatchIn(channel.name) || TWENTY_FOUR_SEVEN.containsMatchIn(channel.group) ||
                // The original names too: the channel name editor ("Add common") removes "24/7"
                // from what's shown, and groups can be renamed. The playlist's own names are kept
                // in the channel's key ("playlist|tvg-id|name"), its group id and its tvg-name.
                TWENTY_FOUR_SEVEN.containsMatchIn(channel.key) || TWENTY_FOUR_SEVEN.containsMatchIn(channel.groupId) ||
                (channel.tvgName?.let { TWENTY_FOUR_SEVEN.containsMatchIn(it) } ?: false)

        /** How much "it's a show" counts on 24/7 channels (enough to beat obscure same-named films). */
        const val SHOW_247_WEIGHT = 80.0

        fun typeHint(program: EpgProgram?, channel: LiveChannel?): TypeHint? {
            if (program == null) return null
            if (!program.episode.isNullOrBlank()) return TypeHint(Kind.SERIES, strong = true)
            // 24/7 channels nearly always play a TV show around the clock, in blocks of any
            // length: lean strongly to shows, whatever the block length suggests.
            if (channel != null && is247(channel)) return TypeHint(Kind.SERIES, strong = false, weight = SHOW_247_WEIGHT)
            program.category?.let { cat ->
                if (movieWords.containsMatchIn(cat)) return TypeHint(Kind.MOVIE, strong = true)
                if (seriesWords.containsMatchIn(cat)) return TypeHint(Kind.SERIES, strong = true)
            }
            val minutes = (program.stopMs - program.startMs) / 60_000
            channel?.group?.let { g ->
                if (movieWords.containsMatchIn(g) && minutes >= 75) return TypeHint(Kind.MOVIE, strong = false)
            }
            return when {
                minutes in 1..65 -> TypeHint(Kind.SERIES, strong = false)
                minutes >= 80 -> TypeHint(Kind.MOVIE, strong = false)
                else -> null
            }
        }
    }
}
