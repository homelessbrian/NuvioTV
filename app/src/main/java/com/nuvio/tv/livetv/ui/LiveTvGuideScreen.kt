package com.nuvio.tv.livetv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.livetv.data.CatchupUrlBuilder
import com.nuvio.tv.livetv.model.ChannelGroup
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SLOT_MS = 30L * 60L * 1000L
private const val WINDOW_MS = 2L * 60L * 60L * 1000L

private enum class GuideColumn { CHANNEL, PROGRAM }

/** What the cursor currently sits on in a channel row. */
private data class GuideBlock(val startMs: Long, val stopMs: Long, val program: EpgProgram?)

private data class MenuTarget(val channel: LiveChannel, val block: GuideBlock?)

private fun floorSlot(ms: Long) = ms - Math.floorMod(ms, SLOT_MS)

@Composable
fun LiveTvGuideScreen(
    onOpenFullscreen: () -> Unit,
    onOpenSettings: () -> Unit,
    onFindInNuvio: () -> Unit = {},
    onOpenNuvioSearch: () -> Unit = onFindInNuvio,
    viewModel: LiveTvViewModel = hiltViewModel()
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val user by viewModel.userState.collectAsStateWithLifecycle()
    val programs by viewModel.programs.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Keep the shared player alive while the guide is on screen.
    DisposableEffect(Unit) {
        viewModel.playback.attach()
        onDispose { viewModel.playback.detach() }
    }
    LaunchedEffect(settings.showPreview) {
        if (!settings.showPreview) viewModel.playback.stop()
    }

    val gridFocus = remember { FocusRequester() }
    val groupsFocus = remember { FocusRequester() }
    val contentFocus = LocalContentFocusRequester.current

    var column by remember { mutableStateOf(GuideColumn.CHANNEL) }
    var row by remember { mutableIntStateOf(0) }
    var cursorMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var windowStart by remember { mutableLongStateOf(floorSlot(System.currentTimeMillis())) }
    var pendingGroupId by remember { mutableStateOf<String?>(null) }
    // A channel to move the cursor to once it's in the list (after a group switch, etc).
    var highlightKey by remember { mutableStateOf<String?>(null) }
    val groupsListState = rememberLazyListState()
    var menuTarget by remember { mutableStateOf<MenuTarget?>(null) }
    var infoTarget by remember { mutableStateOf<MenuTarget?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    var textPrompt by remember { mutableStateOf<TextPrompt?>(null) }
    var groupPickerFor by remember { mutableStateOf<LiveChannel?>(null) }
    var groupMenu by remember { mutableStateOf<ChannelGroup?>(null) }
    var gridFocused by remember { mutableStateOf(false) }
    var longPressFired by remember { mutableStateOf(false) }
    var numberBuffer by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val channels = ui.channels
    val selectedGroupTitle = ui.groups.firstOrNull { it.id == ui.selectedGroupId }?.title ?: "All channels"

    // Reset the cursor when the group changes; land on the playing channel if it is in the list.
    LaunchedEffect(ui.selectedGroupId, channels.size) {
        val playingIdx = channels.indexOfFirst { it.key == playback.channelKey }
        row = if (playingIdx >= 0) playingIdx else row.coerceIn(0, (channels.size - 1).coerceAtLeast(0))
        listState.scrollToItem((row - 2).coerceAtLeast(0))
    }
    // Keep the window anchored on "now" while the user is not browsing ahead.
    LaunchedEffect(now) {
        if (column == GuideColumn.CHANNEL) {
            windowStart = floorSlot(now)
            cursorMs = now
        }
    }
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { gridFocus.requestFocus() }
    }
    LaunchedEffect(numberBuffer) {
        if (numberBuffer.isEmpty()) return@LaunchedEffect
        delay(1_200)
        val n = numberBuffer.toIntOrNull()
        numberBuffer = ""
        if (n != null) {
            val idx = channels.indexOfFirst { it.number == n }
            if (idx >= 0) {
                row = idx
            } else {
                viewModel.channelByNumber(n)?.let { target ->
                    viewModel.selectGroup(ui.groupFor(target))
                    highlightKey = target.key
                }
            }
        }
    }

    fun focusGroups() {
        scope.launch {
            val idx = ui.groups.indexOfFirst { it.id == ui.selectedGroupId }.coerceAtLeast(0)
            val visible = groupsListState.layoutInfo.visibleItemsInfo.map { it.index }
            if (idx !in visible) groupsListState.scrollToItem((idx - 3).coerceAtLeast(0))
            repeat(2) { androidx.compose.runtime.withFrameNanos { } }
            runCatching { groupsFocus.requestFocus() }
        }
    }

    // Moving through the group column shows that group straight away (Search opens on OK).
    LaunchedEffect(pendingGroupId) {
        val id = pendingGroupId ?: return@LaunchedEffect
        delay(350)
        if (id != ChannelGroup.SEARCH && id != ui.selectedGroupId) {
            viewModel.selectGroup(id)
            row = 0
            column = GuideColumn.CHANNEL
            windowStart = floorSlot(now)
            cursorMs = now
        }
    }

    // Opening Live TV (or coming back from full screen):
    //  1. "Auto-play last channel" (once per app launch) goes straight to full screen.
    //  2. Otherwise, if something is already playing, highlight it.
    //  3. Otherwise "Resume last channel in preview" starts the last channel in the preview.
    LaunchedEffect(Unit) {
        viewModel.autoPlayCandidate()?.let { ch ->
            viewModel.preview(ch)
            onOpenFullscreen()
            return@LaunchedEffect
        }
        val target = viewModel.channelByKey(viewModel.playbackState.value.channelKey)
            ?: viewModel.resumeCandidate()?.also { viewModel.preview(it) }
            ?: return@LaunchedEffect
        val current = viewModel.uiState.value
        if (current.channels.none { it.key == target.key }) viewModel.selectGroup(current.groupFor(target))
        highlightKey = target.key
    }
    LaunchedEffect(highlightKey, channels) {
        val key = highlightKey ?: return@LaunchedEffect
        val idx = channels.indexOfFirst { it.key == key }
        if (idx < 0) return@LaunchedEffect
        row = idx
        column = GuideColumn.CHANNEL
        listState.scrollToItem((idx - 2).coerceAtLeast(0))
        highlightKey = null
    }

    // Back: leave programme browsing, then leave a group for All channels, then open Nuvio's menu.
    val sidebarExpanded = com.nuvio.tv.LocalSidebarExpanded.current
    val openSidebar = com.nuvio.tv.LocalOpenSidebar.current
    BackHandler(enabled = !sidebarExpanded) {
        when {
            column == GuideColumn.PROGRAM -> {
                column = GuideColumn.CHANNEL
                windowStart = floorSlot(now)
                cursorMs = now
            }
            ui.selectedGroupId != ui.defaultGroupId -> {
                viewModel.selectGroup(ui.defaultGroupId)
                row = 0
                scope.launch { delay(60); runCatching { gridFocus.requestFocus() } }
            }
            else -> openSidebar()
        }
    }

    fun findInNuvio(program: EpgProgram) {
        LiveTvSearchBridge.request(program.title)
        onFindInNuvio()
    }

    val focusedChannel = channels.getOrNull(row)

    fun blockAt(channel: LiveChannel, at: Long): GuideBlock {
        val list = programs[channel.key].orEmpty()
        list.firstOrNull { at >= it.startMs && at < it.stopMs }?.let { return GuideBlock(it.startMs, it.stopMs, it) }
        // Gap / no data: a 30 minute slot trimmed against neighbouring programmes.
        var s = floorSlot(at)
        var e = s + SLOT_MS
        list.lastOrNull { it.stopMs <= at }?.let { if (it.stopMs > s) s = it.stopMs }
        list.firstOrNull { it.startMs > at }?.let { if (it.startMs < e) e = it.startMs }
        return GuideBlock(s, e, null)
    }

    val focusedBlock: GuideBlock? = focusedChannel?.let { ch ->
        if (column == GuideColumn.PROGRAM) blockAt(ch, cursorMs) else blockAt(ch, now)
    }

    fun ensureRowVisible(target: Int) {
        scope.launch {
            val info = listState.layoutInfo
            val visible = info.visibleItemsInfo
            if (visible.isEmpty()) return@launch
            val first = visible.first().index
            val fullyVisible = visible.count { it.offset >= 0 && it.offset + it.size <= info.viewportEndOffset }
            val last = first + (fullyVisible - 1).coerceAtLeast(0)
            when {
                target < first -> listState.scrollToItem(target)
                target > last -> listState.scrollToItem((target - fullyVisible + 1).coerceAtLeast(0))
            }
        }
    }

    fun playChannel(ch: LiveChannel) {
        val alreadyPreviewing = playback.channelKey == ch.key && playback.catchupTitle == null
        viewModel.preview(ch)
        if (!settings.showPreview || settings.openFullscreenOnSelect || alreadyPreviewing) onOpenFullscreen()
    }

    fun activate() {
        if (!ui.hasSources) { onOpenSettings(); return }
        val ch = focusedChannel ?: return
        if (column == GuideColumn.CHANNEL) { playChannel(ch); return }
        val block = focusedBlock ?: return
        when {
            now >= block.startMs && now < block.stopMs -> playChannel(ch)
            block.stopMs <= now && block.program != null &&
                CatchupUrlBuilder.isAvailable(ch.catchup, block.startMs, now) -> {
                if (viewModel.playCatchup(ch, block.program)) onOpenFullscreen()
            }
            else -> infoTarget = MenuTarget(ch, block)
        }
    }

    fun moveRow(delta: Int) {
        if (channels.isEmpty()) return
        row = (row + delta).coerceIn(0, channels.lastIndex)
        ensureRowVisible(row)
    }

    fun moveRight() {
        val ch = focusedChannel ?: return
        if (column == GuideColumn.CHANNEL) {
            column = GuideColumn.PROGRAM
            cursorMs = maxOf(now, windowStart)
            return
        }
        val block = blockAt(ch, cursorMs)
        val limit = now + settings.epgFutureDays * 24L * 60 * 60 * 1000
        if (block.stopMs >= limit) return
        cursorMs = block.stopMs
        while (cursorMs >= windowStart + WINDOW_MS - SLOT_MS / 2) windowStart += SLOT_MS
    }

    fun resetToNow() {
        column = GuideColumn.CHANNEL
        windowStart = floorSlot(now)
        cursorMs = now
    }

    fun moveLeft() {
        val ch = focusedChannel ?: return
        if (column == GuideColumn.CHANNEL) {
            focusGroups()
            return
        }
        val block = blockAt(ch, cursorMs)
        val earliest = if (ch.catchup != null) {
            floorSlot(now - (ch.catchup.days.coerceAtMost(7) * 24L * 60 * 60 * 1000).coerceAtMost(settings.epgPastHours * 60L * 60 * 1000))
        } else floorSlot(now)
        if (block.startMs <= earliest || (ch.catchup == null && block.startMs <= now)) {
            resetToNow()
            return
        }
        cursorMs = block.startMs - 1
        while (cursorMs < windowStart) windowStart -= SLOT_MS
    }

    CompositionLocalProvider(LocalLiveSolidHighlight provides settings.solidHighlight) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ---------------------------------------------------------------- header
            GuideHeader(
                channel = focusedChannel,
                block = focusedBlock,
                settings = settings,
                now = now,
                showPreview = settings.showPreview,
                player = viewModel.playback.player,
                playingKey = playback.channelKey,
                playbackBuffering = playback.isBuffering,
                playbackError = playback.error,
                catchupTitle = playback.catchupTitle,
                status = status.message.takeIf { status.loading }
            )

            // ---------------------------------------------------------------- groups + guide
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 56.dp)
            ) {
                GroupColumn(
                    groups = ui.groups,
                    selectedId = ui.selectedGroupId,
                    listState = groupsListState,
                    focusRequester = groupsFocus,
                    onFocusGroup = { g -> pendingGroupId = g.id },
                    onSelect = { g ->
                        if (g.id == ChannelGroup.SEARCH) {
                            onOpenNuvioSearch()
                        } else {
                            if (g.id != ui.selectedGroupId) {
                                viewModel.selectGroup(g.id)
                                row = 0
                            }
                            resetToNow()
                            scope.launch { delay(60); runCatching { gridFocus.requestFocus() } }
                        }
                    },
                    onGroupMenu = { g -> if (!g.special) groupMenu = g },
                    onRight = {
                        column = GuideColumn.CHANNEL
                        runCatching { gridFocus.requestFocus() }
                    }
                )
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    // ---------------------------------------------------------------- time ruler
                    val channelColWidth = channelColumnWidth(settings)
                    val rowHeight = if (settings.compactRows) 42.dp else 52.dp
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(34.dp)
                            .padding(end = 24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LiveText(
                            text = selectedGroupTitle,
                            modifier = Modifier.width(channelColWidth).padding(start = 8.dp),
                            color = NuvioTheme.colors.Secondary,
                            weight = FontWeight.SemiBold,
                            size = 15.sp
                        )
                        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            val w = maxWidth
                            for (i in 0 until (WINDOW_MS / SLOT_MS).toInt()) {
                                val t = windowStart + i * SLOT_MS
                                LiveText(
                                    text = formatDayClock(t, now, settings.use24HourClock),
                                    modifier = Modifier
                                        .offset(x = w * (i * SLOT_MS / WINDOW_MS.toFloat()))
                                        .align(Alignment.CenterStart)
                                        .padding(start = 6.dp),
                                    color = NuvioTheme.colors.TextSecondary,
                                    size = 13.sp
                                )
                            }
                        }
                    }

                    // ---------------------------------------------------------------- grid
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(end = 24.dp, bottom = 12.dp)
                            .focusRequester(gridFocus)
                            .focusRequester(contentFocus)
                            .onFocusChanged { gridFocused = it.hasFocus || it.isFocused }
                            .onPreviewKeyEvent { event ->
                                val isOk = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                                if (isOk) {
                                    if (event.type == KeyEventType.KeyDown) {
                                        // A new press: forget a long press whose release went to a dialog.
                                        if (event.nativeKeyEvent.repeatCount == 0) longPressFired = false
                                        if (event.nativeKeyEvent.repeatCount > 0 && !longPressFired) {
                                            longPressFired = true
                                            focusedChannel?.let { menuTarget = MenuTarget(it, if (column == GuideColumn.PROGRAM) focusedBlock else null) }
                                        }
                                        return@onPreviewKeyEvent true
                                    }
                                    if (event.type == KeyEventType.KeyUp) {
                                        if (longPressFired) longPressFired = false else activate()
                                        return@onPreviewKeyEvent true
                                    }
                                }
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                val code = event.nativeKeyEvent.keyCode
                                when {
                                    event.key == Key.DirectionUp -> { moveRow(-1); true }
                                    event.key == Key.DirectionDown -> { moveRow(1); true }
                                    event.key == Key.PageUp || event.key == Key.ChannelUp -> { moveRow(-8); true }
                                    event.key == Key.PageDown || event.key == Key.ChannelDown -> { moveRow(8); true }
                                    event.key == Key.DirectionRight -> { moveRight(); true }
                                    event.key == Key.DirectionLeft -> { moveLeft(); true }
                                    event.key == Key.MediaFastForward -> { repeat(4) { moveRight() }; true }
                                    event.key == Key.MediaRewind -> { repeat(4) { moveLeft() }; true }
                                    event.key == Key.Menu || event.key == Key.Info -> {
                                        focusedChannel?.let { menuTarget = MenuTarget(it, if (column == GuideColumn.PROGRAM) focusedBlock else null) }
                                        true
                                    }
                                            event.key == Key.Search -> { onOpenNuvioSearch(); true }
                                    code in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 -> {
                                        if (numberBuffer.length < 5) numberBuffer += (code - android.view.KeyEvent.KEYCODE_0).toString()
                                        true
                                    }
                                    code == android.view.KeyEvent.KEYCODE_LAST_CHANNEL -> {
                                        viewModel.previousChannel()?.let { playChannel(it) }
                                        true
                                    }
                                    else -> false
                                }
                            }
                            .focusable()
                    ) {
                        when {
                            !ui.hasSources -> EmptyGuideMessage(
                                title = "No playlists yet",
                                body = "Add an M3U playlist, Xtream login or XMLTV guide in Settings › Live TV.",
                                action = "Press OK to open Live TV settings"
                            )
                            channels.isEmpty() && status.loading -> EmptyGuideMessage(
                                title = status.message ?: "Loading…", body = null, action = null
                            )
                            channels.isEmpty() && ui.selectedGroupId == ChannelGroup.SEARCH -> EmptyGuideMessage(
                                title = if (ui.searchQuery.isBlank()) "Search channels" else "No channels match \"${ui.searchQuery}\"",
                                body = "Press OK on Search in the group list, or the search key on your remote.",
                                action = null
                            )
                            channels.isEmpty() -> EmptyGuideMessage(
                                title = "No channels in this group",
                                body = "Press left to pick another group.",
                                action = null
                            )
                            else -> {
                                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), userScrollEnabled = false) {
                                    itemsIndexed(channels, key = { _, c -> c.key }) { index, ch ->
                                        GuideRow(
                                            channel = ch,
                                            programs = programs[ch.key].orEmpty(),
                                            settings = settings,
                                            rowHeight = rowHeight,
                                            channelColWidth = channelColWidth,
                                            windowStart = windowStart,
                                            now = now,
                                            isFavorite = ch.key in user.favorites,
                                            isPlaying = ch.key == playback.channelKey,
                                            focusColumn = if (index == row && gridFocused) column else null,
                                            focusedBlock = if (index == row) focusedBlock else null
                                        )
                                    }
                                }
                                // "Now" line across the programme area.
                                if (now in windowStart until windowStart + WINDOW_MS) {
                                    BoxWithConstraints(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(start = channelColWidth)
                                    ) {
                                        val x = maxWidth * ((now - windowStart) / WINDOW_MS.toFloat())
                                        Box(
                                            modifier = Modifier
                                                .offset(x = x)
                                                .width(2.dp)
                                                .fillMaxHeight()
                                                .background(NuvioTheme.colors.FocusRing.copy(alpha = 0.85f))
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (settings.showProgramDetails || settings.showPreview) {
            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 32.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (status.loading) {
                    status.message?.let {
                        LiveText(it, color = NuvioTheme.colors.TextSecondary, size = 12.sp, modifier = Modifier.padding(end = 14.dp))
                    }
                }
                LiveText(formatClock(now, settings.use24HourClock), size = 15.sp, weight = FontWeight.SemiBold)
            }
        }

        // ---------------------------------------------------------------- dialogs
        menuTarget?.let { target ->
            ChannelContextMenu(
                target = target,
                isFavorite = target.channel.key in user.favorites,
                inFavoritesGroup = ui.selectedGroupId == ChannelGroup.FAVORITES,
                now = now,
                onDismiss = { menuTarget = null },
                onWatch = { menuTarget = null; viewModel.preview(target.channel); onOpenFullscreen() },
                onCatchup = { program ->
                    menuTarget = null
                    if (viewModel.playCatchup(target.channel, program)) onOpenFullscreen()
                },
                onToggleFavorite = { viewModel.toggleFavorite(target.channel); menuTarget = null },
                onMoveFavorite = { d ->
                    viewModel.moveFavorite(target.channel, d)
                    row = (row + d).coerceIn(0, (channels.size - 1).coerceAtLeast(0))
                },
                customGroupId = ui.selectedGroupId.takeIf { it.startsWith(ChannelGroup.CUSTOM_PREFIX) },
                onMoveInGroup = { gid, d ->
                    viewModel.moveInGroup(gid, target.channel, d)
                    row = (row + d).coerceIn(0, (channels.size - 1).coerceAtLeast(0))
                },
                onRemoveFromGroup = { gid -> viewModel.removeFromGroup(gid, target.channel); menuTarget = null },
                onAddToGroup = { menuTarget = null; groupPickerFor = target.channel },
                onRename = {
                    menuTarget = null
                    textPrompt = TextPrompt(
                        title = "Rename channel",
                        initial = target.channel.name,
                        hint = "Leave empty to use the playlist name",
                        onConfirm = { viewModel.renameChannel(target.channel, it) }
                    )
                },
                onRenumber = {
                    menuTarget = null
                    textPrompt = TextPrompt(
                        title = "Channel number",
                        initial = target.channel.number.toString(),
                        hint = "Leave empty to use the playlist number",
                        numeric = true,
                        onConfirm = { viewModel.setChannelNumber(target.channel, it.trim().toIntOrNull()) }
                    )
                },
                onHide = {
                    viewModel.hideChannel(target.channel)
                    menuTarget = null
                },
                onHideGroup = {
                    viewModel.hideGroup(target.channel.groupId)
                    menuTarget = null
                },
                onProgramInfo = { menuTarget = null; infoTarget = target },
                streamProgram = target.block?.program ?: viewModel.currentProgram(target.channel.key),
                onFindInNuvio = { p -> menuTarget = null; findInNuvio(p) },
                onSearch = { menuTarget = null; onOpenNuvioSearch() },
                onRefresh = { menuTarget = null; viewModel.refresh() },
                onSettings = { menuTarget = null; onOpenSettings() }
            )
        }
        infoTarget?.let { target ->
            ProgramInfoDialog(
                target = target,
                now = now,
                use24h = settings.use24HourClock,
                onDismiss = { infoTarget = null },
                onFind = { p -> infoTarget = null; findInNuvio(p) },
                onWatch = { infoTarget = null; playChannel(target.channel) },
                onCatchup = { p ->
                    infoTarget = null
                    if (viewModel.playCatchup(target.channel, p)) onOpenFullscreen()
                }
            )
        }
        textPrompt?.let { prompt ->
            TextInputDialog(
                title = prompt.title,
                initial = prompt.initial,
                hint = prompt.hint,
                confirmLabel = "Save",
                numeric = prompt.numeric,
                onDismiss = { textPrompt = null },
                onConfirm = { value -> prompt.onConfirm(value); textPrompt = null }
            )
        }
        groupPickerFor?.let { ch ->
            GroupPickerDialog(
                channel = ch,
                groups = ui.customGroups,
                onDismiss = { groupPickerFor = null },
                onPick = { g -> viewModel.addToGroup(g.id, ch); groupPickerFor = null },
                onNewGroup = {
                    groupPickerFor = null
                    textPrompt = TextPrompt(
                        title = "New group",
                        initial = "",
                        hint = "Group name",
                        onConfirm = { name -> viewModel.createGroupWith(name, ch) }
                    )
                }
            )
        }
        groupMenu?.let { g ->
            val isCustom = g.id.startsWith(ChannelGroup.CUSTOM_PREFIX)
            ActionMenuDialog(
                title = g.title,
                actions = buildList {
                    add("Rename group" to {
                        groupMenu = null
                        textPrompt = TextPrompt(
                            title = "Rename group",
                            initial = g.title,
                            hint = if (isCustom) "Group name" else "Leave empty to use the playlist name",
                            onConfirm = { viewModel.renameGroup(g.id, it) }
                        )
                    })
                    add("Move up" to { viewModel.moveGroup(g.id, -1) })
                    add("Move down" to { viewModel.moveGroup(g.id, 1) })
                    add("New group" to {
                        groupMenu = null
                        textPrompt = TextPrompt(
                            title = "New group", initial = "", hint = "Group name",
                            onConfirm = { name -> viewModel.createGroupWith(name, null) }
                        )
                    })
                    add("Hide group" to { viewModel.hideGroup(g.id); groupMenu = null })
                    if (isCustom) add("Delete group" to { viewModel.deleteGroup(g.id); groupMenu = null })
                },
                onDismiss = { groupMenu = null }
            )
        }
        if (searchOpen) {
            SearchDialog(
                initial = ui.searchQuery,
                onDismiss = { searchOpen = false },
                onSearch = { q ->
                    searchOpen = false
                    viewModel.setSearchQuery(q)
                    row = 0
                    resetToNow()
                    scope.launch { delay(80); runCatching { gridFocus.requestFocus() } }
                }
            )
        }

        if (numberBuffer.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 24.dp, end = 32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(NuvioTheme.colors.BackgroundElevated)
                    .padding(horizontal = 22.dp, vertical = 10.dp)
            ) {
                LiveText(numberBuffer, size = 34.sp, weight = FontWeight.Bold)
            }
        }
    }
    }
}

