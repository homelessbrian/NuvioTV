package com.nuvio.tv.livetv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.livetv.data.CatchupUrlBuilder
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.data.LiveTvStatus
import com.nuvio.tv.livetv.model.ChannelGroup
import com.nuvio.tv.livetv.model.ChannelSort
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.LiveUserState
import com.nuvio.tv.livetv.model.ZapMode
import com.nuvio.tv.livetv.player.LivePlaybackState
import com.nuvio.tv.livetv.player.LiveTvPlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Matches Nuvio's side-menu setting: classic drawer, modern floating panel, or modern with blur. */
enum class LiveMenuStyle { CLASSIC, MODERN, MODERN_BLUR }

/** In-memory session shared by the guide and the full-screen player (current group, etc). */
@Singleton
class LiveTvSession @Inject constructor() {
    val currentGroupId = MutableStateFlow<String?>(null)
    var autoPlayedThisLaunch = false
    /** Set when full screen opens, so Back to the guide returns to the channel, not the groups. */
    var returningFromFullscreen = false
    /** Overlay mode off: Left in full screen returns to the guide with the groups open. */
    var openGroupsOnReturn = false
    val collapsedPlaylists = MutableStateFlow<Set<String>>(emptySet())
    /** Sleep timer: when playback stops (null = off). Kept while the app is open. */
    val sleepAtMs = MutableStateFlow<Long?>(null)
}

data class LiveTvUiState(
    val groups: List<ChannelGroup> = emptyList(),
    val selectedGroupId: String = ChannelGroup.ALL,
    val channels: List<LiveChannel> = emptyList(),
    val allVisibleChannels: List<LiveChannel> = emptyList(),
    val hasSources: Boolean = true,
    val searchQuery: String = "",
    /** Non-special groups in the order shown, used when the user moves a group. */
    val orderableGroupIds: List<String> = emptyList(),
    val customGroups: List<ChannelGroup> = emptyList(),
    /** Where "back" and fallbacks go: All channels, or the first group when that's hidden. */
    val defaultGroupId: String = ChannelGroup.ALL
) {
    /** The group to switch to so [channel] is in the list. */
    fun groupFor(channel: LiveChannel): String =
        if (groups.any { it.id == ChannelGroup.ALL }) ChannelGroup.ALL else channel.groupId
}

