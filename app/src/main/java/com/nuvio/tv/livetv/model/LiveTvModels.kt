package com.nuvio.tv.livetv.model

/**
 * A user-added M3U playlist. Xtream Codes logins are stored as a playlist too; the
 * URL is built from the server, username and password when the source is refreshed.
 */
data class PlaylistSource(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val userAgent: String = "",
    /** Also load the EPG URL advertised in the playlist header (url-tvg / x-tvg-url). */
    val useEmbeddedEpg: Boolean = true,
    val xtreamServer: String = "",
    val xtreamUsername: String = "",
    val xtreamPassword: String = "",
    val lastUpdatedMs: Long = 0L,
    val lastError: String? = null,
    val channelCount: Int = 0
) {
    val isXtream: Boolean get() = xtreamServer.isNotBlank()

    fun resolvedUrl(): String {
        if (!isXtream) return url.trim()
        val base = xtreamServer.trim().trimEnd('/')
        return "$base/get.php?username=${xtreamUsername.trim()}&password=${xtreamPassword.trim()}&type=m3u_plus&output=ts"
    }

    fun xtreamEpgUrl(): String? {
        if (!isXtream) return null
        val base = xtreamServer.trim().trimEnd('/')
        return "$base/xmltv.php?username=${xtreamUsername.trim()}&password=${xtreamPassword.trim()}"
    }
}

/** A user-added XMLTV guide (plain or .gz). */
data class EpgSource(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val lastUpdatedMs: Long = 0L,
    val lastError: String? = null,
    val programCount: Int = 0
)

data class CatchupInfo(
    val type: String,
    val source: String?,
    val days: Int
)

data class LiveChannel(
    /** Stable key used for favourites, hidden channels and last-channel memory. */
    val key: String,
    val sourceId: String,
    val sourceName: String,
    val name: String,
    val tvgId: String?,
    val tvgName: String?,
    val logo: String?,
    /** Group id (unique across playlists) and its display title. */
    val groupId: String,
    val group: String,
    val number: Int,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val catchup: CatchupInfo? = null
)

data class EpgProgram(
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    val description: String? = null,
    val category: String? = null,
    val episode: String? = null,
    val icon: String? = null
) {
    fun isLive(nowMs: Long): Boolean = nowMs in startMs until stopMs
    fun progress(nowMs: Long): Float {
        val span = (stopMs - startMs).coerceAtLeast(1L)
        return ((nowMs - startMs).toFloat() / span).coerceIn(0f, 1f)
    }
}

enum class ZapMode { GROUP, ALL }

enum class ChannelSort { PLAYLIST, NUMBER, NAME }

data class CustomGroup(
    val id: String,
    val name: String,
    val channelKeys: List<String> = emptyList()
)

data class LiveTvSettings(
    val showInSidebar: Boolean = true,
    val showChannelNames: Boolean = true,
    val showChannelNumbers: Boolean = true,
    val showChannelLogos: Boolean = true,
    val showPreview: Boolean = true,
    val showProgramDetails: Boolean = true,
    val showFavoritesGroup: Boolean = true,
    val showRecentGroup: Boolean = true,
    val compactRows: Boolean = false,
    val epgOffsetMinutes: Int = 0,
    val reverseZap: Boolean = false,
    val zapMode: ZapMode = ZapMode.GROUP,
    val autoPlayLastChannel: Boolean = false,
    val rememberLastGroup: Boolean = true,
    val openFullscreenOnSelect: Boolean = false,
    val infoBannerSeconds: Int = 5,
    val playlistRefreshHours: Int = 24,
    val epgRefreshHours: Int = 12,
    val epgPastHours: Int = 24,
    val epgFutureDays: Int = 3,
    val use24HourClock: Boolean = false,
    val autoReconnect: Boolean = true,
    val channelSort: ChannelSort = ChannelSort.PLAYLIST,
    /** Fill the focused cell with the accent colour instead of outlining it. */
    val solidHighlight: Boolean = false,
    /** Show matching channels and programmes in Nuvio's search. */
    val showInSearch: Boolean = true
)

data class LiveUserState(
    val hiddenChannels: Set<String> = emptySet(),
    val hiddenGroups: Set<String> = emptySet(),
    val favorites: List<String> = emptyList(),
    val recent: List<String> = emptyList(),
    val lastChannelKey: String? = null,
    val previousChannelKey: String? = null,
    val lastGroupId: String? = null,
    val channelNames: Map<String, String> = emptyMap(),
    val channelNumbers: Map<String, Int> = emptyMap(),
    val groupNames: Map<String, String> = emptyMap(),
    val groupOrder: List<String> = emptyList(),
    val customGroups: List<CustomGroup> = emptyList()
)

/** A row in the TiviMate-style group panel. */
data class ChannelGroup(
    val id: String,
    val title: String,
    val count: Int,
    val special: Boolean = false
) {
    companion object {
        const val ALL = "__all__"
        const val FAVORITES = "__fav__"
        const val RECENT = "__recent__"
        const val SEARCH = "__search__"
        const val CUSTOM_PREFIX = "custom:"
    }
}
