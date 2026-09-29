package com.nuvio.tv.livetv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.data.LiveTvStatus
import com.nuvio.tv.livetv.model.EpgSource
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.LiveUserState
import com.nuvio.tv.livetv.model.PlaylistSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LiveTvSettingsViewModel @Inject constructor(
    private val repository: LiveTvRepository,
    private val prefs: LiveTvPreferences,
    val driveSync: com.nuvio.tv.livetv.sync.LiveTvDriveSync
) : ViewModel() {

    val playlists: StateFlow<List<PlaylistSource>> =
        prefs.playlists.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val epgSources: StateFlow<List<EpgSource>> =
        prefs.epgSources.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val settings: StateFlow<LiveTvSettings> =
        prefs.settings.stateIn(viewModelScope, SharingStarted.Eagerly, LiveTvSettings())
    val userState: StateFlow<LiveUserState> =
        prefs.userState.stateIn(viewModelScope, SharingStarted.Eagerly, LiveUserState())
    val status: StateFlow<LiveTvStatus> = repository.status

    init {
        repository.ensureLoaded()
        driveSync.start()
    }

    fun update(transform: (LiveTvSettings) -> LiveTvSettings) {
        viewModelScope.launch { prefs.updateSettings(transform) }
    }

    /** Settings that change which programs are kept need the guide re-read. */
    fun updateAndReloadEpg(transform: (LiveTvSettings) -> LiveTvSettings) {
        viewModelScope.launch {
            prefs.updateSettings(transform)
            repository.refreshEpgOnly()
        }
    }

    fun addPlaylist(name: String, url: String, userAgent: String) =
        viewModelScope.launch { repository.addPlaylist(name, url, userAgent) }

    fun addXtream(name: String, server: String, user: String, pass: String) =
        viewModelScope.launch { repository.addXtream(name, server, user, pass) }

    fun savePlaylist(source: PlaylistSource) = viewModelScope.launch { repository.updatePlaylist(source) }
    fun removePlaylist(id: String) = viewModelScope.launch { repository.removePlaylist(id) }
    fun setPlaylistEnabled(id: String, enabled: Boolean) =
        viewModelScope.launch { repository.setPlaylistEnabled(id, enabled) }
    fun movePlaylist(id: String, delta: Int) = viewModelScope.launch { repository.movePlaylist(id, delta) }

    fun addEpg(name: String, url: String) = viewModelScope.launch { repository.addEpg(name, url) }
    fun saveEpg(source: EpgSource) = viewModelScope.launch { repository.updateEpg(source) }
    fun removeEpg(id: String) = viewModelScope.launch { repository.removeEpg(id) }
    fun setEpgEnabled(id: String, enabled: Boolean) = viewModelScope.launch { repository.setEpgEnabled(id, enabled) }

    fun refreshAll() = repository.refreshAll(force = true)

    fun unhideChannel(key: String) = viewModelScope.launch { prefs.setChannelHidden(key, false) }
    fun unhideAllChannels() = viewModelScope.launch { prefs.clearHiddenChannels() }
    fun unhideGroup(id: String) = viewModelScope.launch { prefs.setGroupHidden(id, false) }
    fun unhideAllGroups() = viewModelScope.launch { prefs.clearHiddenGroups() }
    fun clearFavorites() = viewModelScope.launch { prefs.clearFavorites() }
    fun clearRecent() = viewModelScope.launch { prefs.clearRecent() }
    fun resetChannelEdits() = viewModelScope.launch { prefs.resetChannelEdits() }
    fun resetGroupEdits() = viewModelScope.launch { prefs.resetGroupEdits() }
    fun resetChannelOrder() = viewModelScope.launch { prefs.resetChannelOrder() }
    fun clearChannelCopies() = viewModelScope.launch { prefs.clearChannelCopies() }
    fun resetEpgAssignments() = viewModelScope.launch {
        prefs.clearEpgOverrides()
        repository.rematchEpg()
    }
    fun deleteCustomGroup(id: String) = viewModelScope.launch { prefs.deleteCustomGroup(id) }

    /** Readable name for a hidden channel key, even if its playlist is currently disabled. */
    fun channelLabel(key: String): String {
        repository.channels.value.firstOrNull { it.key == key }?.let { return "${it.number}  ${it.name}" }
        return key.substringAfterLast('|').substringBefore('#')
    }

    fun groupLabel(id: String): String {
        userState.value.customGroups.firstOrNull { it.id == id }?.let { return it.name }
        userState.value.groupNames[id]?.let { return it }
        repository.channels.value.firstOrNull { it.groupId == id }?.let { return it.group }
        return id.substringAfter("::")
    }
}