private fun channelColumnWidth(s: LiveTvSettings): Dp {
    var w = 16.dp
    if (s.showChannelNumbers) w += 52.dp
    if (s.showChannelLogos) w += 72.dp
    if (s.showChannelNames) w += 150.dp
    return w.coerceAtLeast(80.dp)
}

// ==================================================================== header

@Composable
private fun GuideHeader(
    channel: LiveChannel?,
    block: GuideBlock?,
    settings: LiveTvSettings,
    now: Long,
    showPreview: Boolean,
    player: androidx.media3.common.Player?,
    playingKey: String?,
    playbackBuffering: Boolean,
    playbackError: String?,
    catchupTitle: String?,
    status: String?
) {
    if (!settings.showProgramDetails && !showPreview) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 64.dp, end = 32.dp, top = 20.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LiveText("Live TV", size = 22.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
            status?.let { LiveText(it, color = NuvioTheme.colors.TextSecondary, size = 13.sp) }
            Spacer(Modifier.width(16.dp))
            LiveText(formatClock(now, settings.use24HourClock), size = 20.sp, weight = FontWeight.SemiBold)
        }
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(212.dp)
            .padding(start = 64.dp, end = 32.dp, top = 28.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            if (settings.showProgramDetails && channel != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (settings.showChannelLogos) {
                        ChannelLogo(channel.logo, 34.dp)
                        Spacer(Modifier.width(12.dp))
                    }
                    val label = buildString {
                        if (settings.showChannelNumbers) append("${channel.number}  ")
                        append(channel.name)
                    }
                    LiveText(label, color = NuvioTheme.colors.TextSecondary, size = 16.sp, weight = FontWeight.Medium)
                }
                Spacer(Modifier.height(10.dp))
                val p = block?.program
                LiveText(
                    text = p?.title ?: channel.name,
                    modifier = Modifier.fillMaxWidth(),
                    size = 28.sp,
                    weight = FontWeight.Bold,
                    maxLines = 1,
                    marquee = true
                )
                Spacer(Modifier.height(6.dp))
                if (block != null) {
                    val meta = buildList {
                        add(formatDayClock(block.startMs, now, settings.use24HourClock) + " – " + formatClock(block.stopMs, settings.use24HourClock))
                        if (now in block.startMs until block.stopMs) add(minutesLeftLabel(block.stopMs, now))
                        p?.episode?.let { add(it) }
                        p?.category?.let { add(it) }
                        if (channel.catchup != null) add("Catch-up")
                    }.joinToString("  ·  ")
                    LiveText(meta, color = NuvioTheme.colors.TextSecondary, size = 15.sp)
                    if (now in block.startMs until block.stopMs) {
                        Spacer(Modifier.height(8.dp))
                        ProgressBar(fraction = ((now - block.startMs).toFloat() / (block.stopMs - block.startMs).coerceAtLeast(1)).coerceIn(0f, 1f))
                    }
                }
                Spacer(Modifier.height(10.dp))
                LiveText(
                    text = p?.description ?: if (p == null) "No programme information" else "",
                    color = NuvioTheme.colors.TextSecondary,
                    size = 15.sp,
                    maxLines = 4
                )
            } else if (status != null) {
                LiveText(status, color = NuvioTheme.colors.TextSecondary)
            }
        }
        if (showPreview) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black)
            ) {
                if (playingKey != null) {
                    LivePlayerSurface(player = player, useSurfaceView = false, modifier = Modifier.fillMaxSize())
                }
                val overlay = when {
                    playingKey == null -> "Press OK on a channel to preview"
                    playbackError != null -> "Reconnecting… ($playbackError)"
                    playbackBuffering -> "Loading…"
                    else -> null
                }
                overlay?.let {
                    LiveText(it, modifier = Modifier.align(Alignment.Center), color = NuvioTheme.colors.TextSecondary, size = 14.sp)
                }
                catchupTitle?.let {
                    LiveText(
                        "ARCHIVE · $it",
                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                            .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 6.dp, vertical = 2.dp),
                        size = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
internal fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .width(260.dp)
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(NuvioTheme.colors.Border)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .background(NuvioTheme.colors.Secondary)
        )
    }
}

