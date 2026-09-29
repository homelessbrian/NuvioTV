package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.asImageBitmap
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
    data object ConfirmRestore : LiveDialog
    data class ConfirmDeletePlaylist(val source: PlaylistSource) : LiveDialog
    data class ConfirmDeleteEpg(val source: EpgSource) : LiveDialog
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
    val drive by viewModel.driveSync.state.collectAsStateWithLifecycle()
    var driveExpanded by remember { mutableStateOf(false) }

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
            // ------------------------------------------------------------ Google Drive sync
            item(key = "drive") {
                DriveSyncCard(
                    modifier = if (initialFocusRequester != null) Modifier.focusRequester(initialFocusRequester) else Modifier,
                    state = drive,
                    expanded = driveExpanded,
                    onToggleExpanded = { driveExpanded = !driveExpanded },
                    onConnect = { viewModel.driveSync.beginSignIn() },
                    onAutoSync = { viewModel.driveSync.setAutoSync(it) },
                    onBackUp = { viewModel.driveSync.backUpNow() },
                    onRestore = { dialog = LiveDialog.ConfirmRestore },
                    onDisconnect = { viewModel.driveSync.disconnect() }
                )
            }

            // ------------------------------------------------------------ playlists
            item(key = "playlists") {
                SettingsGroupCard(title = "Playlists", subtitle = "Your channel lists. Select one to edit, update, turn off or delete it.") {
                    playlists.forEachIndexed { index, pl ->
                        SettingsActionRow(
                            title = pl.name + if (pl.isXtream) " (Xtream)" else "",
                            subtitle = sourceStatus(pl.enabled, pl.lastUpdatedMs, pl.lastError, "${pl.channelCount} channels"),
                            value = if (pl.enabled) "On" else "Off",
                            leadingIcon = Icons.Default.LiveTv,
                            onClick = { dialog = LiveDialog.PlaylistActions(pl) }
                        )
                    }
                    SettingsActionRow(
                        title = "Add M3U playlist",
                        subtitle = "Add a channel list using the M3U link from your provider",
                        leadingIcon = Icons.Default.Add,
                        onClick = { dialog = LiveDialog.AddM3u }
                    )
                    SettingsActionRow(
                        title = "Add Xtream Codes login",
                        subtitle = "Sign in with the server, username and password from your provider. Its TV guide is added too.",
                        leadingIcon = Icons.Default.Add,
                        onClick = { dialog = LiveDialog.AddXtream }
                    )
                }
            }

            // ------------------------------------------------------------ EPG
            item(key = "epg") {
                SettingsGroupCard(
                    title = "TV guide sources (EPG)",
                    subtitle = "Where TV listings come from. Guides linked in your playlists load automatically; add more here."
                ) {
                    epgs.forEach { e ->
                        SettingsActionRow(
                            title = e.name,
                            subtitle = sourceStatus(e.enabled, e.lastUpdatedMs, e.lastError, "${e.programCount} programs matched"),
                            value = if (e.enabled) "On" else "Off",
                            leadingIcon = Icons.Default.Link,
                            onClick = { dialog = LiveDialog.EpgActions(e) }
                        )
                    }
                    SettingsActionRow(
                        title = "Add EPG source",
                        subtitle = "Add a TV guide using an XMLTV link (ending in .xml or .xml.gz)",
                        leadingIcon = Icons.Default.Add,
                        onClick = { dialog = LiveDialog.AddEpg }
                    )
                    SettingsActionRow(
                        title = "Update playlists and guides now",
                        subtitle = "Downloads the latest channels and listings instead of waiting for the next automatic update",
                        leadingIcon = Icons.Default.Refresh,
                        onClick = { viewModel.refreshAll() }
                    )
                }
            }

            // ------------------------------------------------------------ guide layout
            item(key = "layout") {
                SettingsGroupCard(title = "Guide layout", subtitle = "What the TV guide shows, and how much fits on screen") {
                    SettingsToggleRow(
                        "Hide preview window",
                        "Removes the small video of the highlighted channel from the top-right corner",
                        !s.showPreview,
                        { update { it.copy(showPreview = !it.showPreview) } }
                    )
                    SettingsToggleRow(
                        "Hide info panel",
                        "Removes the top-left panel with the highlighted show's poster, title, time and description",
                        !s.showProgramDetails,
                        { update { it.copy(showProgramDetails = !it.showProgramDetails) } }
                    )
                    SettingsToggleRow(
                        "Smaller info panel and preview",
                        "Shrinks the top of the guide to about two-thirds of its height, so more channels fit",
                        s.smallHeader,
                        { update { it.copy(smallHeader = !it.smallHeader) } }
                    )
                    SettingsToggleRow(
                        "Compact channel rows",
                        "Makes each channel row slimmer, so about two more channels fit",
                        s.compactRows,
                        { update { it.copy(compactRows = !it.compactRows) } }
                    )
                    SettingsToggleRow(
                        "Hide channel numbers",
                        "Removes the number in front of each channel",
                        !s.showChannelNumbers,
                        { update { it.copy(showChannelNumbers = !it.showChannelNumbers) } }
                    )
                    SettingsToggleRow(
                        "Hide channel logos",
                        "Removes the logo in front of each channel name",
                        !s.showChannelLogos,
                        { update { it.copy(showChannelLogos = !it.showChannelLogos) } }
                    )
                    SettingsToggleRow(
                        "Hide channel names",
                        "Shows only numbers and logos, which leaves more room for the schedule",
                        !s.showChannelNames,
                        { update { it.copy(showChannelNames = !it.showChannelNames) } }
                    )
                    SettingsActionRow(
                        title = "Highlight style",
                        subtitle = "How the selected show is marked: an outline, or filled with your theme color",
                        value = if (s.solidHighlight) "Solid" else "Outline",
                        onClick = { dialog = LiveDialog.HighlightChoice }
                    )
                    SettingsToggleRow(
                        "24-hour clock",
                        "Shows times like 20:30 instead of 8:30 PM",
                        s.use24HourClock,
                        { update { it.copy(use24HourClock = !it.use24HourClock) } }
                    )
                }
            }

            // ------------------------------------------------------------ groups
            item(key = "groups") {
                SettingsGroupCard(title = "Channel groups", subtitle = "The group list that slides out when you press Left in the guide") {
                    SettingsToggleRow(
                        "Hide Favorites group",
                        "Removes the group of channels you've marked as favorites",
                        !s.showFavoritesGroup,
                        { update { it.copy(showFavoritesGroup = !it.showFavoritesGroup) } }
                    )
                    SettingsToggleRow(
                        "Hide Recently watched group",
                        "Removes the group of channels you watched last",
                        !s.showRecentGroup,
                        { update { it.copy(showRecentGroup = !it.showRecentGroup) } }
                    )
                    SettingsToggleRow(
                        "Hide All channels group",
                        "Removes the group with every channel, leaving your groups and the playlist's groups",
                        !s.showAllChannelsGroup,
                        { update { it.copy(showAllChannelsGroup = !it.showAllChannelsGroup) } }
                    )
                    SettingsToggleRow(
                        "Hide channel counts",
                        "Removes the number of channels shown next to each group",
                        !s.showGroupCounts,
                        { update { it.copy(showGroupCounts = !it.showGroupCounts) } }
                    )
                    SettingsToggleRow(
                        "Open on last group",
                        "Opening Live TV shows the group you last watched a channel in",
                        s.rememberLastGroup,
                        { update { it.copy(rememberLastGroup = !it.rememberLastGroup) } }
                    )
                    SettingsActionRow(
                        title = "Channel order",
                        subtitle = "How channels are sorted inside a group. Favorites and your own groups keep your order.",
                        value = when (s.channelSort) {
                            ChannelSort.PLAYLIST -> "Playlist order"
                            ChannelSort.NUMBER -> "Channel number"
                            ChannelSort.NAME -> "Name"
                        },
                        onClick = { dialog = LiveDialog.SortChoice }
                    )
                }
            }

            // ------------------------------------------------------------ playback
            item(key = "playback") {
                SettingsGroupCard(title = "Playback", subtitle = "What happens when you pick and watch a channel") {
                    SettingsToggleRow(
                        "OK opens full screen",
                        "Pressing OK on a show goes straight to full screen instead of playing it in the preview first",
                        s.openFullscreenOnSelect,
                        { update { it.copy(openFullscreenOnSelect = !it.openFullscreenOnSelect) } }
                    )
                    SettingsToggleRow(
                        "Play last channel when Live TV opens",
                        "Opening Live TV starts the channel you watched last in the preview window",
                        s.resumeLastInPreview,
                        { update { it.copy(resumeLastInPreview = !it.resumeLastInPreview) } }
                    )
                    SettingsToggleRow(
                        "Start in full screen",
                        "The first time you open Live TV after starting the app, your last channel plays in full screen",
                        s.autoPlayLastChannel,
                        { update { it.copy(autoPlayLastChannel = !it.autoPlayLastChannel) } }
                    )
                    SettingsActionRow(
                        title = "Channel up / down switches within",
                        subtitle = "Which channels Up and Down (or CH+ and CH−) move through while watching",
                        value = if (s.zapMode == ZapMode.GROUP) "Current group" else "All channels",
                        onClick = { dialog = LiveDialog.ZapModeChoice }
                    )
                    SettingsToggleRow(
                        "Reverse channel up / down",
                        "Up goes to the previous channel instead of the next one",
                        s.reverseZap,
                        { update { it.copy(reverseZap = !it.reverseZap) } }
                    )
                    SettingsActionRow(
                        title = "Info bar time",
                        subtitle = "How long the show info stays on screen after changing channel or pressing OK",
                        value = "${s.infoBannerSeconds} seconds",
                        onClick = { dialog = LiveDialog.BannerTime }
                    )
                    SettingsToggleRow(
                        "Reconnect automatically",
                        "Tries the stream again by itself if it drops or freezes",
                        s.autoReconnect,
                        { update { it.copy(autoReconnect = !it.autoReconnect) } }
                    )
                }
            }

            // ------------------------------------------------------------ guide data
            item(key = "guide_data") {
                SettingsGroupCard(title = "Guide data", subtitle = "How often listings update, how far they reach, and timing fixes") {
                    SettingsActionRow(
                        "Update playlists every", "How often channel lists are downloaded again from your providers",
                        hoursLabel(s.playlistRefreshHours), onClick = { dialog = LiveDialog.PlaylistRefresh }
                    )
                    SettingsActionRow(
                        "Update guides every", "How often TV listings are downloaded again",
                        hoursLabel(s.epgRefreshHours), onClick = { dialog = LiveDialog.EpgRefresh }
                    )
                    SettingsActionRow(
                        "Days of listings ahead", "How far into the future the guide keeps shows",
                        if (s.epgFutureDays == 1) "1 day" else "${s.epgFutureDays} days", onClick = { dialog = LiveDialog.FutureDays }
                    )
                    SettingsActionRow(
                        "Past listings kept", "How far back the guide keeps shows, for catch-up",
                        hoursLabel(s.epgPastHours), onClick = { dialog = LiveDialog.PastHours }
                    )
                    SettingsActionRow(
                        title = "Guide time shift",
                        subtitle = "Moves all listings earlier or later, for a guide that's out of step with what's playing",
                        value = offsetLabel(s.epgOffsetMinutes),
                        onClick = { dialog = LiveDialog.EpgShift }
                    )
                }
            }

            // ------------------------------------------------------------ Nuvio
            item(key = "nuvio") {
                SettingsGroupCard(title = "In the rest of Nuvio") {
                    SettingsToggleRow(
                        "Hide from side menu",
                        "Removes Live TV from Nuvio's main menu. You can still reach it from Settings.",
                        !s.showInSidebar,
                        { update { it.copy(showInSidebar = !it.showInSidebar) } }
                    )
                    SettingsToggleRow(
                        "Hide from Nuvio search",
                        "Stops channels and TV shows from appearing in Nuvio's search results",
                        !s.showInSearch,
                        { update { it.copy(showInSearch = !it.showInSearch) } }
                    )
                }
            }

            // ------------------------------------------------------------ channel management
            item(key = "channels") {
                SettingsGroupCard(
                    title = "Your channel changes",
                    subtitle = "Tip: long-press a channel in the guide to rename, renumber, hide or favorite it. Long-press a group to rename, move or hide it."
                ) {
                    SettingsActionRow("Hidden channels", "Channels you hid. Select one to show it again.", "${user.hiddenChannels.size}", leadingIcon = Icons.Default.VisibilityOff, onClick = { dialog = LiveDialog.HiddenChannels })
                    SettingsActionRow("Hidden groups", "Groups you hid. Select one to show it again.", "${user.hiddenGroups.size}", leadingIcon = Icons.Default.VisibilityOff, onClick = { dialog = LiveDialog.HiddenGroups })
                    SettingsActionRow("My groups", "Groups you created. Select one to delete it.", "${user.customGroups.size}", onClick = { dialog = LiveDialog.CustomGroups })
                    SettingsActionRow("Clear favorites", "Removes every channel from Favorites", "${user.favorites.size}", onClick = { viewModel.clearFavorites() })
                    SettingsActionRow("Clear recently watched", "Empties the Recently watched group", "${user.recent.size}", onClick = { viewModel.clearRecent() })
                    SettingsActionRow(
                        "Reset channel names and numbers", "Puts back the names and numbers from your playlist",
                        "${(user.channelNames.keys + user.channelNumbers.keys).size}",
                        onClick = { viewModel.resetChannelEdits() }
                    )
                    SettingsActionRow("Reset group names and order", "Puts back the group names and order from your playlist", onClick = { viewModel.resetGroupEdits() })
                    SettingsActionRow(
                        "Reset EPG assignments",
                        "Channels you assigned a guide to go back to automatic matching",
                        "${user.epgOverrides.size}",
                        onClick = { viewModel.resetEpgAssignments() }
                    )
                }
            }
        }
    }

    drive.signIn?.let { prompt ->
        DriveSignInDialog(prompt = prompt, onCancel = { viewModel.driveSync.cancelSignIn() })
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
                "Update now" to { viewModel.savePlaylist(d.source); close() },
                (if (d.source.enabled) "Disable" else "Enable") to { viewModel.setPlaylistEnabled(d.source.id, !d.source.enabled); close() },
                "Delete playlist" to { dialog = LiveDialog.ConfirmDeletePlaylist(d.source) },
                "Move up" to { viewModel.movePlaylist(d.source.id, -1); close() },
                "Move down" to { viewModel.movePlaylist(d.source.id, 1); close() }
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
                "Update now" to { viewModel.saveEpg(d.source); close() },
                (if (d.source.enabled) "Disable" else "Enable") to { viewModel.setEpgEnabled(d.source.id, !d.source.enabled); close() },
                "Delete EPG source" to { dialog = LiveDialog.ConfirmDeleteEpg(d.source) }
            ),
            onDismiss = close
        )
        LiveDialog.ConfirmRestore -> ConfirmDeleteDialog(
            title = "Restore from Google Drive?",
            message = "This TV's Live TV setup (playlists, guides, favorites and settings) is replaced with the copy in your Google Drive.",
            onDismiss = close,
            onConfirm = { viewModel.driveSync.restoreNow(); close() },
            confirmLabel = "Restore"
        )
        is LiveDialog.ConfirmDeletePlaylist -> ConfirmDeleteDialog(
            title = "Delete \"${d.source.name}\"?",
            message = "Its ${d.source.channelCount} channels leave the guide. Favorites, hidden channels and EPG assignments for them are kept in case you add it back.",
            onDismiss = close,
            onConfirm = { viewModel.removePlaylist(d.source.id); close() }
        )
        is LiveDialog.ConfirmDeleteEpg -> ConfirmDeleteDialog(
            title = "Delete \"${d.source.name}\"?",
            message = "Channels using this guide go back to other guides or automatic matching.",
            onDismiss = close,
            onConfirm = { viewModel.removeEpg(d.source.id); close() }
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
                SettingsPickerOption(false, "Outline", "Dark tint with a colored border — logos stay visible"),
                SettingsPickerOption(true, "Solid", "Fill with the theme's accent color")
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

// ==================================================================== Google Drive sync

/** Collapsed to one line until selected; then shows sign-in, backup and restore. */
@Composable
private fun DriveSyncCard(
    modifier: Modifier = Modifier,
    state: com.nuvio.tv.livetv.sync.DriveSyncState,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onConnect: () -> Unit,
    onAutoSync: (Boolean) -> Unit,
    onBackUp: () -> Unit,
    onRestore: () -> Unit,
    onDisconnect: () -> Unit
) {
    val status = when {
        !state.available -> "Not available in this build"
        !state.connected -> "Off"
        state.busy -> "Syncing…"
        state.lastSyncMs > 0 -> "On · last synced " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(state.lastSyncMs))
        else -> "On"
    }
    SettingsGroupCard(title = "Backup & sync") {
        SettingsActionRow(
            title = "Google Drive sync",
            subtitle = if (expanded) "Keeps your Live TV setup the same on every TV, using your own Google Drive" else status,
            value = if (expanded) "Hide" else if (state.connected) "On" else "Off",
            onClick = onToggleExpanded,
            modifier = modifier
        )
        if (expanded) {
            when {
                !state.available -> Text(
                    text = "Google Drive sync needs to be set up by whoever builds this app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary
                )
                !state.connected -> {
                    Text(
                        text = "Your playlists, guides, favorites, hidden channels and Live TV settings are saved in your own Google Drive, in a private app folder only this app can see. Nothing is stored anywhere else.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary
                    )
                    SettingsActionRow("Connect Google Drive", "Sign in with a code on your phone", onClick = onConnect)
                }
                else -> {
                    SettingsActionRow("Account", "The Google account your Live TV setup is saved to", state.email ?: "Connected", onClick = {})
                    SettingsToggleRow(
                        "Sync automatically",
                        "Back up changes, and pick up changes made on your other TVs",
                        state.autoSync,
                        { onAutoSync(!state.autoSync) }
                    )
                    SettingsActionRow("Back up now", "Save this TV's setup to Google Drive", onClick = onBackUp)
                    SettingsActionRow("Restore from Google Drive", "Replace this TV's setup with the saved copy", onClick = onRestore)
                    SettingsActionRow("Disconnect", "Stop syncing on this TV (your backup stays in Drive)", onClick = onDisconnect)
                }
            }
            state.message?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
            }
        }
    }
}