@HiltViewModel
class LiveTvViewModel @Inject constructor(
    private val repository: LiveTvRepository,
    private val prefs: LiveTvPreferences,
    val playback: LiveTvPlaybackController,
    private val session: LiveTvSession,
    private val channelStore: com.nuvio.tv.livetv.data.LiveTvChannelStore,
    private val posterResolver: com.nuvio.tv.livetv.data.LiveTvPosterResolver,
    layoutPrefs: com.nuvio.tv.data.local.LayoutPreferenceDataStore,
    driveSync: com.nuvio.tv.livetv.sync.LiveTvDriveSync
) : ViewModel() {

    init {
        // Google Drive sync (if connected): pick up a newer setup from another TV.
        driveSync.start()
    }


    /** Nuvio's side-menu appearance (Layout settings), so Live TV panels can match it. */
    val menuStyle: StateFlow<LiveMenuStyle> = combine(
        layoutPrefs.modernSidebarEnabled,
        layoutPrefs.modernSidebarBlurEnabled
    ) { modern, blur ->
        when {
            !modern -> LiveMenuStyle.CLASSIC
            blur && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S -> LiveMenuStyle.MODERN_BLUR
            else -> LiveMenuStyle.MODERN
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LiveMenuStyle.CLASSIC)


    val settings: StateFlow<LiveTvSettings> = prefs.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, LiveTvSettings())

    val userState: StateFlow<LiveUserState> = prefs.userState
        .stateIn(viewModelScope, SharingStarted.Eagerly, LiveUserState())

    val status: StateFlow<LiveTvStatus> = repository.status
    val playbackState: StateFlow<LivePlaybackState> = playback.state

    private val _now = MutableStateFlow(System.currentTimeMillis())
    val now: StateFlow<Long> = _now

    private val searchQuery = MutableStateFlow("")

    /** Programs with the user's EPG time offset applied. */
    val programs: StateFlow<Map<String, List<EpgProgram>>> = combine(
        repository.programs,
        settings.map { it.epgOffsetMinutes }.distinctUntilChanged()
    ) { map, offset ->
        if (offset == 0) map else {
            val shift = offset * 60_000L
            map.mapValues { (_, list) -> list.map { it.copy(startMs = it.startMs + shift, stopMs = it.stopMs + shift) } }
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val hasSources: StateFlow<Boolean> = prefs.playlists.map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /**
     * Channels with the user's renames and renumbering applied. Worked out once for the whole
     * app (guide, full screen and search share it) instead of once per screen.
     */
    val displayChannels: StateFlow<List<LiveChannel>> = channelStore.displayChannels

    val uiState: StateFlow<LiveTvUiState> = combine(
        combine(displayChannels, userState, settings) { a, b, c -> Triple(a, b, c) },
        session.currentGroupId,
        searchQuery,
        combine(hasSources, com.nuvio.tv.livetv.parental.ParentalControls.unlocked) { h, _ -> h }
    ) { (channels, user, s), groupIdRaw, query, hasSrc ->
        buildUi(channels, user, s, groupIdRaw, query, hasSrc)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, LiveTvUiState())

    init {
        repository.ensureLoaded()
        viewModelScope.launch {
            while (true) {
                _now.value = System.currentTimeMillis()
                delay(30_000)
            }
        }
        viewModelScope.launch {
            settings.collect {
                playback.autoReconnect = it.autoReconnect
                playback.audioPassthrough = it.audioPassthrough
            }
        }
        viewModelScope.launch {
            // Restore the remembered group once the user state is known.
            val remembered = prefs.userState.first()
            if (session.currentGroupId.value == null) {
                val s = prefs.currentSettings()
                session.currentGroupId.value = if (s.rememberLastGroup) remembered.lastGroupId else null
            }
        }
    }

    private fun buildUi(
        channels: List<LiveChannel>,
        user: LiveUserState,
        s: LiveTvSettings,
        groupIdRaw: String?,
        query: String,
        hasSrc: Boolean
    ): LiveTvUiState {
        val shown = channels.filter { it.key !in user.hiddenChannels && it.groupId !in user.hiddenGroups }
        // Parental controls: channels in locked groups stay out of every list until unlocked.
        val lockedIds = HashSet<String>()
        shown.distinctBy { it.groupId }.forEach { c ->
            if (com.nuvio.tv.livetv.parental.ParentalControls.isLocked(c.groupId, c.group, s, user.lockedGroups)) lockedIds += c.groupId
        }
        user.customGroups.forEach { g ->
            if (com.nuvio.tv.livetv.parental.ParentalControls.isLocked(g.id, g.name, s, user.lockedGroups)) lockedIds += g.id
        }
        val visible = shown.filter { it.groupId !in lockedIds }
        val byKey = visible.associateBy { it.key }
        val favorites = user.favorites.mapNotNull { byKey[it] }
        val recent = user.recent.mapNotNull { byKey[it] }

        fun sorted(list: List<LiveChannel>): List<LiveChannel> = when (s.channelSort) {
            ChannelSort.PLAYLIST -> list
            ChannelSort.NUMBER -> list.sortedBy { it.number }
            ChannelSort.NAME -> list.sortedBy { it.name.lowercase() }
        }

        /** Your order from "Reorder channels" wins; channels you haven't placed follow as sorted. */
        fun ordered(groupId: String, list: List<LiveChannel>): List<LiveChannel> {
            val order = user.channelOrder[groupId] ?: return sorted(list)
            val byKeyHere = list.associateBy { it.key }
            val placed = order.mapNotNull { byKeyHere[it] }
            val placedKeys = placed.mapTo(HashSet()) { it.key }
            return placed + sorted(list.filter { it.key !in placedKeys })
        }

        /** A playlist group's own channels plus channels copied into it. */
        fun groupChannels(groupId: String): List<LiveChannel> {
            val own = visible.filter { it.groupId == groupId }
            val ownKeys = own.mapTo(HashSet()) { it.key }
            val copies = user.channelCopies[groupId].orEmpty().mapNotNull { byKey[it] }.filter { it.key !in ownKeys }
            return own + copies
        }

        val groups = mutableListOf<ChannelGroup>()
        groups += ChannelGroup(ChannelGroup.SEARCH, "Search", 0, special = true)
        if (s.showFavoritesGroup) groups += ChannelGroup(ChannelGroup.FAVORITES, "Favorites", favorites.size, special = true)
        if (s.showRecentGroup) groups += ChannelGroup(ChannelGroup.RECENT, "Recently watched", recent.size, special = true)
        if (s.showAllChannelsGroup) groups += ChannelGroup(ChannelGroup.ALL, "All channels", visible.size, special = true)

        // Your own groups first, then the playlist's groups, then the saved order on top.
        val regular = mutableListOf<ChannelGroup>()
        val custom = user.customGroups
            .filter { it.id !in user.hiddenGroups }
            .map { g -> ChannelGroup(g.id, g.name, g.channelKeys.count { it in byKey }, locked = g.id in lockedIds) }
        regular += custom
        val playlistGroups = LinkedHashMap<String, Pair<String, Int>>()
        shown.forEach { c ->
            val cur = playlistGroups[c.groupId]
            playlistGroups[c.groupId] = (cur?.first ?: c.group) to ((cur?.second ?: 0) + 1)
        }
        val groupSource = HashMap<String, String>()
        shown.forEach { c -> groupSource.putIfAbsent(c.groupId, c.sourceId) }
        playlistGroups.forEach { (id, v) ->
            val copied = user.channelCopies[id].orEmpty().count { k -> byKey[k]?.let { it.groupId != id } == true }
            regular += ChannelGroup(id, v.first, v.second + copied, sourceId = groupSource[id], locked = id in lockedIds)
        }
        val orderIndex = user.groupOrder.withIndex().associate { (i, id) -> id to i }
        val orderedRegular = regular.withIndex()
            .sortedWith(compareBy<IndexedValue<ChannelGroup>>({ orderIndex[it.value.id] ?: Int.MAX_VALUE }, { it.index }))
            .map { it.value }
        groups += orderedRegular

        val defaultGroupId = when {
            s.showAllChannelsGroup -> ChannelGroup.ALL
            orderedRegular.isNotEmpty() -> orderedRegular.first().id
            s.showFavoritesGroup -> ChannelGroup.FAVORITES
            else -> ChannelGroup.ALL
        }
        val groupId = groupIdRaw?.takeIf { id -> groups.any { it.id == id } } ?: defaultGroupId
        val rawList = when {
            groupId == ChannelGroup.ALL && s.showAllChannelsGroup -> ordered(ChannelGroup.ALL, visible)
            // "Playlist order" keeps your own order here; Number / Name sort these too.
            groupId == ChannelGroup.FAVORITES -> sorted(favorites)
            groupId == ChannelGroup.RECENT -> recent
            groupId == ChannelGroup.SEARCH -> {
                val q = query.trim()
                if (q.isEmpty()) emptyList() else sorted(visible.filter {
                    it.name.contains(q, ignoreCase = true) || it.number.toString() == q
                })
            }
            groupId in lockedIds -> emptyList()
            groupId.startsWith(ChannelGroup.CUSTOM_PREFIX) ->
                sorted(user.customGroups.firstOrNull { it.id == groupId }?.channelKeys?.mapNotNull { byKey[it] }.orEmpty())
            else -> ordered(groupId, groupChannels(groupId))
        }
        // TiviMate's number override: 1, 2, 3… in the order the group shows them.
        val list = if (s.sequentialNumbers && groupId != ChannelGroup.SEARCH) {
            rawList.mapIndexed { i, c -> c.copy(number = i + 1) }
        } else rawList
        return LiveTvUiState(
            groups = groups,
            selectedGroupId = groupId,
            channels = list,
            allVisibleChannels = sorted(visible),
            hasSources = hasSrc,
            searchQuery = query,
            orderableGroupIds = orderedRegular.map { it.id },
            customGroups = custom,
            defaultGroupId = defaultGroupId
        )
    }

    // ------------------------------------------------------------ actions

    fun selectGroup(id: String) {
        session.currentGroupId.value = id
    }

    // ------------------------------------------------------------ sleep timer, quality

    /** Sleep timer: lives in the playback controller, so it works on every screen. */
    val sleepAtMs: StateFlow<Long?> = playback.sleepAtMs

    fun setSleepTimer(minutes: Int?) = playback.setSleepTimer(minutes)

    /** Remembers the picture quality a channel played at, for the guide's badges. */
    fun recordQuality(channelKey: String, height: Int) {
        val q = when {
            height >= 1800 -> "4K"
            height >= 1000 -> "FHD"
            height >= 700 -> "HD"
            height > 0 -> "SD"
            else -> return
        }
        if (userState.value.channelQuality[channelKey] == q) return
        viewModelScope.launch { prefs.setChannelQuality(channelKey, q) }
    }

    // ------------------------------------------------------------ reminders

    /** Sets or cancels "Remind me" for an upcoming show. */
    fun toggleReminder(channel: LiveChannel, program: EpgProgram) = viewModelScope.launch {
        val exists = userState.value.reminders.any { it.channelKey == channel.key && it.startMs == program.startMs }
        if (exists) prefs.removeReminder(channel.key, program.startMs)
        else prefs.addReminder(com.nuvio.tv.livetv.model.Reminder(channel.key, channel.name, program.title, program.startMs))
    }

    // ------------------------------------------------------------ parental controls

    fun parentalEnabled(): Boolean = com.nuvio.tv.livetv.parental.ParentalControls.enabled(settings.value)

    /** Opens a locked group with the PIN; false if the PIN is wrong. */
    fun unlockGroup(group: ChannelGroup, pin: String): Boolean =
        com.nuvio.tv.livetv.parental.ParentalControls.unlock(group.id, pin, settings.value)

    fun isGroupLockSet(groupId: String) = groupId in userState.value.lockedGroups

    fun setGroupLocked(groupId: String, locked: Boolean) = viewModelScope.launch { prefs.setGroupLocked(groupId, locked) }

    fun checkPin(pin: String) = com.nuvio.tv.livetv.parental.ParentalControls.checkPin(pin, settings.value)

    fun setSearchQuery(q: String) {
        searchQuery.value = q
        session.currentGroupId.value = ChannelGroup.SEARCH
    }

    fun preview(channel: LiveChannel) {
        _catchup.value = null
        playback.play(channel)
        // Remember the group you watched in, so Live TV reopens there.
        val group = session.currentGroupId.value?.takeIf { it != ChannelGroup.SEARCH }
            ?: uiState.value.selectedGroupId
        viewModelScope.launch {
            prefs.recordWatched(channel.key)
            prefs.setLastGroup(group)
        }
    }

    /** Every channel in the current group, hidden ones included, for Manage visibility. */
    fun channelsWithHidden(): List<LiveChannel> {
        val user = userState.value.copy(hiddenChannels = emptySet())
        return buildUi(
            displayChannels.value, user, settings.value,
            session.currentGroupId.value ?: uiState.value.selectedGroupId, "", true
        ).channels
    }

    /** Every group you can hide (not Favorites, Recently watched or All), hidden ones included. */
    fun groupsWithHidden(): List<ChannelGroup> {
        val user = userState.value.copy(hiddenGroups = emptySet())
        return buildUi(displayChannels.value, user, settings.value, uiState.value.selectedGroupId, "", true)
            .groups.filter { !it.special && !it.id.startsWith(ChannelGroup.CUSTOM_PREFIX) }
    }

    fun saveGroupVisibility(inList: List<ChannelGroup>, hidden: Set<String>) = viewModelScope.launch {
        val ids = inList.map { it.id }.toSet()
        prefs.updateHiddenGroups(show = ids - hidden, hide = hidden intersect ids)
    }

    /** Saves Manage visibility: [hidden] is the full set of hidden keys among [inList]. */
    fun saveVisibility(inList: List<LiveChannel>, hidden: Set<String>) = viewModelScope.launch {
        val keys = inList.map { it.key }.toSet()
        prefs.updateHiddenChannels(show = keys - hidden, hide = hidden intersect keys)
    }

    fun updateSettings(transform: (LiveTvSettings) -> LiveTvSettings) {
        viewModelScope.launch { prefs.updateSettings(transform) }
    }

    fun requestGroupsOnReturn() {
        session.openGroupsOnReturn = true
    }

    fun consumeGroupsOnReturn(): Boolean =
        session.openGroupsOnReturn.also { session.openGroupsOnReturn = false }

    /** Whether the guide is being shown again after full screen (without using it up). */
    fun isReturningFromFullscreen(): Boolean = session.returningFromFullscreen

    /**
     * The group to show [channel] in when Live TV opens or you come back to it: the group you
     * watched it in (a custom group, Favorites…) if it's still there, otherwise the group on
     * screen, otherwise its own playlist group.
     */
    suspend fun groupToShow(channel: LiveChannel): String? {
        val ui = awaitGroups() ?: uiState.value
        val user = prefs.userState.first()
        val last = user.lastGroupId
        if (last != null && ui.groups.any { it.id == last } && channelInGroup(channel, last, user)) return last
        if (ui.channels.any { it.key == channel.key }) return null // already showing
        return ui.groupFor(channel)
    }

    private fun channelInGroup(channel: LiveChannel, groupId: String, user: LiveUserState): Boolean = when {
        groupId == ChannelGroup.ALL -> true
        groupId == ChannelGroup.FAVORITES -> channel.key in user.favorites
        groupId == ChannelGroup.RECENT -> channel.key in user.recent
        groupId.startsWith(ChannelGroup.CUSTOM_PREFIX) ->
            user.customGroups.firstOrNull { it.id == groupId }?.channelKeys?.contains(channel.key) == true
        else -> channel.groupId == groupId || channel.key in user.channelCopies[groupId].orEmpty()
    }

    fun markFullscreenOpened() {
        session.returningFromFullscreen = true
    }

    /** True once, right after coming back from full screen. */
    fun consumeReturningFromFullscreen(): Boolean =
        session.returningFromFullscreen.also { session.returningFromFullscreen = false }

    /** The group list, once it has loaded (for opening Live TV on the groups). */
    suspend fun awaitGroups(): LiveTvUiState? =
        withTimeoutOrNull(10_000) { uiState.first { it.groups.size > 1 } }

    /** The catch-up program playing, and where in it the current stream starts. */
    data class CatchupSession(val channel: LiveChannel, val program: EpgProgram, val baseOffsetMs: Long)

    private val _catchup = MutableStateFlow<CatchupSession?>(null)
    val catchup: StateFlow<CatchupSession?> = _catchup

    /** Plays [program] from the archive, starting [offsetMs] into it. */
    fun playCatchup(channel: LiveChannel, program: EpgProgram, offsetMs: Long = 0L): Boolean {
        val catchup = channel.catchup ?: return false
        val now = System.currentTimeMillis()
        if (!CatchupUrlBuilder.isAvailable(catchup, program.startMs, now)) return false
        val shift = settings.value.epgOffsetMinutes * 60_000L
        val start = program.startMs + offsetMs.coerceAtLeast(0)
        val url = CatchupUrlBuilder.build(
            channel.url, catchup, start - shift, program.stopMs - shift, now,
            preferHls = settings.value.catchupPreferHls
        ) ?: return false
        playback.play(channel, overrideUrl = url, catchupTitle = program.title, fallback = CatchupUrlBuilder.tsFallback(url))
        _catchup.value = CatchupSession(channel, program, offsetMs.coerceAtLeast(0))
        return true
    }

    /** True if the show on now can be restarted from its beginning (the channel has catch-up). */
    fun canWatchFromStart(channel: LiveChannel): Boolean {
        val p = currentProgram(channel.key) ?: return false
        return CatchupUrlBuilder.isAvailable(channel.catchup, p.startMs, System.currentTimeMillis())
    }

    /** Restarts the show that's on now, from its beginning. */
    fun watchFromStart(channel: LiveChannel): Boolean =
        currentProgram(channel.key)?.let { playCatchup(channel, it, 0L) } ?: false

    /** Where playback is, as time into the catch-up program. */
    fun catchupPositionMs(): Long? {
        val s = _catchup.value ?: return null
        val pos = playback.player?.currentPosition ?: 0L
        return s.baseOffsetMs + pos.coerceAtLeast(0)
    }

    /**
     * Seeks the catch-up program to [offsetMs]. Seeks inside the stream when the provider allows
     * it; otherwise asks the provider for the archive again, starting at that point.
     */
    fun seekCatchupTo(offsetMs: Long) {
        val s = _catchup.value ?: return
        val now = System.currentTimeMillis()
        val latest = (minOf(s.program.stopMs, now - 15_000) - s.program.startMs).coerceAtLeast(0)
        val target = offsetMs.coerceIn(0, latest)
        val p = playback.player
        val inStream = target - s.baseOffsetMs
        if (p != null && p.isCurrentMediaItemSeekable && p.duration != androidx.media3.common.C.TIME_UNSET &&
            inStream >= 0 && inStream <= p.duration
        ) {
            p.seekTo(inStream)
        } else {
            playCatchup(s.channel, s.program, target)
        }
    }

    fun backToLive(channel: LiveChannel) {
        _catchup.value = null
        playback.play(channel)
    }

    fun toggleFavorite(channel: LiveChannel) = viewModelScope.launch { prefs.toggleFavorite(channel.key) }
    fun moveFavorite(channel: LiveChannel, delta: Int) = viewModelScope.launch { prefs.moveFavorite(channel.key, delta) }
    fun hideChannel(channel: LiveChannel) = viewModelScope.launch { prefs.setChannelHidden(channel.key, true) }
    fun hideGroup(groupId: String) = viewModelScope.launch {
        prefs.setGroupHidden(groupId, true)
        if (session.currentGroupId.value == groupId) selectGroup(uiState.value.defaultGroupId)
    }
    fun refresh() = repository.refreshAll(force = true)

    // ------------------------------------------------------------ per-channel EPG

    val epgSources: StateFlow<List<com.nuvio.tv.livetv.model.EpgSourceChannels>> = repository.epgSources

    /** Assign EPG: make sure the full guide-channel lists are loaded (the saved guide skips them). */
    fun ensureEpgDetails() = repository.ensureEpgDetails()
    val epgAutoMatches: StateFlow<Map<String, com.nuvio.tv.livetv.model.EpgAssignment>> = repository.autoMatches

    fun setChannelEpg(channel: LiveChannel, sourceId: String, xmltvId: String) = viewModelScope.launch {
        prefs.setEpgOverride(channel.key, com.nuvio.tv.livetv.model.EpgAssignment(sourceId, xmltvId))
        repository.rematchEpg()
    }

    /** Re-downloads every guide and rebuilds the assignable channel lists. */
    fun fullEpgScan() = repository.refreshEpgOnly()

    fun resetChannelEpg(channel: LiveChannel) = viewModelScope.launch {
        prefs.setEpgOverride(channel.key, null)
        repository.rematchEpg()
    }

    /** The poster Nuvio's catalogs would show for this program, or null if there's no good match. */
    suspend fun posterFor(
        programTitle: String,
        program: EpgProgram? = null,
        channel: LiveChannel? = null
    ): String? = posterResolver.posterFor(
        programTitle,
        com.nuvio.tv.livetv.data.LiveTvPosterResolver.typeHint(program, channel),
        com.nuvio.tv.livetv.data.LiveTvPosterResolver.Clues(
            year = program?.year,
            people = program?.people.orEmpty(),
            description = program?.description
        )
    )

    // ------------------------------------------------------------ channel management

    fun renameChannel(channel: LiveChannel, name: String?) = viewModelScope.launch {
        val original = repository.channels.value.firstOrNull { it.key == channel.key }?.name
        prefs.setChannelName(channel.key, name?.takeIf { it.isNotBlank() && it != original })
    }

    fun setChannelNumber(channel: LiveChannel, number: Int?) = viewModelScope.launch {
        val original = repository.channels.value.firstOrNull { it.key == channel.key }?.number
        prefs.setChannelNumber(channel.key, number?.takeIf { it != original })
    }

    fun renameGroup(groupId: String, name: String?) = viewModelScope.launch { prefs.setGroupName(groupId, name) }

    /** Playlist id -> name, for the playlist headings in the group list. */
    val playlistNames: StateFlow<Map<String, String>> = prefs.playlists
        .map { list -> list.associate { it.id to it.name } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Playlists folded away in the group list (kept while the app is open). */
    val collapsedPlaylists = session.collapsedPlaylists

    fun togglePlaylistCollapsed(id: String) {
        val cur = session.collapsedPlaylists.value
        session.collapsedPlaylists.value = if (id in cur) cur - id else cur + id
    }

    /** Saves the whole group order at once (Reorder groups). */
    fun saveGroupOrder(ids: List<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch { prefs.moveGroup(ids.first(), 0, ids) }
    }

    fun moveGroup(groupId: String, delta: Int) {
        viewModelScope.launch { prefs.moveGroup(groupId, delta, uiState.value.orderableGroupIds) }
    }

    fun createGroupWith(name: String, channel: LiveChannel?) = viewModelScope.launch {
        val id = prefs.createCustomGroup(name)
        channel?.let { prefs.addToCustomGroup(id, it.key) }
    }

    fun addToGroup(groupId: String, channel: LiveChannel) = viewModelScope.launch { prefs.addToCustomGroup(groupId, channel.key) }
    fun removeFromGroup(groupId: String, channel: LiveChannel) = viewModelScope.launch { prefs.removeFromCustomGroup(groupId, channel.key) }
    fun moveInGroup(groupId: String, channel: LiveChannel, delta: Int) = viewModelScope.launch {
        prefs.moveInCustomGroup(groupId, channel.key, delta)
    }

    fun deleteGroup(groupId: String) = viewModelScope.launch {
        prefs.deleteCustomGroup(groupId)
        if (session.currentGroupId.value == groupId) selectGroup(uiState.value.defaultGroupId)
    }

    fun channelByKey(key: String?): LiveChannel? =
        key?.let { k -> displayChannels.value.firstOrNull { it.key == k } ?: playback.playingChannel?.takeIf { it.key == k } }

    fun currentProgram(channelKey: String, at: Long = _now.value): EpgProgram? =
        programs.value[channelKey]?.firstOrNull { at >= it.startMs && at < it.stopMs }

    fun nextProgram(channelKey: String, at: Long = _now.value): EpgProgram? =
        programs.value[channelKey]?.firstOrNull { it.startMs >= at }

    /** Channel list used for up/down zapping in the full-screen player. */
    fun zapList(): List<LiveChannel> {
        val ui = uiState.value
        return if (settings.value.zapMode == ZapMode.ALL || ui.channels.isEmpty()) ui.allVisibleChannels else ui.channels
    }

    fun zap(currentKey: String?, direction: Int): LiveChannel? {
        val list = zapList()
        if (list.isEmpty()) return null
        val dir = if (settings.value.reverseZap) -direction else direction
        val idx = list.indexOfFirst { it.key == currentKey }
        val next = if (idx < 0) 0 else Math.floorMod(idx + dir, list.size)
        return list[next]
    }

    /** The channel with this number, looking in the current group first (numbers can repeat). */
    fun channelByNumber(number: Int): LiveChannel? =
        uiState.value.channels.firstOrNull { it.number == number }
            ?: uiState.value.allVisibleChannels.firstOrNull { it.number == number }

    // ------------------------------------------------------------ reorder / copy

    /** Groups you can reorder: not Search or Recently watched (that one follows what you watch). */
    fun canReorder(groupId: String) = groupId != ChannelGroup.SEARCH && groupId != ChannelGroup.RECENT

    fun saveOrder(groupId: String, keys: List<String>) = viewModelScope.launch {
        when {
            groupId == ChannelGroup.FAVORITES -> prefs.setFavoritesOrder(keys)
            groupId.startsWith(ChannelGroup.CUSTOM_PREFIX) -> prefs.setCustomGroupOrder(groupId, keys)
            else -> prefs.setChannelOrder(groupId, keys)
        }
    }

    /** Copy to a playlist group, or add to one of your own groups. */
    fun copyToGroup(groupId: String, channel: LiveChannel) = viewModelScope.launch {
        if (groupId.startsWith(ChannelGroup.CUSTOM_PREFIX)) prefs.addToCustomGroup(groupId, channel.key)
        else prefs.copyChannel(groupId, channel.key)
    }

    /** True if [channel] is only in [groupId] because it was copied there. */
    fun isCopyIn(groupId: String, channel: LiveChannel): Boolean =
        channel.groupId != groupId && channel.key in userState.value.channelCopies[groupId].orEmpty()

    fun removeCopy(groupId: String, channel: LiveChannel) = viewModelScope.launch {
        prefs.removeChannelCopy(groupId, channel.key)
    }

    /** Groups a channel can be copied to (yours and the playlist's), minus the one it's in. */
    fun copyTargets(channel: LiveChannel): List<ChannelGroup> =
        uiState.value.groups.filter { g ->
            !g.special && g.id != channel.groupId &&
                (g.id.startsWith(ChannelGroup.CUSTOM_PREFIX) ||
                    channel.key !in userState.value.channelCopies[g.id].orEmpty())
        }

    fun previousChannel(): LiveChannel? = channelByKey(userState.value.previousChannelKey)

    /** The channel to start in the preview when Live TV opens ("Resume last channel in preview"). */
    suspend fun resumeCandidate(): LiveChannel? {
        val s = prefs.currentSettings()
        if (!s.resumeLastInPreview || !s.showPreview) return null
        val key = prefs.userState.first().lastChannelKey ?: return null
        val channels = withTimeoutOrNull(15_000) {
            displayChannels.first { list -> list.any { it.key == key } }
        } ?: return null
        return channels.firstOrNull { it.key == key }
    }

    /** The last watched channel, once per app launch, when "auto-play last channel" is on. */
    suspend fun autoPlayCandidate(): LiveChannel? {
        if (session.autoPlayedThisLaunch) return null
        session.autoPlayedThisLaunch = true
        if (!prefs.currentSettings().autoPlayLastChannel) return null
        val key = prefs.userState.first().lastChannelKey ?: return null
        val channels = withTimeoutOrNull(15_000) {
            repository.channels.first { it.isNotEmpty() }
        } ?: return null
        return channels.firstOrNull { it.key == key }
            ?.let { c -> displayChannels.value.firstOrNull { it.key == c.key } ?: c }
    }
}