// ==================================================================== rows

@Composable
private fun GuideRow(
    channel: LiveChannel,
    programs: List<EpgProgram>,
    settings: LiveTvSettings,
    rowHeight: Dp,
    channelColWidth: Dp,
    windowStart: Long,
    now: Long,
    isFavorite: Boolean,
    isPlaying: Boolean,
    focusColumn: GuideColumn?,
    focusedBlock: GuideBlock?
) {
    val windowEnd = windowStart + WINDOW_MS
    val cellShape = RoundedCornerShape(6.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .padding(vertical = 2.dp)
    ) {
        // Channel cell
        val channelFocused = focusColumn == GuideColumn.CHANNEL
        val cell = liveCellColors(
            focused = channelFocused,
            idle = if (focusColumn != null) guideSurfaceVariant() else guideSurface()
        )
        Row(
            modifier = Modifier
                .width(channelColWidth)
                .fillMaxHeight()
                .padding(end = 3.dp)
                .clip(cellShape)
                .background(cell.background)
                .border(2.dp, cell.border, cellShape)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (settings.showChannelNumbers) {
                LiveText(
                    channel.number.toString(),
                    modifier = Modifier.width(46.dp),
                    color = if (channelFocused) cell.text else NuvioTheme.colors.TextSecondary,
                    size = 15.sp,
                    weight = FontWeight.Medium
                )
            }
            if (settings.showChannelLogos) {
                ChannelLogo(channel.logo, (rowHeight - 20.dp).coerceAtLeast(24.dp))
                Spacer(Modifier.width(8.dp))
            }
            if (settings.showChannelNames) {
                LiveText(
                    channel.name,
                    modifier = Modifier.weight(1f),
                    color = cell.text,
                    size = 15.sp,
                    weight = if (isPlaying) FontWeight.Bold else FontWeight.Normal,
                    marquee = channelFocused
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (isFavorite) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = null,
                    tint = if (channelFocused && LocalLiveSolidHighlight.current) cell.text else NuvioTheme.colors.Rating,
                    modifier = Modifier.size(14.dp)
                )
            }
            if (isPlaying) {
                Spacer(Modifier.width(4.dp))
                Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(NuvioTheme.colors.Error))
            }
        }

        // Programme blocks
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxHeight()) {
            val totalW = maxWidth
            fun xOf(t: Long): Dp = totalW * ((t.coerceIn(windowStart, windowEnd) - windowStart) / WINDOW_MS.toFloat())

            val visible = programs.filter { it.stopMs > windowStart && it.startMs < windowEnd }
            if (visible.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 1.dp)
                        .clip(cellShape)
                        .background(guideSurface().copy(alpha = 0.55f))
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    LiveText(channel.name, color = NuvioTheme.colors.TextTertiary, size = 14.sp)
                }
            }
            visible.forEach { p ->
                val x = xOf(p.startMs)
                val w = (xOf(p.stopMs) - x).coerceAtLeast(2.dp)
                val live = now >= p.startMs && now < p.stopMs
                val past = p.stopMs <= now
                val isFocused = focusColumn == GuideColumn.PROGRAM && focusedBlock?.program == p
                val colors = liveCellColors(
                    focused = isFocused,
                    idle = when {
                        live -> guideSurfaceVariant()
                        past -> guideSurface().copy(alpha = 0.5f)
                        else -> guideSurface()
                    },
                    idleText = if (past) NuvioTheme.colors.TextSecondary else NuvioTheme.colors.TextPrimary
                )
                ProgramBlock(
                    title = p.title,
                    subtitle = if (w > 140.dp || isFocused) p.episode else null,
                    x = x, width = w,
                    colors = colors,
                    focused = isFocused,
                    progress = if (live) p.progress(now) else null,
                    continuesLeft = p.startMs < windowStart
                )
            }
            // Highlight for an empty slot under the cursor.
            if (focusColumn == GuideColumn.PROGRAM && focusedBlock != null && focusedBlock.program == null) {
                val x = xOf(focusedBlock.startMs)
                val w = (xOf(focusedBlock.stopMs) - x).coerceAtLeast(2.dp)
                ProgramBlock(
                    title = "No information", subtitle = null, x = x, width = w,
                    colors = liveCellColors(focused = true, idle = guideSurface()),
                    focused = true, progress = null, continuesLeft = false
                )
            }
        }
    }
}

