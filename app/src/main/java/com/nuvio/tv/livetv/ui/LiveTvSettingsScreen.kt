package com.nuvio.tv.livetv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.livetv.model.EpgSource
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.PlaylistSource
import com.nuvio.tv.livetv.model.ZapMode
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

private sealed interface SettingsDialog {
    data object AddM3u : SettingsDialog
    data object AddXtream : SettingsDialog
    data object AddEpg : SettingsDialog
    data class EditPlaylist(val source: PlaylistSource) : SettingsDialog
    data class PlaylistActions(val source: PlaylistSource) : SettingsDialog
    data class EditEpg(val source: EpgSource) : SettingsDialog
    data class EpgActions(val source: EpgSource) : SettingsDialog
    data object HiddenChannels : SettingsDialog
    data object HiddenGroups : SettingsDialog
}

private val OFFSETS = (-24..24).map { it * 30 }
private val REFRESH_HOURS = listOf(3, 6, 12, 24, 48, 72, 168)
private val BANNER_SECONDS = listOf(3, 5, 8, 10, 15)
private val FUTURE_DAYS = listOf(1, 2, 3, 5, 7, 10, 14)
private val PAST_HOURS = listOf(6, 12, 24, 48, 72, 168)

private fun <T> List<T>.nextAfter(value: T): T {
    val i = indexOf(value)
    return if (i < 0) first() else this[(i + 1) % size]
}

private fun offsetLabel(min: Int): String {
    if (min == 0) return "None"
    val sign = if (min > 0) "+" else "−"
    val a = kotlin.math.abs(min)
    return if (a % 60 == 0) "$sign${a / 60}h" else "$sign${a / 60}h ${a % 60}m"
}

