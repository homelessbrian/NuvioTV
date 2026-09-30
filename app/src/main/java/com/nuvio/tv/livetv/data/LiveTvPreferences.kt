package com.nuvio.tv.livetv.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.nuvio.tv.livetv.model.ChannelSort
import com.nuvio.tv.livetv.model.CustomGroup
import com.nuvio.tv.livetv.model.EpgSource
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.LiveUserState
import com.nuvio.tv.livetv.model.PlaylistSource
import com.nuvio.tv.livetv.model.Reminder
import com.nuvio.tv.livetv.model.ZapMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LiveTvPreferences @Inject constructor(
    @ApplicationContext context: Context
) {
    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        produceFile = { context.preferencesDataStoreFile("live_tv") }
    )

    private object Keys {
        val playlists = stringPreferencesKey("playlists")
        val epgs = stringPreferencesKey("epg_sources")

        val showInSidebar = booleanPreferencesKey("show_in_sidebar")
        val showNames = booleanPreferencesKey("show_channel_names")
        val showNumbers = booleanPreferencesKey("show_channel_numbers")
        val showLogos = booleanPreferencesKey("show_channel_logos")
        val showPreview = booleanPreferencesKey("show_preview")
        val showDetails = booleanPreferencesKey("show_program_details")
        val showFavGroup = booleanPreferencesKey("show_favorites_group")
        val showRecentGroup = booleanPreferencesKey("show_recent_group")
        val compactRows = booleanPreferencesKey("compact_rows")
        val epgOffset = intPreferencesKey("epg_offset_minutes")
        val reverseZap = booleanPreferencesKey("reverse_zap")
        val zapMode = stringPreferencesKey("zap_mode")
        val autoPlayLast = booleanPreferencesKey("auto_play_last")
        val rememberGroup = booleanPreferencesKey("remember_group")
        val fullscreenOnSelect = booleanPreferencesKey("fullscreen_on_select")
        val bannerSeconds = intPreferencesKey("banner_seconds")
        val playlistRefresh = intPreferencesKey("playlist_refresh_hours")
        val epgRefresh = intPreferencesKey("epg_refresh_hours")
        val epgPast = intPreferencesKey("epg_past_hours")
        val epgFuture = intPreferencesKey("epg_future_days")
        val clock24 = booleanPreferencesKey("clock_24h")
        val autoReconnect = booleanPreferencesKey("auto_reconnect")
        val channelSort = stringPreferencesKey("channel_sort")
        val solidHighlight = booleanPreferencesKey("solid_highlight")
        val showInSearch = booleanPreferencesKey("show_in_search")
        val showAllGroup = booleanPreferencesKey("show_all_group")
        val resumeInPreview = booleanPreferencesKey("resume_in_preview")
        val showGroupCounts = booleanPreferencesKey("show_group_counts")
        val smallHeader = booleanPreferencesKey("small_header")
        val aspectMode = stringPreferencesKey("aspect_mode")
        val sequentialNumbers = booleanPreferencesKey("sequential_numbers")
        val overlayMode = booleanPreferencesKey("overlay_mode")
        val startOnLiveTv = booleanPreferencesKey("start_on_live_tv")
        val startPage = stringPreferencesKey("start_page")
        val catchupPreferHls = booleanPreferencesKey("catchup_prefer_hls")
        val parentalPin = stringPreferencesKey("parental_pin")
        val lockAdultContent = booleanPreferencesKey("lock_adult_content")
        val showQualityBadges = booleanPreferencesKey("show_quality_badges")
        val matchFrameRate = booleanPreferencesKey("match_frame_rate")
        val onDemandInSidebar = booleanPreferencesKey("on_demand_in_sidebar")
        val onDemandInStreams = booleanPreferencesKey("on_demand_in_streams")
        val onDemandAddonPosters = booleanPreferencesKey("on_demand_addon_posters")
        val groupPlaylistHeadings = booleanPreferencesKey("group_playlist_headings")
        val nameRemovals = stringPreferencesKey("name_removals")
        val developerTools = booleanPreferencesKey("developer_tools")
        val lockedGroups = stringSetPreferencesKey("locked_groups")
        val reminders = stringPreferencesKey("reminders")
        val channelQuality = stringPreferencesKey("channel_quality")
        val vodHiddenCategories = stringSetPreferencesKey("vod_hidden_categories")
        val onDemandImports = stringPreferencesKey("on_demand_imports")
        val channelOrder = stringPreferencesKey("channel_order")
        val channelCopies = stringPreferencesKey("channel_copies")

        val hiddenChannels = stringSetPreferencesKey("hidden_channels")
        val hiddenGroups = stringSetPreferencesKey("hidden_groups")
        val favorites = stringPreferencesKey("favorites")
        val recent = stringPreferencesKey("recent")
        val lastChannel = stringPreferencesKey("last_channel")
        val previousChannel = stringPreferencesKey("previous_channel")
        val lastGroup = stringPreferencesKey("last_group")
        val channelNames = stringPreferencesKey("channel_names")
        val channelNumbers = stringPreferencesKey("channel_numbers")
        val groupNames = stringPreferencesKey("group_names")
        val groupOrder = stringPreferencesKey("group_order")
        val customGroups = stringPreferencesKey("custom_groups")
        val epgOverrides = stringPreferencesKey("epg_overrides")
    }

    val playlists: Flow<List<PlaylistSource>> = store.data
        .map { decodePlaylists(it[Keys.playlists]) }
        .distinctUntilChanged()

    val epgSources: Flow<List<EpgSource>> = store.data
        .map { decodeEpgs(it[Keys.epgs]) }
        .distinctUntilChanged()

    val settings: Flow<LiveTvSettings> = store.data.map { p ->
        val d = LiveTvSettings()
        LiveTvSettings(
            showInSidebar = p[Keys.showInSidebar] ?: d.showInSidebar,
            showChannelNames = p[Keys.showNames] ?: d.showChannelNames,
            showChannelNumbers = p[Keys.showNumbers] ?: d.showChannelNumbers,
            showChannelLogos = p[Keys.showLogos] ?: d.showChannelLogos,
            showPreview = p[Keys.showPreview] ?: d.showPreview,
            showProgramDetails = p[Keys.showDetails] ?: d.showProgramDetails,
            showFavoritesGroup = p[Keys.showFavGroup] ?: d.showFavoritesGroup,
            showRecentGroup = p[Keys.showRecentGroup] ?: d.showRecentGroup,
            compactRows = p[Keys.compactRows] ?: d.compactRows,
            epgOffsetMinutes = p[Keys.epgOffset] ?: d.epgOffsetMinutes,
            reverseZap = p[Keys.reverseZap] ?: d.reverseZap,
            zapMode = p[Keys.zapMode]?.let { runCatching { ZapMode.valueOf(it) }.getOrNull() } ?: d.zapMode,
            autoPlayLastChannel = p[Keys.autoPlayLast] ?: d.autoPlayLastChannel,
            rememberLastGroup = p[Keys.rememberGroup] ?: d.rememberLastGroup,
            openFullscreenOnSelect = p[Keys.fullscreenOnSelect] ?: d.openFullscreenOnSelect,
            infoBannerSeconds = p[Keys.bannerSeconds] ?: d.infoBannerSeconds,
            playlistRefreshHours = p[Keys.playlistRefresh] ?: d.playlistRefreshHours,
            epgRefreshHours = p[Keys.epgRefresh] ?: d.epgRefreshHours,
            epgPastHours = p[Keys.epgPast] ?: d.epgPastHours,
            epgFutureDays = p[Keys.epgFuture] ?: d.epgFutureDays,
            use24HourClock = p[Keys.clock24] ?: d.use24HourClock,
            autoReconnect = p[Keys.autoReconnect] ?: d.autoReconnect,
            channelSort = p[Keys.channelSort]?.let { runCatching { ChannelSort.valueOf(it) }.getOrNull() } ?: d.channelSort,
            solidHighlight = p[Keys.solidHighlight] ?: d.solidHighlight,
            showInSearch = p[Keys.showInSearch] ?: d.showInSearch,
            showAllChannelsGroup = p[Keys.showAllGroup] ?: d.showAllChannelsGroup,
            resumeLastInPreview = p[Keys.resumeInPreview] ?: d.resumeLastInPreview,
            showGroupCounts = p[Keys.showGroupCounts] ?: d.showGroupCounts,
            smallHeader = p[Keys.smallHeader] ?: d.smallHeader,
            aspectMode = p[Keys.aspectMode] ?: d.aspectMode,
            sequentialNumbers = p[Keys.sequentialNumbers] ?: d.sequentialNumbers,
            overlayMode = p[Keys.overlayMode] ?: d.overlayMode,
            startOnLiveTv = p[Keys.startOnLiveTv] ?: d.startOnLiveTv,
            startPage = p[Keys.startPage] ?: d.startPage,
            catchupPreferHls = p[Keys.catchupPreferHls] ?: d.catchupPreferHls,
            parentalPin = p[Keys.parentalPin] ?: d.parentalPin,
            lockAdultContent = p[Keys.lockAdultContent] ?: d.lockAdultContent,
            showQualityBadges = p[Keys.showQualityBadges] ?: d.showQualityBadges,
            matchFrameRate = p[Keys.matchFrameRate] ?: d.matchFrameRate,
            onDemandInSidebar = p[Keys.onDemandInSidebar] ?: d.onDemandInSidebar,
            onDemandInStreams = p[Keys.onDemandInStreams] ?: d.onDemandInStreams,
            onDemandAddonPosters = p[Keys.onDemandAddonPosters] ?: d.onDemandAddonPosters,
            groupPlaylistHeadings = p[Keys.groupPlaylistHeadings] ?: d.groupPlaylistHeadings,
            nameRemovals = p[Keys.nameRemovals] ?: d.nameRemovals,
            developerTools = p[Keys.developerTools] ?: d.developerTools
        )
    }.distinctUntilChanged()

    val userState: Flow<LiveUserState> = store.data.map { p ->
        LiveUserState(
            hiddenChannels = p[Keys.hiddenChannels] ?: emptySet(),
            hiddenGroups = p[Keys.hiddenGroups] ?: emptySet(),
            favorites = decodeStringList(p[Keys.favorites]),
            recent = decodeStringList(p[Keys.recent]),
            lastChannelKey = p[Keys.lastChannel],
            previousChannelKey = p[Keys.previousChannel],
            lastGroupId = p[Keys.lastGroup],
            channelNames = decodeStringMap(p[Keys.channelNames]),
            channelNumbers = decodeStringMap(p[Keys.channelNumbers]).mapNotNull { (k, v) -> v.toIntOrNull()?.let { k to it } }.toMap(),
            groupNames = decodeStringMap(p[Keys.groupNames]),
            groupOrder = decodeStringList(p[Keys.groupOrder]),
            customGroups = decodeCustomGroups(p[Keys.customGroups]),
            epgOverrides = decodeStringMap(p[Keys.epgOverrides]).mapNotNull { (k, v) ->
                val parts = v.split(EPG_SEP, limit = 2)
                if (parts.size == 2) k to com.nuvio.tv.livetv.model.EpgAssignment(parts[0], parts[1]) else null
            }.toMap(),
            channelOrder = decodeListMap(p[Keys.channelOrder]),
            channelCopies = decodeListMap(p[Keys.channelCopies]),
            lockedGroups = p[Keys.lockedGroups] ?: emptySet(),
            reminders = decodeReminders(p[Keys.reminders]),
            channelQuality = decodeStringMap(p[Keys.channelQuality]),
            vodHiddenCategories = p[Keys.vodHiddenCategories] ?: emptySet()
        )
    }.distinctUntilChanged()

    suspend fun currentPlaylists(): List<PlaylistSource> = playlists.first()
    suspend fun currentEpgSources(): List<EpgSource> = epgSources.first()
    suspend fun currentSettings(): LiveTvSettings = settings.first()

    // ---------- sources ----------

    suspend fun updatePlaylists(transform: (List<PlaylistSource>) -> List<PlaylistSource>) {
        store.edit { p -> p[Keys.playlists] = encodePlaylists(transform(decodePlaylists(p[Keys.playlists]))) }
    }

    suspend fun updateEpgSources(transform: (List<EpgSource>) -> List<EpgSource>) {
        store.edit { p -> p[Keys.epgs] = encodeEpgs(transform(decodeEpgs(p[Keys.epgs]))) }
    }

    // ---------- settings ----------

    suspend fun updateSettings(transform: (LiveTvSettings) -> LiveTvSettings) {
        val next = transform(currentSettings())
        store.edit { p -> writeSettings(p, next) }
    }

    private fun writeSettings(p: MutablePreferences, s: LiveTvSettings) {
        p[Keys.showInSidebar] = s.showInSidebar
        p[Keys.showNames] = s.showChannelNames
        p[Keys.showNumbers] = s.showChannelNumbers
        p[Keys.showLogos] = s.showChannelLogos
        p[Keys.showPreview] = s.showPreview
        p[Keys.showDetails] = s.showProgramDetails
        p[Keys.showFavGroup] = s.showFavoritesGroup
        p[Keys.showRecentGroup] = s.showRecentGroup
        p[Keys.compactRows] = s.compactRows
        p[Keys.epgOffset] = s.epgOffsetMinutes
        p[Keys.reverseZap] = s.reverseZap
        p[Keys.zapMode] = s.zapMode.name
        p[Keys.autoPlayLast] = s.autoPlayLastChannel
        p[Keys.rememberGroup] = s.rememberLastGroup
        p[Keys.fullscreenOnSelect] = s.openFullscreenOnSelect
        p[Keys.bannerSeconds] = s.infoBannerSeconds
        p[Keys.playlistRefresh] = s.playlistRefreshHours
        p[Keys.epgRefresh] = s.epgRefreshHours
        p[Keys.epgPast] = s.epgPastHours
        p[Keys.epgFuture] = s.epgFutureDays
        p[Keys.clock24] = s.use24HourClock
        p[Keys.autoReconnect] = s.autoReconnect
        p[Keys.channelSort] = s.channelSort.name
        p[Keys.solidHighlight] = s.solidHighlight
        p[Keys.showInSearch] = s.showInSearch
        p[Keys.showAllGroup] = s.showAllChannelsGroup
        p[Keys.resumeInPreview] = s.resumeLastInPreview
        p[Keys.showGroupCounts] = s.showGroupCounts
        p[Keys.smallHeader] = s.smallHeader
        p[Keys.aspectMode] = s.aspectMode
        p[Keys.sequentialNumbers] = s.sequentialNumbers
        p[Keys.overlayMode] = s.overlayMode
        p[Keys.startOnLiveTv] = s.startOnLiveTv
        p[Keys.startPage] = s.startPage
        p[Keys.catchupPreferHls] = s.catchupPreferHls
        p[Keys.parentalPin] = s.parentalPin
        p[Keys.lockAdultContent] = s.lockAdultContent
        p[Keys.showQualityBadges] = s.showQualityBadges
        p[Keys.matchFrameRate] = s.matchFrameRate
        p[Keys.onDemandInSidebar] = s.onDemandInSidebar
        p[Keys.onDemandInStreams] = s.onDemandInStreams
        p[Keys.onDemandAddonPosters] = s.onDemandAddonPosters
        p[Keys.groupPlaylistHeadings] = s.groupPlaylistHeadings
        p[Keys.nameRemovals] = s.nameRemovals
        p[Keys.developerTools] = s.developerTools
    }

    // ---------- channel management ----------

    suspend fun setChannelName(key: String, name: String?) {
        store.edit { p ->
            val m = decodeStringMap(p[Keys.channelNames]).toMutableMap()
            if (name.isNullOrBlank()) m.remove(key) else m[key] = name.trim()
            p[Keys.channelNames] = encodeStringMap(m)
        }
    }

    suspend fun setChannelNumber(key: String, number: Int?) {
        store.edit { p ->
            val m = decodeStringMap(p[Keys.channelNumbers]).toMutableMap()
            if (number == null) m.remove(key) else m[key] = number.toString()
            p[Keys.channelNumbers] = encodeStringMap(m)
        }
    }

    suspend fun setGroupName(groupId: String, name: String?) {
        if (groupId.startsWith(com.nuvio.tv.livetv.model.ChannelGroup.CUSTOM_PREFIX)) {
            updateCustomGroups { list -> list.map { if (it.id == groupId && !name.isNullOrBlank()) it.copy(name = name.trim()) else it } }
            return
        }
        store.edit { p ->
            val m = decodeStringMap(p[Keys.groupNames]).toMutableMap()
            if (name.isNullOrBlank()) m.remove(groupId) else m[groupId] = name.trim()
            p[Keys.groupNames] = encodeStringMap(m)
        }
    }

    /** Moves [groupId] within [currentOrder] (the order currently shown) and saves the result. */
    suspend fun moveGroup(groupId: String, delta: Int, currentOrder: List<String>) {
        val order = currentOrder.toMutableList()
        val i = order.indexOf(groupId)
        if (i < 0) return
        order.removeAt(i)
        order.add((i + delta).coerceIn(0, order.size), groupId)
        store.edit { it[Keys.groupOrder] = encodeStringList(order) }
    }

    suspend fun createCustomGroup(name: String): String {
        val id = com.nuvio.tv.livetv.model.ChannelGroup.CUSTOM_PREFIX + java.util.UUID.randomUUID().toString().take(8)
        updateCustomGroups { it + CustomGroup(id, name.trim().ifBlank { "My group" }) }
        return id
    }

    suspend fun deleteCustomGroup(groupId: String) = updateCustomGroups { list -> list.filterNot { it.id == groupId } }

    suspend fun addToCustomGroup(groupId: String, key: String) = updateCustomGroups { list ->
        list.map { if (it.id == groupId && key !in it.channelKeys) it.copy(channelKeys = it.channelKeys + key) else it }
    }

    suspend fun removeFromCustomGroup(groupId: String, key: String) = updateCustomGroups { list ->
        list.map { if (it.id == groupId) it.copy(channelKeys = it.channelKeys - key) else it }
    }

    suspend fun moveInCustomGroup(groupId: String, key: String, delta: Int) = updateCustomGroups { list ->
        list.map { g ->
            if (g.id != groupId) return@map g
            val keys = g.channelKeys.toMutableList()
            val i = keys.indexOf(key)
            if (i < 0) return@map g
            keys.removeAt(i)
            keys.add((i + delta).coerceIn(0, keys.size), key)
            g.copy(channelKeys = keys)
        }
    }

    suspend fun setEpgOverride(key: String, assignment: com.nuvio.tv.livetv.model.EpgAssignment?) {
        store.edit { p ->
            val m = decodeStringMap(p[Keys.epgOverrides]).toMutableMap()
            if (assignment == null) m.remove(key) else m[key] = assignment.sourceId + EPG_SEP + assignment.xmltvId
            p[Keys.epgOverrides] = encodeStringMap(m)
        }
    }

    suspend fun clearEpgOverrides() {
        store.edit { it[Keys.epgOverrides] = encodeStringMap(emptyMap()) }
    }

    // ---------- parental controls, reminders, quality, On Demand ----------

    suspend fun setGroupLocked(groupId: String, locked: Boolean) {
        store.edit { p ->
            val cur = p[Keys.lockedGroups] ?: emptySet()
            p[Keys.lockedGroups] = if (locked) cur + groupId else cur - groupId
        }
    }

    suspend fun addReminder(r: Reminder) {
        store.edit { p ->
            val cur = decodeReminders(p[Keys.reminders]).filterNot { it.channelKey == r.channelKey && it.startMs == r.startMs }
            p[Keys.reminders] = encodeReminders((cur + r).sortedBy { it.startMs })
        }
    }

    suspend fun removeReminder(channelKey: String, startMs: Long) {
        store.edit { p ->
            p[Keys.reminders] = encodeReminders(
                decodeReminders(p[Keys.reminders]).filterNot { it.channelKey == channelKey && it.startMs == startMs }
            )
        }
    }

    /** Drops reminders for shows that started more than [graceMs] ago. */
    suspend fun pruneReminders(now: Long, graceMs: Long = 30 * 60_000L) {
        store.edit { p ->
            val cur = decodeReminders(p[Keys.reminders])
            val kept = cur.filter { it.startMs + graceMs > now }
            if (kept.size != cur.size) p[Keys.reminders] = encodeReminders(kept)
        }
    }

    suspend fun setChannelQuality(key: String, quality: String) {
        store.edit { p ->
            val m = decodeStringMap(p[Keys.channelQuality]).toMutableMap()
            if (m[key] == quality) return@edit
            m[key] = quality
            p[Keys.channelQuality] = encodeStringMap(m)
        }
    }

    suspend fun updateVodHiddenCategories(show: Set<String>, hide: Set<String>) {
        store.edit { p ->
            val cur = p[Keys.vodHiddenCategories] ?: emptySet()
            p[Keys.vodHiddenCategories] = (cur - show) + hide
        }
    }

    /** When each provider's On Demand catalog was last imported (per TV, not synced). */
    suspend fun onDemandImportTimes(): Map<String, Long> =
        decodeStringMap(store.data.first()[Keys.onDemandImports]).mapValues { it.value.toLongOrNull() ?: 0L }

    suspend fun setOnDemandImportTime(playlistId: String, time: Long) {
        store.edit { p ->
            val m = decodeStringMap(p[Keys.onDemandImports]).toMutableMap()
            m[playlistId] = time.toString()
            p[Keys.onDemandImports] = encodeStringMap(m)
        }
    }

    suspend fun clearVodHiddenCategories() {
        store.edit { it[Keys.vodHiddenCategories] = emptySet() }
    }

    private fun decodeReminders(raw: String?): List<Reminder> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Reminder(o.optString("k"), o.optString("c"), o.optString("t"), o.optLong("s"))
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeReminders(list: List<Reminder>): String {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("k", it.channelKey).put("c", it.channelName).put("t", it.title).put("s", it.startMs)) }
        return arr.toString()
    }

    /** Saves the order of a playlist group or All channels (Reorder channels). */
    suspend fun setChannelOrder(groupId: String, keys: List<String>) {
        store.edit { p ->
            val m = decodeListMap(p[Keys.channelOrder]).toMutableMap()
            m[groupId] = keys
            p[Keys.channelOrder] = encodeListMap(m)
        }
    }

    suspend fun setFavoritesOrder(keys: List<String>) {
        store.edit { p ->
            val cur = decodeStringList(p[Keys.favorites])
            // Keep any favorites that weren't in the list (e.g. hidden ones) at the end.
            p[Keys.favorites] = encodeStringList(keys.filter { it in cur } + cur.filter { it !in keys })
        }
    }

    suspend fun setCustomGroupOrder(groupId: String, keys: List<String>) = updateCustomGroups { list ->
        list.map { g ->
            if (g.id != groupId) g
            else g.copy(channelKeys = keys.filter { it in g.channelKeys } + g.channelKeys.filter { it !in keys })
        }
    }

    /** Copies a channel into another playlist group (it stays in its own group too). */
    suspend fun copyChannel(groupId: String, key: String) {
        store.edit { p ->
            val m = decodeListMap(p[Keys.channelCopies]).toMutableMap()
            val cur = m[groupId].orEmpty()
            if (key !in cur) m[groupId] = cur + key
            p[Keys.channelCopies] = encodeListMap(m)
        }
    }

    suspend fun removeChannelCopy(groupId: String, key: String) {
        store.edit { p ->
            val m = decodeListMap(p[Keys.channelCopies]).toMutableMap()
            m[groupId] = m[groupId].orEmpty() - key
            if (m[groupId].isNullOrEmpty()) m.remove(groupId)
            p[Keys.channelCopies] = encodeListMap(m)
        }
    }

    suspend fun resetChannelOrder() {
        store.edit { it[Keys.channelOrder] = encodeListMap(emptyMap()) }
    }

    suspend fun clearChannelCopies() {
        store.edit { it[Keys.channelCopies] = encodeListMap(emptyMap()) }
    }

    suspend fun resetChannelEdits() {
        store.edit {
            it[Keys.channelNames] = encodeStringMap(emptyMap())
            it[Keys.channelNumbers] = encodeStringMap(emptyMap())
        }
    }

    suspend fun resetGroupEdits() {
        store.edit {
            it[Keys.groupNames] = encodeStringMap(emptyMap())
            it[Keys.groupOrder] = encodeStringList(emptyList())
        }
    }

    private suspend fun updateCustomGroups(transform: (List<CustomGroup>) -> List<CustomGroup>) {
        store.edit { p -> p[Keys.customGroups] = encodeCustomGroups(transform(decodeCustomGroups(p[Keys.customGroups]))) }
    }

    // ---------- per-channel state ----------

    suspend fun setChannelHidden(key: String, hidden: Boolean) {
        store.edit { p ->
            val cur = p[Keys.hiddenChannels] ?: emptySet()
            p[Keys.hiddenChannels] = if (hidden) cur + key else cur - key
        }
    }

    /** Applies a batch of group show/hide changes at once (Manage visibility for groups). */
    suspend fun updateHiddenGroups(show: Set<String>, hide: Set<String>) {
        store.edit { p ->
            val cur = p[Keys.hiddenGroups] ?: emptySet()
            p[Keys.hiddenGroups] = (cur - show) + hide
        }
    }

    /** Applies a batch of show/hide changes at once (Manage visibility). */
    suspend fun updateHiddenChannels(show: Set<String>, hide: Set<String>) {
        store.edit { p ->
            val cur = p[Keys.hiddenChannels] ?: emptySet()
            p[Keys.hiddenChannels] = (cur - show) + hide
        }
    }

    suspend fun clearHiddenChannels() {
        store.edit { it[Keys.hiddenChannels] = emptySet() }
    }

    suspend fun setGroupHidden(groupId: String, hidden: Boolean) {
        store.edit { p ->
            val cur = p[Keys.hiddenGroups] ?: emptySet()
            p[Keys.hiddenGroups] = if (hidden) cur + groupId else cur - groupId
        }
    }

    suspend fun clearHiddenGroups() {
        store.edit { it[Keys.hiddenGroups] = emptySet() }
    }

    suspend fun toggleFavorite(key: String) {
        store.edit { p ->
            val cur = decodeStringList(p[Keys.favorites])
            p[Keys.favorites] = encodeStringList(if (key in cur) cur - key else cur + key)
        }
    }

    suspend fun moveFavorite(key: String, delta: Int) {
        store.edit { p ->
            val cur = decodeStringList(p[Keys.favorites]).toMutableList()
            val idx = cur.indexOf(key)
            if (idx < 0) return@edit
            val target = (idx + delta).coerceIn(0, cur.lastIndex)
            cur.removeAt(idx)
            cur.add(target, key)
            p[Keys.favorites] = encodeStringList(cur)
        }
    }

    suspend fun clearFavorites() {
        store.edit { it[Keys.favorites] = encodeStringList(emptyList()) }
    }

    suspend fun clearRecent() {
        store.edit { it[Keys.recent] = encodeStringList(emptyList()) }
    }

    suspend fun recordWatched(key: String) {
        store.edit { p ->
            val last = p[Keys.lastChannel]
            if (last != null && last != key) p[Keys.previousChannel] = last
            p[Keys.lastChannel] = key
            val recent = decodeStringList(p[Keys.recent]).filter { it != key }
            p[Keys.recent] = encodeStringList((listOf(key) + recent).take(30))
        }
    }

    suspend fun setLastGroup(groupId: String) {
        store.edit { it[Keys.lastGroup] = groupId }
    }

    // ---------- Google Drive sync ----------

    /** Fires whenever anything in Live TV's stored data changes. */
    val changes: Flow<Unit> = store.data.map { }

    /**
     * Everything worth carrying to another TV, as JSON: sources, settings, favorites, hidden
     * channels, groups, renames and EPG assignments. Left out: what this TV last watched, and
     * download status (last updated, errors, counts), which differs per TV and changes constantly.
     */
    suspend fun exportForSync(): JSONObject {
        val p = store.data.first()
        val out = JSONObject()
        p.asMap().forEach { (key, value) ->
            val name = key.name
            if (name in SYNC_EXCLUDED) return@forEach
            val entry = when (value) {
                is Boolean -> JSONObject().put("t", "b").put("v", value)
                is Int -> JSONObject().put("t", "i").put("v", value)
                is Long -> JSONObject().put("t", "l").put("v", value)
                is Set<*> -> JSONObject().put("t", "s").put("v", JSONArray(value.map { it.toString() }))
                is String -> {
                    val v = when (name) {
                        Keys.playlists.name -> encodePlaylists(decodePlaylists(value).map {
                            it.copy(lastUpdatedMs = 0, lastError = null, channelCount = 0)
                        })
                        Keys.epgs.name -> encodeEpgs(decodeEpgs(value).map {
                            it.copy(lastUpdatedMs = 0, lastError = null, programCount = 0)
                        })
                        else -> value
                    }
                    JSONObject().put("t", "str").put("v", v)
                }
                else -> null
            } ?: return@forEach
            out.put(name, entry)
        }
        return out
    }

    /** Replaces this TV's Live TV setup with [data] from [exportForSync], keeping per-TV state. */
    suspend fun importFromSync(data: JSONObject) {
        store.edit { p ->
            val localPlaylists = decodePlaylists(p[Keys.playlists]).associateBy { it.id }
            val localEpgs = decodeEpgs(p[Keys.epgs]).associateBy { it.id }
            // Remove synced keys the other TV doesn't have, then write everything it does.
            p.asMap().keys.filter { it.name !in SYNC_EXCLUDED && !data.has(it.name) }.forEach {
                @Suppress("UNCHECKED_CAST")
                p.remove(it as Preferences.Key<Any>)
            }
            data.keys().forEach { name ->
                if (name in SYNC_EXCLUDED) return@forEach
                val e = data.optJSONObject(name) ?: return@forEach
                when (e.optString("t")) {
                    "b" -> p[booleanPreferencesKey(name)] = e.optBoolean("v")
                    "i" -> p[intPreferencesKey(name)] = e.optInt("v")
                    "l" -> p[androidx.datastore.preferences.core.longPreferencesKey(name)] = e.optLong("v")
                    "s" -> {
                        val arr = e.optJSONArray("v") ?: JSONArray()
                        p[stringSetPreferencesKey(name)] = (0 until arr.length()).map { arr.getString(it) }.toSet()
                    }
                    "str" -> {
                        var v = e.optString("v")
                        // Keep this TV's download status for sources it already had.
                        if (name == Keys.playlists.name) {
                            v = encodePlaylists(decodePlaylists(v).map { r ->
                                localPlaylists[r.id]?.let { l ->
                                    r.copy(lastUpdatedMs = l.lastUpdatedMs, lastError = l.lastError, channelCount = l.channelCount)
                                } ?: r
                            })
                        } else if (name == Keys.epgs.name) {
                            v = encodeEpgs(decodeEpgs(v).map { r ->
                                localEpgs[r.id]?.let { l ->
                                    r.copy(lastUpdatedMs = l.lastUpdatedMs, lastError = l.lastError, programCount = l.programCount)
                                } ?: r
                            })
                        }
                        p[stringPreferencesKey(name)] = v
                    }
                }
            }
        }
    }

    // ---------- JSON helpers ----------

    private fun decodePlaylists(raw: String?): List<PlaylistSource> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                PlaylistSource(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    url = o.optString("url"),
                    enabled = o.optBoolean("enabled", true),
                    userAgent = o.optString("userAgent"),
                    useEmbeddedEpg = o.optBoolean("useEmbeddedEpg", true),
                    xtreamServer = o.optString("xtreamServer"),
                    xtreamUsername = o.optString("xtreamUsername"),
                    xtreamPassword = o.optString("xtreamPassword"),
                    lastUpdatedMs = o.optLong("lastUpdatedMs"),
                    lastError = o.optString("lastError").ifBlank { null },
                    channelCount = o.optInt("channelCount"),
                    // Logins from before On Demand existed stay off until you switch them on.
                    importVod = o.optBoolean("importVod", false),
                    importLive = o.optBoolean("importLive", true)
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun encodePlaylists(list: List<PlaylistSource>): String {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(
                JSONObject()
                    .put("id", s.id).put("name", s.name).put("url", s.url)
                    .put("enabled", s.enabled).put("userAgent", s.userAgent)
                    .put("useEmbeddedEpg", s.useEmbeddedEpg)
                    .put("xtreamServer", s.xtreamServer).put("xtreamUsername", s.xtreamUsername)
                    .put("xtreamPassword", s.xtreamPassword)
                    .put("lastUpdatedMs", s.lastUpdatedMs).put("lastError", s.lastError ?: "")
                    .put("channelCount", s.channelCount)
                    .put("importVod", s.importVod)
                    .put("importLive", s.importLive)
            )
        }
        return arr.toString()
    }

    private fun decodeEpgs(raw: String?): List<EpgSource> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                EpgSource(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    url = o.optString("url"),
                    enabled = o.optBoolean("enabled", true),
                    lastUpdatedMs = o.optLong("lastUpdatedMs"),
                    lastError = o.optString("lastError").ifBlank { null },
                    programCount = o.optInt("programCount")
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeEpgs(list: List<EpgSource>): String {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(
                JSONObject()
                    .put("id", s.id).put("name", s.name).put("url", s.url)
                    .put("enabled", s.enabled).put("lastUpdatedMs", s.lastUpdatedMs)
                    .put("lastError", s.lastError ?: "").put("programCount", s.programCount)
            )
        }
        return arr.toString()
    }

    private fun decodeStringList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
    }

    private fun encodeStringList(list: List<String>): String = JSONArray(list).toString()

    private companion object {
        const val EPG_SEP = "\u0001"
        /** Per-TV keys that Google Drive sync never copies. */
        val SYNC_EXCLUDED = setOf("last_channel", "previous_channel", "last_group", "recent", "on_demand_imports", "channel_quality", "developer_tools")
    }

    private fun decodeListMap(raw: String?): Map<String, List<String>> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { k ->
                val arr = o.optJSONArray(k) ?: JSONArray()
                (0 until arr.length()).map { arr.getString(it) }
            }
        }.getOrDefault(emptyMap())
    }

    private fun encodeListMap(map: Map<String, List<String>>): String {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, JSONArray(v)) }
        return o.toString()
    }

    private fun decodeStringMap(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { o.optString(it) }
        }.getOrDefault(emptyMap())
    }

    private fun encodeStringMap(map: Map<String, String>): String {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, v) }
        return o.toString()
    }

    private fun decodeCustomGroups(raw: String?): List<CustomGroup> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val keys = o.optJSONArray("keys")
                CustomGroup(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    channelKeys = if (keys == null) emptyList() else (0 until keys.length()).map { keys.getString(it) }
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeCustomGroups(list: List<CustomGroup>): String {
        val arr = JSONArray()
        list.forEach { g ->
            arr.put(JSONObject().put("id", g.id).put("name", g.name).put("keys", JSONArray(g.channelKeys)))
        }
        return arr.toString()
    }
}