@Composable
private fun DriveSignInDialog(prompt: com.nuvio.tv.livetv.sync.SignInPrompt, onCancel: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { cancelFocus.requestFocus() }
    }
    // Same layout as Nuvio's other QR sign-ins (debrid, Simkl): instructions, QR code, then the code.
    val qrBitmap = remember(prompt.qrUrl) {
        runCatching { com.nuvio.tv.core.qr.QrCodeGenerator.generate(prompt.qrUrl, 420, margin = 1) }.getOrNull()
    }
    val shortUrl = prompt.url.removePrefix("https://").removePrefix("http://")
    NuvioDialog(onDismiss = onCancel, title = "Connect Google Drive", width = 560.dp) {
        Text(
            text = "Scan the QR code with your phone, or go to $shortUrl, then enter the code below and allow access.",
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        if (qrBitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = "QR code",
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(196.dp),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit
            )
        }
        Text(
            text = prompt.userCode,
            style = MaterialTheme.typography.displaySmall,
            color = NuvioTheme.colors.Secondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = shortUrl,
            style = MaterialTheme.typography.titleMedium,
            color = NuvioTheme.colors.TextPrimary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "Waiting for you to allow access… this screen continues by itself.",
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        SettingsDialogActionRow(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.foundation.layout.Box(Modifier.focusRequester(cancelFocus)) {
                SettingsDialogActionButton(text = "Cancel", onClick = onCancel)
            }
        }
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
        // Scrollable: on a 1080p TV not every option fits, and the last ones were unreachable.
        LazyColumn(
            modifier = Modifier.heightIn(max = 380.dp),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
        ) {
            itemsIndexed(actions) { i, (label, action) ->
                SettingsActionRow(
                    title = label,
                    subtitle = null,
                    onClick = action,
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier
                )
            }
        }
    }
}

@Composable
private fun ConfirmDeleteDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmLabel: String = "Delete"
) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { cancelFocus.requestFocus() }
    }
    NuvioDialog(onDismiss = onDismiss, title = title, subtitle = message, width = 520.dp) {
        SettingsDialogActionRow {
            // Cancel is focused first, so a stray OK press can't delete anything.
            androidx.compose.foundation.layout.Box(Modifier.focusRequester(cancelFocus)) {
                SettingsDialogActionButton(text = "Cancel", onClick = onDismiss)
            }
            SettingsDialogActionButton(text = confirmLabel, onClick = onConfirm, primary = true)
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
