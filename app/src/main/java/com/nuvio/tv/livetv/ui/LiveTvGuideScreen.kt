package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.data.LiveTvRepository
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.ManageSearch
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.graphics.graphicsLayer
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
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SLOT_MS = 30L * 60L * 1000L
private const val WINDOW_MS = 2L * 60L * 60L * 1000L

private enum class GuideColumn { CHANNEL, PROGRAM }

/** What the cursor currently sits on in a channel row. */
private data class GuideBlock(val startMs: Long, val stopMs: Long, val program: EpgProgram?)

private data class MenuTarget(val channel: LiveChannel, val block: GuideBlock?)

private fun floorSlot(ms: Long) = ms - Math.floorMod(ms, SLOT_MS)

/** The first midnight (device time) after [ms]. */
private fun nextMidnight(ms: Long): Long = java.util.Calendar.getInstance().apply {
    timeInMillis = ms
    set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
    set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    add(java.util.Calendar.DAY_OF_MONTH, 1)
}.timeInMillis

@Composable
fun LiveTvGuideScreen(
    onOpenFullscreen: () -> Unit,
    onOpenSettings: () -> Unit,
    onFindInNuvio: () -> Unit = {},
    onOpenNuvioSearch: () -> Unit = onFindInNuvio,
    viewModel: LiveTvViewModel = hiltViewModel()
) {
    // Full screen happens right here, TiviMate style: the same video view grows from the
    // preview window to the whole screen and back. The picture never moves to a different
    // view, which is what froze it (or left it loading) on some TVs.
    var fullscreen by remember { mutableStateOf(false) }
    // Where you were browsing when you held Back / pressed Play to go full screen; restored
    // when you come back, so you return to the channel you had selected.
    var savedSpot by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var exitFullscreenTick by remember { mutableIntStateOf(0) }
    fun goFullscreen() {
        fullscreen = true
    }
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val user by viewModel.userState.collectAsStateWithLifecycle()
    val programs by viewModel.programs.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    PauseLiveTvInBackground(viewModel.playback)

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
    // Load listings for the hours being looked at (scrolling ahead, or back for catch-up).
    LaunchedEffect(windowStart) {
        viewModel.ensureGuideRange(windowStart - 2 * 3_600_000L, windowStart + WINDOW_MS + 6 * 3_600_000L)
    }
    // Going back in time on catch-up channels: fetch their full catch-up archive (many guide
    // files only reach about a day back; the provider keeps up to a week or more).
    var pendingGroupId by remember { mutableStateOf<String?>(null) }
    // The group list slides in from the left when you press Left, and slides away again when
    // you pick a group or go back to the channels, giving the guide the full width.
    var groupsOpen by remember { mutableStateOf(false) }
    var leftTapPending by remember { mutableStateOf(false) }
    var lastVerticalAt by remember { mutableStateOf(0L) }
    var okHoldJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var okDuringScroll by remember { mutableStateOf(false) }
    var lastHeldLeftAt by remember { mutableStateOf(0L) }
    // "Browse by channel name": the highlight is on the channel name itself (OK plays it,
    // Right steps into its shows, Left opens the groups).
    var nameFocus by remember { mutableStateOf(false) }
    var focusGroupsOnOpen by remember { mutableStateOf(false) }
    // Manage visibility (TiviMate style): every channel in the group, hidden ones included.
    var visibilityMode by remember { mutableStateOf(false) }
    // Reorder channels: the channel being moved, and the group's list while you move it.
    var reorderKey by remember { mutableStateOf<String?>(null) }
    var reorderList by remember { mutableStateOf<List<LiveChannel>>(emptyList()) }
    var reorderGroupId by remember { mutableStateOf("") }
    var copyPickerFor by remember { mutableStateOf<LiveChannel?>(null) }
    // After saving a new order, keep showing it until the group reloads in that order
    // (otherwise the cursor lands on the channel's old spot for a moment).
    var savedOrder by remember { mutableStateOf<List<LiveChannel>?>(null) }
    // Reorder groups: the group being moved, and the group order while you move it.
    var groupReorderId by remember { mutableStateOf<String?>(null) }
    var groupWorking by remember { mutableStateOf<List<ChannelGroup>>(emptyList()) }
    // Manage visibility for groups: every group (hidden ones too) and which ones are hidden.
    var groupVisibilityList by remember { mutableStateOf<List<ChannelGroup>?>(null) }
    var groupHiddenPending by remember { mutableStateOf<Set<String>>(emptySet()) }
    // Parental controls: the group waiting for its PIN (and whether that removes the lock).
    var pinGroup by remember { mutableStateOf<ChannelGroup?>(null) }
    var pinWrong by remember { mutableStateOf(false) }
    var pinRemovesLock by remember { mutableStateOf(false) }
    val playlistNames by viewModel.playlistNames.collectAsStateWithLifecycle()
    val collapsedPlaylists by viewModel.collapsedPlaylists.collectAsStateWithLifecycle()
    var savedMovedKey by remember { mutableStateOf<String?>(null) }
    var visibilityChannels by remember { mutableStateOf<List<LiveChannel>>(emptyList()) }
    var pendingHidden by remember { mutableStateOf<Set<String>>(emptySet()) }
    // A channel to move the cursor to once it's in the list (after a group switch, etc).
    var highlightKey by remember { mutableStateOf<String?>(null) }
    val groupsListState = rememberLazyListState()
    var menuTarget by remember { mutableStateOf<MenuTarget?>(null) }
    var infoTarget by remember { mutableStateOf<MenuTarget?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    var textPrompt by remember { mutableStateOf<TextPrompt?>(null) }
    var groupPickerFor by remember { mutableStateOf<LiveChannel?>(null) }
    var groupMenu by remember { mutableStateOf<ChannelGroup?>(null) }
    var epgPickerFor by remember { mutableStateOf<LiveChannel?>(null) }
    LaunchedEffect(epgPickerFor) { if (epgPickerFor != null) viewModel.ensureEpgDetails() }
    var epgPanelFocusTick by remember { mutableIntStateOf(0) }
    val epgSources by viewModel.epgSources.collectAsStateWithLifecycle()
    val epgAutoMatches by viewModel.epgAutoMatches.collectAsStateWithLifecycle()
    val menuStyle by viewModel.menuStyle.collectAsStateWithLifecycle()
    val panelHaze = remember { dev.chrisbanes.haze.HazeState() }
    var gridFocused by remember { mutableStateOf(false) }
    var longPressFired by remember { mutableStateOf(false) }
    var numberBuffer by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // "Unassigned channels" (from the Assign EPG panel): only channels with no guide data.
    var epgUnassignedOnly by remember { mutableStateOf(false) }
    val channels = when {
        visibilityMode -> visibilityChannels
        reorderKey != null -> reorderList
        savedOrder != null -> savedOrder!!
        epgUnassignedOnly -> ui.channels.filter { programs[it.key].isNullOrEmpty() }
        else -> ui.channels
    }
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
    // Opening Live TV from the menu: show the group list on the group you last watched in.
    // Coming back from full screen: stay on the channel.
    LaunchedEffect(Unit) {
        delay(150)
        val returning = viewModel.consumeReturningFromFullscreen()
        val toGroups = returning && viewModel.consumeGroupsOnReturn()
        if (returning && !toGroups) {
            nameFocus = viewModel.settings.value.browseByChannelName
            runCatching { gridFocus.requestFocus() }
        } else {
            val loaded = viewModel.awaitGroups()
            // "Open on last group" off: opening Live TV starts on the main group every time
            // (not the group from earlier in this session, or the last channel's group).
            if (!returning && !viewModel.settings.value.rememberLastGroup && loaded != null) {
                viewModel.selectGroup(loaded.defaultGroupId)
            }
            delay(100)
            focusGroupsOnOpen = true
        }
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
        groupsOpen = true
        // If the group on screen sits in a folded playlist, unfold it so it can take focus.
        ui.groups.firstOrNull { it.id == ui.selectedGroupId }?.sourceId?.let { src ->
            if (src in viewModel.collapsedPlaylists.value) viewModel.togglePlaylistCollapsed(src)
        }
        scope.launch {
            val idx = ui.groups.filter { it.id != ChannelGroup.SEARCH }
                .indexOfFirst { it.id == ui.selectedGroupId }.coerceAtLeast(0)
            val visible = groupsListState.layoutInfo.visibleItemsInfo.map { it.index }
            if (idx !in visible) groupsListState.scrollToItem((idx - 3).coerceAtLeast(0))
            repeat(3) { androidx.compose.runtime.withFrameNanos { } }
            runCatching { groupsFocus.requestFocus() }
        }
    }

    LaunchedEffect(windowStart < System.currentTimeMillis() - 6 * 3_600_000L, row / 6, channels.size) {
        if (windowStart >= System.currentTimeMillis() - 6 * 3_600_000L || channels.isEmpty()) return@LaunchedEffect
        delay(300)
        val from = (row - 6).coerceAtLeast(0)
        val to = (row + 14).coerceAtMost(channels.size)
        viewModel.loadCatchupArchive(channels.subList(from, to))
    }
    // Leaving full screen: the guide lands on the channel you were watching (in its group,
    // at the current time), not where you'd been browsing before going full screen.
    LaunchedEffect(exitFullscreenTick) {
        if (exitFullscreenTick == 0) return@LaunchedEffect
        val spot = savedSpot
        savedSpot = null
        val playing = playback.channelKey
        if (playing != null) {
            // Stay in the group you were in if the channel's there; otherwise its own group.
            if (channels.none { it.key == playing }) {
                viewModel.displayChannels.value.firstOrNull { it.key == playing }?.groupId?.let { viewModel.selectGroup(it) }
            }
            highlightKey = playing
            windowStart = floorSlot(System.currentTimeMillis())
        } else if (spot != null) {
            viewModel.selectGroup(spot.first)
            spot.second?.let { highlightKey = it }
        }
        groupsOpen = false
        delay(80)
        runCatching { gridFocus.requestFocus() }
    }
    LaunchedEffect(fullscreen) { LiveTvFullscreen.active.value = fullscreen }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { LiveTvFullscreen.active.value = false } }
    // Full screen asked for from elsewhere (home screen row, reminders, search…).
    val fullscreenRequests by LiveTvFullscreen.requests.collectAsStateWithLifecycle()
    LaunchedEffect(fullscreenRequests) {
        if (!LiveTvFullscreen.pending) return@LaunchedEffect
        LiveTvFullscreen.pending = false
        delay(150)
        fullscreen = true
    }
    LaunchedEffect(focusGroupsOnOpen) {
        if (focusGroupsOnOpen) {
            focusGroupsOnOpen = false
            focusGroups()
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
        if (viewModel.playbackState.value.channelKey == null) viewModel.autoPlayCandidate()?.let { ch ->
            viewModel.preview(ch)
            goFullscreen()
            return@LaunchedEffect
        }
        val target = viewModel.channelByKey(viewModel.playbackState.value.channelKey)
            ?: viewModel.resumeCandidate()?.also { viewModel.preview(it) }
            ?: return@LaunchedEffect
        // Only change group when coming back from full screen, or when "Open on last group" is
        // on. Then it's the group you watched in (a custom group stays a custom group), not
        // the channel's original playlist group.
        val mayChangeGroup = viewModel.isReturningFromFullscreen() || viewModel.settings.value.rememberLastGroup
        val group = viewModel.groupToShow(target)
        if (group != null) {
            if (!mayChangeGroup) return@LaunchedEffect
            viewModel.selectGroup(group)
        }
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

    // Back: leave program browsing, then leave a group for All channels, then open Nuvio's menu.
    val sidebarExpanded = com.nuvio.tv.LocalSidebarExpanded.current
    val openSidebar = com.nuvio.tv.LocalOpenSidebar.current
    BackHandler(enabled = !sidebarExpanded) {
        when {
            column == GuideColumn.PROGRAM -> {
                column = GuideColumn.CHANNEL
                windowStart = floorSlot(now)
                cursorMs = now
            }
            // Browse by channel name: from the shows, Back goes to the channel name first.
            settings.browseByChannelName && !nameFocus && !groupsOpen -> nameFocus = true
            // Back from the channels: the group list, with the group you're in highlighted.
            !groupsOpen -> focusGroups()
            else -> openSidebar()
        }
    }

    fun findInNuvio(program: EpgProgram) {
        // For a series with a "Show - Episode" title, search for the show.
        LiveTvSearchBridge.request(com.nuvio.tv.livetv.data.LiveTvPosterResolver.searchTitleFor(program, null))
        onFindInNuvio()
    }

    val focusedChannel = channels.getOrNull(row)


    fun blockAt(channel: LiveChannel, at: Long): GuideBlock {
        val list = programs[channel.key].orEmpty()
        list.firstOrNull { at >= it.startMs && at < it.stopMs }?.let { return GuideBlock(it.startMs, it.stopMs, it) }
        // Gap / no data: a 30 minute slot trimmed against neighbouring programs.
        var s = floorSlot(at)
        var e = s + SLOT_MS
        list.lastOrNull { it.stopMs <= at }?.let { if (it.stopMs > s) s = it.stopMs }
        list.firstOrNull { it.startMs > at }?.let { if (it.startMs < e) e = it.startMs }
        return GuideBlock(s, e, null)
    }

    val focusedBlock: GuideBlock? = focusedChannel?.let { ch ->
        if (column == GuideColumn.PROGRAM) blockAt(ch, cursorMs) else blockAt(ch, now)
    }

    // Poster for the highlighted show in the info panel (what Nuvio's catalogs would show).
    // While browsing the group list, the info panel stays on what's playing in the preview;
    // it only follows the highlight when you're moving through the channels themselves.
    val browsingGroups = groupsOpen && !gridFocused
    val playingChannel = viewModel.channelByKey(playback.channelKey)
    val headerChannel = if (browsingGroups && playingChannel != null) playingChannel else focusedChannel
    val headerBlock = if (browsingGroups && playingChannel != null) blockAt(playingChannel, now) else focusedBlock
    val headerProgramKey = headerChannel?.key to headerBlock?.program?.startMs
    val headerPoster by androidx.compose.runtime.produceState<String?>(initialValue = null, headerProgramKey) {
        value = null
        val p = headerBlock?.program ?: return@produceState
        if (!settings.showProgramDetails) return@produceState
        // The guide's own image for the show (if it has one) straight away; your addon's
        // poster replaces it when found.
        if (LiveTvRepository.isPlaceholderTitle(p.title)) return@produceState // channel logo
        value = p.icon
        delay(250) // don't look up every show while scrolling quickly
        viewModel.posterFor(p.title, p, headerChannel)?.let { value = it }
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
    LaunchedEffect(savedOrder, ui.channels) {
        val order = savedOrder ?: return@LaunchedEffect
        if (ui.channels.map { it.key } != order.map { it.key }) {
            delay(3_000) // the reload normally arrives well before this
        }
        val moved = savedMovedKey
        savedOrder = null
        savedMovedKey = null
        val idx = ui.channels.indexOfFirst { it.key == moved }
        if (idx >= 0) {
            row = idx
            ensureRowVisible(idx)
        }
    }

    fun playChannel(ch: LiveChannel) {
        val alreadyPreviewing = playback.channelKey == ch.key && playback.catchupTitle == null
        viewModel.preview(ch)
        if (!settings.showPreview || settings.openFullscreenOnSelect || alreadyPreviewing) goFullscreen()
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
                if (viewModel.playCatchup(ch, block.program)) goFullscreen()
            }
            else -> infoTarget = MenuTarget(ch, block)
        }
    }

    fun moveRow(delta: Int) {
        if (channels.isEmpty()) return
        row = when {
            // One step past the end wraps around; paging stops at the ends.
            delta == 1 && row == channels.lastIndex -> 0
            delta == -1 && row == 0 -> channels.lastIndex
            else -> (row + delta).coerceIn(0, channels.lastIndex)
        }
        ensureRowVisible(row)
    }

    // The cursor is always on a program. "CHANNEL" now just means "resting on what's on now"
    // (the guide keeps following the clock); "PROGRAM" means browsing ahead or back in time.
    fun moveRight() {
        val ch = focusedChannel ?: return
        if (column == GuideColumn.CHANNEL) {
            column = GuideColumn.PROGRAM
            cursorMs = now
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

    /** True when the cursor is on what's on now. */
    fun isAtNow(): Boolean {
        val ch = focusedChannel ?: return true
        if (column == GuideColumn.CHANNEL) return true
        val b = blockAt(ch, cursorMs)
        return now >= b.startMs && now < b.stopMs
    }

    /**
     * Left. A tap on what's on now opens the groups (catch-up or not). Holding Left on a
     * catch-up channel goes back into earlier shows; once you're there, each Left steps back.
     */
    fun moveLeft(held: Boolean = false) {
        val ch = focusedChannel ?: return
        val from = if (column == GuideColumn.CHANNEL) now else cursorMs
        val block = blockAt(ch, from)
        val atNow = now >= block.startMs && now < block.stopMs
        val earliest = if (ch.catchup != null) {
            floorSlot(now - (ch.catchup.days.coerceAtMost(7) * 24L * 60 * 60 * 1000).coerceAtMost(settings.epgPastHours * 60L * 60 * 1000))
        } else floorSlot(now)
        if (atNow && !held) {
            resetToNow()
            if (settings.browseByChannelName) nameFocus = true else focusGroups()
            return
        }
        // At the oldest show (or no catch-up on this channel): a tap opens the groups; holding
        // just stays put.
        if (block.startMs <= earliest || (ch.catchup == null && block.startMs <= now)) {
            if (!held) {
                resetToNow()
                if (settings.browseByChannelName) nameFocus = true else focusGroups()
            }
            return
        }
        column = GuideColumn.PROGRAM
        cursorMs = block.startMs - 1
        while (cursorMs < windowStart) windowStart -= SLOT_MS
        // Stepping back onto what's on now: rest there, so the next Left opens the groups.
        val landed = blockAt(ch, cursorMs)
        if (now >= landed.startMs && now < landed.stopMs && ch.catchup == null) resetToNow()
    }

    CompositionLocalProvider(LocalLiveSolidHighlight provides settings.solidHighlight) {
    val hostActivity = androidx.compose.ui.platform.LocalContext.current as? com.nuvio.tv.MainActivity
    // Where the preview window is (the video view sits there when not full screen).
    var rootBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var previewBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    // Only a Back press that *started* in the guide counts as "hold Back" here.
    var backPressStartedHere by remember { mutableStateOf(false) }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalShowDetails provides { key, p -> viewModel.details(key, p) },
        LocalChannelAbout provides { ch -> viewModel.channelAbout(ch) }
    ) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
            .onGloballyPositioned { rootBounds = it.boundsInRoot() }
            .onPreviewKeyEvent { e ->
                // Full screen handles its own keys.
                if (fullscreen) return@onPreviewKeyEvent false
                val playingNow = playback.channelKey != null
                fun returnToPlaying() {
                    // Remember where you were browsing, to come back to it afterwards.
                    savedSpot = ui.selectedGroupId to focusedChannel?.key
                    // Back to the group the playing channel was watched in (not the group you were
                    // browsing), so Up/Down and overlay mode work from the channel you're on.
                    scope.launch {
                        viewModel.channelByKey(playback.channelKey)?.let { playing ->
                            viewModel.groupToShow(playing)?.let { viewModel.selectGroup(it) }
                        }
                        goFullscreen()
                    }
                }
                // Play/Pause on the remote: straight to full screen on what's playing. Works on
                // every remote, including ones that can't report a held Back button.
                if (e.type == KeyEventType.KeyDown && playingNow && e.nativeKeyEvent.repeatCount == 0 &&
                    (e.key == Key.MediaPlayPause || e.key == Key.MediaPlay)
                ) {
                    returnToPlaying()
                    return@onPreviewKeyEvent true
                }
                // Hold Back: straight back to full screen on what's playing.
                if (e.key != Key.Back) return@onPreviewKeyEvent false
                val native = e.nativeKeyEvent
                when {
                    e.type == KeyEventType.KeyDown && native.repeatCount == 0 && !native.isLongPress -> {
                        backPressStartedHere = true
                        false
                    }
                    // Remotes that repeat a held button: act while it's still held.
                    e.type == KeyEventType.KeyDown -> {
                        if (!backPressStartedHere || !playingNow) return@onPreviewKeyEvent false
                        backPressStartedHere = false
                        // Nuvio then swallows the rest of this Back press (so letting go doesn't
                        // also count as a normal Back and leave full screen again).
                        hostActivity?.longPressBackHeld?.value = true
                        returnToPlaying()
                        true
                    }
                    // Remotes that don't repeat (many Fire TV and Google TV remotes): a press
                    // held for half a second or more counts as "hold", judged on release.
                    e.type == KeyEventType.KeyUp -> {
                        val started = backPressStartedHere
                        backPressStartedHere = false
                        if (started && playingNow && native.eventTime - native.downTime >= 500) {
                            returnToPlaying()
                            true
                        } else false
                    }
                    else -> false
                }
            }
    ) {
        // A soft glow in the theme color behind the info panel and preview (fades into the guide).
        if (settings.showProgramDetails || settings.showPreview) {
            ThemeGlow(Modifier.fillMaxWidth().height(if (settings.smallHeader) 230.dp else 330.dp))
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (menuStyle == LiveMenuStyle.MODERN_BLUR) Modifier.hazeSource(state = panelHaze)
                    else Modifier
                )
        ) {
            // ---------------------------------------------------------------- header
            GuideHeader(
                channel = headerChannel,
                block = headerBlock,
                poster = headerPoster,
                onSurfaceAttached = { viewModel.playback.onSurfaceAttached() },
                onPreviewBounds = { previewBounds = it },
                playlistName = if (settings.showPlaylistInInfo) headerChannel?.sourceId?.let { playlistNames[it] } else null,
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
                val groupsWidth by androidx.compose.animation.core.animateDpAsState(
                    targetValue = if (groupsOpen) 190.dp else 0.dp,
                    animationSpec = androidx.compose.animation.core.tween(220),
                    label = "groupsWidth"
                )
                if (groupsWidth > 1.dp) GroupColumn(
                    width = groupsWidth,
                    groups = when {
                        groupVisibilityList != null -> groupVisibilityList!!
                        groupReorderId != null -> ui.groups.filter { it.special } + groupWorking
                        else -> ui.groups
                    },
                    hiddenPending = if (groupVisibilityList != null) groupHiddenPending else null,
                    onToggleGroupVisible = { id ->
                        groupHiddenPending = if (id in groupHiddenPending) groupHiddenPending - id else groupHiddenPending + id
                    },
                    onHideAllGroups = { groupHiddenPending = groupVisibilityList.orEmpty().map { it.id }.toSet() },
                    onShowAllGroups = { groupHiddenPending = emptySet() },
                    onVisibilityDone = {
                        val list = groupVisibilityList.orEmpty()
                        viewModel.saveGroupVisibility(list, groupHiddenPending)
                        // If the group on screen was just hidden, move to the main group.
                        if (ui.selectedGroupId in groupHiddenPending) viewModel.selectGroup(ui.defaultGroupId)
                        groupVisibilityList = null
                    },
                    playlistNames = playlistNames,
                    showPlaylistHeadings = settings.groupPlaylistHeadings,
                    collapsed = collapsedPlaylists,
                    onToggleCollapse = { viewModel.togglePlaylistCollapsed(it) },
                    reorderingId = groupReorderId,
                    onReorderMove = { delta ->
                        val from = groupWorking.indexOfFirst { it.id == groupReorderId }
                        val to = from + delta
                        if (from >= 0 && to in groupWorking.indices) {
                            groupWorking = groupWorking.toMutableList().apply { add(to, removeAt(from)) }
                            scope.launch {
                                val index = ui.groups.count { it.special && it.id != ChannelGroup.SEARCH } + to
                                runCatching { groupsListState.scrollToItem((index - 3).coerceAtLeast(0)) }
                            }
                        }
                    },
                    onReorderDone = {
                        viewModel.saveGroupOrder(groupWorking.map { it.id })
                        groupReorderId = null
                    },
                    selectedId = ui.selectedGroupId,
                    showCounts = settings.showGroupCounts,
                    listState = groupsListState,
                    focusRequester = groupsFocus,
                    onFocusGroup = { g -> pendingGroupId = g.id },
                    onSelect = { g ->
                        if (g.locked) {
                            // Parental controls: ask for the PIN first.
                            pinGroup = g
                            pinWrong = false
                        } else if (g.id == ChannelGroup.SEARCH) {
                            LiveTvSearchBridge.liveOnly.value = true
                            onOpenNuvioSearch()
                        } else {
                            if (g.id != ui.selectedGroupId) {
                                viewModel.selectGroup(g.id)
                                row = 0
                            }
                            resetToNow()
                            groupsOpen = false
                            nameFocus = settings.browseByChannelName
                            scope.launch { delay(60); runCatching { gridFocus.requestFocus() } }
                        }
                    },
                    onGroupMenu = { g -> if (!g.special) groupMenu = g },
                    onRight = {
                        column = GuideColumn.CHANNEL
                        groupsOpen = false
                        nameFocus = settings.browseByChannelName
                        runCatching { gridFocus.requestFocus() }
                    }
                )
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    // ---------------------------------------------------------------- time ruler
                    val channelColWidth = channelColumnWidth(settings)
                    // Tight rows so at least 8 channels fit even with the info panel and preview on.
                    val rowHeight = if (settings.compactRows) 32.dp else 38.dp
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(34.dp)
                            .padding(end = 24.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Date and time above the channels (like TiviMate), or the Unassigned filter.
                        Box(Modifier.width(channelColWidth).padding(start = 8.dp)) {
                            LiveText(
                                if (epgUnassignedOnly) "Unassigned channels"
                                else java.text.SimpleDateFormat("EEE, MMM d", java.util.Locale.getDefault()).format(java.util.Date(now)) +
                                    ", " + formatClock(now, settings.use24HourClock),
                                color = NuvioTheme.colors.TextPrimary,
                                size = 13.sp,
                                weight = FontWeight.Bold
                            )
                        }
                        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            val w = maxWidth
                            // The next day starts in view: a tag at midnight ("Tomorrow · Fri").
                            val midnight = nextMidnight(windowStart)
                            val showMidnight = midnight < windowStart + WINDOW_MS
                            for (i in 0 until (WINDOW_MS / SLOT_MS).toInt()) {
                                val t = windowStart + i * SLOT_MS
                                if (showMidnight && t == midnight) continue
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
                            if (showMidnight) {
                                val dayName = java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(java.util.Date(midnight))
                                val isTomorrow = midnight - now in 0..(24 * 3_600_000L)
                                LiveTag(
                                    if (isTomorrow) "TOMORROW · ${dayName.uppercase()}" else dayName.uppercase(),
                                    NuvioTheme.colors.FocusBackground,
                                    NuvioTheme.colors.FocusRing,
                                    Modifier
                                        .offset(x = w * ((midnight - windowStart) / WINDOW_MS.toFloat()) + 4.dp)
                                        .align(Alignment.CenterStart)
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
                            .onFocusChanged {
                                gridFocused = it.hasFocus || it.isFocused
                                // Back on the channels: the group list slides away.
                                if (gridFocused) groupsOpen = false
                            }
                            .onPreviewKeyEvent { event ->
                                val isOk = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                                if (reorderKey != null) {
                                    // Up/Down moves the channel; OK or Back saves.
                                    if (event.type == KeyEventType.KeyDown) {
                                        val from = reorderList.indexOfFirst { it.key == reorderKey }
                                        fun moveTo(to: Int) {
                                            if (from < 0 || to !in reorderList.indices || to == from) return
                                            reorderList = reorderList.toMutableList().apply { add(to, removeAt(from)) }
                                            row = to
                                            ensureRowVisible(to)
                                        }
                                        when {
                                            event.key == Key.DirectionUp -> moveTo(from - 1)
                                            event.key == Key.DirectionDown -> moveTo(from + 1)
                                            event.key == Key.PageUp || event.key == Key.ChannelUp -> moveTo((from - 8).coerceAtLeast(0))
                                            event.key == Key.PageDown || event.key == Key.ChannelDown -> moveTo((from + 8).coerceAtMost(reorderList.lastIndex))
                                            event.key == Key.Back -> {
                                                viewModel.saveOrder(reorderGroupId, reorderList.map { it.key })
                                                savedOrder = reorderList
                                                savedMovedKey = reorderKey
                                                reorderKey = null
                                            }
                                        }
                                    } else if (isOk && event.type == KeyEventType.KeyUp) {
                                        viewModel.saveOrder(reorderGroupId, reorderList.map { it.key })
                                        savedOrder = reorderList
                                        savedMovedKey = reorderKey
                                        reorderKey = null
                                    }
                                    return@onPreviewKeyEvent true
                                }
                                if (visibilityMode) {
                                    // OK: show/hide · Left: hide all · Right: show all · Back: save and return
                                    val down = event.type == KeyEventType.KeyDown
                                    when {
                                        isOk && event.type == KeyEventType.KeyUp -> {
                                            channels.getOrNull(row)?.let { ch ->
                                                pendingHidden = if (ch.key in pendingHidden) pendingHidden - ch.key else pendingHidden + ch.key
                                            }
                                        }
                                        !down -> Unit
                                        event.key == Key.DirectionUp -> moveRow(-1)
                                        event.key == Key.DirectionDown -> moveRow(1)
                                        event.key == Key.PageUp || event.key == Key.ChannelUp -> moveRow(-8)
                                        event.key == Key.PageDown || event.key == Key.ChannelDown -> moveRow(8)
                                        event.key == Key.DirectionLeft -> pendingHidden = channels.map { it.key }.toSet()
                                        event.key == Key.DirectionRight -> pendingHidden = emptySet()
                                        event.key == Key.Back -> {
                                            val current = channels.getOrNull(row)
                                            viewModel.saveVisibility(visibilityChannels, pendingHidden)
                                            visibilityMode = false
                                            current?.takeIf { it.key !in pendingHidden }?.let { highlightKey = it.key }
                                        }
                                    }
                                    return@onPreviewKeyEvent true
                                }
                                // Assign EPG open: Right goes into the panel for the highlighted channel.
                                if (epgPickerFor != null && event.key == Key.DirectionRight) {
                                    if (event.type == KeyEventType.KeyDown) {
                                        focusedChannel?.let { epgPickerFor = it }
                                        epgPanelFocusTick++
                                    }
                                    return@onPreviewKeyEvent true
                                }
                                // Remember Up/Down presses: some remotes (clickable rings, e.g. on some
                                // JVC Google TVs) also send OK with them, which would play the
                                // highlighted channel while just scrolling.
                                if (event.type == KeyEventType.KeyDown &&
                                    (event.key == Key.DirectionUp || event.key == Key.DirectionDown)
                                ) lastVerticalAt = event.nativeKeyEvent.eventTime
                                if (isOk && event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                                    okDuringScroll = event.nativeKeyEvent.eventTime - lastVerticalAt < OK_WITH_ARROW_MS
                                }
                                if (isOk && okDuringScroll) {
                                    // Part of an Up/Down press: ignore this OK entirely.
                                    if (event.type == KeyEventType.KeyUp) okDuringScroll = false
                                    return@onPreviewKeyEvent true
                                }
                                if (isOk) {
                                    fun openMenu() {
                                        longPressFired = true
                                        focusedChannel?.let { menuTarget = MenuTarget(it, focusedBlock) }
                                    }
                                    if (event.type == KeyEventType.KeyDown) {
                                        if (event.nativeKeyEvent.repeatCount == 0) {
                                            // A new press: forget a long press whose release went to a dialog.
                                            longPressFired = false
                                            // Long press by time held, not only by repeats: some remotes
                                            // (HDMI-CEC TV remotes, e.g. Samsung on a Shield) never repeat.
                                            okHoldJob?.cancel()
                                            okHoldJob = scope.launch {
                                                delay(LONG_PRESS_MS)
                                                if (!longPressFired) openMenu()
                                            }
                                        } else if (!longPressFired) {
                                            okHoldJob?.cancel()
                                            openMenu()
                                        }
                                        return@onPreviewKeyEvent true
                                    }
                                    if (event.type == KeyEventType.KeyUp) {
                                        okHoldJob?.cancel()
                                        val heldMs = event.nativeKeyEvent.eventTime - event.nativeKeyEvent.downTime
                                        when {
                                            longPressFired -> longPressFired = false
                                            heldMs >= LONG_PRESS_MS -> { openMenu(); longPressFired = false }
                                            else -> activate()
                                        }
                                        return@onPreviewKeyEvent true
                                    }
                                }
                                // Left, TiviMate style: a tap on what's on now opens the groups; holding
                                // Left goes back into earlier shows (catch-up). The tap is acted on
                                // when the button is let go, so a hold never opens the groups first.
                                if (nameFocus && (event.key == Key.DirectionLeft || event.key == Key.DirectionRight)) {
                                    if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                                        if (event.key == Key.DirectionLeft) focusGroups()
                                        else { nameFocus = false; resetToNow() }
                                    }
                                    return@onPreviewKeyEvent true
                                }
                                if (event.key == Key.DirectionLeft) {
                                    val held = event.nativeKeyEvent.repeatCount >= 1 || event.nativeKeyEvent.isLongPress
                                    if (event.type == KeyEventType.KeyDown) {
                                        if (!held && isAtNow()) {
                                            leftTapPending = true
                                        } else if (held && event.nativeKeyEvent.eventTime - lastHeldLeftAt < HELD_STEP_MS) {
                                            // Holding Left: step back at a readable pace (about 3
                                            // shows a second), not every repeat the remote sends.
                                        } else {
                                            if (held) lastHeldLeftAt = event.nativeKeyEvent.eventTime
                                            leftTapPending = false
                                            moveLeft(held)
                                        }
                                    } else if (event.type == KeyEventType.KeyUp && leftTapPending) {
                                        leftTapPending = false
                                        moveLeft(held = false)
                                    }
                                    return@onPreviewKeyEvent true
                                }
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                val code = event.nativeKeyEvent.keyCode
                                when {
                                    event.key == Key.DirectionUp -> { moveRow(-1); true }
                                    event.key == Key.DirectionDown -> { moveRow(1); true }
                                    event.key == Key.PageUp || event.key == Key.ChannelUp -> { moveRow(-8); true }
                                    event.key == Key.PageDown || event.key == Key.ChannelDown -> { moveRow(8); true }
                                    event.key == Key.DirectionRight -> { moveRight(); true }
                                    event.key == Key.MediaFastForward -> { repeat(4) { moveRight() }; true }
                                    event.key == Key.MediaRewind -> { repeat(4) { moveLeft(held = true) }; true }
                                    event.key == Key.Menu || event.key == Key.Info -> {
                                        focusedChannel?.let { menuTarget = MenuTarget(it, focusedBlock) }
                                        true
                                    }
                                            event.key == Key.Search -> { LiveTvSearchBridge.liveOnly.value = true; onOpenNuvioSearch(); true }
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
                                            focusColumn = if (index == row && (gridFocused || epgPickerFor != null) && !nameFocus) column else null,
                                            nameFocused = index == row && gridFocused && nameFocus,
                                            focusedBlock = if (index == row) focusedBlock else null,
                                            visible = if (visibilityMode) ch.key !in pendingHidden else null,
                                            moving = reorderKey == ch.key,
                                            quality = if (settings.showQualityBadges) user.channelQuality[ch.key] else null,
                                            reminderStarts = if (user.reminders.isEmpty()) emptySet()
                                                else user.reminders.filter { it.channelKey == ch.key }.map { it.startMs }.toSet()
                                        )
                                    }
                                }
                                // "Now" line across the program area (can be hidden in settings).
                                if (settings.showNowLine && now in windowStart until windowStart + WINDOW_MS) {
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
                                                .clip(RoundedCornerShape(1.dp))
                                                .background(NuvioTheme.colors.Secondary.copy(alpha = 0.9f))
                                        )
                                        Box(
                                            modifier = Modifier
                                                .offset(x = x - 4.dp)
                                                .size(10.dp)
                                                .clip(RoundedCornerShape(50))
                                                .background(NuvioTheme.colors.Secondary)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Loading status in the top corner (the clock now sits above the channel list).
        // ---------------------------------------------------------------- the video
        // One video view for the preview window and full screen: it just changes size. Drawn
        // right after the guide itself, so menus and panels (Assign EPG, channel menu…) cover
        // it instead of the video showing through them.
        val density = androidx.compose.ui.platform.LocalDensity.current
        val pb = previewBounds
        val rb = rootBounds
        val videoModifier = when {
            fullscreen -> Modifier.fillMaxSize()
            pb != null && rb != null && pb.width > 1f -> with(density) {
                Modifier
                    .offset { androidx.compose.ui.unit.IntOffset((pb.left - rb.left).toInt(), (pb.top - rb.top).toInt()) }
                    .size(pb.width.toDp(), pb.height.toDp())
            }
            // Preview hidden: keep the view (so nothing has to be rebuilt), out of sight.
            else -> Modifier.size(1.dp)
        }
        // Full screen: black behind the picture, so channels that don't fill the screen (other
        // shapes, 4:3) show black bars instead of the guide underneath.
        if (fullscreen) Box(Modifier.fillMaxSize().background(Color.Black))
        LivePlayerSurface(
            player = viewModel.playback.player,
            useSurfaceView = true,
            modifier = videoModifier,
            onAttached = { viewModel.playback.onSurfaceAttached() },
            aspectMode = if (fullscreen) aspectModeOf(settings.aspectMode) else null
        )
        // The preview's own messages, drawn over the video.
        if (!fullscreen && pb != null && rb != null && pb.width > 1f) {
            val showLoading = rememberDelayedTrue(playback.isBuffering && playback.error == null, 1_200)
            val text = when {
                playback.channelKey == null -> "Press OK on a channel to preview"
                playback.error != null -> "Reconnecting… (${playback.error})"
                showLoading -> "Loading…"
                else -> null
            }
            with(density) {
                Box(
                    modifier = Modifier
                        .offset { androidx.compose.ui.unit.IntOffset((pb.left - rb.left).toInt(), (pb.top - rb.top).toInt()) }
                        .size(pb.width.toDp(), pb.height.toDp()),
                    contentAlignment = Alignment.Center
                ) {
                    text?.let { LiveText(it, color = NuvioTheme.colors.TextSecondary, size = 14.sp) }
                    playback.catchupTitle?.let {
                        LiveText(
                            "ARCHIVE · $it",
                            modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            color = Color.White, size = 11.sp
                        )
                    }
                }
            }
        }
        if (!fullscreen && (settings.showProgramDetails || settings.showPreview) && status.loading) {
            status.message?.let {
                LiveText(
                    it,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 32.dp),
                    color = NuvioTheme.colors.TextSecondary,
                    size = 12.sp
                )
            }
        }

        if (groupVisibilityList != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 36.dp, bottom = 24.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.96f))
                    .border(1.dp, NuvioTheme.colors.Border, RoundedCornerShape(10.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                LiveText("Manage group visibility", size = 15.sp, weight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                LiveText("OK: show or hide group", color = NuvioTheme.colors.TextSecondary, size = 13.sp)
                LiveText("Left: hide all", color = NuvioTheme.colors.TextSecondary, size = 13.sp)
                LiveText("Right: show all", color = NuvioTheme.colors.TextSecondary, size = 13.sp)
                LiveText("Back: save and return", color = NuvioTheme.colors.TextSecondary, size = 13.sp)
            }
        }
        if (groupReorderId != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 36.dp, bottom = 24.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.96f))
                    .border(1.dp, NuvioTheme.colors.Border, RoundedCornerShape(10.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                LiveText("Reorder groups", size = 15.sp, weight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                LiveText("Up / Down: move the group", color = NuvioTheme.colors.TextSecondary, size = 13.sp)
                LiveText("OK or Back: save", color = NuvioTheme.colors.TextSecondary, size = 13.sp)
            }
        }
        if (reorderKey != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 36.dp, bottom = 24.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.96f))
                    .border(1.dp, NuvioTheme.colors.Border, RoundedCornerShape(10.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                LiveText("Reorder channels", size = 15.sp, weight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                LiveText("Up / Down: move the channel", color = NuvioTheme.colors.TextSecondary, size = 13.sp)
                LiveText("CH+ / CH−: move 8 places", color = NuvioTheme.colors.TextSecondary, size = 13.sp)
                LiveText("OK or Back: save", color = NuvioTheme.colors.TextSecondary, size = 13.sp)
            }
        }
        if (visibilityMode) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 36.dp, bottom = 24.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.96f))
                    .border(1.dp, NuvioTheme.colors.Border, RoundedCornerShape(10.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                LiveText("Manage visibility", size = 15.sp, weight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                LiveText("OK: show or hide channel", color = NuvioTheme.colors.TextSecondary, size = 13.sp)
                LiveText("Left: hide all", color = NuvioTheme.colors.TextSecondary, size = 13.sp)
                LiveText("Right: show all", color = NuvioTheme.colors.TextSecondary, size = 13.sp)
                LiveText("Back: save and return", color = NuvioTheme.colors.TextSecondary, size = 13.sp)
            }
        }

        // ---------------------------------------------------------------- Change EPG panel
        epgPickerFor?.let { ch ->
            val closePanel = {
                epgPickerFor = null
                viewModel.commitEpgChanges() // the guide updates once, with all your changes
                scope.launch { delay(60); runCatching { gridFocus.requestFocus() } }
                Unit
            }
            BackHandler { closePanel() }
            // Moving through the channels with the panel open: the panel follows.
            val fc = focusedChannel
            LaunchedEffect(fc?.key, gridFocused) {
                if (gridFocused && fc != null && fc.key != ch.key) epgPickerFor = fc
            }
            EpgSidePanel(
                channel = ch,
                sources = epgSources,
                current = user.epgOverrides[ch.key],
                automatic = epgAutoMatches[ch.key],
                loading = status.loading,
                statusMessage = status.message.takeIf { status.loading },
                unassignedOnly = epgUnassignedOnly,
                onToggleUnassigned = { epgUnassignedOnly = !epgUnassignedOnly },
                onFullScan = { viewModel.fullEpgScan() },
                style = menuStyle,
                hazeState = panelHaze,
                modifier = Modifier.align(Alignment.CenterEnd),
                // Assign, then back to the channel list with the panel still open: move to the
                // next channel and press Right to assign it too. Back closes the panel.
                onPick = { src, entry ->
                    viewModel.setChannelEpg(ch, src.sourceId, entry.id)
                    scope.launch { delay(60); runCatching { gridFocus.requestFocus() } }
                },
                onUnassign = {
                    viewModel.resetChannelEpg(ch)
                    scope.launch { delay(60); runCatching { gridFocus.requestFocus() } }
                },
                onLeft = { runCatching { gridFocus.requestFocus() } },
                focusTick = epgPanelFocusTick
            )
        }

        // ---------------------------------------------------------------- dialogs
        menuTarget?.let { target ->
            ChannelContextMenu(
                target = target,
                reminderSet = target.block?.program?.let { p -> user.reminders.any { it.channelKey == target.channel.key && it.startMs == p.startMs } } == true,
                onRemind = { p -> viewModel.toggleReminder(target.channel, p); menuTarget = null },
                canRestart = viewModel.canWatchFromStart(target.channel),
                onRestart = {
                    menuTarget = null
                    if (viewModel.watchFromStart(target.channel)) goFullscreen()
                },
                isFavorite = target.channel.key in user.favorites,
                inFavoritesGroup = ui.selectedGroupId == ChannelGroup.FAVORITES,
                now = now,
                onDismiss = { menuTarget = null },
                onWatch = { menuTarget = null; viewModel.preview(target.channel); goFullscreen() },
                onCatchup = { program ->
                    menuTarget = null
                    if (viewModel.playCatchup(target.channel, program)) goFullscreen()
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
                onChangeEpg = { menuTarget = null; epgPickerFor = target.channel },
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
                canReorder = viewModel.canReorder(ui.selectedGroupId),
                onReorder = {
                    menuTarget = null
                    reorderGroupId = ui.selectedGroupId
                    reorderList = channels
                    reorderKey = target.channel.key
                    row = channels.indexOfFirst { it.key == target.channel.key }.coerceAtLeast(0)
                    scope.launch { delay(60); runCatching { gridFocus.requestFocus() } }
                },
                onCopy = { menuTarget = null; copyPickerFor = target.channel },
                isCopyHere = viewModel.isCopyIn(ui.selectedGroupId, target.channel),
                onRemoveCopy = { viewModel.removeCopy(ui.selectedGroupId, target.channel); menuTarget = null },
                onManageVisibility = {
                    menuTarget = null
                    val list = viewModel.channelsWithHidden()
                    visibilityChannels = list
                    pendingHidden = user.hiddenChannels intersect list.map { it.key }.toSet()
                    visibilityMode = true
                    row = list.indexOfFirst { it.key == target.channel.key }.coerceAtLeast(0)
                    ensureRowVisible(row)
                    scope.launch { delay(60); runCatching { gridFocus.requestFocus() } }
                },
                onHideGroup = {
                    viewModel.hideGroup(target.channel.groupId)
                    menuTarget = null
                },
                onProgramInfo = { menuTarget = null; infoTarget = target },
                streamProgram = target.block?.program ?: viewModel.currentProgram(target.channel.key),
                onFindInNuvio = { p -> menuTarget = null; findInNuvio(p) },
                onSearch = { menuTarget = null; LiveTvSearchBridge.liveOnly.value = true; onOpenNuvioSearch() },
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
                    if (viewModel.playCatchup(target.channel, p)) goFullscreen()
                },
                reminderSet = target.block?.program?.let { p -> user.reminders.any { it.channelKey == target.channel.key && it.startMs == p.startMs } } == true,
                onRemind = { p -> viewModel.toggleReminder(target.channel, p); infoTarget = null },
                canRestart = viewModel.canWatchFromStart(target.channel),
                onRestart = {
                    infoTarget = null
                    if (viewModel.watchFromStart(target.channel)) goFullscreen()
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
        pinGroup?.let { g ->
            TextInputDialog(
                title = if (pinWrong) "Wrong PIN, try again" else if (pinRemovesLock) "Enter your PIN to remove the lock" else "\"${g.title}\" is locked. Enter your PIN",
                initial = "",
                hint = "PIN",
                confirmLabel = if (pinRemovesLock) "Remove lock" else "Unlock",
                numeric = true,
                onDismiss = { pinGroup = null; pinRemovesLock = false },
                onConfirm = { pin ->
                    if (pinRemovesLock) {
                        if (viewModel.checkPin(pin)) { viewModel.setGroupLocked(g.id, false); pinGroup = null; pinRemovesLock = false } else pinWrong = true
                    } else if (viewModel.unlockGroup(g, pin)) {
                        pinGroup = null
                        viewModel.selectGroup(g.id)
                        groupsOpen = false
                        scope.launch { delay(80); runCatching { gridFocus.requestFocus() } }
                    } else pinWrong = true
                }
            )
        }
        copyPickerFor?.let { ch ->
            GroupPickerDialog(
                channel = ch,
                groups = viewModel.copyTargets(ch),
                title = "Copy \"${ch.name}\" to…",
                onDismiss = { copyPickerFor = null },
                onPick = { g -> viewModel.copyToGroup(g.id, ch); copyPickerFor = null },
                onNewGroup = {
                    copyPickerFor = null
                    textPrompt = TextPrompt(
                        title = "New group",
                        initial = "",
                        hint = "Group name",
                        onConfirm = { name -> viewModel.createGroupWith(name, ch) }
                    )
                }
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
                    if (viewModel.parentalEnabled()) {
                        if (viewModel.isGroupLockSet(g.id)) add("Remove parental lock" to {
                            groupMenu = null
                            pinRemovesLock = true
                            pinWrong = false
                            pinGroup = g
                        }) else add("Lock with PIN" to {
                            groupMenu = null
                            viewModel.setGroupLocked(g.id, true)
                            if (ui.selectedGroupId == g.id) viewModel.selectGroup(ui.defaultGroupId)
                        })
                    }
                    add("Manage visibility" to {
                        groupMenu = null
                        val all = viewModel.groupsWithHidden()
                        groupVisibilityList = all
                        groupHiddenPending = user.hiddenGroups intersect all.map { it.id }.toSet()
                        scope.launch { delay(80); runCatching { groupsFocus.requestFocus() } }
                    })
                    add("Reorder groups" to {
                        groupMenu = null
                        groupWorking = ui.groups.filter { !it.special }
                        groupReorderId = g.id
                        scope.launch { delay(80); runCatching { groupsFocus.requestFocus() } }
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
    
        // ---------------------------------------------------------------- full screen
        if (fullscreen) {
            LiveTvPlayerScreen(
                onBack = { fullscreen = false; exitFullscreenTick++ },
                onFindInNuvio = { fullscreen = false; exitFullscreenTick++; onFindInNuvio() },
                onBackToGroups = { fullscreen = false; savedSpot = null; exitFullscreenTick++; focusGroups() },
                embedded = true,
                viewModel = viewModel
            )
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
    poster: String?,
    /** Which playlist the channel comes from (setting "Show playlist in info panel"). */
    playlistName: String?,
    settings: LiveTvSettings,
    now: Long,
    showPreview: Boolean,
    player: androidx.media3.common.Player?,
    playingKey: String?,
    playbackBuffering: Boolean,
    playbackError: String?,
    catchupTitle: String?,
    status: String?,
    /** The preview took over the picture (the player checks the picture actually starts). */
    onSurfaceAttached: () -> Unit = {},
    /** Where the preview window is on screen (null when it isn't shown). */
    onPreviewBounds: (androidx.compose.ui.geometry.Rect?) -> Unit = {}
) {
    if (!settings.showProgramDetails && !showPreview) {
        Row(
            // Info panel and preview both off: just a slim bar with the clock.
            modifier = Modifier.fillMaxWidth().padding(start = 64.dp, end = 32.dp, top = 8.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LiveText("Live TV", size = 16.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
            status?.let { LiveText(it, color = NuvioTheme.colors.TextSecondary, size = 13.sp) }
            Spacer(Modifier.width(16.dp))
            LiveText(formatClock(now, settings.use24HourClock), size = 16.sp, weight = FontWeight.SemiBold)
        }
        return
    }
    // Standard or small ("Small info & preview"): the small one leaves room for more channels.
    val small = settings.smallHeader
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (small) 110.dp else 176.dp)
            .padding(start = 64.dp, end = 32.dp, top = if (small) 12.dp else 20.dp, bottom = if (small) 4.dp else 6.dp),
        horizontalArrangement = Arrangement.spacedBy(if (small) 16.dp else 20.dp)
    ) {
        // Posters off: just the channel logo, in a logo-shaped space (not a poster box).
        if (settings.showProgramDetails && channel != null && !settings.showPosters) {
            Box(
                modifier = Modifier.width(if (small) 96.dp else 130.dp).fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                ChannelLogo(channel.logo, if (small) 46.dp else 64.dp)
            }
        }
        // Poster of the highlighted show (channel logo when there isn't one).
        if (settings.showProgramDetails && channel != null && settings.showPosters) {
            val posterShape = RoundedCornerShape(if (small) 10.dp else 14.dp)
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(2f / 3f)
                    .clip(posterShape)
                    .background(guideSurface())
                    .border(1.dp, Color.White.copy(alpha = 0.10f), posterShape),
                contentAlignment = Alignment.Center
            ) {
                if (!poster.isNullOrBlank()) {
                    coil3.compose.AsyncImage(
                        model = poster,
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    ChannelLogo(channel.logo, if (small) 24.dp else 34.dp)
                }
            }
        }
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            if (settings.showProgramDetails && channel != null) {
                if (!small) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (settings.showChannelLogos) {
                            ChannelLogo(channel.logo, 28.dp)
                            Spacer(Modifier.width(10.dp))
                        }
                        val label = buildString {
                            if (settings.showChannelNumbers) append("${channel.number}  ")
                            append(channel.name)
                            playlistName?.let { append("  ·  $it") }
                        }
                        LiveText(label, color = NuvioTheme.colors.TextSecondary, size = 14.sp, weight = FontWeight.Medium)
                        // On now (red LIVE tag) and catch-up, as tags beside the channel.
                        if (block != null && now in block.startMs until block.stopMs) {
                            Spacer(Modifier.width(10.dp))
                            LiveTag("LIVE", LIVE_RED, Color.White)
                        }
                        if (channel.catchup != null) {
                            Spacer(Modifier.width(6.dp))
                            LiveTag("CATCH-UP", Color.Transparent, NuvioTheme.colors.TextSecondary,
                                Modifier.border(1.dp, NuvioTheme.colors.Border, RoundedCornerShape(5.dp)))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                val p = rememberShowDetails(channel.key, block?.program)
                LiveText(
                    text = p?.title?.let { titleMarks(it).title } ?: channel.name,
                    modifier = Modifier.fillMaxWidth(),
                    size = if (small) 22.sp else 28.sp,
                    weight = FontWeight.ExtraBold,
                    maxLines = 1,
                    marquee = true
                )
                Spacer(Modifier.height(if (small) 2.dp else 4.dp))
                if (block != null) {
                    val meta = buildList {
                        add(formatDayClock(block.startMs, now, settings.use24HourClock) + " – " + formatClock(block.stopMs, settings.use24HourClock))
                        if (now in block.startMs until block.stopMs) add(minutesLeftLabel(block.stopMs, now))
                        p?.episode?.let { add(it) }
                        p?.category?.let { add(it) }
                        // The standard panel shows catch-up as a tag; the small one has no room for it.
                        if (small && channel.catchup != null) add("Catch-up")
                        // The small info panel has no channel line, so the playlist goes here.
                        if (small) playlistName?.let { add(it) }
                    }.joinToString("  ·  ")
                    LiveText(meta, color = NuvioTheme.colors.TextSecondary, size = if (small) 13.sp else 14.sp)
                    if (now in block.startMs until block.stopMs) {
                        Spacer(Modifier.height(if (small) 4.dp else 6.dp))
                        ProgressBar(fraction = ((now - block.startMs).toFloat() / (block.stopMs - block.startMs).coerceAtLeast(1)).coerceIn(0f, 1f))
                    }
                }
                Spacer(Modifier.height(if (small) 4.dp else 6.dp))
                // 24/7 channels (no episode info): what the channel plays, from your addons.
                val about = rememberChannelAbout(channel, p)
                if (about != null) {
                    aboutLine(about).takeIf { it.isNotBlank() }?.let {
                        LiveText(it, color = NuvioTheme.colors.TextSecondary, size = if (small) 13.sp else 14.sp)
                        Spacer(Modifier.height(if (small) 2.dp else 4.dp))
                    }
                }
                LiveText(
                    text = about?.description?.takeIf { it.isNotBlank() }
                        ?: p?.description ?: if (p == null) "No program information" else "",
                    color = NuvioTheme.colors.TextSecondary,
                    size = if (small) 13.sp else 14.sp,
                    maxLines = if (small) 1 else 2
                )
            } else if (status != null) {
                LiveText(status, color = NuvioTheme.colors.TextSecondary)
            }
        }
        // Preview only (info panel off): keep it at the right, as usual.
        if (showPreview) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(if (small) 12.dp else 16.dp))
                    .background(Color.Black)
            ) {
                // The video itself is drawn by the guide, over this spot (one view for the
                // preview and full screen); this just reports where the preview window is.
                Box(Modifier.fillMaxSize().onGloballyPositioned { onPreviewBounds(it.boundsInRoot()) })
                androidx.compose.runtime.DisposableEffect(Unit) { onDispose { onPreviewBounds(null) } }
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
internal fun ProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    /** Overrides for when the bar sits on a solid highlight (otherwise it'd vanish into it). */
    color: Color? = null,
    trackColor: Color? = null
) {
    Box(
        modifier = modifier
            .width(260.dp)
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(trackColor ?: NuvioTheme.colors.Border)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .background(color ?: NuvioTheme.colors.Secondary)
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
    focusedBlock: GuideBlock?,
    /** In Manage visibility: true = shown, false = hidden. Null otherwise. */
    visible: Boolean? = null,
    /** Being moved with "Reorder channels". */
    moving: Boolean = false,
    /** "Browse by channel name": this row's channel name has the highlight. */
    nameFocused: Boolean = false,
    /** Picture quality seen when this channel last played ("4K", "FHD", "HD", "SD"). */
    quality: String? = null,
    /** Start times of this channel's shows you've set a reminder for. */
    reminderStarts: Set<Long> = emptySet()
) {
    val windowEnd = windowStart + WINDOW_MS
    val cellShape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .padding(vertical = 3.dp)
            .graphicsLayer { alpha = if (visible == false) 0.45f else 1f }
    ) {
        // Channel cell
        // The channel cell is never highlighted; the program under the cursor is.
        // …except while it's being moved with Reorder channels.
        val channelFocused = moving || nameFocused
        val cell = liveCellColors(focused = moving || nameFocused, idle = guideSurface())
        Row(
            modifier = Modifier
                .width(channelColWidth)
                .fillMaxHeight()
                .padding(end = 6.dp)
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
                    size = 14.sp,
                    weight = FontWeight.Medium
                )
            }
            if (settings.showChannelLogos) {
                ChannelLogo(channel.logo, (rowHeight - 12.dp).coerceAtLeast(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            if (settings.showChannelNames) {
                LiveText(
                    channel.name,
                    modifier = Modifier.weight(1f),
                    color = cell.text,
                    size = 14.sp,
                    weight = if (isPlaying) FontWeight.Bold else FontWeight.Normal,
                    marquee = focusColumn != null
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            // Quality badge, from the last time this channel played.
            if (quality != null && !moving) {
                LiveText(
                    quality,
                    modifier = Modifier
                        .border(1.dp, if (channelFocused) cell.text else NuvioTheme.colors.TextTertiary, RoundedCornerShape(3.dp))
                        .padding(horizontal = 3.dp),
                    color = if (channelFocused) cell.text else NuvioTheme.colors.TextSecondary,
                    size = 9.sp,
                    weight = FontWeight.Bold
                )
                Spacer(Modifier.width(4.dp))
            }
            // Catch-up available on this channel.
            if (channel.catchup != null && !moving) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = "Catch-up",
                    tint = if (channelFocused) cell.text else NuvioTheme.colors.TextSecondary,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(3.dp))
            }
            if (isFavorite && !moving) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = null,
                    tint = if (channelFocused && LocalLiveSolidHighlight.current) cell.text else NuvioTheme.colors.Rating,
                    modifier = Modifier.size(14.dp)
                )
            }
            // While moving, only the move marker shows (no playing dot or star in the way).
            if (isPlaying && !moving) {
                Spacer(Modifier.width(4.dp))
                Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(NuvioTheme.colors.Error))
            }
            if (moving) {
                Spacer(Modifier.width(6.dp))
                LiveText("⇅", color = cell.text, size = 18.sp, weight = FontWeight.Bold)
            }
            // Manage visibility: an eye for shown channels, a crossed-out eye for hidden ones.
            if (visible != null) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = if (visible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    contentDescription = if (visible) "Shown" else "Hidden",
                    tint = if (visible) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextTertiary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Program blocks
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxHeight()) {
            val totalW = maxWidth
            fun xOf(t: Long): Dp = totalW * ((t.coerceIn(windowStart, windowEnd) - windowStart) / WINDOW_MS.toFloat())

            val visible = programs.filter { it.stopMs > windowStart && it.startMs < windowEnd }
            if (visible.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 3.dp)
                        .clip(cellShape)
                        .background(guideSurface().copy(alpha = 0.55f))
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    // With names hidden, the channel's name is the only way to tell which it is.
                    LiveText(
                        if (settings.showChannelNames) "No guide info" else channel.name,
                        color = NuvioTheme.colors.TextTertiary,
                        size = 14.sp
                    )
                }
            }
            visible.forEach { p ->
                val x = xOf(p.startMs)
                val w = (xOf(p.stopMs) - x).coerceAtLeast(2.dp)
                val live = now >= p.startMs && now < p.stopMs
                val past = p.stopMs <= now
                val isFocused = focusColumn != null && focusedBlock?.program == p
                val colors = liveCellColors(
                    focused = isFocused,
                    idle = when {
                        live -> guideSurfaceVariant()
                        past -> guideSurface().copy(alpha = 0.5f)
                        else -> guideSurface()
                    },
                    idleText = if (past) NuvioTheme.colors.TextSecondary else NuvioTheme.colors.TextPrimary
                )
                val marks = titleMarks(p.title)
                ProgramBlock(
                    title = marks.title,
                    subtitle = null,
                    x = x, width = w,
                    colors = colors,
                    focused = isFocused,
                    progress = if (live) p.progress(now) else null,
                    continuesLeft = p.startMs < windowStart,
                    liveTag = marks.live && !past,
                    newTag = marks.isNew,
                    episode = p.episode?.takeIf { it.length <= 12 },
                    reminder = p.startMs in reminderStarts,
                    catchup = past && channel.catchup != null
                )
            }
            // Highlight for an empty slot under the cursor.
            if (focusColumn != null && focusedBlock != null && focusedBlock.program == null) {
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
    continuesLeft: Boolean,
    /** On live (sports, events): a red LIVE tag before the title. */
    liveTag: Boolean = false,
    /** A new episode: a NEW tag after the title. */
    newTag: Boolean = false,
    /** Short episode number ("S2 E14"), shown after the title when there's room. */
    episode: String? = null,
    /** You've set a reminder for it: a bell. */
    reminder: Boolean = false,
    /** Already shown, and the channel has catch-up: a replay symbol. */
    catchup: Boolean = false
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .offset(x = x)
            .width(width)
            .fillMaxHeight()
            .padding(horizontal = 3.dp)
            .clip(shape)
            .background(colors.background)
            .border(2.dp, colors.border, shape)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (liveTag && width > 90.dp) {
                    LiveTag("LIVE", LIVE_RED, Color.White)
                    Spacer(Modifier.width(6.dp))
                }
                // The focused program scrolls when its title doesn't fit, like TiviMate.
                LiveText(
                    (if (continuesLeft) "‹ " else "") + title,
                    modifier = Modifier.weight(1f, fill = false),
                    color = colors.text,
                    size = 14.sp,
                    marquee = focused
                )
                // Extras only where there's room, so short shows keep their title.
                if (width > 150.dp) {
                    episode?.let {
                        Spacer(Modifier.width(6.dp))
                        LiveText(it, color = colors.text.copy(alpha = 0.6f), size = 12.sp)
                    }
                    if (newTag) {
                        Spacer(Modifier.width(6.dp))
                        LiveTag("NEW", NuvioTheme.colors.Secondary, NuvioTheme.colors.OnSecondary)
                    }
                }
                if (reminder && width > 60.dp) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Default.Notifications, contentDescription = "Reminder set", tint = colors.text, modifier = Modifier.size(13.dp))
                }
                if (catchup && width > 60.dp) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Default.History, contentDescription = "Catch-up", tint = colors.text.copy(alpha = 0.8f), modifier = Modifier.size(13.dp))
                }
            }
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
                    .height(3.dp)
                    .background(NuvioTheme.colors.Secondary.copy(alpha = 0.9f))
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
    width: Dp,
    groups: List<ChannelGroup>,
    selectedId: String,
    showCounts: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState,
    focusRequester: FocusRequester,
    onFocusGroup: (ChannelGroup) -> Unit,
    onSelect: (ChannelGroup) -> Unit,
    onGroupMenu: (ChannelGroup) -> Unit,
    onRight: () -> Unit,
    playlistNames: Map<String, String> = emptyMap(),
    collapsed: Set<String> = emptySet(),
    onToggleCollapse: (String) -> Unit = {},
    reorderingId: String? = null,
    onReorderMove: (Int) -> Unit = {},
    onReorderDone: () -> Unit = {},
    /** Non-null while managing group visibility: the groups set to hidden. */
    hiddenPending: Set<String>? = null,
    /** With several playlists, show each playlist's groups under its own heading (setting). */
    showPlaylistHeadings: Boolean = false,
    onToggleGroupVisible: (String) -> Unit = {},
    onHideAllGroups: () -> Unit = {},
    onShowAllGroups: () -> Unit = {},
    onVisibilityDone: () -> Unit = {}
) {
    var focusedGroupId by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(width)
            .padding(end = 8.dp, bottom = 12.dp)
            .onPreviewKeyEvent { e ->
                if (hiddenPending != null) {
                    // Manage visibility: OK toggles, Left hides all, Right shows all, Back saves.
                    val isOk = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                    return@onPreviewKeyEvent when {
                        isOk && e.type == KeyEventType.KeyUp -> { focusedGroupId?.let(onToggleGroupVisible); true }
                        isOk -> true
                        e.type != KeyEventType.KeyDown -> e.key == Key.Back || e.key == Key.DirectionLeft || e.key == Key.DirectionRight
                        e.key == Key.DirectionLeft -> { onHideAllGroups(); true }
                        e.key == Key.DirectionRight -> { onShowAllGroups(); true }
                        e.key == Key.Back -> { onVisibilityDone(); true }
                        else -> false // Up / Down move through the groups as usual
                    }
                }
                if (reorderingId != null) {
                    // Reorder groups: Up/Down moves the group, OK or Back saves.
                    val isOk = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                    if (e.type == KeyEventType.KeyDown) when (e.key) {
                        Key.DirectionUp -> onReorderMove(-1)
                        Key.DirectionDown -> onReorderMove(1)
                        Key.Back -> onReorderDone()
                        else -> Unit
                    } else if (isOk && e.type == KeyEventType.KeyUp) onReorderDone()
                    return@onPreviewKeyEvent true
                }
                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) {
                    onRight(); true
                } else false
            }
    ) {
        // Search sits above the groups (it opens Nuvio's search), in the row beside the timeline.
        groups.firstOrNull { it.id == ChannelGroup.SEARCH }?.let { searchGroup ->
            GroupSearchButton(
                modifier = Modifier.padding(bottom = 4.dp),
                onClick = { onSelect(searchGroup) }
            )
        }
        // With more than one playlist, each playlist's groups sit under its own heading,
        // which folds away when selected. (Not while reordering: that's one plain list.)
        val listed = groups.filter { it.id != ChannelGroup.SEARCH }
        val sources = listed.mapNotNull { it.sourceId }.distinct()
        val sectioned = showPlaylistHeadings && reorderingId == null && hiddenPending == null && sources.size > 1
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            fun groupItem(g: ChannelGroup) {
                item(key = g.id) {
                    val selected = g.id == selectedId
                    val moving = g.id == reorderingId
                    val requester = when {
                        reorderingId != null -> moving
                        hiddenPending != null ->
                            g.id == (if (groups.any { it.id == selectedId }) selectedId else groups.firstOrNull()?.id)
                        else -> selected
                    }
                    GroupItem(
                        group = g,
                        selected = selected || moving,
                        showCount = showCounts,
                        moving = moving,
                        visible = hiddenPending?.let { g.id !in it },
                        modifier = if (requester) Modifier.focusRequester(focusRequester) else Modifier,
                        onFocused = {
                            focusedGroupId = g.id
                            if (reorderingId == null && hiddenPending == null) onFocusGroup(g)
                        },
                        onClick = { onSelect(g) },
                        onLongClick = if (g.special) null else ({ onGroupMenu(g) })
                    )
                }
            }
            if (!sectioned) {
                listed.forEach { groupItem(it) }
            } else {
                listed.filter { it.sourceId == null }.forEach { groupItem(it) }
                sources.forEach { src ->
                    val inSource = listed.filter { it.sourceId == src }
                    val isCollapsed = src in collapsed
                    item(key = "header:$src") {
                        PlaylistHeader(
                            name = playlistNames[src] ?: "Playlist",
                            groupCount = inSource.size,
                            collapsed = isCollapsed,
                            // Folded, but its group is the one showing: keep a way back to it.
                            containsSelected = inSource.any { it.id == selectedId },
                            onClick = { onToggleCollapse(src) }
                        )
                    }
                    if (!isCollapsed) inSource.forEach { groupItem(it) }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PlaylistHeader(
    name: String,
    groupCount: Int,
    collapsed: Boolean,
    containsSelected: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val colors = liveCellColors(focused = focused, idle = Color.Transparent, idleText = NuvioTheme.colors.Secondary)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .height(32.dp)
            .clip(shape)
            .background(colors.background)
            .border(2.dp, colors.border, shape)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LiveText(if (collapsed) "▸" else "▾", color = colors.text, size = 13.sp, modifier = Modifier.width(16.dp))
        LiveText(
            name.uppercase(),
            color = colors.text,
            size = 12.sp,
            weight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
            marquee = focused
        )
        LiveText(
            if (collapsed && containsSelected) "•" else "$groupCount",
            color = if (focused) colors.text else NuvioTheme.colors.TextTertiary,
            size = 11.sp
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun GroupSearchButton(modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    val colors = liveCellColors(focused = focused, idle = guideSurface(), idleText = NuvioTheme.colors.TextPrimary)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(30.dp)
            .clip(shape)
            .background(colors.background)
            .border(if (focused) 2.dp else 1.dp, if (focused) colors.border else NuvioTheme.colors.Border, shape)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = colors.text, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        LiveText("Search", color = colors.text, size = 13.sp, weight = FontWeight.Medium)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun GroupItem(
    group: ChannelGroup,
    selected: Boolean,
    showCount: Boolean,
    moving: Boolean = false,
    visible: Boolean? = null,
    modifier: Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    // Tiles like the channel rows; the group on screen is tinted, with a thin accent outline.
    val colors = liveCellColors(
        focused = focused,
        idle = if (selected) NuvioTheme.colors.FocusBackground else guideSurface(),
        idleText = if (selected) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .clip(shape)
            .background(colors.background)
            .border(
                if (focused) 2.dp else 1.dp,
                when {
                    focused -> colors.border
                    selected -> NuvioTheme.colors.Secondary.copy(alpha = 0.8f)
                    else -> Color.Transparent
                },
                shape
            )
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
        Spacer(Modifier.width(12.dp))
        LiveText(
            group.title,
            modifier = Modifier.weight(1f),
            color = colors.text,
            size = 14.sp,
            weight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            marquee = focused
        )
        if (visible != null) {
            Icon(
                imageVector = if (visible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                contentDescription = if (visible) "Shown" else "Hidden",
                tint = if (visible) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextTertiary,
                modifier = Modifier.size(16.dp)
            )
        } else if (group.locked) {
            Icon(Icons.Default.Lock, contentDescription = "Locked", tint = focusedSecondaryTextColor(focused), modifier = Modifier.size(14.dp))
        } else if (moving) {
            LiveText("⇅", color = colors.text, size = 16.sp, weight = FontWeight.Bold)
        } else if (showCount && group.id != ChannelGroup.SEARCH) {
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
    onChangeEpg: () -> Unit,
    onRenumber: () -> Unit,
    onHide: () -> Unit,
    canReorder: Boolean,
    onReorder: () -> Unit,
    onCopy: () -> Unit,
    isCopyHere: Boolean,
    onRemoveCopy: () -> Unit,
    onManageVisibility: () -> Unit,
    onHideGroup: () -> Unit,
    onProgramInfo: () -> Unit,
    streamProgram: EpgProgram?,
    onFindInNuvio: (EpgProgram) -> Unit,
    reminderSet: Boolean = false,
    onRemind: (EpgProgram) -> Unit = {},
    /** The show on now can be restarted (catch-up channel). */
    canRestart: Boolean = false,
    onRestart: () -> Unit = {},
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
            // On now, on a catch-up channel: start it over.
            if (program != null && now >= program.startMs && now < program.stopMs && canRestart) item {
                MenuItem("Watch from the beginning", onClick = onRestart)
            }
            // Upcoming show: Remind me (or Cancel reminder), right in the long-press menu.
            if (program != null && program.startMs > now) item {
                MenuItem(if (reminderSet) "Cancel reminder" else "Remind me", onClick = { onRemind(program) })
            }
            if (program != null) item { MenuItem("Program info", onClick = onProgramInfo) }
            item { MenuItem(if (isFavorite) "Remove from favorites" else "Add to favorites", onClick = onToggleFavorite) }
            if (isFavorite && inFavoritesGroup) {
                item { MenuItem("Move up in favorites", onClick = { onMoveFavorite(-1) }) }
                item { MenuItem("Move down in favorites", onClick = { onMoveFavorite(1) }) }
            }
            item { MenuItem("Add to group…", onClick = onAddToGroup) }
            if (customGroupId != null) {
                item { MenuItem("Move up in this group", onClick = { onMoveInGroup(customGroupId, -1) }) }
                item { MenuItem("Move down in this group", onClick = { onMoveInGroup(customGroupId, 1) }) }
                item { MenuItem("Remove from this group", onClick = { onRemoveFromGroup(customGroupId) }) }
            }
            item { MenuItem("Rename channel", onClick = onRename) }
            item { MenuItem("Change channel number", onClick = onRenumber) }
            item { MenuItem("Assign EPG", onClick = onChangeEpg) }
            item { MenuItem("Hide channel", onClick = onHide) }
            item { MenuItem("Manage visibility", onClick = onManageVisibility) }
            if (canReorder) item { MenuItem("Reorder channels", onClick = onReorder) }
            item { MenuItem("Copy channel…", onClick = onCopy) }
            if (isCopyHere) item { MenuItem("Remove copy from this group", onClick = onRemoveCopy) }
            item { MenuItem("Hide group \"${ch.group}\"", onClick = onHideGroup) }
            item { MenuItem("Update playlists & EPG", onClick = onRefresh) }
            item { MenuItem("Live TV settings", onClick = onSettings) }
        }
    }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
}

private data class EpgRow(
    val source: com.nuvio.tv.livetv.model.EpgSourceChannels,
    val entry: com.nuvio.tv.livetv.model.EpgChannelEntry
)

/**
 * "Assign EPG" panel. Styled like Nuvio's own side menu (floating rounded panel, pill-shaped
 * items, accent for the current choice) and laid out like TiviMate's: every loaded guide's
 * channels in one alphabetical list with the source underneath, opened at the current pick or
 * where this channel's name would be. Buttons: Search, Filter by guide, Find, Automatic.
 */
@Composable
private fun EpgSidePanel(
    channel: LiveChannel,
    sources: List<com.nuvio.tv.livetv.model.EpgSourceChannels>,
    current: com.nuvio.tv.livetv.model.EpgAssignment?,
    automatic: com.nuvio.tv.livetv.model.EpgAssignment?,
    loading: Boolean,
    statusMessage: String?,
    unassignedOnly: Boolean,
    onToggleUnassigned: () -> Unit,
    onFullScan: () -> Unit,
    style: LiveMenuStyle,
    hazeState: dev.chrisbanes.haze.HazeState,
    modifier: Modifier,
    onPick: (com.nuvio.tv.livetv.model.EpgSourceChannels, com.nuvio.tv.livetv.model.EpgChannelEntry) -> Unit,
    onUnassign: () -> Unit,
    /** Left from the list: back to the guide's channels (the panel stays open). */
    onLeft: () -> Unit = {},
    /** Goes up when the guide asks the panel to take the highlight (Right from the channels). */
    focusTick: Int = 0
) {
    var query by remember { mutableStateOf("") }
    var sourceFilter by remember { mutableStateOf<String?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    var filterOpen by remember { mutableStateOf(false) }
    val seed = remember(channel.key) { epgSearchSeed(channel.name) }

    val rows = remember(sources, query, sourceFilter, unassignedOnly, current) {
        val words = query.lowercase().split(Regex("""\s+""")).filter { it.isNotBlank() }
        sources.asSequence()
            .filter { sourceFilter == null || it.sourceId == sourceFilter }
            .flatMap { src ->
                src.channels.asSequence()
                    .filter { e ->
                        // Unassigned: guide channels not feeding any playlist channel yet
                        // (this channel's own pick stays visible).
                        !unassignedOnly || e.id !in src.usedIds ||
                            (current != null && current.sourceId == src.sourceId && current.xmltvId == e.id)
                    }
                    .map { EpgRow(src, it) }
            }
            .filter { r ->
                words.isEmpty() || (r.entry.names + r.entry.id).joinToString(" ").lowercase().let { h -> words.all { it in h } }
            }
            .sortedWith(compareBy<EpgRow>({ it.entry.displayName.lowercase() }, { it.source.label }))
            .toList()
    }

    fun anchorIndex(): Int {
        if (rows.isEmpty()) return 0
        (current ?: automatic)?.let { a ->
            val i = rows.indexOfFirst { it.source.sourceId == a.sourceId && it.entry.id == a.xmltvId }
            if (i >= 0) return i
        }
        val key = seed.lowercase()
        val i = rows.indexOfFirst { it.entry.displayName.lowercase() >= key }
        return if (i >= 0) i else rows.lastIndex
    }

    val listState = rememberLazyListState()
    var focusIndex by remember { mutableIntStateOf(-1) }
    val itemFocus = remember { FocusRequester() }
    val buttonsFocus = remember { FocusRequester() }
    LaunchedEffect(focusTick) {
        if (focusTick == 0) return@LaunchedEffect
        delay(40)
        runCatching { itemFocus.requestFocus() }.onFailure { runCatching { buttonsFocus.requestFocus() } }
    }
    var jumpToken by remember { mutableIntStateOf(0) }

    LaunchedEffect(rows, jumpToken) {
        val idx = anchorIndex()
        focusIndex = idx
        if (rows.isNotEmpty()) {
            listState.scrollToItem((idx - 4).coerceAtLeast(0))
            repeat(2) { androidx.compose.runtime.withFrameNanos { } }
            runCatching { itemFocus.requestFocus() }
        } else {
            runCatching { buttonsFocus.requestFocus() }
        }
    }

    // Opens like Nuvio's menu: a quick fade and scale from its corner.
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val progress by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(260),
        label = "epgPanel"
    )

    // Panel surface, matching Nuvio's side menu for the chosen menu appearance.
    val amoled = NuvioTheme.colors.BackgroundElevated == Color.Black
    val classic = style == LiveMenuStyle.CLASSIC
    val panelShape = if (classic) androidx.compose.ui.graphics.RectangleShape
    else RoundedCornerShape(com.nuvio.tv.ui.theme.NuvioComponents.tokens.sidebar.panelRadius)
    // The theme's own surfaces (they carry its tint), like the rest of the guide.
    val panelBackground = when {
        classic -> NuvioTheme.colors.Background
        amoled -> Color.Black
        style == LiveMenuStyle.MODERN_BLUR -> NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.65f)
        else -> NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.97f)
    }
    val panelBlur = NuvioTheme.effects.blurPanel

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(if (classic) 440.dp else 480.dp)
            .then(if (classic) Modifier else Modifier.padding(top = 24.dp, bottom = 16.dp, end = 20.dp))
            .graphicsLayer {
                alpha = progress
                val sc = 0.92f + 0.08f * progress
                scaleX = sc
                scaleY = sc
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0f)
            }
            .clip(panelShape)
            .then(
                if (style == LiveMenuStyle.MODERN_BLUR) {
                    Modifier.hazeEffect(state = hazeState) {
                        blurRadius = panelBlur
                        noiseFactor = 0.04f
                        inputScale = dev.chrisbanes.haze.HazeInputScale.Fixed(0.66f)
                    }
                } else Modifier
            )
            .background(panelBackground, panelShape)
            .then(
                if (!classic) {
                    Modifier.border(1.dp, NuvioTheme.colors.Border.copy(alpha = 0.9f), panelShape)
                } else Modifier
            )
            .padding(
                horizontal = if (classic) NuvioTheme.spacing.card.outer else 16.dp,
                vertical = 20.dp
            )
    ) {
        // Header
        LiveText("Assign EPG", modifier = Modifier.padding(start = 12.dp), size = 24.sp, weight = FontWeight.ExtraBold)
        val filterName = sources.firstOrNull { it.sourceId == sourceFilter }?.name
        // What the channel uses now: your pick, or the automatic match (handy when a channel shows
        // the wrong listings, e.g. a guide's "Programming" placeholder).
        val using = (current ?: automatic)?.let { a ->
            val src = sources.firstOrNull { it.sourceId == a.sourceId }
            val name = src?.channels?.firstOrNull { it.id == a.xmltvId }?.displayName ?: a.xmltvId
            (if (current != null) "Assigned: " else "Automatic: ") + name + (src?.let { " · ${it.label}" } ?: "")
        } ?: "Automatic: no guide found"
        val sub = statusMessage ?: listOfNotNull(
            channel.name,
            using,
            if (unassignedOnly) "Unassigned channels" else null,
            filterName,
            query.takeIf { it.isNotBlank() }?.let { "\"$it\"" }
        ).joinToString("  ·  ")
        LiveText(sub, modifier = Modifier.padding(start = 12.dp, top = 2.dp), color = NuvioTheme.colors.TextSecondary, size = 13.sp, marquee = true)
        Spacer(Modifier.height(14.dp))

        // Buttons, as pills like Nuvio's menu items
        val buttons = listOf<Triple<androidx.compose.ui.graphics.vector.ImageVector, String, () -> Unit>>(
            Triple(Icons.Default.Search, "Search") { searchOpen = true },
            // Toggles the guide's channel list and this list between everything and unassigned only.
            Triple(Icons.Default.FilterAlt, if (unassignedOnly) "All channels" else "Unassigned") { onToggleUnassigned() },
            Triple(Icons.Default.Refresh, "Full scan") { onFullScan() },
            Triple(Icons.Default.LinkOff, "Unassign") { onUnassign() }
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            buttons.forEachIndexed { i, (icon, label, action) ->
                EpgPanelButton(
                    icon = icon,
                    label = label,
                    style = style,
                    modifier = Modifier
                        .weight(1f)
                        .then(if (i == 0) Modifier.focusRequester(buttonsFocus) else Modifier)
                        .onPreviewKeyEvent { e ->
                            // Keep focus inside the panel.
                            // Keep focus inside the panel; Down moves into the list normally.
                            e.type == KeyEventType.KeyDown && (
                                (e.key == Key.DirectionLeft && i == 0) ||
                                    (e.key == Key.DirectionRight && i == buttons.lastIndex)
                                )
                        },
                    onClick = action
                )
            }
        }
        // Which guide to pick from: one tap per guide (with how many channels each has).
        if (sources.size > 1) {
            Spacer(Modifier.height(8.dp))
            val chips = listOf<Pair<String?, String>>(null to "All guides") +
                sources.map { it.sourceId to "${it.name} (${it.channels.size})" }
            androidx.compose.foundation.lazy.LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(chips, key = { _, c -> c.first ?: "__all__" }) { i, (id, label) ->
                    EpgPanelButton(
                        icon = if (id == sourceFilter) Icons.Default.Check else Icons.Default.Tv,
                        label = label,
                        style = style,
                        modifier = Modifier
                            .widthIn(min = 104.dp)
                            .onPreviewKeyEvent { e ->
                                e.type == KeyEventType.KeyDown && (
                                    (e.key == Key.DirectionLeft && i == 0) ||
                                        (e.key == Key.DirectionRight && i == chips.lastIndex)
                                    )
                            },
                        onClick = { sourceFilter = id }
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(NuvioTheme.colors.Border.copy(alpha = 0.5f))
        )
        Spacer(Modifier.height(8.dp))

        if (rows.isEmpty()) {
            LiveText(
                when {
                    loading && sources.isEmpty() -> "Guides are still loading…"
                    sources.isEmpty() -> "No guides loaded. Add an EPG source in Live TV settings."
                    else -> "No guide channels match."
                },
                modifier = Modifier.padding(12.dp),
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 3
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        // Left: back to the guide's channels, to pick the next one.
                        Key.DirectionLeft -> { onLeft(); true }
                        // Right: up to the buttons (Search first).
                        Key.DirectionRight -> { runCatching { buttonsFocus.requestFocus() }; true }
                        else -> false
                    }
                },
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            // Some guides list the same channel id twice: index keeps the keys unique.
            itemsIndexed(rows, key = { i, r -> r.source.sourceId + "|" + r.entry.id + "|" + i }) { index, r ->
                val selected = current != null && current.sourceId == r.source.sourceId && current.xmltvId == r.entry.id
                EpgPanelRow(
                    row = r,
                    selected = selected,
                    style = style,
                    modifier = if (index == focusIndex) Modifier.focusRequester(itemFocus) else Modifier,
                    onClick = { onPick(r.source, r.entry) }
                )
            }
        }
    }

    if (searchOpen) {
        TextInputDialog(
            title = "Search guide channels",
            initial = query.ifBlank { seed },
            hint = "Channel name or ID",
            confirmLabel = "Search",
            onDismiss = { searchOpen = false },
            onConfirm = { q -> query = q.trim(); searchOpen = false }
        )
    }
    if (filterOpen) {
        ActionMenuDialog(
            title = "Show channels from",
            actions = buildList<Pair<String, () -> Unit>> {
                add(("All guides" + if (sourceFilter == null) "  ✓" else "") to { sourceFilter = null; filterOpen = false })
                sources.forEach { src ->
                    add(("${src.name} · ${src.label} (${src.channels.size})" + if (sourceFilter == src.sourceId) "  ✓" else "") to {
                        sourceFilter = src.sourceId
                        filterOpen = false
                    })
                }
            },
            onDismiss = { filterOpen = false }
        )
    }
}

/** Item colors copied from Nuvio's side menu, for whichever menu appearance is selected. */
private data class MenuItemColors(val background: Color, val content: Color, val secondary: Color)

@Composable
private fun menuItemColors(style: LiveMenuStyle, focused: Boolean, selected: Boolean): MenuItemColors {
    val colors = NuvioTheme.colors
    val target = if (style == LiveMenuStyle.CLASSIC) {
        // Classic drawer: theme focus fill; the current choice filled with the accent.
        when {
            focused -> MenuItemColors(colors.FocusBackground, colors.TextPrimary, colors.TextSecondary)
            selected -> MenuItemColors(colors.Secondary, colors.OnSecondary, colors.OnSecondary.copy(alpha = 0.8f))
            else -> MenuItemColors(Color.Transparent, colors.TextSecondary, colors.TextTertiary)
        }
    } else {
        // Modern panel: soft white when focused, accent tint and accent text for the current choice.
        val accent = NuvioTheme.palette.secondary
        MenuItemColors(
            background = when {
                focused && selected -> accent.copy(alpha = 0.28f)
                focused -> Color.White.copy(alpha = 0.12f)
                selected -> accent.copy(alpha = 0.15f)
                else -> Color.Transparent
            },
            content = when {
                selected -> accent
                focused -> colors.TextPrimary
                else -> colors.text.onOverlay
            },
            secondary = colors.TextSecondary
        )
    }
    val bg by androidx.compose.animation.animateColorAsState(target.background, androidx.compose.animation.core.tween(150), label = "menuItemBg")
    val fg by androidx.compose.animation.animateColorAsState(target.content, androidx.compose.animation.core.tween(150), label = "menuItemFg")
    return MenuItemColors(bg, fg, target.secondary)
}

@Composable
private fun menuItemShape(style: LiveMenuStyle): androidx.compose.ui.graphics.Shape =
    if (style == LiveMenuStyle.CLASSIC) NuvioTheme.shapes.navItem
    else RoundedCornerShape(com.nuvio.tv.ui.theme.NuvioRadii.tokens.full)

/** Assign EPG tiles: the guide's own look (theme surface, focus fill and ring). */
@Composable
private fun epgTileColors(focused: Boolean, selected: Boolean): LiveCellColors = liveCellColors(
    focused = focused,
    idle = if (selected) NuvioTheme.colors.FocusBackground else guideSurface(),
    idleText = NuvioTheme.colors.TextPrimary
)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun EpgPanelRow(row: EpgRow, selected: Boolean, style: LiveMenuStyle, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val colors = epgTileColors(focused, selected)
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.background)
            .border(
                if (focused) 2.dp else 1.dp,
                when {
                    focused -> colors.border
                    selected -> NuvioTheme.colors.Secondary.copy(alpha = 0.8f)
                    else -> Color.Transparent
                },
                shape
            )
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The guide channel's logo first (or an empty space, so names line up).
        Box(Modifier.width(52.dp), contentAlignment = Alignment.Center) {
            if (!row.entry.icon.isNullOrBlank()) ChannelLogo(row.entry.icon, 26.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            LiveText(
                row.entry.displayName,
                color = colors.text,
                size = 15.sp,
                weight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                marquee = focused
            )
            LiveText(row.source.label, color = focusedSecondaryTextColor(focused), size = 12.sp)
        }
        // The guide channel in use: a tag on the right.
        if (selected) {
            Spacer(Modifier.width(8.dp))
            LiveTag("IN USE", NuvioTheme.colors.Secondary, NuvioTheme.colors.OnSecondary)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun EpgPanelButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    style: LiveMenuStyle,
    modifier: Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val colors = epgTileColors(focused, selected = false)
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(colors.background)
            .border(if (focused) 2.dp else 1.dp, if (focused) colors.border else NuvioTheme.colors.Border, shape)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 10.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = label, tint = colors.text, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(4.dp))
        LiveText(label, color = colors.text, size = 12.sp)
    }
}

private const val EPG_PICKER_LIMIT = 300

private fun epgSearchSeed(name: String): String =
    name.replace(Regex("""^[A-Za-z]{2,3}\s*[:|]\s*"""), "")
        .replace(Regex("""\b(FHD|UHD|HD|SD|4K|HEVC|H265|RAW|BACKUP)\b""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""[\[(].*?[\])]"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()

/** Every word of the query must appear in one of the channel's names or its id. */
private fun filterEpgChannels(
    channels: List<com.nuvio.tv.livetv.model.EpgChannelEntry>,
    query: String
): List<com.nuvio.tv.livetv.model.EpgChannelEntry> {
    val words = query.lowercase().split(Regex("""\s+""")).filter { it.isNotBlank() }
    if (words.isEmpty()) return channels.take(EPG_PICKER_LIMIT)
    val scored = channels.mapNotNull { c ->
        val haystack = (c.names + c.id).joinToString(" ").lowercase()
        if (words.all { it in haystack }) {
            val exact = c.names.any { it.equals(query.trim(), ignoreCase = true) }
            c to if (exact) 0 else 1
        } else null
    }
    return scored.sortedBy { it.second }.map { it.first }.take(EPG_PICKER_LIMIT)
}

private data class TextPrompt(
    val title: String,
    val initial: String,
    val hint: String,
    val numeric: Boolean = false,
    val onConfirm: (String) -> Unit
)

@Composable
internal fun GroupPickerDialog(
    channel: LiveChannel,
    groups: List<ChannelGroup>,
    onDismiss: () -> Unit,
    onPick: (ChannelGroup) -> Unit,
    onNewGroup: () -> Unit,
    title: String = "Add \"${channel.name}\" to…"
) {
    val first = remember { FocusRequester() }
    LiveDialog(onDismiss = onDismiss, width = 460.dp) {
        LiveText(title, size = 20.sp, weight = FontWeight.Bold)
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
    onCatchup: (EpgProgram) -> Unit,
    reminderSet: Boolean = false,
    onRemind: (EpgProgram) -> Unit = {},
    canRestart: Boolean = false,
    onRestart: () -> Unit = {}
) {
    val first = remember { FocusRequester() }
    val block = target.block
    val p = rememberShowDetails(target.channel.key, block?.program)
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
            // On now, on a catch-up channel: start it over.
            if (p != null && now >= p.startMs && now < p.stopMs && canRestart) {
                LiveFocusRow(onClick = onRestart) { f -> LiveText("Watch from the beginning", color = focusedTextColor(f)) }
            }
            // Reminders: a message a minute before it starts, wherever you are in Nuvio.
            if (p != null && upcoming) {
                LiveFocusRow(onClick = { onRemind(p) }) { f ->
                    LiveText(if (reminderSet) "Cancel reminder" else "Remind me", color = focusedTextColor(f))
                }
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

/** While Left is held in the guide, one step back through earlier shows per this many ms. */
private const val HELD_STEP_MS = 320L

/** Asks the Live TV guide to go full screen (from the home row, reminders, search…). */
object LiveTvFullscreen {
    /** True while Live TV is full screen (the loading bubble stays out of the way). */
    val active = kotlinx.coroutines.flow.MutableStateFlow(false)
    val requests = kotlinx.coroutines.flow.MutableStateFlow(0)
    @Volatile var pending = false
    fun request() {
        pending = true
        requests.value++
    }
}

/** An OK arriving this soon after Up/Down is treated as part of that press and ignored. */
private const val OK_WITH_ARROW_MS = 250L

/** OK held this long counts as a long press (also without key repeats, e.g. HDMI-CEC remotes). */
internal const val LONG_PRESS_MS = 550L
