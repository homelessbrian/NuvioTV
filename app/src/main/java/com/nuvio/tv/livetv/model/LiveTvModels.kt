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
    val channelCount: Int = 0,
    /** Xtream: also import the provider's movies and series (On Demand). */
    val importVod: Boolean = true,
    /** Xtream: import the live TV channels (off = On Demand only). */
    val importLive: Boolean = true,
    /** Xtream live stream format: "auto" (what the provider allows), "ts" or "m3u8". */
    val streamFormat: String = "auto",
    /** Xtream: the server's time zone (from its account info), used for catch-up times. */
    val serverTimezone: String = ""
) {
    val isXtream: Boolean get() = xtreamServer.isNotBlank()

    /** Whether this source's live channels (and their guide) are loaded. */
    val liveEnabled: Boolean get() = enabled && (!isXtream || importLive)

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
    /** Open Live TV instead of Nuvio's home screen when the app starts (older setting). */
    val startOnLiveTv: Boolean = false,
    /** The page Nuvio opens on: HOME, LIVE_TV, ON_DEMAND, SEARCH, LIBRARY or DISCOVER. */
    val startPage: String = "",
    /** Xtream catch-up: ask for HLS (.m3u8) first, which gives replays a length for seeking. */
    val catchupPreferHls: Boolean = true,
    /** Parental controls: a 4-digit PIN; empty = parental controls off. */
    val parentalPin: String = "",
    /** With a PIN set, lock adult groups and categories automatically. */
    val lockAdultContent: Boolean = true,
    /** Small 4K / FHD / HD / SD badges next to channels you've watched. */
    val showQualityBadges: Boolean = true,
    /** Switch the TV's refresh rate to match the video (auto frame rate). */
    val matchFrameRate: Boolean = false,
    /** Show On Demand in Nuvio's side menu (when there's something to show). */
    val onDemandInSidebar: Boolean = true,
    /** Offer "Watch On Demand" in Nuvio's stream list for movies and episodes. */
    val onDemandInStreams: Boolean = true,
    /** On Demand posters from your own addons (off = the provider's images only). */
    val onDemandAddonPosters: Boolean = true,
    /** Several playlists: list each playlist's groups under a foldable heading. */
    val groupPlaylistHeadings: Boolean = false,
    /** Channel name editor (TiviMate style): prefixes/suffixes to remove, comma-separated. */
    val nameRemovals: String = "",
    /** Hidden developer tools (Test poster lookup). Turned on with a secret word. */
    val developerTools: Boolean = false,
    /** Satellite-box style: picking a group lands on the channel names (OK plays, Right opens the schedule). */
    val browseByChannelName: Boolean = false,
    /** Show which playlist the highlighted channel comes from in the info panel. */
    val showPlaylistInInfo: Boolean = true,
    /** The vertical line marking the current time in the guide. */
    val showNowLine: Boolean = true,
    /** Send Dolby / DTS audio untouched to the TV or speaker (off: the app decodes it). */
    val audioPassthrough: Boolean = true,
    /** Show posters for what's on; off: just the channel logo (no poster lookups at all). */
    val showPosters: Boolean = true,
    /** On Demand: show each title once, even when the provider lists several copies. */
    val vodMergeDuplicates: Boolean = true,
    // ---- Live TV row on Nuvio's home screen
    /** Show the Live TV row on the home screen at all. */
    val homeRowEnabled: Boolean = true,
    /** Above (true) or below (false) Continue watching. */
    val homeRowAboveContinueWatching: Boolean = false,
    /** What it shows: "favorites", "recent", or a group id (e.g. one of your own groups). */
    val homeRowSource: String = "favorites",
    /** Row title; empty = "Live TV". */
    val homeRowTitle: String = "",
    /** "yours" (as in the guide), "number" or "ending" (ending soonest first). */
    val homeRowSort: String = "yours",
    /** How many channels (0 = all). */
    val homeRowLimit: Int = 20,
    /** Leave out channels showing only "Programming" / no information. */
    val homeRowHidePlaceholders: Boolean = true,
    /** OK opens the guide on the channel instead of playing it full screen. */
    val homeRowOpensGuide: Boolean = false,
    /** A "Next: 9:00 The News" line on each card. */
    val homeRowShowNext: Boolean = false,
    /** Compact cards: logo and show name only. */
    val homeRowCompact: Boolean = false
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
    val channelCopies: Map<String, List<String>> = emptyMap(),
    /** Groups (and On Demand categories, prefixed "vod:") that need the parental PIN. */
    val lockedGroups: Set<String> = emptySet(),
    /** Shows you asked to be reminded about. */
    val reminders: List<Reminder> = emptyList(),
    /** Channel key -> picture quality seen when it last played ("4K", "FHD", "HD", "SD"). */
    val channelQuality: Map<String, String> = emptyMap(),
    /** On Demand categories you hid ("movie:<playlist>:<category>" / "series:…"). */
    val vodHiddenCategories: Set<String> = emptySet()
)