@Composable
private fun ProgramBlock(
    title: String,
    subtitle: String?,
    x: Dp,
    width: Dp,
    colors: LiveCellColors,
    focused: Boolean,
    progress: Float?,
    continuesLeft: Boolean
) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .offset(x = x)
            .width(width)
            .fillMaxHeight()
            .padding(horizontal = 1.5.dp)
            .clip(shape)
            .background(colors.background)
            .border(2.dp, colors.border, shape)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.Center
        ) {
            // The focused programme scrolls when its title doesn't fit, like TiviMate.
            LiveText(
                (if (continuesLeft) "‹ " else "") + title,
                modifier = Modifier.fillMaxWidth(),
                color = colors.text,
                size = 15.sp,
                marquee = focused
            )
            subtitle?.let {
                LiveText(
                    it,
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.text.copy(alpha = 0.7f),
                    size = 12.sp,
                    marquee = focused
                )
            }
        }
        progress?.let {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(it)
                    .height(2.dp)
                    .background(NuvioTheme.colors.Secondary.copy(alpha = 0.8f))
            )
        }
    }
}

@Composable
private fun EmptyGuideMessage(title: String, body: String?, action: String?) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        LiveText(title, size = 22.sp, weight = FontWeight.SemiBold)
        body?.let {
            Spacer(Modifier.height(8.dp))
            LiveText(it, color = NuvioTheme.colors.TextSecondary, maxLines = 3)
        }
        if (action != null) {
            Spacer(Modifier.height(18.dp))
            LiveText(action, color = NuvioTheme.colors.Secondary, weight = FontWeight.SemiBold)
        }
    }
}

