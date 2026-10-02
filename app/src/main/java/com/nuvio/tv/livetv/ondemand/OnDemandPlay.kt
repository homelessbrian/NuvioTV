package com.nuvio.tv.livetv.ondemand

import android.content.Context

/**
 * "Play" on a details page opened from On Demand plays the provider's copy straight away.
 * The details page remembers which On Demand title it was opened for (in its own saved
 * state), so opening the same movie from anywhere else in Nuvio still works as normal.
 * Long-press Play (Nuvio's "Play manually") always shows the full stream list.
 */
object OnDemandPlay {
    /** Saved-state key on the details page's back stack entry. */
    const val KEY = "livetv_on_demand_uid"

    data class Resolved(val url: String, val title: String, val poster: String?)

    /** The provider link for [uid] (and, for series, the chosen episode), or null. */
    suspend fun resolve(context: Context, uid: String, season: Int?, episode: Int?): Resolved? {
        val repo = onDemandRepository(context)
        val item = repo.itemByUid(uid) ?: return null
        val name = OnDemandDatabase.displayTitle(item.name)
        return if (item.kind == VodKind.MOVIE) {
            repo.movieUrl(item)?.let { Resolved(it, name, item.icon) }
        } else {
            val info = repo.info(item) ?: return null
            val ep = info.episodes.firstOrNull { it.season == season && it.episode == episode }
                ?: if (season == null && episode == null) info.episodes.firstOrNull() else null
            ep?.let { Resolved(it.url, "$name · S${it.season}E${it.episode}", ep.image ?: item.icon) }
        }
    }
}