@Composable
fun LiveTvSettingsScreen(
    onBack: () -> Unit,
    viewModel: LiveTvSettingsViewModel = hiltViewModel()
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val epgs by viewModel.epgSources.collectAsStateWithLifecycle()
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val user by viewModel.userState.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { delay(120); runCatching { firstFocus.requestFocus() } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 64.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LiveText("Live TV settings", size = 28.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (status.loading) {
                        LiveText(status.message ?: "Updating…", color = NuvioTheme.colors.TextSecondary, size = 14.sp)
                    }
                }
            }

            // ---------------------------------------------------------- playlists
            item { SectionHeader("Playlists") }
            items(playlists, key = { "pl_" + it.id }) { pl ->
                SourceRow(
                    title = pl.name + if (pl.isXtream) "  (Xtream)" else "",
                    subtitle = sourceSubtitle(pl.enabled, pl.lastUpdatedMs, pl.lastError, "${pl.channelCount} channels"),
                    enabled = pl.enabled,
                    modifier = if (pl == playlists.first()) Modifier.focusRequester(firstFocus) else Modifier,
                    onClick = { dialog = SettingsDialog.PlaylistActions(pl) }
                )
            }
            item {
                ActionRow(
                    "+ Add M3U playlist",
                    if (playlists.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier
                ) { dialog = SettingsDialog.AddM3u }
            }
            item { ActionRow("+ Add Xtream Codes login") { dialog = SettingsDialog.AddXtream } }

            // ---------------------------------------------------------- EPG
            item { SectionHeader("TV guide (EPG)") }
            item {
                LiveText(
                    "Guides linked inside your playlists are loaded automatically. Add extra XMLTV guides here — earlier sources win when two guides cover the same channel.",
                    color = NuvioTheme.colors.TextSecondary, size = 13.sp, maxLines = 3,
                    modifier = Modifier.padding(start = 14.dp, bottom = 6.dp)
                )
            }
            items(epgs, key = { "epg_" + it.id }) { e ->
                SourceRow(
                    title = e.name,
                    subtitle = sourceSubtitle(e.enabled, e.lastUpdatedMs, e.lastError, "${e.programCount} programmes matched"),
                    enabled = e.enabled,
                    onClick = { dialog = SettingsDialog.EpgActions(e) }
                )
            }
            item { ActionRow("+ Add EPG source (XMLTV URL)") { dialog = SettingsDialog.AddEpg } }
            item { ActionRow("Update all playlists and guides now") { viewModel.refreshAll() } }

            // ---------------------------------------------------------- guide
            item { SectionHeader("Guide") }
            item { ToggleRow("Show Live TV in side menu", s.showInSidebar) { viewModel.update { it.copy(showInSidebar = !it.showInSidebar) } } }
            item { ToggleRow("Show channel names", s.showChannelNames) { viewModel.update { it.copy(showChannelNames = !it.showChannelNames) } } }
            item { ToggleRow("Show channel numbers", s.showChannelNumbers) { viewModel.update { it.copy(showChannelNumbers = !it.showChannelNumbers) } } }
            item { ToggleRow("Show channel logos", s.showChannelLogos) { viewModel.update { it.copy(showChannelLogos = !it.showChannelLogos) } } }
            item { ToggleRow("Preview window", s.showPreview) { viewModel.update { it.copy(showPreview = !it.showPreview) } } }
            item { ToggleRow("Programme details panel", s.showProgramDetails) { viewModel.update { it.copy(showProgramDetails = !it.showProgramDetails) } } }
            item { ToggleRow("Compact rows", s.compactRows) { viewModel.update { it.copy(compactRows = !it.compactRows) } } }
            item { ToggleRow("Favourites group", s.showFavoritesGroup) { viewModel.update { it.copy(showFavoritesGroup = !it.showFavoritesGroup) } } }
            item { ToggleRow("Recently watched group", s.showRecentGroup) { viewModel.update { it.copy(showRecentGroup = !it.showRecentGroup) } } }
            item { ToggleRow("Remember last group", s.rememberLastGroup) { viewModel.update { it.copy(rememberLastGroup = !it.rememberLastGroup) } } }
            item { ToggleRow("24-hour clock", s.use24HourClock) { viewModel.update { it.copy(use24HourClock = !it.use24HourClock) } } }
            item {
                ValueRow("EPG time shift  (OK = +30 min)", offsetLabel(s.epgOffsetMinutes)) {
                    viewModel.update { it.copy(epgOffsetMinutes = OFFSETS.nextAfter(it.epgOffsetMinutes)) }
                }
            }
            item {
                ValueRow("EPG time shift  (OK = −30 min)", offsetLabel(s.epgOffsetMinutes)) {
                    viewModel.update {
                        val i = OFFSETS.indexOf(it.epgOffsetMinutes).coerceAtLeast(0)
                        it.copy(epgOffsetMinutes = OFFSETS[(i - 1 + OFFSETS.size) % OFFSETS.size])
                    }
                }
            }
            item {
                ValueRow("Reset EPG time shift", "") { viewModel.update { it.copy(epgOffsetMinutes = 0) } }
            }

            // ---------------------------------------------------------- playback
            item { SectionHeader("Playback") }
            item { ToggleRow("OK opens full screen (skip preview)", s.openFullscreenOnSelect) { viewModel.update { it.copy(openFullscreenOnSelect = !it.openFullscreenOnSelect) } } }
            item { ToggleRow("Auto-play last channel when opening Live TV", s.autoPlayLastChannel) { viewModel.update { it.copy(autoPlayLastChannel = !it.autoPlayLastChannel) } } }
            item { ToggleRow("Reverse channel up / down", s.reverseZap) { viewModel.update { it.copy(reverseZap = !it.reverseZap) } } }
            item {
                ValueRow("Channel switching", if (s.zapMode == ZapMode.GROUP) "Within current group" else "All channels") {
                    viewModel.update { it.copy(zapMode = if (it.zapMode == ZapMode.GROUP) ZapMode.ALL else ZapMode.GROUP) }
                }
            }
            item { ValueRow("Info banner duration", "${s.infoBannerSeconds} s") { viewModel.update { it.copy(infoBannerSeconds = BANNER_SECONDS.nextAfter(it.infoBannerSeconds)) } } }
            item { ToggleRow("Reconnect automatically", s.autoReconnect) { viewModel.update { it.copy(autoReconnect = !it.autoReconnect) } } }

            // ---------------------------------------------------------- updates
            item { SectionHeader("Updates") }
            item { ValueRow("Update playlists every", hoursLabel(s.playlistRefreshHours)) { viewModel.update { it.copy(playlistRefreshHours = REFRESH_HOURS.nextAfter(it.playlistRefreshHours)) } } }
            item { ValueRow("Update guides every", hoursLabel(s.epgRefreshHours)) { viewModel.update { it.copy(epgRefreshHours = REFRESH_HOURS.nextAfter(it.epgRefreshHours)) } } }
            item { ValueRow("Keep guide days ahead", "${s.epgFutureDays}") { viewModel.updateAndReloadEpg { it.copy(epgFutureDays = FUTURE_DAYS.nextAfter(it.epgFutureDays)) } } }
            item { ValueRow("Keep past guide (catch-up)", hoursLabel(s.epgPastHours)) { viewModel.updateAndReloadEpg { it.copy(epgPastHours = PAST_HOURS.nextAfter(it.epgPastHours)) } } }

            // ---------------------------------------------------------- channels
            item { SectionHeader("Channels") }
            item { ValueRow("Hidden channels", "${user.hiddenChannels.size}") { dialog = SettingsDialog.HiddenChannels } }
            item { ValueRow("Hidden groups", "${user.hiddenGroups.size}") { dialog = SettingsDialog.HiddenGroups } }
            item { ActionRow("Clear favourites (${user.favorites.size})") { viewModel.clearFavorites() } }
            item { ActionRow("Clear recently watched") { viewModel.clearRecent() } }
            item { Spacer(Modifier.height(48.dp)) }
        }
    }

    when (val d = dialog) {
        null -> Unit
        SettingsDialog.AddM3u -> SourceFormDialog(
            title = "Add M3U playlist",
            fields = listOf(
                FormField("Name", "My provider"),
                FormField("Playlist URL", "http://…/playlist.m3u"),
                FormField("User agent (optional)", "Leave empty for default")
            ),
            onDismiss = { dialog = null },
            onSave = { v -> if (v[1].isNotBlank()) viewModel.addPlaylist(v[0], v[1], v[2]); dialog = null }
        )
        SettingsDialog.AddXtream -> SourceFormDialog(
            title = "Add Xtream Codes login",
            fields = listOf(
                FormField("Name", "My provider"),
                FormField("Server", "http://host:port"),
                FormField("Username", ""),
                FormField("Password", "", password = true)
            ),
            onDismiss = { dialog = null },
            onSave = { v -> if (v[1].isNotBlank()) viewModel.addXtream(v[0], v[1], v[2], v[3]); dialog = null }
        )
        SettingsDialog.AddEpg -> SourceFormDialog(
            title = "Add EPG source",
            fields = listOf(FormField("Name", "Guide"), FormField("XMLTV URL (.xml or .xml.gz)", "http://…/epg.xml.gz")),
            onDismiss = { dialog = null },
            onSave = { v -> if (v[1].isNotBlank()) viewModel.addEpg(v[0], v[1]); dialog = null }
        )
        is SettingsDialog.PlaylistActions -> ActionsDialog(
            title = d.source.name,
            actions = listOf(
                "Edit" to { dialog = SettingsDialog.EditPlaylist(d.source) },
                (if (d.source.enabled) "Disable" else "Enable") to {
                    viewModel.setPlaylistEnabled(d.source.id, !d.source.enabled); dialog = null
                },
                "Update now" to { viewModel.savePlaylist(d.source); dialog = null },
                "Move up" to { viewModel.movePlaylist(d.source.id, -1); dialog = null },
                "Move down" to { viewModel.movePlaylist(d.source.id, 1); dialog = null },
                "Delete" to { viewModel.removePlaylist(d.source.id); dialog = null }
            ),
            onDismiss = { dialog = null }
        )
        is SettingsDialog.EditPlaylist -> {
            val src = d.source
            if (src.isXtream) {
                SourceFormDialog(
                    title = "Edit Xtream login",
                    fields = listOf(
                        FormField("Name", "", src.name),
                        FormField("Server", "", src.xtreamServer),
                        FormField("Username", "", src.xtreamUsername),
                        FormField("Password", "", src.xtreamPassword, password = true),
                        FormField("User agent (optional)", "", src.userAgent)
                    ),
                    extraToggle = "Load built-in guide" to src.useEmbeddedEpg,
                    onDismiss = { dialog = null },
                    onSave = { v, toggle ->
                        viewModel.savePlaylist(src.copy(name = v[0], xtreamServer = v[1], xtreamUsername = v[2], xtreamPassword = v[3], userAgent = v[4], useEmbeddedEpg = toggle))
                        dialog = null
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
                    extraToggle = "Load guide linked in playlist" to src.useEmbeddedEpg,
                    onDismiss = { dialog = null },
                    onSave = { v, toggle ->
                        viewModel.savePlaylist(src.copy(name = v[0], url = v[1], userAgent = v[2], useEmbeddedEpg = toggle))
                        dialog = null
                    }
                )
            }
        }
        is SettingsDialog.EpgActions -> ActionsDialog(
            title = d.source.name,
            actions = listOf(
                "Edit" to { dialog = SettingsDialog.EditEpg(d.source) },
                (if (d.source.enabled) "Disable" else "Enable") to {
                    viewModel.setEpgEnabled(d.source.id, !d.source.enabled); dialog = null
                },
                "Update now" to { viewModel.saveEpg(d.source); dialog = null },
                "Delete" to { viewModel.removeEpg(d.source.id); dialog = null }
            ),
            onDismiss = { dialog = null }
        )
        is SettingsDialog.EditEpg -> SourceFormDialog(
            title = "Edit EPG source",
            fields = listOf(FormField("Name", "", d.source.name), FormField("XMLTV URL", "", d.source.url)),
            onDismiss = { dialog = null },
            onSave = { v -> viewModel.saveEpg(d.source.copy(name = v[0], url = v[1])); dialog = null }
        )
        SettingsDialog.HiddenChannels -> UnhideDialog(
            title = "Hidden channels",
            entries = user.hiddenChannels.sorted().map { it to viewModel.channelLabel(it) },
            onUnhide = { viewModel.unhideChannel(it) },
            onUnhideAll = { viewModel.unhideAllChannels(); dialog = null },
            onDismiss = { dialog = null }
        )
        SettingsDialog.HiddenGroups -> UnhideDialog(
            title = "Hidden groups",
            entries = user.hiddenGroups.sorted().map { it to viewModel.groupLabel(it) },
            onUnhide = { viewModel.unhideGroup(it) },
            onUnhideAll = { viewModel.unhideAllGroups(); dialog = null },
            onDismiss = { dialog = null }
        )
    }
}

