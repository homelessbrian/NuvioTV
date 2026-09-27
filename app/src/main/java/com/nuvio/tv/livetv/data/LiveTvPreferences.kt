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
            showInSearch = p[Keys.showInSearch] ?: d.showInSearch
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
            customGroups = decodeCustomGroups(p[Keys.customGroups])
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
                    channelCount = o.optInt("channelCount")
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
