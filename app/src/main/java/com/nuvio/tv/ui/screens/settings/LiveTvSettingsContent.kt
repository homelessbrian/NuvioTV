package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.livetv.model.ChannelSort
import com.nuvio.tv.livetv.model.EpgSource
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.PlaylistSource
import com.nuvio.tv.livetv.model.ZapMode
import com.nuvio.tv.livetv.ui.LiveTvSettingsViewModel
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.collection.NuvioTextField
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

private const val LIVE_TV_TITLE = "Live TV"
private const val LIVE_TV_SUBTITLE = "Playlists, TV guide and channel options"

/** Standalone version, opened from the guide's long-press menu. */
@Composable
fun LiveTvSettingsScreen(
    onBack: () -> Unit = {},
    viewModel: LiveTvSettingsViewModel = hiltViewModel()
) {
    SettingsStandaloneScaffold(title = LIVE_TV_TITLE, subtitle = LIVE_TV_SUBTITLE) {
        LiveTvSettingsContent(viewModel = viewModel)
    }
}

private sealed interface LiveDialog {
    data object AddM3u : LiveDialog
    data object AddXtream : LiveDialog
    data object AddEpg : LiveDialog
    data class PlaylistActions(val source: PlaylistSource) : LiveDialog
    data class EditPlaylist(val source: PlaylistSource) : LiveDialog
    data class EpgActions(val source: EpgSource) : LiveDialog
    data class EditEpg(val source: EpgSource) : LiveDialog
    data object HiddenChannels : LiveDialog
    data object HiddenGroups : LiveDialog
    data object EpgShift : LiveDialog
    data object ZapModeChoice : LiveDialog
    data object BannerTime : LiveDialog
    data object PlaylistRefresh : LiveDialog
    data object EpgRefresh : LiveDialog
    data object FutureDays : LiveDialog
    data object PastHours : LiveDialog
    data object SortChoice : LiveDialog
    data object HighlightChoice : LiveDialog
    data object CustomGroups : LiveDialog
}

private val EPG_OFFSETS = (-24..24).map { it * 30 }
private val REFRESH_HOURS = listOf(3, 6, 12, 24, 48, 72, 168)
private val BANNER_SECONDS = listOf(3, 5, 8, 10, 15)
private val FUTURE_DAYS = listOf(1, 2, 3, 5, 7, 10, 14)
private val PAST_HOURS = listOf(6, 12, 24, 48, 72, 168)

private fun offsetLabel(min: Int): String {
    if (min == 0) return "None"
    val sign = if (min > 0) "+" else "−"
    val a = kotlin.math.abs(min)
    return if (a % 60 == 0) "$sign${a / 60} h" else "$sign${a / 60} h ${a % 60} min"
}

private fun hoursLabel(h: Int): String = when {
    h >= 24 && h % 24 == 0 -> if (h == 24) "1 day" else "${h / 24} days"
    else -> "$h hours"
}

private fun sourceStatus(enabled: Boolean, updated: Long, error: String?, count: String): String {
    if (!enabled) return "Disabled"
    val parts = mutableListOf<String>()
    if (error != null) parts += "Error: $error"
    if (updated > 0) {
        parts += count
        parts += "Updated " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(updated))
    } else if (error == null) {
        parts += "Not downloaded yet"
    }
    return parts.joinToString(" · ")
}