private fun hoursLabel(h: Int): String = when {
    h % 24 == 0 && h >= 24 -> if (h == 24) "1 day" else "${h / 24} days"
    else -> "$h hours"
}

private fun sourceSubtitle(enabled: Boolean, updated: Long, error: String?, count: String): String {
    if (!enabled) return "Disabled"
    val parts = mutableListOf<String>()
    if (error != null) parts += "Error: $error"
    if (updated > 0) {
        parts += count
        parts += "Updated " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(updated))
    } else if (error == null) parts += "Not downloaded yet"
    return parts.joinToString("  ·  ")
}

// ==================================================================== rows

@Composable
private fun SectionHeader(title: String) {
    LiveText(
        title,
        modifier = Modifier.padding(start = 14.dp, top = 24.dp, bottom = 6.dp),
        color = NuvioTheme.colors.Secondary,
        size = 15.sp,
        weight = FontWeight.SemiBold
    )
}

@Composable
private fun SourceRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    LiveFocusRow(modifier = modifier.fillMaxWidth().widthIn(max = 900.dp), onClick = onClick) { f ->
        Column(modifier = Modifier.weight(1f)) {
            LiveText(title, color = focusedTextColor(f), weight = FontWeight.Medium)
            LiveText(
                subtitle,
                color = if (f) NuvioTheme.colors.OnSecondary.copy(alpha = 0.8f) else NuvioTheme.colors.TextSecondary,
                size = 13.sp
            )
        }
        LiveText(if (enabled) "On" else "Off", color = focusedTextColor(f), size = 14.sp)
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onToggle: () -> Unit) {
    ValueRow(label, if (value) "On" else "Off", onClick = onToggle)
}

