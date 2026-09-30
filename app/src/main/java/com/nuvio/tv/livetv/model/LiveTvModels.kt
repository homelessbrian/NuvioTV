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
        return "${xtreamBase()}/get.php?username=${enc(xtreamUsername)}&password=${enc(xtreamPassword)}&type=m3u_plus&output=ts"
    }

    fun xtreamEpgUrl(): String? {
        if (!isXtream) return null
        return "${xtreamBase()}/xmltv.php?username=${enc(xtreamUsername)}&password=${enc(xtreamPassword)}"
    }

    fun xtreamApiUrl(): String =
        "${xtreamBase()}/player_api.php?username=${enc(xtreamUsername)}&password=${enc(xtreamPassword)}"

    /** Just scheme://host:port, even if a full link (get.php?..., player_api.php, /c/) was pasted. */
    fun xtreamBase(): String = cleanXtreamServer(xtreamServer)

    private fun enc(s: String) = java.net.URLEncoder.encode(s.trim(), "UTF-8")

    companion object {
        fun cleanXtreamServer(raw: String): String {
            var s = raw.trim()
            if (s.isEmpty()) return s
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
            return runCatching {
                val u = java.net.URI(s)
                val host = u.host ?: return@runCatching s.trimEnd('/')
                val port = if (u.port > 0) ":${u.port}" else ""
                "${u.scheme.lowercase()}://$host$port"
            }.getOrDefault(s.trimEnd('/'))
        }

        /** Username / password from a pasted link like ".../get.php?username=a&password=b". */
        fun credentialsFromLink(raw: String): Pair<String, String>? {
            val query = raw.substringAfter('?', "")
            if (query.isEmpty()) return null
            val params = query.split('&').associate {
                it.substringBefore('=').lowercase() to java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            }
            val user = params["username"].orEmpty()
            val pass = params["password"].orEmpty()
            return if (user.isNotBlank() && pass.isNotBlank()) user to pass else null
        }
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
    /** Stable key used for favorites, hidden channels and last-channel memory. */
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
    val icon: String? = null,
    /** Release year from the guide (XMLTV <date>, or "(1990)" in the title/description). */
    val year: Int? = null,
    /** Actors and directors from the guide's <credits>, when it has them. */
    val people: List<String> = emptyList()
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
    /** Fill the focused cell with the accent color instead of outlining it. */
    val solidHighlight: Boolean = false,
    /** Show matching channels and programs in Nuvio's search. */
    val showInSearch: Boolean = true,
    val showAllChannelsGroup: Boolean = true,
    /** When Live TV opens, start the last channel in the preview window and highlight it. */
    val resumeLastInPreview: Boolean = true,
    /** Show how many channels each group has in the guide's group list. */
    val showGroupCounts: Boolean = true,
    /** Smaller info panel and preview at the top of the guide, so more channels fit. */
    val smallHeader: Boolean = false,
    /** Full-screen picture size, using Nuvio's own modes (Fit, Crop, Stretch, Cinema Zoom, …). */
    val aspectMode: String = "ORIGINAL",
    /** Number channels 1, 2, 3… in the order they appear in each group (TiviMate's override). */
    val sequentialNumbers: Boolean = false,
    /** Left while watching opens overlay mode; off: Left returns to the guide's group list. */
    val overlayMode: Boolean = true,
    /** Open Live TV instead of Nuvio's home screen when the app starts. */
    val startOnLiveTv: Boolean = false,
    /** Xtream catch-up: ask for HLS (.m3u8) first, which gives replays a length for seeking. */
    val catchupPreferHls: Boolean = true
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
    val customGroups: List<CustomGroup> = emptyList(),
    /** Channel key -> EPG assigned by hand from the long-press menu. */
    val epgOverrides: Map<String, EpgAssignment> = emptyMap(),
    /** Group id -> channel keys in the order you set with "Reorder channels". */
    val channelOrder: Map<String, List<String>> = emptyMap(),
    /** Group id -> channels copied into it from other groups ("Copy channel"). */
    val channelCopies: Map<String, List<String>> = emptyMap()
)

/** A channel's guide, picked by hand: which EPG source, and which channel inside it. */
data class EpgAssignment(val sourceId: String, val xmltvId: String)

/** One loaded EPG source and its channel list, for the "Change EPG" picker. */
data class EpgSourceChannels(
    val sourceId: String,
    val name: String,
    /** Short label shown under each channel in the picker, e.g. "github.com". */
    val label: String,
    val channels: List<EpgChannelEntry>,
    /** Guide channel ids already feeding a playlist channel (matched or assigned). */
    val usedIds: Set<String> = emptySet()
)

data class EpgChannelEntry(
    val id: String,
    val names: List<String>,
    val icon: String?
) {
    val displayName: String get() = names.firstOrNull() ?: id
}

/** A row in the TiviMate-style group panel. */
data class ChannelGroup(
    val id: String,
    val title: String,
    val count: Int,
    val special: Boolean = false,
    /** The playlist a playlist group comes from (null for special and your own groups). */
    val sourceId: String? = null
) {
    companion object {
        const val ALL = "__all__"
        const val FAVORITES = "__fav__"
        const val RECENT = "__recent__"
        const val SEARCH = "__search__"
        const val CUSTOM_PREFIX = "custom:"
    }
}
