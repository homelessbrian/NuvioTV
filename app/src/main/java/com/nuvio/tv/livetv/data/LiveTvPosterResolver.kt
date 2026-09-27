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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finds the poster Nuvio would show for a programme, by searching the user's own addon catalogs
 * (the same catalogs Nuvio's search uses) for the programme title. Uses the Home poster settings,
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

    /** Whether a programme is a movie or a series, and how sure we are. */
    enum class Kind { MOVIE, SERIES }
    data class TypeHint(val kind: Kind, val strong: Boolean)

    suspend fun posterFor(programTitle: String, hint: TypeHint? = null): String? {
        val query = LiveTvSearchBridge.cleanTitle(programTitle).ifBlank { programTitle.trim() }
        if (query.length < 2) return null
        val key = normalize(query) + "|" + (hint?.kind ?: "any")
        cache[key]?.let { return it.poster }
        return mutex.withLock {
            cache[key]?.let { return@withLock it.poster }
            val poster = runCatching { lookup(query, hint) }.getOrNull()
            cache[key] = Result(poster)
            poster
        }
    }

    private suspend fun lookup(query: String, hint: TypeHint?): String? {
        val addons = withTimeoutOrNull(5_000) { addonRepository.getInstalledAddons().first() }
            ?.enabledAddons() ?: return null
        val all = searchTargets(addons)
        if (hint == null) return lookupIn(query, all.take(MAX_CATALOGS))
        val preferredType = if (hint.kind == Kind.MOVIE) "movie" else "series"
        // Search the right kind of catalog first. With a strong hint (episode number, or the guide
        // says "Movie"), never take the other kind: that's how a sitcom ends up with a movie poster.
        val preferred = all.filter { it.second.apiType == preferredType }.take(MAX_CATALOGS)
        lookupIn(query, preferred)?.let { return it }
        if (hint.strong) return null
        val others = all.filter { it.second.apiType != preferredType }.take(MAX_CATALOGS)
        return lookupIn(query, others)
    }

    private suspend fun lookupIn(query: String, targets: List<Pair<Addon, CatalogDescriptor>>): String? {
        val wanted = normalize(query)
        var closeMatch: MetaPreview? = null

        for ((addon, catalog) in targets) {
            val result = withTimeoutOrNull(8_000) {
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
            val items = (result as? NetworkResult.Success)?.data?.items.orEmpty()
            items.firstOrNull { normalize(it.name) == wanted && !it.poster.isNullOrBlank() }
                ?.let { return it.poster }
            if (closeMatch == null) {
                closeMatch = items.take(5).firstOrNull { item ->
                    val n = normalize(item.name)
                    !item.poster.isNullOrBlank() && n.isNotEmpty() &&
                        (n.startsWith(wanted) || wanted.startsWith(n)) &&
                        minOf(n.length, wanted.length) * 10 >= maxOf(n.length, wanted.length) * 7
                }
            }
        }
        return closeMatch?.poster
    }

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