@Composable
private fun ValueRow(label: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    LiveFocusRow(modifier = modifier.fillMaxWidth(), onClick = onClick) { f ->
        LiveText(label, modifier = Modifier.weight(1f), color = focusedTextColor(f))
        LiveText(value, color = if (f) NuvioTheme.colors.OnSecondary else NuvioTheme.colors.TextSecondary)
    }
}

@Composable
private fun ActionRow(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    LiveFocusRow(modifier = modifier.fillMaxWidth(), onClick = onClick) { f ->
        LiveText(label, color = if (f) NuvioTheme.colors.OnSecondary else NuvioTheme.colors.Secondary, weight = FontWeight.Medium)
    }
}

// ==================================================================== dialogs

private data class FormField(val label: String, val hint: String, val initial: String = "", val password: Boolean = false)

@Composable
private fun SourceFormDialog(
    title: String,
    fields: List<FormField>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit
) = SourceFormDialog(title, fields, null, onDismiss) { v, _ -> onSave(v) }

@Composable
private fun SourceFormDialog(
    title: String,
    fields: List<FormField>,
    extraToggle: Pair<String, Boolean>?,
    onDismiss: () -> Unit,
    onSave: (List<String>, Boolean) -> Unit
) {
    val values = remember { fields.map { mutableStateOf(it.initial) } }
    var toggle by remember { mutableStateOf(extraToggle?.second ?: true) }
    val firstField = remember { FocusRequester() }
    LiveDialog(onDismiss = onDismiss, width = 640.dp) {
        LiveText(title, size = 20.sp, weight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(fields.size) { i ->
                val field = fields[i]
                Column {
                    LiveText(field.label, color = NuvioTheme.colors.TextSecondary, size = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
                    LiveTextField(
                        value = values[i].value,
                        onValueChange = { values[i].value = it },
                        hint = field.hint,
                        focusRequester = if (i == 0) firstField else null,
                        password = field.password
                    )
                }
            }
            if (extraToggle != null) {
                item {
                    ValueRow(extraToggle.first, if (toggle) "On" else "Off") { toggle = !toggle }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                    LiveFocusRow(onClick = { onSave(values.map { it.value.trim() }, toggle) }) { f ->
                        LiveText("Save", color = focusedTextColor(f), weight = FontWeight.SemiBold)
                    }
                    LiveFocusRow(onClick = onDismiss) { f -> LiveText("Cancel", color = focusedTextColor(f)) }
                }
            }
        }
    }
    LaunchedEffect(Unit) { delay(80); runCatching { firstField.requestFocus() } }
}

@Composable
private fun ActionsDialog(title: String, actions: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    LiveDialog(onDismiss = onDismiss, width = 420.dp) {
        LiveText(title, size = 20.sp, weight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            actions.forEachIndexed { i, (label, action) ->
                MenuItem(label, if (i == 0) Modifier.focusRequester(first) else Modifier, action)
            }
        }
    }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
}

@Composable
private fun UnhideDialog(
    title: String,
    entries: List<Pair<String, String>>,
    onUnhide: (String) -> Unit,
    onUnhideAll: () -> Unit,
    onDismiss: () -> Unit
) {
    val first = remember { FocusRequester() }
    LiveDialog(onDismiss = onDismiss, width = 560.dp) {
        LiveText(title, size = 20.sp, weight = FontWeight.Bold)
        LiveText("Press OK on an item to show it again.", color = NuvioTheme.colors.TextSecondary, size = 13.sp)
        Spacer(Modifier.height(12.dp))
        if (entries.isEmpty()) {
            LiveText("Nothing is hidden.", color = NuvioTheme.colors.TextSecondary)
            Spacer(Modifier.height(12.dp))
            MenuItem("Close", Modifier.focusRequester(first), onDismiss)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item { MenuItem("Show all (${entries.size})", Modifier.focusRequester(first), onUnhideAll) }
                items(entries, key = { it.first }) { (key, label) ->
                    MenuItem(label, onClick = { onUnhide(key) })
                }
            }
        }
    }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
}
