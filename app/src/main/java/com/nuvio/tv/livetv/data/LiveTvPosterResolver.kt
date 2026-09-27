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

    suspend fun posterFor(programTitle: String): String? {
        val query = LiveTvSearchBridge.cleanTitle(programTitle).ifBlank { programTitle.trim() }
        if (query.length < 2) return null
        val key = normalize(query)
        cache[key]?.let { return it.poster }
        return mutex.withLock {
            cache[key]?.let { return@withLock it.poster }
            val poster = runCatching { lookup(query) }.getOrNull()
            cache[key] = Result(poster)
            poster
        }
    }

    private suspend fun lookup(query: String): String? {
        val addons = withTimeoutOrNull(5_000) { addonRepository.getInstalledAddons().first() }
            ?.enabledAddons() ?: return null
        val targets = searchTargets(addons).take(MAX_CATALOGS)
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
                        (c.apiType == "movie" || c.apiType == "series" || c.apiType == "tv")
                }
                .map { addon to it }
        }.sortedBy { (_, c) -> if (c.apiType == "movie") 0 else 1 }

    private fun normalize(s: String): String =
        s.lowercase().replace("&", "and").filter { it.isLetterOrDigit() }

    private companion object {
        const val MAX_CATALOGS = 6
    }
}
