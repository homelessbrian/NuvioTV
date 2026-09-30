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
    private val catalogRepository: CatalogRepository
) {
    private val cache = ConcurrentHashMap<String, Result>()
    private val mutex = Mutex()

    data class Result(val poster: String?)

    /** Whether a program is a movie or a series, and how sure we are. */
    enum class Kind { MOVIE, SERIES }
    data class TypeHint(val kind: Kind, val strong: Boolean)

    /** What the guide says about the program, used to tell same-named titles apart. */
    data class Clues(
        val year: Int? = null,
        val people: List<String> = emptyList(),
        val description: String? = null
    )

    private val matchCache = ConcurrentHashMap<String, Hit>()
    @Volatile private var pausedUntil = 0L
    @Volatile private var failuresInARow = 0
    private val noMatch = ConcurrentHashMap.newKeySet<String>()

    /**
     * On Demand: finds a provider title in your own addon catalogs, so it opens on Nuvio's
     * details page with your metadata and posters. Null if none of your catalogs has it.
     */
    suspend fun matchFor(title: String, series: Boolean, year: Int?): Hit? {
        // If addons keep failing (rate limits, offline), pause lookups for a minute.
        if (System.currentTimeMillis() < pausedUntil) return null
        val query = LiveTvSearchBridge.cleanTitle(title).ifBlank { title.trim() }
        if (query.length < 2) return null
        val key = normalize(query) + "|" + series + "|" + (year ?: "")
        matchCache[key]?.let { return it }
        if (key in noMatch) return null
        return permits.withPermit {
            val attempt = Attempt()
            val hit = runCatching {
                lookup(query, TypeHint(if (series) Kind.SERIES else Kind.MOVIE, strong = true), Clues(year = year), attempt)
            }.getOrNull()
            if (hit != null) matchCache[key] = hit else if (attempt.reached) noMatch += key
            if (hit == null && !attempt.reached) {
                if (++failuresInARow >= 5) { pausedUntil = System.currentTimeMillis() + 60_000; failuresInARow = 0 }
            } else failuresInARow = 0
            hit
        }
    }

    /** Tracks whether a lookup actually reached any catalog (so failures aren't remembered). */
    private class Attempt { @Volatile var reached = false }

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private val inFlight = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<String?>>()
    // A few lookups at once, so one slow title doesn't hold up every other poster.
    private val permits = kotlinx.coroutines.sync.Semaphore(3)

    suspend fun posterFor(programTitle: String, hint: TypeHint? = null, clues: Clues = Clues()): String? {
        val query = LiveTvSearchBridge.cleanTitle(programTitle).ifBlank { programTitle.trim() }
        if (query.length < 2) return null
        val key = normalize(query) + "|" + (hint?.kind ?: "any") + "|" + (clues.year ?: "") +
            "|" + (clues.description?.hashCode() ?: 0)
        cache[key]?.let { return it.poster }
        val job = inFlight.getOrPut(key) {
            scope.async {
                try {
                    permits.withPermit { resolve(programTitle, query, hint, clues, key) }
                } finally {
                    inFlight.remove(key)
                }
            }
        }
        return runCatching { job.await() }.getOrNull()
    }

    private suspend fun resolve(programTitle: String, query: String, hint: TypeHint?, clues: Clues, key: String): String? {
        cache[key]?.let { return it.poster }
        val attempt = Attempt()
        // Titles like "The Game - The Trey Wiggs Taps Back Episode" glue the show and episode
        // together. For series, try the show name first (that's what catalogs know); otherwise
        // the whole title first. Only one extra lookup, and only when the title has a separator.
        val shows = showNameCandidates(programTitle, hint).filter { normalize(it) != normalize(query) }.take(1)
        val attempts = buildList {
            if (hint?.kind == Kind.SERIES) shows.forEach { add(it to true) }
            add(query to false)
            if (hint?.kind != Kind.SERIES) shows.forEach { add(it to true) }
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
        if (poster != null || attempt.reached) cache[key] = Result(poster)
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
        // Search the right kind of catalog first. With a strong hint (episode number, or the guide
        // says "Movie"), never take the other kind: that's how a sitcom ends up with a movie poster.
        val preferred = all.filter { it.second.apiType == preferredType }.take(MAX_CATALOGS)
        lookupIn(query, preferred, clues, attempt)?.let { return it }
        if (hint.strong) return null
        val others = all.filter { it.second.apiType != preferredType }.take(MAX_CATALOGS)
        return lookupIn(query, others, clues, attempt)
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
    ): Hit? {
        val wanted = normalize(query)
        val exact = LinkedHashMap<String, Hit>()
        var closeMatch: Hit? = null

        // Ask every catalog at once (not one after another), so a slow addon costs a few seconds
        // instead of holding up the poster while each catalog is tried in turn.
        val results = kotlinx.coroutines.coroutineScope {
            targets.map { (addon, catalog) ->
                async {
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
            }.map { it.await() }
        }

        for ((i, result) in results.withIndex()) {
            if (result is NetworkResult.Success) attempt.reached = true
            val (addon, catalog) = targets[i]
            val items = (result as? NetworkResult.Success)?.data?.items.orEmpty()
            items.filter { normalize(it.name) == wanted && !it.poster.isNullOrBlank() }
                .forEach { exact.putIfAbsent(it.imdbId ?: it.id, Hit(it, addon.baseUrl, catalog.apiType)) }
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
            val best = exact.values.maxByOrNull { score(it.meta, clues) }!!
            if (clues.year != null) {
                val y = yearOf(best.meta)
                if (y != null && kotlin.math.abs(y - clues.year) > 1) return null
            }
            return best
        }
        val close = closeMatch ?: return null
        if (clues.year != null) {
            val y = yearOf(close.meta)
            if (y != null && kotlin.math.abs(y - clues.year) > 1) return null
        }
        return close
    }

    /** A match in one of your addon catalogs: its details page and its poster. */
    data class Hit(val meta: MetaPreview, val addonBaseUrl: String, val type: String)

    private fun score(item: MetaPreview, clues: Clues): Double {
        var score = 0.0
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
        private const val MAX_CATALOGS = 6

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
        fun typeHint(program: EpgProgram?, channel: LiveChannel?): TypeHint? {
            if (program == null) return null
            if (!program.episode.isNullOrBlank()) return TypeHint(Kind.SERIES, strong = true)
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