@Composable
fun LiveTvSettingsContent(
    viewModel: LiveTvSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val epgs by viewModel.epgSources.collectAsStateWithLifecycle()
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val user by viewModel.userState.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<LiveDialog?>(null) }

    fun update(t: (LiveTvSettings) -> LiveTvSettings) = viewModel.update(t)

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        SettingsDetailHeader(
            title = LIVE_TV_TITLE,
            subtitle = if (status.loading) (status.message ?: "Updating…") else LIVE_TV_SUBTITLE
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            // ------------------------------------------------------------ playlists
            item(key = "playlists") {
                SettingsGroupCard(title = "Playlists") {
                    playlists.forEachIndexed { index, pl ->
                        SettingsActionRow(
                            title = pl.name + if (pl.isXtream) " (Xtream)" else "",
                            subtitle = sourceStatus(pl.enabled, pl.lastUpdatedMs, pl.lastError, "${pl.channelCount} channels"),
                            value = if (pl.enabled) "On" else "Off",
                            leadingIcon = Icons.Default.LiveTv,
                            onClick = { dialog = LiveDialog.PlaylistActions(pl) },
                            modifier = if (index == 0 && initialFocusRequester != null) {
                                Modifier.focusRequester(initialFocusRequester)
                            } else Modifier
                        )
                    }
                    SettingsActionRow(
                        title = "Add M3U playlist",
                        subtitle = "A playlist URL from your provider",
                        leadingIcon = Icons.Default.Add,
                        onClick = { dialog = LiveDialog.AddM3u },
                        modifier = if (playlists.isEmpty() && initialFocusRequester != null) {
                            Modifier.focusRequester(initialFocusRequester)
                        } else Modifier
                    )
                    SettingsActionRow(
                        title = "Add Xtream Codes login",
                        subtitle = "Server, username and password — the guide is added automatically",
                        leadingIcon = Icons.Default.Add,
                        onClick = { dialog = LiveDialog.AddXtream }
                    )
                }
            }

            // ------------------------------------------------------------ EPG
            item(key = "epg") {
                SettingsGroupCard(
                    title = "TV guide (EPG)",
                    subtitle = "Guides linked inside your playlists load automatically. Sources higher in the list win when two cover the same channel."
                ) {
                    epgs.forEach { e ->
                        SettingsActionRow(
                            title = e.name,
                            subtitle = sourceStatus(e.enabled, e.lastUpdatedMs, e.lastError, "${e.programCount} programmes matched"),
                            value = if (e.enabled) "On" else "Off",
                            leadingIcon = Icons.Default.Link,
                            onClick = { dialog = LiveDialog.EpgActions(e) }
                        )
                    }
                    SettingsActionRow(
                        title = "Add EPG source",
                        subtitle = "An XMLTV address (.xml or .xml.gz)",
                        leadingIcon = Icons.Default.Add,
                        onClick = { dialog = LiveDialog.AddEpg }
                    )
                    SettingsActionRow(
                        title = "Update playlists and guides now",
                        subtitle = null,
                        leadingIcon = Icons.Default.Refresh,
                        onClick = { viewModel.refreshAll() }
                    )
                }
            }

            // ------------------------------------------------------------ guide
            item(key = "guide") {
                SettingsGroupCard(title = "Guide") {
                    SettingsToggleRow("Show in side menu", "Adds Live TV to Nuvio's main menu", s.showInSidebar, { update { it.copy(showInSidebar = !it.showInSidebar) } })
                    SettingsToggleRow("Show in Nuvio search", "Matching channels and shows appear in search results", s.showInSearch, { update { it.copy(showInSearch = !it.showInSearch) } })
                    SettingsToggleRow("Show channel names", null, s.showChannelNames, { update { it.copy(showChannelNames = !it.showChannelNames) } })
                    SettingsToggleRow("Show channel numbers", null, s.showChannelNumbers, { update { it.copy(showChannelNumbers = !it.showChannelNumbers) } })
                    SettingsToggleRow("Show channel logos", null, s.showChannelLogos, { update { it.copy(showChannelLogos = !it.showChannelLogos) } })
                    SettingsToggleRow("Preview window", "Play the selected channel above the guide", s.showPreview, { update { it.copy(showPreview = !it.showPreview) } })
                    SettingsToggleRow("Programme details", "Title, time and description above the guide", s.showProgramDetails, { update { it.copy(showProgramDetails = !it.showProgramDetails) } })
                    SettingsToggleRow("Compact rows", "Fit more channels on screen", s.compactRows, { update { it.copy(compactRows = !it.compactRows) } })
                    SettingsToggleRow("Favourites group", null, s.showFavoritesGroup, { update { it.copy(showFavoritesGroup = !it.showFavoritesGroup) } })
                    SettingsToggleRow("All channels group", "Turn off to only show your groups and the playlist's groups", s.showAllChannelsGroup, { update { it.copy(showAllChannelsGroup = !it.showAllChannelsGroup) } })
                    SettingsToggleRow("Show channel counts", "The number of channels next to each group", s.showGroupCounts, { update { it.copy(showGroupCounts = !it.showGroupCounts) } })
                    SettingsToggleRow("Recently watched group", null, s.showRecentGroup, { update { it.copy(showRecentGroup = !it.showRecentGroup) } })
                    SettingsToggleRow("Remember last group", null, s.rememberLastGroup, { update { it.copy(rememberLastGroup = !it.rememberLastGroup) } })
                    SettingsToggleRow("24-hour clock", null, s.use24HourClock, { update { it.copy(use24HourClock = !it.use24HourClock) } })
                    SettingsActionRow(
                        title = "Highlight style",
                        subtitle = "How the selected channel and programme are marked",
                        value = if (s.solidHighlight) "Solid" else "Outline",
                        onClick = { dialog = LiveDialog.HighlightChoice }
                    )
                    SettingsActionRow(
                        title = "EPG time shift",
                        subtitle = "Fix a guide that runs early or late",
                        value = offsetLabel(s.epgOffsetMinutes),
                        onClick = { dialog = LiveDialog.EpgShift }
                    )
                }
            }

            // ------------------------------------------------------------ playback
            item(key = "playback") {
                SettingsGroupCard(title = "Playback") {
                    SettingsToggleRow("OK opens full screen", "Skip the preview window", s.openFullscreenOnSelect, { update { it.copy(openFullscreenOnSelect = !it.openFullscreenOnSelect) } })
                    SettingsToggleRow("Resume last channel in preview", "Opening Live TV starts your last channel in the preview window and highlights it", s.resumeLastInPreview, { update { it.copy(resumeLastInPreview = !it.resumeLastInPreview) } })
                    SettingsToggleRow("Auto-play last channel", "When Live TV is opened", s.autoPlayLastChannel, { update { it.copy(autoPlayLastChannel = !it.autoPlayLastChannel) } })
                    SettingsToggleRow("Reverse channel up / down", null, s.reverseZap, { update { it.copy(reverseZap = !it.reverseZap) } })
                    SettingsToggleRow("Reconnect automatically", "Retry when a stream drops", s.autoReconnect, { update { it.copy(autoReconnect = !it.autoReconnect) } })
                    SettingsActionRow(
                        title = "Channel switching",
                        subtitle = null,
                        value = if (s.zapMode == ZapMode.GROUP) "Current group" else "All channels",
                        onClick = { dialog = LiveDialog.ZapModeChoice }
                    )
                    SettingsActionRow(
                        title = "Info banner duration",
                        subtitle = null,
                        value = "${s.infoBannerSeconds} s",
                        onClick = { dialog = LiveDialog.BannerTime }
                    )
                }
            }

            // ------------------------------------------------------------ updates
            item(key = "updates") {
                SettingsGroupCard(title = "Updates") {
                    SettingsActionRow("Update playlists every", null, hoursLabel(s.playlistRefreshHours), onClick = { dialog = LiveDialog.PlaylistRefresh })
                    SettingsActionRow("Update guides every", null, hoursLabel(s.epgRefreshHours), onClick = { dialog = LiveDialog.EpgRefresh })
                    SettingsActionRow("Guide days ahead", null, "${s.epgFutureDays}", onClick = { dialog = LiveDialog.FutureDays })
                    SettingsActionRow("Past guide kept (catch-up)", null, hoursLabel(s.epgPastHours), onClick = { dialog = LiveDialog.PastHours })
                }
            }

            // ------------------------------------------------------------ channels
            item(key = "channels") {
                SettingsGroupCard(
                    title = "Channels",
                    subtitle = "Long-press a channel in the guide to rename, renumber or add it to a group. Long-press a group to rename, move or hide it."
                ) {
                    SettingsActionRow(
                        title = "Channel order",
                        subtitle = "Favourites and your own groups keep the order you set",
                        value = when (s.channelSort) {
                            ChannelSort.PLAYLIST -> "Playlist order"
                            ChannelSort.NUMBER -> "Channel number"
                            ChannelSort.NAME -> "Name"
                        },
                        onClick = { dialog = LiveDialog.SortChoice }
                    )
                    SettingsActionRow("My groups", "Groups you created", "${user.customGroups.size}", onClick = { dialog = LiveDialog.CustomGroups })
                    SettingsActionRow("Hidden channels", "Show channels again", "${user.hiddenChannels.size}", leadingIcon = Icons.Default.VisibilityOff, onClick = { dialog = LiveDialog.HiddenChannels })
                    SettingsActionRow("Hidden groups", "Show groups again", "${user.hiddenGroups.size}", leadingIcon = Icons.Default.VisibilityOff, onClick = { dialog = LiveDialog.HiddenGroups })
                    SettingsActionRow("Clear favourites", null, "${user.favorites.size}", onClick = { viewModel.clearFavorites() })
                    SettingsActionRow("Clear recently watched", null, "${user.recent.size}", onClick = { viewModel.clearRecent() })
                    SettingsActionRow(
                        "Reset channel names and numbers", null,
                        "${(user.channelNames.keys + user.channelNumbers.keys).size}",
                        onClick = { viewModel.resetChannelEdits() }
                    )
                    SettingsActionRow("Reset group names and order", null, onClick = { viewModel.resetGroupEdits() })
                }
            }
        }
    }

    // ---------------------------------------------------------------- dialogs
    val close = { dialog = null }
    when (val d = dialog) {
        null -> Unit
        LiveDialog.AddM3u -> SourceFormDialog(
            title = "Add M3U playlist",
            fields = listOf(
                FormField("Name", "My provider"),
                FormField("Playlist URL", "http://…/playlist.m3u"),
                FormField("User agent (optional)", "Leave empty for default")
            ),
            onDismiss = close,
            onSave = { v, _ -> if (v[1].isNotBlank()) viewModel.addPlaylist(v[0], v[1], v[2]); close() }
        )
        LiveDialog.AddXtream -> SourceFormDialog(
            title = "Add Xtream Codes login",
            fields = listOf(
                FormField("Name", "My provider"),
                FormField("Server", "http://host:port"),
                FormField("Username", ""),
                FormField("Password", "")
            ),
            onDismiss = close,
            onSave = { v, _ -> if (v[1].isNotBlank()) viewModel.addXtream(v[0], v[1], v[2], v[3]); close() }
        )
        LiveDialog.AddEpg -> SourceFormDialog(
            title = "Add EPG source",
            fields = listOf(FormField("Name", "Guide"), FormField("XMLTV URL", "http://…/epg.xml.gz")),
            onDismiss = close,
            onSave = { v, _ -> if (v[1].isNotBlank()) viewModel.addEpg(v[0], v[1]); close() }
        )
        is LiveDialog.PlaylistActions -> ActionListDialog(
            title = d.source.name,
            actions = listOf(
                "Edit" to { dialog = LiveDialog.EditPlaylist(d.source) },
                (if (d.source.enabled) "Disable" else "Enable") to { viewModel.setPlaylistEnabled(d.source.id, !d.source.enabled); close() },
                "Update now" to { viewModel.savePlaylist(d.source); close() },
                "Move up" to { viewModel.movePlaylist(d.source.id, -1); close() },
                "Move down" to { viewModel.movePlaylist(d.source.id, 1); close() },
                "Delete" to { viewModel.removePlaylist(d.source.id); close() }
            ),
            onDismiss = close
        )
        is LiveDialog.EditPlaylist -> {
            val src = d.source
            if (src.isXtream) {
                SourceFormDialog(
                    title = "Edit Xtream login",
                    fields = listOf(
                        FormField("Name", "", src.name),
                        FormField("Server", "", src.xtreamServer),
                        FormField("Username", "", src.xtreamUsername),
                        FormField("Password", "", src.xtreamPassword),
                        FormField("User agent (optional)", "", src.userAgent)
                    ),
                    toggle = "Load built-in guide" to src.useEmbeddedEpg,
                    onDismiss = close,
                    onSave = { v, t ->
                        viewModel.savePlaylist(src.copy(name = v[0], xtreamServer = v[1], xtreamUsername = v[2], xtreamPassword = v[3], userAgent = v[4], useEmbeddedEpg = t))
                        close()
                    }
                )
            } else {
                SourceFormDialog(
                    title = "Edit playlist",
                    fields = listOf(
                        FormField("Name", "", src.name),
                        FormField("Playlist URL", "", src.url),
                        FormField("User agent (optional)", "", src.userAgent)
                    ),
                    toggle = "Load guide linked in playlist" to src.useEmbeddedEpg,
                    onDismiss = close,
                    onSave = { v, t ->
                        viewModel.savePlaylist(src.copy(name = v[0], url = v[1], userAgent = v[2], useEmbeddedEpg = t))
                        close()
                    }
                )
            }
        }
        is LiveDialog.EpgActions -> ActionListDialog(
            title = d.source.name,
            actions = listOf(
                "Edit" to { dialog = LiveDialog.EditEpg(d.source) },
                (if (d.source.enabled) "Disable" else "Enable") to { viewModel.setEpgEnabled(d.source.id, !d.source.enabled); close() },
                "Update now" to { viewModel.saveEpg(d.source); close() },
                "Delete" to { viewModel.removeEpg(d.source.id); close() }
            ),
            onDismiss = close
        )
        is LiveDialog.EditEpg -> SourceFormDialog(
            title = "Edit EPG source",
            fields = listOf(FormField("Name", "", d.source.name), FormField("XMLTV URL", "", d.source.url)),
            onDismiss = close,
            onSave = { v, _ -> viewModel.saveEpg(d.source.copy(name = v[0], url = v[1])); close() }
        )
        LiveDialog.HiddenChannels -> UnhideDialog(
            title = "Hidden channels",
            entries = user.hiddenChannels.sorted().map { it to viewModel.channelLabel(it) },
            onUnhide = { viewModel.unhideChannel(it) },
            onUnhideAll = { viewModel.unhideAllChannels(); close() },
            onDismiss = close
        )
        LiveDialog.HiddenGroups -> UnhideDialog(
            title = "Hidden groups",
            entries = user.hiddenGroups.sorted().map { it to viewModel.groupLabel(it) },
            onUnhide = { viewModel.unhideGroup(it) },
            onUnhideAll = { viewModel.unhideAllGroups(); close() },
            onDismiss = close
        )
        LiveDialog.EpgShift -> SettingsSingleChoiceDialog(
            title = "EPG time shift",
            options = EPG_OFFSETS.map { SettingsPickerOption(it, offsetLabel(it)) },
            selectedValue = s.epgOffsetMinutes,
            onOptionSelected = { v -> update { it.copy(epgOffsetMinutes = v) }; close() },
            onDismiss = close
        )
        LiveDialog.ZapModeChoice -> SettingsSingleChoiceDialog(
            title = "Channel up / down switches within",
            options = listOf(
                SettingsPickerOption(ZapMode.GROUP, "Current group"),
                SettingsPickerOption(ZapMode.ALL, "All channels")
            ),
            selectedValue = s.zapMode,
            onOptionSelected = { v -> update { it.copy(zapMode = v) }; close() },
            onDismiss = close
        )
        LiveDialog.BannerTime -> SettingsSingleChoiceDialog(
            title = "Info banner duration",
            options = BANNER_SECONDS.map { SettingsPickerOption(it, "$it seconds") },
            selectedValue = s.infoBannerSeconds,
            onOptionSelected = { v -> update { it.copy(infoBannerSeconds = v) }; close() },
            onDismiss = close
        )
        LiveDialog.PlaylistRefresh -> SettingsSingleChoiceDialog(
            title = "Update playlists every",
            options = REFRESH_HOURS.map { SettingsPickerOption(it, hoursLabel(it)) },
            selectedValue = s.playlistRefreshHours,
            onOptionSelected = { v -> update { it.copy(playlistRefreshHours = v) }; close() },
            onDismiss = close
        )
        LiveDialog.EpgRefresh -> SettingsSingleChoiceDialog(
            title = "Update guides every",
            options = REFRESH_HOURS.map { SettingsPickerOption(it, hoursLabel(it)) },
            selectedValue = s.epgRefreshHours,
            onOptionSelected = { v -> update { it.copy(epgRefreshHours = v) }; close() },
            onDismiss = close
        )
        LiveDialog.FutureDays -> SettingsSingleChoiceDialog(
            title = "Guide days ahead",
            options = FUTURE_DAYS.map { SettingsPickerOption(it, if (it == 1) "1 day" else "$it days") },
            selectedValue = s.epgFutureDays,
            onOptionSelected = { v -> viewModel.updateAndReloadEpg { it.copy(epgFutureDays = v) }; close() },
            onDismiss = close
        )
        LiveDialog.SortChoice -> SettingsSingleChoiceDialog(
            title = "Channel order",
            options = listOf(
                SettingsPickerOption(ChannelSort.PLAYLIST, "Playlist order"),
                SettingsPickerOption(ChannelSort.NUMBER, "Channel number"),
                SettingsPickerOption(ChannelSort.NAME, "Name (A–Z)")
            ),
            selectedValue = s.channelSort,
            onOptionSelected = { v -> update { it.copy(channelSort = v) }; close() },
            onDismiss = close
        )
        LiveDialog.HighlightChoice -> SettingsSingleChoiceDialog(
            title = "Highlight style",
            options = listOf(
                SettingsPickerOption(false, "Outline", "Dark tint with a coloured border — logos stay visible"),
                SettingsPickerOption(true, "Solid", "Fill with the theme's accent colour")
            ),
            selectedValue = s.solidHighlight,
            onOptionSelected = { v -> update { it.copy(solidHighlight = v) }; close() },
            onDismiss = close
        )
        LiveDialog.CustomGroups -> UnhideDialog(
            title = "My groups",
            entries = user.customGroups.map { it.id to "${it.name} (${it.channelKeys.size})" },
            onUnhide = { viewModel.deleteCustomGroup(it) },
            onUnhideAll = { user.customGroups.forEach { g -> viewModel.deleteCustomGroup(g.id) }; close() },
            onDismiss = close,
            itemAction = "Delete",
            allLabel = "Delete all",
            emptyText = "You haven't made any groups yet. Long-press a channel in the guide and choose \"Add to group\".",
            hintText = "Select a group to delete it. The channels stay in your playlist."
        )
        LiveDialog.PastHours -> SettingsSingleChoiceDialog(
            title = "Past guide kept",
            options = PAST_HOURS.map { SettingsPickerOption(it, hoursLabel(it)) },
            selectedValue = s.epgPastHours,
            onOptionSelected = { v -> viewModel.updateAndReloadEpg { it.copy(epgPastHours = v) }; close() },
            onDismiss = close
        )
    }
}

