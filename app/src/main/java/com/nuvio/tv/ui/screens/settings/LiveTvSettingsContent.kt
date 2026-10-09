package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
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
import com.nuvio.tv.livetv.model.effectiveStartPage
import com.nuvio.tv.livetv.model.EpgSource
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.PlaylistSource
import com.nuvio.tv.livetv.model.ZapMode
import com.nuvio.tv.livetv.ui.LiveTvSettingsViewModel
import com.nuvio.tv.livetv.ui.TextInputDialog
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
    data object PinCheck : LiveDialog
    data object PinNew : LiveDialog
    data object PinRemove : LiveDialog
    data object Reminders : LiveDialog
    data object StartPage : LiveDialog
    data object NameEditor : LiveDialog
    data object PosterTest : LiveDialog
    data object ButtonTester : LiveDialog
    data object LoadReport : LiveDialog
    data object HomeRowSource : LiveDialog
    data object HomeRowTitle : LiveDialog
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

/** Typed into the channel name editor, this turns the hidden developer tools on or off. */
private const val DEVELOPER_WORD = "#nuviodev"

private fun startPageLabel(page: String): String = when (page) {
    "LIVE_TV" -> "Live TV"
    "ON_DEMAND" -> "On Demand"
    "DISCOVER" -> "Discover"
    "SEARCH" -> "Search"
    "LIBRARY" -> "Library"
    else -> "Home"
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
    val developerToolsOn by com.nuvio.tv.livetv.model.DeveloperTools.enabled.collectAsStateWithLifecycle()
    val onDemandHas by viewModel.onDemand.hasContent.collectAsStateWithLifecycle()
    val onDemandStatus by viewModel.onDemand.status.collectAsStateWithLifecycle()
    var pinWrong by remember { mutableStateOf(false) }
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
                    if (status.loading) LoadingLine(status.message ?: "Updating…")
                    playlists.forEachIndexed { index, pl ->
                        SettingsActionRow(
                            title = pl.name + if (pl.isXtream) " (Xtream)" else "",
                            subtitle = when {
                                pl.isXtream && pl.enabled && !pl.importLive && pl.importVod -> "Movies & series only (TV channels off)"
                                pl.isXtream && pl.enabled && !pl.importLive && !pl.importVod -> "Nothing included: turn on TV channels or movies & series under Edit"
                                else -> sourceStatus(pl.enabled, pl.lastUpdatedMs, pl.lastError, "${pl.channelCount} channels") +
                                    if (pl.isXtream && pl.importVod && pl.enabled) " · + movies & series" else ""
                            },
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
                        "Show posters",
                        "Posters for what's on, from your addons. Off: just the channel logo.",
                        s.showPosters,
                        { update { it.copy(showPosters = !it.showPosters) } }
                    )
                    SettingsToggleRow(
                        "Show playlist in info panel",
                        "Shows which playlist the highlighted channel comes from",
                        s.showPlaylistInInfo,
                        { update { it.copy(showPlaylistInInfo = !it.showPlaylistInInfo) } }
                    )
                    SettingsToggleRow(
                        "Smaller info panel and preview",
                        "Shrinks the top of the guide to about two-thirds of its height, so more channels fit",
                        s.smallHeader,
                        { update { it.copy(smallHeader = !it.smallHeader) } }
                    )
                    SettingsToggleRow(
                        "Hide the \"now\" line",
                        "Removes the vertical line that marks the current time in the guide",
                        !s.showNowLine,
                        { update { it.copy(showNowLine = !it.showNowLine) } }
                    )
                    SettingsToggleRow(
                        "Compact channel rows",
                        "Makes each channel row slimmer, so about two more channels fit",
                        s.compactRows,
                        { update { it.copy(compactRows = !it.compactRows) } }
                    )
                    SettingsToggleRow(
                        "Browse by channel name",
                        "Like satellite boxes: picking a group lands on the channel names. OK plays, Right opens the schedule, Left goes back to the groups.",
                        s.browseByChannelName,
                        { update { it.copy(browseByChannelName = !it.browseByChannelName) } }
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
                    SettingsActionRow(
                        title = "Channel name editor",
                        subtitle = "Prefixes and suffixes to remove from channel names, like USA, 24/7 or FHD, separated by commas",
                        value = com.nuvio.tv.livetv.model.ChannelNameEditor.terms(s.nameRemovals).size.let { if (it == 0) "Off" else "$it removed" },
                        onClick = { dialog = LiveDialog.NameEditor }
                    )
                    if (s.nameRemovals.isBlank()) {
                        SettingsActionRow(
                            "Add common prefixes and suffixes",
                            "Fills in: ${com.nuvio.tv.livetv.model.ChannelNameEditor.COMMON}",
                            onClick = { update { it.copy(nameRemovals = com.nuvio.tv.livetv.model.ChannelNameEditor.COMMON) } }
                        )
                    }
                    SettingsToggleRow(
                        "Hide channel names",
                        "Shows only numbers and logos, which leaves more room for the schedule",
                        !s.showChannelNames,
                        { update { it.copy(showChannelNames = !it.showChannelNames) } }
                    )
                    // Hidden developer tool: only shown after typing the secret word into the
                    // channel name editor (see NameEditor below).
                    if (developerToolsOn) {
                        SettingsActionRow(
                            "Test poster lookup",
                            "Developer tool: what your addons return for a title",
                            onClick = { dialog = LiveDialog.PosterTest }
                        )
                        SettingsActionRow(
                            "Remote button tester",
                            "Developer tool: shows every button the remote sends",
                            onClick = { dialog = LiveDialog.ButtonTester }
                        )
                        SettingsActionRow(
                            "Live TV start-up report",
                            "Developer tool: what loading did this time, and how long it took",
                            onClick = { dialog = LiveDialog.LoadReport }
                        )
                    }
                    SettingsActionRow(
                        title = "Highlight style",
                        subtitle = "How the selected show is marked: an outline, or filled with your theme color",
                        value = if (s.solidHighlight) "Solid" else "Outline",
                        onClick = { dialog = LiveDialog.HighlightChoice }
                    )
                    SettingsToggleRow(
                        "Quality badges",
                        "Shows 4K, FHD, HD or SD next to channels you've watched",
                        s.showQualityBadges,
                        { update { it.copy(showQualityBadges = !it.showQualityBadges) } }
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
                        "Group by playlist",
                        "With more than one playlist, lists each playlist's groups under its own heading, which you can fold away",
                        s.groupPlaylistHeadings,
                        { update { it.copy(groupPlaylistHeadings = !it.groupPlaylistHeadings) } }
                    )
                    SettingsToggleRow(
                        "Hide channel counts",
                        "Removes the number of channels shown next to each group",
                        !s.showGroupCounts,
                        { update { it.copy(showGroupCounts = !it.showGroupCounts) } }
                    )
                    SettingsToggleRow(
                        "Number channels 1, 2, 3… in each group",
                        "Replaces the playlist's numbers with the order channels appear in each group. 20 favorites become 1 to 20.",
                        s.sequentialNumbers,
                        { update { it.copy(sequentialNumbers = !it.sequentialNumbers) } }
                    )
                    SettingsToggleRow(
                        "Open on last group",
                        "Opening Live TV shows the group you last watched a channel in",
                        s.rememberLastGroup,
                        { update { it.copy(rememberLastGroup = !it.rememberLastGroup) } }
                    )
                    SettingsActionRow(
                        title = "Group order",
                        subtitle = "How the group list is sorted. Your order follows Reorder groups (anything not moved stays in playlist order).",
                        value = when (s.groupSort) { "playlist" -> "Playlist order"; "name" -> "Name (A–Z)"; else -> "Your order" },
                        onClick = {
                            update { st -> st.copy(groupSort = when (st.groupSort) { "custom" -> "playlist"; "playlist" -> "name"; else -> "custom" }) }
                        }
                    )
                    SettingsActionRow(
                        title = "Channel order",
                        subtitle = "How channels are sorted in every group, Favorites included. Playlist order keeps your own order in Favorites and your groups.",
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
                        "Overlay mode",
                        "Left while watching shows channels and schedules over the video. Off: Left goes back to the guide's group list.",
                        s.overlayMode,
                        { update { it.copy(overlayMode = !it.overlayMode) } }
                    )
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
                        "Prefer m3u8 for catch-up (enables scrubbing)",
                        "Asks Xtream providers for HLS catch-up first, so replays have a working seek bar. Falls back to TS if the provider doesn't offer it.",
                        s.catchupPreferHls,
                        { update { it.copy(catchupPreferHls = !it.catchupPreferHls) } }
                    )
                    SettingsToggleRow(
                        "Closed captions on by default",
                        "Turns captions on automatically when a channel has them. You can still switch them off with CC.",
                        s.captionsByDefault,
                        { update { it.copy(captionsByDefault = !it.captionsByDefault) } }
                    )
                    SettingsToggleRow(
                        "Pause and rewind live TV",
                        "On channels without catch-up, pausing records the channel to this device, so play carries on from where you paused",
                        s.timeshiftEnabled,
                        { update { it.copy(timeshiftEnabled = !it.timeshiftEnabled) } }
                    )
                    if (s.timeshiftEnabled) SettingsActionRow(
                        title = "Keep up to",
                        subtitle = "How much can be recorded while paused (uses this device's storage, deleted afterwards)",
                        value = "${s.timeshiftMinutes} min · about " + (s.timeshiftMinutes * 35).let { mb -> if (mb >= 1000) String.format(java.util.Locale.US, "%.1f GB", mb / 1024f) else "$mb MB" },
                        onClick = {
                            update { st -> st.copy(timeshiftMinutes = when (st.timeshiftMinutes) { 10 -> 30; 30 -> 60; else -> 10 }) }
                        }
                    )
                    SettingsActionRow(
                        title = "Buffer size",
                        subtitle = "Larger rides out a slow connection or provider (fewer pauses); smaller changes channels a little faster",
                        value = when (s.bufferSize) { "small" -> "Small"; "large" -> "Large"; "xlarge" -> "Extra large"; else -> "Normal" },
                        onClick = {
                            update { st -> st.copy(bufferSize = when (st.bufferSize) { "small" -> "normal"; "normal" -> "large"; "large" -> "xlarge"; else -> "small" }) }
                        }
                    )
                    SettingsToggleRow(
                        "Audio passthrough",
                        "Sends Dolby audio untouched to your TV or speaker. Turn off if sound cuts out or stops, especially with Echo speakers or Alexa Home Theater on Fire TV.",
                        s.audioPassthrough,
                        { update { it.copy(audioPassthrough = !it.audioPassthrough) } }
                    )
                    SettingsToggleRow(
                        "Match frame rate",
                        "Switches the TV to the video's frame rate (for example 50 Hz for UK and European channels) for smoother motion. Only on TVs that support it.",
                        s.matchFrameRate,
                        { update { it.copy(matchFrameRate = !it.matchFrameRate) } }
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
                    val clearContext = androidx.compose.ui.platform.LocalContext.current
                    SettingsActionRow(
                        "Clear posters and show info",
                        "Forgets the posters and show info found for Live TV, so they're fetched fresh from your addons next time",
                        onClick = {
                            viewModel.clearPosterData()
                            android.widget.Toast.makeText(clearContext, "Posters and show info cleared", android.widget.Toast.LENGTH_SHORT).show()
                        }
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
                    // ---- Live TV row on the home screen
                    // On or off for the Nuvio profile in use (each profile has its own choice).
                    val homeProfileContext = androidx.compose.ui.platform.LocalContext.current
                    val homeProfileId by remember {
                        dagger.hilt.android.EntryPointAccessors.fromApplication(
                            homeProfileContext.applicationContext, com.nuvio.tv.livetv.startup.LiveTvStartupEntryPoint::class.java
                        ).profileManager()
                    }.activeProfileId.collectAsStateWithLifecycle()
                    val homeRowOn = homeProfileId !in s.homeRowHiddenProfiles
                    SettingsToggleRow(
                        "Live TV row on the home screen",
                        "For this profile. Tucked above your top row: press Up to show it.",
                        homeRowOn,
                        {
                            update { st ->
                                st.copy(
                                    homeRowHiddenProfiles = if (homeProfileId in st.homeRowHiddenProfiles) st.homeRowHiddenProfiles - homeProfileId
                                    else st.homeRowHiddenProfiles + homeProfileId
                                )
                            }
                        }
                    )
                    if (homeRowOn) {
                        SettingsActionRow(
                            title = "Home row shows",
                            subtitle = "Favorites, Recently watched, or one of your own groups",
                            value = when (s.homeRowSource) {
                                "favorites", "" -> "Favorites"
                                "recent" -> "Recently watched"
                                else -> user.customGroups.firstOrNull { it.id == s.homeRowSource }?.name ?: "Favorites"
                            },
                            onClick = { dialog = LiveDialog.HomeRowSource }
                        )
                        SettingsActionRow(
                            title = "Home row title",
                            subtitle = "The name shown above the row",
                            value = s.homeRowTitle.ifBlank { "Live TV" },
                            onClick = { dialog = LiveDialog.HomeRowTitle }
                        )
                        SettingsActionRow(
                            title = "Home row order",
                            subtitle = "Your order, channel number, or the shows ending soonest first",
                            value = when (s.homeRowSort) { "number" -> "Channel number"; "ending" -> "Ending soonest"; else -> "Your order" },
                            onClick = {
                                update { st -> st.copy(homeRowSort = when (st.homeRowSort) { "yours" -> "number"; "number" -> "ending"; else -> "yours" }) }
                            }
                        )
                        SettingsActionRow(
                            title = "Home row channels",
                            subtitle = "How many channels the row shows",
                            value = if (s.homeRowLimit == 0) "All" else "${s.homeRowLimit}",
                            onClick = {
                                update { st -> st.copy(homeRowLimit = when (st.homeRowLimit) { 10 -> 20; 20 -> 40; 40 -> 0; else -> 10 }) }
                            }
                        )
                        SettingsToggleRow(
                            "Hide channels with no listings",
                            "Leaves out channels showing only \"Programming\" or no information",
                            s.homeRowHidePlaceholders,
                            { update { it.copy(homeRowHidePlaceholders = !it.homeRowHidePlaceholders) } }
                        )
                        SettingsToggleRow(
                            "OK opens the guide",
                            "Off: OK plays the channel full screen. On: opens the Live TV guide with the channel playing.",
                            s.homeRowOpensGuide,
                            { update { it.copy(homeRowOpensGuide = !it.homeRowOpensGuide) } }
                        )
                        SettingsToggleRow(
                            "Show what's next",
                            "A \"Next: 9:00 The News\" line on each card",
                            s.homeRowShowNext,
                            { update { it.copy(homeRowShowNext = !it.homeRowShowNext) } }
                        )
                        SettingsToggleRow(
                            "Compact cards",
                            "Just the logo and the show name",
                            s.homeRowCompact,
                            { update { it.copy(homeRowCompact = !it.homeRowCompact) } }
                        )
                    }
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

            // ------------------------------------------------------------ On Demand
            item(key = "on_demand") {
                SettingsGroupCard(
                    title = "On Demand",
                    subtitle = "Movies and series from Xtream logins with \"Include movies & series\" on. Change it per login under Playlists → Edit."
                ) {
                    if (onDemandStatus.loading) LoadingLine(onDemandStatus.message ?: "Importing movies and series…")
                    val genreProgress by viewModel.onDemand.genreProgress.collectAsStateWithLifecycle()
                    genreProgress?.let { LoadingLine(it) }
                    SettingsActionRow(
                        "Update movies & series now",
                        onDemandStatus.message ?: if (onDemandHas) "Imported. Updates once a day by itself." else "Nothing imported yet",
                        onClick = { viewModel.onDemand.refreshNow() }
                    )
                    // One switch per Xtream login (logins added before On Demand start switched off).
                    playlists.filter { it.isXtream }.forEach { pl ->
                        SettingsToggleRow(
                            "Movies & series from ${pl.name}",
                            if (pl.importVod) "Included" else "Not included. Switch on to import this provider's movies and series.",
                            pl.importVod,
                            { viewModel.savePlaylist(pl.copy(importVod = !pl.importVod)) }
                        )
                    }
                    SettingsToggleRow(
                        "On Demand in Nuvio's stream list",
                        "Adds your provider's copy to the end of Nuvio's stream list for movies and episodes. Auto-play never picks it.",
                        s.onDemandInStreams,
                        { update { it.copy(onDemandInStreams = !it.onDemandInStreams) } }
                    )
                    SettingsToggleRow(
                        "Merge duplicates",
                        "Shows each movie or series once, even when your provider lists several copies (other qualities, categories or providers). Play lets you pick the version.",
                        s.vodMergeDuplicates,
                        { update { it.copy(vodMergeDuplicates = !it.vodMergeDuplicates) } }
                    )
                    SettingsToggleRow(
                        "Use posters from my addons",
                        "Shows your addons' posters in On Demand where they have the title. Off: the provider's images only.",
                        s.onDemandAddonPosters,
                        { update { it.copy(onDemandAddonPosters = !it.onDemandAddonPosters) } }
                    )
                    SettingsToggleRow(
                        "Show On Demand in the side menu",
                        "Only appears when a provider's movies or series are imported",
                        s.onDemandInSidebar,
                        { update { it.copy(onDemandInSidebar = !it.onDemandInSidebar) } }
                    )
                    SettingsActionRow(
                        "Show hidden categories", "Brings back every On Demand category you hid",
                        "${user.vodHiddenCategories.size}", onClick = { viewModel.clearVodHidden() }
                    )
                }
            }

            // ------------------------------------------------------------ parental controls
            item(key = "parental") {
                SettingsGroupCard(
                    title = "Parental controls",
                    subtitle = "Locked groups and categories need the PIN to open, and stay out of All channels and search until unlocked."
                ) {
                    val hasPin = s.parentalPin.length >= 4
                    SettingsActionRow(
                        if (hasPin) "Change PIN" else "Set a PIN",
                        if (hasPin) "Parental controls are on" else "Turns parental controls on",
                        onClick = { pinWrong = false; dialog = if (hasPin) LiveDialog.PinCheck else LiveDialog.PinNew }
                    )
                    if (hasPin) {
                        SettingsToggleRow(
                            "Lock adult content automatically",
                            "Locks groups and categories with names like Adult, XXX or 18+",
                            s.lockAdultContent,
                            { update { it.copy(lockAdultContent = !it.lockAdultContent) } }
                        )
                        SettingsActionRow(
                            "Turn off parental controls", "Removes the PIN. Your locked groups are remembered.",
                            "${user.lockedGroups.size} locked",
                            onClick = { pinWrong = false; dialog = LiveDialog.PinRemove }
                        )
                    }
                }
            }

            // ------------------------------------------------------------ reminders
            item(key = "reminders") {
                SettingsGroupCard(title = "Reminders") {
                    SettingsActionRow(
                        "Upcoming reminders",
                        "Set from a show's info in the guide. You get a message a minute before it starts.",
                        "${user.reminders.size}",
                        onClick = { dialog = LiveDialog.Reminders }
                    )
                }
            }

            // ------------------------------------------------------------ help
            item(key = "help") {
                val crashContext = androidx.compose.ui.platform.LocalContext.current
                SettingsGroupCard(title = "Help") {
                    SettingsActionRow(
                        "Crash report",
                        "The last time the app crashed, froze or was closed. Scan the code with your phone to send it.",
                        onClick = { com.nuvio.tv.livetv.crash.CrashCatcher.openLastReport(crashContext) }
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
                        "Reset channel order", "Undoes \"Reorder channels\" in every group",
                        "${user.channelOrder.size}", onClick = { viewModel.resetChannelOrder() }
                    )
                    SettingsActionRow(
                        "Remove copied channels", "Takes out every channel you copied into another group. The originals stay.",
                        "${user.channelCopies.values.sumOf { it.size }}", onClick = { viewModel.clearChannelCopies() }
                    )
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
            onSave = { v, _ -> if (v[1].isNotBlank()) viewModel.addXtream(v[0], v[1], v[2], v[3]); close() },
            toggleLive = "Include TV channels" to true,
            toggle2 = "Include movies & series (On Demand)" to true,
            onSaveAll = { v, _, vod, live ->
                if (v[1].isNotBlank()) viewModel.addXtream(v[0], v[1], v[2], v[3], importVod = vod, importLive = live)
                close()
            }
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
                // Whose channel logos to show: the playlist's own or the guide's (each falls back
                // to the other when it has none).
                (if (d.source.logoSource == "epg") "Logos: Prefer logos from EPG" else "Logos: Prefer logos from playlist") to {
                    viewModel.setPlaylistLogoSource(d.source.id, if (d.source.logoSource == "epg") "playlist" else "epg")
                    close()
                },
                // Xtream: which stream format to ask for. Auto follows what the account allows.
                *(if (d.source.isXtream) arrayOf<Pair<String, () -> Unit>>(
                    "Stream format: " + when (d.source.streamFormat) { "ts" -> "TS"; "m3u8" -> "HLS (m3u8)"; else -> "Auto" } to {
                        val next = when (d.source.streamFormat) { "auto" -> "ts"; "ts" -> "m3u8"; else -> "auto" }
                        viewModel.savePlaylist(d.source.copy(streamFormat = next))
                        close()
                    }
                ) else emptyArray()),
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
                    toggleLive = "Include TV channels" to src.importLive,
                    toggle2 = "Include movies & series (On Demand)" to src.importVod,
                    onDismiss = close,
                    onSave = { v, t ->
                        viewModel.savePlaylist(src.copy(name = v[0], xtreamServer = v[1], xtreamUsername = v[2], xtreamPassword = v[3], userAgent = v[4], useEmbeddedEpg = t))
                        close()
                    },
                    onSaveAll = { v, t, vod, live ->
                        viewModel.savePlaylist(
                            src.copy(
                                name = v[0], xtreamServer = v[1], xtreamUsername = v[2], xtreamPassword = v[3],
                                userAgent = v[4], useEmbeddedEpg = t, importVod = vod, importLive = live
                            )
                        )
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
        LiveDialog.PinCheck -> TextInputDialog(
            title = if (pinWrong) "Wrong PIN, try again" else "Enter your current PIN",
            initial = "", hint = "Current PIN", confirmLabel = "Next", numeric = true,
            onDismiss = close,
            onConfirm = { if (it.trim() == s.parentalPin) { pinWrong = false; dialog = LiveDialog.PinNew } else pinWrong = true }
        )
        LiveDialog.PinNew -> TextInputDialog(
            title = if (pinWrong) "The PIN needs 4 to 8 digits" else "Choose a PIN (4 to 8 digits)",
            initial = "", hint = "New PIN", confirmLabel = "Save", numeric = true,
            onDismiss = close,
            onConfirm = { pin ->
                val p = pin.trim()
                if (p.length in 4..8 && p.all { it.isDigit() }) { update { it.copy(parentalPin = p) }; close() } else pinWrong = true
            }
        )
        LiveDialog.PinRemove -> TextInputDialog(
            title = if (pinWrong) "Wrong PIN, try again" else "Enter your PIN to turn parental controls off",
            initial = "", hint = "PIN", confirmLabel = "Turn off", numeric = true,
            onDismiss = close,
            onConfirm = { if (it.trim() == s.parentalPin) { update { st -> st.copy(parentalPin = "") }; close() } else pinWrong = true }
        )
        LiveDialog.HomeRowSource -> SettingsSingleChoiceDialog(
            title = "Home row shows",
            options = buildList {
                add(SettingsPickerOption("favorites", "Favorites"))
                add(SettingsPickerOption("recent", "Recently watched"))
                user.customGroups.forEach { add(SettingsPickerOption(it.id, it.name)) }
            },
            selectedValue = s.homeRowSource.ifBlank { "favorites" },
            onOptionSelected = { v -> update { it.copy(homeRowSource = v) }; close() },
            onDismiss = close
        )
        LiveDialog.HomeRowTitle -> TextInputDialog(
            title = "Home row title",
            initial = s.homeRowTitle,
            hint = "Live TV",
            confirmLabel = "Save",
            onDismiss = close,
            onConfirm = { t -> update { it.copy(homeRowTitle = t.trim()) }; close() }
        )
        LiveDialog.ButtonTester -> com.nuvio.tv.livetv.ui.RemoteButtonTester(onClose = close)
        LiveDialog.LoadReport -> com.nuvio.tv.livetv.ui.LoadReportDialog(onDismiss = close)
        LiveDialog.PosterTest -> {
            val result by viewModel.posterTest.collectAsStateWithLifecycle()
            if (result == null) {
                TextInputDialog(
                    title = "Test poster lookup",
                    initial = "",
                    hint = "e.g. Family Guy",
                    confirmLabel = "Test",
                    onDismiss = close,
                    onConfirm = { viewModel.testPoster(it) }
                )
            } else {
                NuvioDialog(onDismiss = { viewModel.posterTest.value = null; close() }, title = "Poster lookup", width = 720.dp) {
                    Text(
                        text = result.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.heightIn(max = 320.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())
                    )
                    SettingsDialogActionRow {
                        SettingsDialogActionButton(text = "Close", onClick = { viewModel.posterTest.value = null; close() }, primary = true)
                    }
                }
            }
        }
        LiveDialog.NameEditor -> TextInputDialog(
            title = "Prefixes and suffixes to remove",
            initial = s.nameRemovals,
            hint = "e.g. USA, US, UK, 24/7, FHD, HD",
            confirmLabel = "Save",
            onDismiss = close,
            onConfirm = { text ->
                // Secret word: turns the hidden developer tools on or off instead of saving.
                if (text.trim().equals(DEVELOPER_WORD, ignoreCase = true)) {
                    com.nuvio.tv.livetv.model.DeveloperTools.toggle() // until the app next starts
                } else {
                    update { it.copy(nameRemovals = text.trim()) }
                }
                close()
            }
        )
        LiveDialog.StartPage -> SettingsSingleChoiceDialog(
            title = "Start page",
            // On Demand is only offered once something has been imported.
            options = buildList {
                add(SettingsPickerOption("HOME", "Home"))
                add(SettingsPickerOption("LIVE_TV", "Live TV"))
                if (onDemandHas) add(SettingsPickerOption("ON_DEMAND", "On Demand"))
                add(SettingsPickerOption("DISCOVER", "Discover"))
                add(SettingsPickerOption("SEARCH", "Search"))
                add(SettingsPickerOption("LIBRARY", "Library"))
            },
            selectedValue = s.effectiveStartPage,
            onOptionSelected = { v -> update { it.copy(startPage = v, startOnLiveTv = v == "LIVE_TV") }; close() },
            onDismiss = close
        )
        LiveDialog.Reminders -> ActionListDialog(
            title = if (user.reminders.isEmpty()) "No reminders" else "Reminders (select to remove)",
            actions = user.reminders.map { r ->
                val whenText = java.text.SimpleDateFormat("EEE h:mm a", java.util.Locale.getDefault()).format(java.util.Date(r.startMs))
                "$whenText · ${r.title} · ${r.channelName}" to { viewModel.removeReminder(r.channelKey, r.startMs); Unit }
            }.ifEmpty { listOf("Close" to close) },
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

// ==================================================================== loading line

/** A spinner and what's happening, while playlists, guides or On Demand are downloading. */
@Composable
private fun LoadingLine(message: String) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        com.nuvio.tv.livetv.ui.LoadingSpinner()
        androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
        Text(text = message, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
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
    toggle: Pair<String, Boolean>? = null,
    /** A second switch (Xtream: import movies & series); its value goes to [onSaveBoth]. */
    toggle2: Pair<String, Boolean>? = null,
    onSaveBoth: ((List<String>, Boolean, Boolean) -> Unit)? = null,
    /** Xtream: "Include TV channels", shown first. Its value goes to [onSaveAll]. */
    toggleLive: Pair<String, Boolean>? = null,
    onSaveAll: ((values: List<String>, guide: Boolean, vod: Boolean, live: Boolean) -> Unit)? = null
) {
    val values = remember { fields.map { mutableStateOf(it.initial) } }
    var toggleValue by remember { mutableStateOf(toggle?.second ?: true) }
    var toggle2Value by remember { mutableStateOf(toggle2?.second ?: true) }
    var toggleLiveValue by remember { mutableStateOf(toggleLive?.second ?: true) }
    val firstField = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { firstField.requestFocus() }
    }
    NuvioDialog(onDismiss = onDismiss, title = title, width = 620.dp) {
        // The fields scroll inside the dialog, so Save and Cancel always stay on screen
        // (with a user agent field and the guide switch, they used to fall off the bottom).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp)
                .verticalScroll(androidx.compose.foundation.rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
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
        if (toggleLive != null) {
            SettingsToggleRow(
                title = toggleLive.first,
                subtitle = "Adds the provider's live channels to the TV guide",
                checked = toggleLiveValue,
                onToggle = { toggleLiveValue = !toggleLiveValue }
            )
        }
        // The guide switch only matters when TV channels are included.
        if (toggle != null && (toggleLive == null || toggleLiveValue)) {
            SettingsToggleRow(
                title = toggle.first,
                subtitle = null,
                checked = toggleValue,
                onToggle = { toggleValue = !toggleValue }
            )
        }
        if (toggle2 != null) {
            SettingsToggleRow(
                title = toggle2.first,
                subtitle = "Adds the provider's movies and series to On Demand and to Nuvio's stream list",
                checked = toggle2Value,
                onToggle = { toggle2Value = !toggle2Value }
            )
        }
        }
        SettingsDialogActionRow {
            SettingsDialogActionButton(text = "Cancel", onClick = onDismiss)
            SettingsDialogActionButton(
                text = "Save",
                onClick = {
                    val v = values.map { it.value.trim() }
                    when {
                        onSaveAll != null -> onSaveAll(v, toggleValue, toggle2Value, toggleLiveValue)
                        onSaveBoth != null -> onSaveBoth(v, toggleValue, toggle2Value)
                        else -> onSave(v, toggleValue)
                    }
                },
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
