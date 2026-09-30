package com.nuvio.tv.livetv.ondemand

import android.content.Context
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.ProxyHeaders
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow

@EntryPoint
@InstallIn(SingletonComponent::class)
interface OnDemandEntryPoint {
    fun onDemandRepository(): OnDemandRepository
    fun tmdbService(): TmdbService
}

fun onDemandRepository(context: Context): OnDemandRepository =
    EntryPointAccessors.fromApplication(context.applicationContext, OnDemandEntryPoint::class.java).onDemandRepository()

/**
 * Adds a "Watch On Demand" group to the streams Nuvio finds for a movie or episode, when one of
 * your IPTV providers has it. It shows up next to your addon and debrid streams.
 */
object OnDemandStreams {
    const val GROUP_NAME = "Watch On Demand"

    fun withOnDemand(
        context: Context,
        source: Flow<NetworkResult<List<AddonStreams>>>,
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        title: String,
        year: String?
    ): Flow<NetworkResult<List<AddonStreams>>> {
        val entry = runCatching {
            EntryPointAccessors.fromApplication(context.applicationContext, OnDemandEntryPoint::class.java)
        }.getOrNull() ?: return source
        val onDemand = flow {
            emit(emptyList<Stream>())
            val found = runCatching { lookup(entry, type, videoId, season, episode, title, year) }.getOrDefault(emptyList())
            if (found.isNotEmpty()) emit(found)
        }
        return combine(source, onDemand) { result, extra ->
            if (extra.isEmpty() || result !is NetworkResult.Success) result
            else NetworkResult.Success(listOf(AddonStreams(GROUP_NAME, null, extra)) + result.data)
        }
    }

    private suspend fun lookup(
        entry: OnDemandEntryPoint,
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        title: String,
        year: String?
    ): List<Stream> {
        val repo = entry.onDemandRepository()
        repo.start()
        if (!repo.hasContent.value) return emptyList()
        val base = videoId.substringBefore(':')
        val tmdbId = when {
            base.startsWith("tmdb", ignoreCase = true) -> videoId.substringAfter("tmdb:").substringBefore(':').takeIf { it.all(Char::isDigit) }
            base.startsWith("tt") -> runCatching {
                entry.tmdbService().imdbToTmdb(base, if (type.equals("movie", true)) "movie" else "tv")?.toString()
            }.getOrNull()
            else -> null
        }
        val sources = repo.sourcesFor(type, tmdbId, title, year?.take(4)?.toIntOrNull(), season, episode)
        return sources.map { s ->
            Stream(
                name = GROUP_NAME,
                title = s.label,
                description = null,
                url = s.url,
                ytId = null,
                infoHash = null,
                fileIdx = null,
                externalUrl = null,
                behaviorHints = if (s.headers.isEmpty()) null else StreamBehaviorHints(
                    notWebReady = true,
                    bingeGroup = null,
                    countryWhitelist = null,
                    proxyHeaders = ProxyHeaders(request = s.headers, response = null)
                ),
                addonName = GROUP_NAME,
                addonLogo = null
            )
        }
    }
}