// ==================================================================== dialogs

private data class FormField(val label: String, val hint: String, val initial: String = "")

@Composable
private fun SourceFormDialog(
    title: String,
    fields: List<FormField>,
    onDismiss: () -> Unit,
    onSave: (List<String>, Boolean) -> Unit,
    toggle: Pair<String, Boolean>? = null
) {
    val values = remember { fields.map { mutableStateOf(it.initial) } }
    var toggleValue by remember { mutableStateOf(toggle?.second ?: true) }
    val firstField = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { firstField.requestFocus() }
    }
    NuvioDialog(onDismiss = onDismiss, title = title, width = 620.dp) {
        fields.forEachIndexed { i, field ->
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
                Text(
                    text = field.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary
                )
                NuvioTextField(
                    value = values[i].value,
                    onValueChange = { values[i].value = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = field.hint,
                    focusRequester = if (i == 0) firstField else null
                )
            }
        }
        if (toggle != null) {
            SettingsToggleRow(
                title = toggle.first,
                subtitle = null,
                checked = toggleValue,
                onToggle = { toggleValue = !toggleValue }
            )
        }
        SettingsDialogActionRow {
            SettingsDialogActionButton(text = "Cancel", onClick = onDismiss)
            SettingsDialogActionButton(
                text = "Save",
                onClick = { onSave(values.map { it.value.trim() }, toggleValue) },
                primary = true
            )
        }
    }
}

