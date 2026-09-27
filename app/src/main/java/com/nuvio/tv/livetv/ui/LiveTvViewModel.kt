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

/** In-memory session shared by the guide and the full-screen player (current group, etc). */
@Singleton
class LiveTvSession @Inject constructor() {
    val currentGroupId = MutableStateFlow<String?>(null)
    var autoPlayedThisLaunch = false
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
    val customGroups: List<ChannelGroup> = emptyList()
)

@HiltViewModel
class LiveTvViewModel @Inject constructor(
    private val repository: LiveTvRepository,
    private val prefs: LiveTvPreferences,
    val playback: LiveTvPlaybackController,
    private val session: LiveTvSession,
    private val posterResolver: com.nuvio.tv.livetv.data.LiveTvPosterResolver
) : ViewModel() {

    val settings: StateFlow<LiveTvSettings> = prefs.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, LiveTvSettings())

    val userState: StateFlow<LiveUserState> = prefs.userState
        .stateIn(viewModelScope, SharingStarted.Eagerly, LiveUserState())

    val status: StateFlow<LiveTvStatus> = repository.status
    val playbackState: StateFlow<LivePlaybackState> = playback.state

    private val _now = MutableStateFlow(System.currentTimeMillis())
    val now: StateFlow<Long> = _now

    private val searchQuery = MutableStateFlow("")

    /** Programmes with the user's EPG time offset applied. */
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

    /** Channels with the user's renames and renumbering applied. */
    val displayChannels: StateFlow<List<LiveChannel>> = combine(repository.channels, userState) { channels, user ->
        if (user.channelNames.isEmpty() && user.channelNumbers.isEmpty() && user.groupNames.isEmpty()) channels
        else channels.map { c ->
            c.copy(
                name = user.channelNames[c.key] ?: c.name,
                number = user.channelNumbers[c.key] ?: c.number,
                group = user.groupNames[c.groupId] ?: c.group
            )
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val uiState: StateFlow<LiveTvUiState> = combine(
        combine(displayChannels, userState, settings) { a, b, c -> Triple(a, b, c) },
        session.currentGroupId,
        searchQuery,
        hasSources
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
            settings.collect { playback.autoReconnect = it.autoReconnect }
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
        val visible = channels.filter { it.key !in user.hiddenChannels && it.groupId !in user.hiddenGroups }
        val byKey = visible.associateBy { it.key }
        val favorites = user.favorites.mapNotNull { byKey[it] }
        val recent = user.recent.mapNotNull { byKey[it] }

        fun sorted(list: List<LiveChannel>): List<LiveChannel> = when (s.channelSort) {
            ChannelSort.PLAYLIST -> list
            ChannelSort.NUMBER -> list.sortedBy { it.number }
            ChannelSort.NAME -> list.sortedBy { it.name.lowercase() }
        }

        val groups = mutableListOf<ChannelGroup>()
        groups += ChannelGroup(ChannelGroup.SEARCH, "Search", 0, special = true)
        if (s.showFavoritesGroup) groups += ChannelGroup(ChannelGroup.FAVORITES, "Favourites", favorites.size, special = true)
        if (s.showRecentGroup) groups += ChannelGroup(ChannelGroup.RECENT, "Recently watched", recent.size, special = true)
        groups += ChannelGroup(ChannelGroup.ALL, "All channels", visible.size, special = true)

        // Your own groups first, then the playlist's groups, then the saved order on top.
        val regular = mutableListOf<ChannelGroup>()
        val custom = user.customGroups
            .filter { it.id !in user.hiddenGroups }
            .map { g -> ChannelGroup(g.id, g.name, g.channelKeys.count { it in byKey }) }
        regular += custom
        val playlistGroups = LinkedHashMap<String, Pair<String, Int>>()
        visible.forEach { c ->
            val cur = playlistGroups[c.groupId]
            playlistGroups[c.groupId] = (cur?.first ?: c.group) to ((cur?.second ?: 0) + 1)
        }
        playlistGroups.forEach { (id, v) -> regular += ChannelGroup(id, v.first, v.second) }
        val orderIndex = user.groupOrder.withIndex().associate { (i, id) -> id to i }
        val orderedRegular = regular.withIndex()
            .sortedWith(compareBy<IndexedValue<ChannelGroup>>({ orderIndex[it.value.id] ?: Int.MAX_VALUE }, { it.index }))
            .map { it.value }
        groups += orderedRegular

        val groupId = groupIdRaw?.takeIf { id -> groups.any { it.id == id } } ?: ChannelGroup.ALL
        val list = when {
            groupId == ChannelGroup.ALL -> sorted(visible)
            groupId == ChannelGroup.FAVORITES -> favorites
            groupId == ChannelGroup.RECENT -> recent
            groupId == ChannelGroup.SEARCH -> {
                val q = query.trim()
                if (q.isEmpty()) emptyList() else sorted(visible.filter {
                    it.name.contains(q, ignoreCase = true) || it.number.toString() == q
                })
            }
            groupId.startsWith(ChannelGroup.CUSTOM_PREFIX) ->
                user.customGroups.firstOrNull { it.id == groupId }?.channelKeys?.mapNotNull { byKey[it] }.orEmpty()
            else -> sorted(visible.filter { it.groupId == groupId })
        }
        return LiveTvUiState(
            groups = groups,
            selectedGroupId = groupId,
            channels = list,
            allVisibleChannels = sorted(visible),
            hasSources = hasSrc,
            searchQuery = query,
            orderableGroupIds = orderedRegular.map { it.id },
            customGroups = custom
        )
    }

    // ------------------------------------------------------------ actions

    fun selectGroup(id: String) {
        session.currentGroupId.value = id
        if (id != ChannelGroup.SEARCH) viewModelScope.launch { prefs.setLastGroup(id) }
    }

    fun setSearchQuery(q: String) {
        searchQuery.value = q
        session.currentGroupId.value = ChannelGroup.SEARCH
    }

    fun preview(channel: LiveChannel) {
        playback.play(channel)
        viewModelScope.launch { prefs.recordWatched(channel.key) }
    }

    fun playCatchup(channel: LiveChannel, program: EpgProgram): Boolean {
        val catchup = channel.catchup ?: return false
        val now = System.currentTimeMillis()
        if (!CatchupUrlBuilder.isAvailable(catchup, program.startMs, now)) return false
        val shift = settings.value.epgOffsetMinutes * 60_000L
        val url = CatchupUrlBuilder.build(channel.url, catchup, program.startMs - shift, program.stopMs - shift, now)
            ?: return false
        playback.play(channel, overrideUrl = url, catchupTitle = program.title)
        return true
    }

    fun toggleFavorite(channel: LiveChannel) = viewModelScope.launch { prefs.toggleFavorite(channel.key) }
    fun moveFavorite(channel: LiveChannel, delta: Int) = viewModelScope.launch { prefs.moveFavorite(channel.key, delta) }
    fun hideChannel(channel: LiveChannel) = viewModelScope.launch { prefs.setChannelHidden(channel.key, true) }
    fun hideGroup(groupId: String) = viewModelScope.launch {
        prefs.setGroupHidden(groupId, true)
        if (session.currentGroupId.value == groupId) selectGroup(ChannelGroup.ALL)
    }
    fun refresh() = repository.refreshAll(force = true)

    /** The poster Nuvio's catalogs would show for this programme, or null if there's no good match. */
    suspend fun posterFor(programTitle: String): String? = posterResolver.posterFor(programTitle)

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
        if (session.currentGroupId.value == groupId) selectGroup(ChannelGroup.ALL)
    }

    fun channelByKey(key: String?): LiveChannel? =
        key?.let { k -> displayChannels.value.firstOrNull { it.key == k } }

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

    fun channelByNumber(number: Int): LiveChannel? =
        uiState.value.allVisibleChannels.firstOrNull { it.number == number }

    fun previousChannel(): LiveChannel? = channelByKey(userState.value.previousChannelKey)

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