// ==================================================================== group panel

@Composable
private fun GroupColumn(
    groups: List<ChannelGroup>,
    selectedId: String,
    listState: androidx.compose.foundation.lazy.LazyListState,
    focusRequester: FocusRequester,
    onFocusGroup: (ChannelGroup) -> Unit,
    onSelect: (ChannelGroup) -> Unit,
    onGroupMenu: (ChannelGroup) -> Unit,
    onRight: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(190.dp)
            .padding(end = 8.dp, bottom = 12.dp)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) {
                    onRight(); true
                } else false
            }
    ) {
        LiveText(
            "Groups",
            modifier = Modifier.height(34.dp).padding(start = 12.dp, top = 8.dp),
            color = NuvioTheme.colors.TextSecondary,
            size = 13.sp,
            weight = FontWeight.SemiBold
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            itemsIndexed(groups, key = { _, g -> g.id }) { _, g ->
                val selected = g.id == selectedId
                GroupItem(
                    group = g,
                    selected = selected,
                    modifier = if (selected) Modifier.focusRequester(focusRequester) else Modifier,
                    onFocused = { onFocusGroup(g) },
                    onClick = { onSelect(g) },
                    onLongClick = if (g.special) null else ({ onGroupMenu(g) })
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun GroupItem(
    group: ChannelGroup,
    selected: Boolean,
    modifier: Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val colors = liveCellColors(
        focused = focused,
        idle = if (selected) guideSurfaceVariant() else Color.Transparent,
        idleText = if (selected) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(shape)
            .background(colors.background)
            .border(2.dp, colors.border, shape)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .combinedClickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(end = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Accent bar marks the group being shown, even when focus is elsewhere.
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .padding(vertical = 8.dp)
                .background(if (selected) NuvioTheme.colors.Secondary else Color.Transparent)
        )
        Spacer(Modifier.width(9.dp))
        LiveText(
            group.title,
            modifier = Modifier.weight(1f),
            color = colors.text,
            size = 14.sp,
            weight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            marquee = focused
        )
        if (group.id != ChannelGroup.SEARCH) {
            LiveText(group.count.toString(), color = focusedSecondaryTextColor(focused), size = 12.sp)
        }
    }
}

// ==================================================================== dialogs

@Composable
private fun ChannelContextMenu(
    target: MenuTarget,
    isFavorite: Boolean,
    inFavoritesGroup: Boolean,
    now: Long,
    onDismiss: () -> Unit,
    onWatch: () -> Unit,
    onCatchup: (EpgProgram) -> Unit,
    onToggleFavorite: () -> Unit,
    onMoveFavorite: (Int) -> Unit,
    customGroupId: String?,
    onMoveInGroup: (String, Int) -> Unit,
    onRemoveFromGroup: (String) -> Unit,
    onAddToGroup: () -> Unit,
    onRename: () -> Unit,
    onRenumber: () -> Unit,
    onHide: () -> Unit,
    onHideGroup: () -> Unit,
    onProgramInfo: () -> Unit,
    streamProgram: EpgProgram?,
    onFindInNuvio: (EpgProgram) -> Unit,
    onSearch: () -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit
) {
    val first = remember { FocusRequester() }
    val ch = target.channel
    val program = target.block?.program
    val canCatchup = program != null && program.stopMs <= now && CatchupUrlBuilder.isAvailable(ch.catchup, program.startMs, now)
    LiveDialog(onDismiss = onDismiss, width = 460.dp) {
        LiveText("${ch.number}  ${ch.name}", size = 20.sp, weight = FontWeight.Bold)
        LiveText(ch.group, color = NuvioTheme.colors.TextSecondary, size = 13.sp)
        Spacer(Modifier.height(14.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { MenuItem("Watch full screen", Modifier.focusRequester(first), onWatch) }
            if (canCatchup) item { MenuItem("Play from archive: ${program!!.title}", onClick = { onCatchup(program!!) }) }
            if (streamProgram != null) {
                item { MenuItem("Find & stream \"${streamProgram.title}\" in Nuvio", onClick = { onFindInNuvio(streamProgram) }) }
            }
            if (program != null) item { MenuItem("Programme info", onClick = onProgramInfo) }
            item { MenuItem(if (isFavorite) "Remove from favourites" else "Add to favourites", onClick = onToggleFavorite) }
            if (isFavorite && inFavoritesGroup) {
                item { MenuItem("Move up in favourites", onClick = { onMoveFavorite(-1) }) }
                item { MenuItem("Move down in favourites", onClick = { onMoveFavorite(1) }) }
            }
            item { MenuItem("Add to group…", onClick = onAddToGroup) }
            if (customGroupId != null) {
                item { MenuItem("Move up in this group", onClick = { onMoveInGroup(customGroupId, -1) }) }
                item { MenuItem("Move down in this group", onClick = { onMoveInGroup(customGroupId, 1) }) }
                item { MenuItem("Remove from this group", onClick = { onRemoveFromGroup(customGroupId) }) }
            }
            item { MenuItem("Rename channel", onClick = onRename) }
            item { MenuItem("Change channel number", onClick = onRenumber) }
            item { MenuItem("Hide channel", onClick = onHide) }
            item { MenuItem("Hide group \"${ch.group}\"", onClick = onHideGroup) }
            item { MenuItem("Search", onClick = onSearch) }
            item { MenuItem("Update playlists & EPG", onClick = onRefresh) }
            item { MenuItem("Live TV settings", onClick = onSettings) }
        }
    }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
}

private data class TextPrompt(
    val title: String,
    val initial: String,
    val hint: String,
    val numeric: Boolean = false,
    val onConfirm: (String) -> Unit
)

@Composable
private fun GroupPickerDialog(
    channel: LiveChannel,
    groups: List<ChannelGroup>,
    onDismiss: () -> Unit,
    onPick: (ChannelGroup) -> Unit,
    onNewGroup: () -> Unit
) {
    val first = remember { FocusRequester() }
    LiveDialog(onDismiss = onDismiss, width = 460.dp) {
        LiveText("Add \"${channel.name}\" to…", size = 20.sp, weight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { MenuItem("+ New group", Modifier.focusRequester(first), onNewGroup) }
            items(groups, key = { it.id }) { g -> MenuItem(g.title, onClick = { onPick(g) }) }
        }
    }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
}

@Composable
private fun ActionMenuDialog(title: String, actions: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    LiveDialog(onDismiss = onDismiss, width = 420.dp) {
        LiveText(title, size = 20.sp, weight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            actions.forEachIndexed { i, (label, action) ->
                MenuItem(label, if (i == 0) Modifier.focusRequester(first) else Modifier, action)
            }
        }
    }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
}

@Composable
internal fun MenuItem(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    LiveFocusRow(modifier = modifier.fillMaxWidth(), onClick = onClick) { f ->
        LiveText(label, color = focusedTextColor(f), size = 16.sp)
    }
}

@Composable
private fun ProgramInfoDialog(
    target: MenuTarget,
    now: Long,
    use24h: Boolean,
    onDismiss: () -> Unit,
    onFind: (EpgProgram) -> Unit,
    onWatch: () -> Unit,
    onCatchup: (EpgProgram) -> Unit
) {
    val first = remember { FocusRequester() }
    val block = target.block
    val p = block?.program
    LiveDialog(onDismiss = onDismiss, width = 640.dp) {
        LiveText(p?.title ?: target.channel.name, size = 24.sp, weight = FontWeight.Bold, maxLines = 2)
        Spacer(Modifier.height(6.dp))
        if (block != null) {
            val meta = listOfNotNull(
                "${target.channel.number}  ${target.channel.name}",
                formatDayClock(block.startMs, now, use24h) + " – " + formatClock(block.stopMs, use24h),
                p?.episode, p?.category
            ).joinToString("  ·  ")
            LiveText(meta, color = NuvioTheme.colors.TextSecondary, size = 14.sp, maxLines = 2)
        }
        Spacer(Modifier.height(12.dp))
        LiveText(p?.description ?: "No description available.", color = NuvioTheme.colors.TextSecondary, size = 16.sp, maxLines = 10)
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val live = block != null && now >= block.startMs && now < block.stopMs
            val archive = p != null && p.stopMs <= now && CatchupUrlBuilder.isAvailable(target.channel.catchup, p.startMs, now)
            val upcoming = p != null && block != null && block.startMs > now
            // For a show that hasn't started, streaming it through Nuvio is the main action.
            if (upcoming) {
                LiveFocusRow(modifier = Modifier.focusRequester(first), onClick = { onFind(p!!) }) { f ->
                    LiveText("Find & stream in Nuvio", color = focusedTextColor(f))
                }
            }
            when {
                live -> LiveFocusRow(modifier = Modifier.focusRequester(first), onClick = onWatch) { f -> LiveText("Watch", color = focusedTextColor(f)) }
                archive -> LiveFocusRow(modifier = Modifier.focusRequester(first), onClick = { onCatchup(p!!) }) { f -> LiveText("Play from archive", color = focusedTextColor(f)) }
                upcoming -> LiveFocusRow(onClick = onWatch) { f -> LiveText("Watch channel now", color = focusedTextColor(f)) }
                else -> LiveFocusRow(modifier = Modifier.focusRequester(first), onClick = onWatch) { f -> LiveText("Watch channel now", color = focusedTextColor(f)) }
            }
            if (p != null && !upcoming) {
                LiveFocusRow(onClick = { onFind(p) }) { f -> LiveText("Find & stream in Nuvio", color = focusedTextColor(f)) }
            }
            LiveFocusRow(onClick = onDismiss) { f -> LiveText("Close", color = focusedTextColor(f)) }
        }
    }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
}

@Composable
internal fun SearchDialog(initial: String, onDismiss: () -> Unit, onSearch: (String) -> Unit) {
    TextInputDialog(
        title = "Search channels",
        initial = initial,
        hint = "Channel name or number",
        confirmLabel = "Search",
        onDismiss = onDismiss,
        onConfirm = onSearch
    )
}

@Composable
internal fun TextInputDialog(
    title: String,
    initial: String,
    hint: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    numeric: Boolean = false
) {
    var text by remember { mutableStateOf(initial) }
    val fieldFocus = remember { FocusRequester() }
    LiveDialog(onDismiss = onDismiss) {
        LiveText(title, size = 20.sp, weight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        LiveTextField(value = text, onValueChange = { text = it }, hint = hint, focusRequester = fieldFocus, numeric = numeric, onDone = { onConfirm(text) })
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LiveFocusRow(onClick = { onConfirm(text) }) { f -> LiveText(confirmLabel, color = focusedTextColor(f)) }
            LiveFocusRow(onClick = onDismiss) { f -> LiveText("Cancel", color = focusedTextColor(f)) }
        }
    }
    LaunchedEffect(Unit) { delay(80); runCatching { fieldFocus.requestFocus() } }
}

@Composable
internal fun LiveTextField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    focusRequester: FocusRequester? = null,
    password: Boolean = false,
    numeric: Boolean = false,
    onDone: () -> Unit = {}
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(NuvioTheme.colors.Field)
            .border(2.dp, if (focused) NuvioTheme.colors.FocusRing else Color.Transparent, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        if (value.isEmpty()) LiveText(hint, color = NuvioTheme.colors.TextTertiary)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = NuvioTheme.colors.TextPrimary, fontSize = 16.sp),
            cursorBrush = SolidColor(NuvioTheme.colors.FocusRing),
            visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation()
            else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Done,
                keyboardType = if (numeric) androidx.compose.ui.text.input.KeyboardType.Number
                else androidx.compose.ui.text.input.KeyboardType.Text
            ),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier
                .fillMaxWidth()
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged { focused = it.isFocused }
        )
    }
}