@Composable
private fun ActionListDialog(
    title: String,
    actions: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { first.requestFocus() }
    }
    NuvioDialog(onDismiss = onDismiss, title = title, width = 460.dp) {
        actions.forEachIndexed { i, (label, action) ->
            SettingsActionRow(
                title = label,
                subtitle = null,
                onClick = action,
                modifier = if (i == 0) Modifier.focusRequester(first) else Modifier
            )
        }
    }
}

@Composable
private fun UnhideDialog(
    title: String,
    entries: List<Pair<String, String>>,
    onUnhide: (String) -> Unit,
    onUnhideAll: () -> Unit,
    onDismiss: () -> Unit,
    itemAction: String = "Show",
    allLabel: String = "Show all",
    emptyText: String = "Nothing is hidden.",
    hintText: String = "Select an item to show it again."
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { first.requestFocus() }
    }
    NuvioDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = if (entries.isEmpty()) emptyText else hintText,
        width = 560.dp
    ) {
        if (entries.isEmpty()) {
            SettingsDialogActionRow {
                SettingsDialogActionButton(text = "Close", onClick = onDismiss, primary = true)
            }
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
            ) {
                item(key = "__all__") {
                    SettingsActionRow(
                        title = allLabel,
                        subtitle = null,
                        value = "${entries.size}",
                        onClick = onUnhideAll,
                        modifier = Modifier.focusRequester(first)
                    )
                }
                items(entries, key = { it.first }) { (key, label) ->
                    SettingsActionRow(title = label, subtitle = null, value = itemAction, onClick = { onUnhide(key) })
                }
            }
        }
    }
}