/** "Remind me" on an upcoming show. */
data class Reminder(
    val channelKey: String,
    val channelName: String,
    val title: String,
    val startMs: Long
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
    val sourceId: String? = null,
    /** Needs the parental PIN (and its channels are kept out of other lists). */
    val locked: Boolean = false
) {
    companion object {
        const val ALL = "__all__"
        const val FAVORITES = "__fav__"
        const val RECENT = "__recent__"
        const val SEARCH = "__search__"
        const val CUSTOM_PREFIX = "custom:"
    }
}


/** The start page, including the older "Open Live TV when Nuvio starts" switch. */
val LiveTvSettings.effectiveStartPage: String
    get() = startPage.ifBlank { if (startOnLiveTv) "LIVE_TV" else "HOME" }


/**
 * Channel name editor, TiviMate style: removes each listed prefix or suffix (like "USA", "US:",
 * "24/7", "FHD") from the start or end of channel names, along with the separator next to it
 * (":", "|", "-", "/", brackets or spaces). Matching ignores case; the text must be a whole word
 * or symbol group, so "US" never cuts the start of "USA Network".
 */
object ChannelNameEditor {
    private const val SEP = """[\s:|\-–—/.•*]*"""

    fun terms(raw: String): List<String> =
        raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length }

    /** The prefix / suffix / suffix-check patterns for one term, built once and reused. */
    private class Compiled(val prefix: Regex, val suffix: Regex, val suffixCheck: Regex)

    @Volatile private var compiledFor: List<String> = emptyList()
    @Volatile private var compiled: List<Compiled> = emptyList()

    private fun compile(terms: List<String>): List<Compiled> {
        if (terms == compiledFor) return compiled
        val list = terms.map { t ->
            val q = Regex.escape(t)
            // Prefix: "USA: ESPN", "[USA] ESPN", "|US| ESPN", "USA - ESPN". A plain word needs
            // brackets or punctuation after it, so "USA" never eats "USA Network".
            val prefix = if (t.all { it.isLetter() }) {
                Regex("""^(?:[\[(|]$q[\])|]|$q\s*[:|\-–—/.•*]+)\s*""", RegexOption.IGNORE_CASE)
            } else {
                Regex("""^[\[(|]?$q[\])|]?$SEP""", RegexOption.IGNORE_CASE)
            }
            // Suffix: "ESPN HD", "ESPN (US)", "ESPN | FHD", "ESPN 24/7"
            Compiled(
                prefix,
                Regex("""$SEP[\[(|]?$q[\])|]?$""", RegexOption.IGNORE_CASE),
                Regex("""(^|[\s:|\-–—/.•*\[(])[\[(|]?$q[\])|]?$""", RegexOption.IGNORE_CASE)
            )
        }
        compiled = list
        compiledFor = terms
        return list
    }

    fun clean(name: String, terms: List<String>): String {
        if (terms.isEmpty()) return name
        // Fast path: most names contain none of the terms, so skip the patterns entirely.
        val lower = name.lowercase()
        if (terms.none { lower.contains(it.lowercase()) }) return name.trim()
        val patterns = compile(terms)
        var out = name.trim()
        var changed = true
        var guard = 0
        while (changed && guard++ < 6) {
            changed = false
            for (c in patterns) {
                val p = c.prefix.replace(out, "").trim()
                if (p != out && p.isNotBlank()) { out = p; changed = true }
                if (c.suffixCheck.containsMatchIn(out)) {
                    val sfx = c.suffix.replace(out, "").trim()
                    if (sfx != out && sfx.isNotBlank()) { out = sfx; changed = true }
                }
            }
        }
        return out
    }

    /** Common ones, for "Add common prefixes and suffixes". */
    const val COMMON = "USA, US, UK, CA, AU, 24/7, FHD, HD, SD, UHD, 4K, HEVC, RAW, VIP, ᴴᴰ, ᵁᴴᴰ, ᶠᴴᴰ"
}
