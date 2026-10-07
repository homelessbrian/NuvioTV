package com.nuvio.tv.livetv.ondemand

import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape

/**
 * The On Demand page, Nuvio-home style: one row per provider group (in your order, with your
 * names, hidden ones left out), shown with whatever home layout Nuvio is set to.
 */
data class VodHomeState(
    val rows: List<CatalogRow> = emptyList(),
    /** Every group, for Manage VOD Groups (including hidden ones). */
    val groups: List<VodGroupEntry> = emptyList(),
    val loading: Boolean = true
)

/** One group as Manage VOD Groups shows it. */
data class VodGroupEntry(val uid: String, val defaultName: String, val name: String, val visible: Boolean, val kind: VodKind)

/** Ids and markers that tie Nuvio's rows and cards back to On Demand. */
object VodHomeIds {
    const val ADDON_ID = "nuvio-iptv-on-demand"
    private const val PREFIX = "nuviovod:"

    fun metaId(uid: String) = PREFIX + uid
    fun uidOf(metaId: String): String? = metaId.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)

    /** A card for a provider title: the matched addon details when found (poster, backdrop,
     *  logo, description, rating), otherwise the provider's own name and picture. */
    fun toMeta(item: VodItem, matched: MetaPreview?, poster: String?, name: String): MetaPreview {
        val type = if (item.kind == VodKind.SERIES) ContentType.SERIES else ContentType.MOVIE
        return matched?.copy(
            id = metaId(item.uid),
            type = type,
            rawType = if (item.kind == VodKind.SERIES) "series" else "movie",
            poster = poster ?: matched.poster ?: item.icon
        ) ?: MetaPreview(
            id = metaId(item.uid),
            type = type,
            name = name,
            poster = poster ?: item.icon,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = item.year?.toString(),
            imdbRating = null,
            genres = item.genre?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
        )
    }

    fun row(uid: String, name: String, kind: VodKind, items: List<MetaPreview>, hasMore: Boolean, page: Int) = CatalogRow(
        addonId = ADDON_ID,
        addonName = "On Demand",
        addonBaseUrl = ADDON_ID,
        catalogId = uid,
        catalogName = name,
        type = if (kind == VodKind.SERIES) ContentType.SERIES else ContentType.MOVIE,
        items = items,
        isLoading = false,
        hasMore = hasMore,
        currentPage = page
    )
}
