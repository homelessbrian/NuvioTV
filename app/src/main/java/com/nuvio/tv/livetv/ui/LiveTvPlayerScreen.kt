package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.data.LiveTvRepository
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.ui.AspectRatioFrameLayout
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.player.LiveTrackOption
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class PlayerDialog { NONE, OPTIONS, AUDIO, SUBTITLES, SCREEN_SIZE, STREAM_INFO, SLEEP }

/** The picture size saved in settings, as one of Nuvio's own player modes. */
internal fun aspectModeOf(name: String): com.nuvio.tv.ui.screens.player.AspectMode =
    com.nuvio.tv.ui.screens.player.AspectMode.entries.firstOrNull { it.name == name }
        ?: com.nuvio.tv.ui.screens.player.AspectMode.ORIGINAL

@Composable
fun LiveTvPlayerScreen(
    onBack: () -> Unit,
    onFindInNuvio: () -> Unit = {},
    /** Overlay mode off: Left goes back to the guide with the group list open. */
    onBackToGroups: () -> Unit = onBack,
    /**
     * Shown inside the guide (the guide draws the video, the same view as its preview, so the
     * picture never has to move to another view). This screen then only draws the controls.
     */
    embedded: Boolean = false,
    viewModel: LiveTvViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val user by viewModel.userState.collectAsStateWithLifecycle()
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val programs by viewModel.programs.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val view = LocalView.current

    if (!embedded) PauseLiveTvInBackground(viewModel.playback)

    DisposableEffect(Unit) {
        viewModel.playback.attach()
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            viewModel.playback.detach()
        }
    }

    val rootFocus = remember { FocusRequester() }
    val listFocus = remember { FocusRequester() }
    var bannerVisible by remember { mutableStateOf(true) }
    var bannerToken by remember { mutableIntStateOf(0) }
    var listVisible by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf(PlayerDialog.NONE) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var numberBuffer by remember { mutableStateOf("") }
    var longPressFired by remember { mutableStateOf(false) }
    var okHoldJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    // Follows the channel list as it loads (it was read once before, so a list that was still
    // loading left full screen stuck on "Nothing playing"). Falls back to the player's own
    // channel, so full screen always knows what it's showing.
    val displayList by viewModel.displayChannels.collectAsStateWithLifecycle()
    val current: LiveChannel? = remember(displayList, playback.channelKey) {
        playback.channelKey?.let { k -> displayList.firstOrNull { it.key == k } }
            ?: viewModel.playback.playingChannel?.takeIf { it.key == playback.channelKey }
    }

    fun showBanner() { bannerVisible = true; bannerToken++ }

    // The bottom panel's controls (Down while it's showing): which one is highlighted.
    var controlFocus by remember { mutableStateOf<PanelControl?>(null) }
    // The full panel (OK): timeline, buttons and options. Changing channels shows the compact one.
    var panelFull by remember { mutableStateOf(false) }
    fun closeFull() { panelFull = false; controlFocus = null }
    val shiftStartedAt by viewModel.playback.shiftStartedAt.collectAsStateWithLifecycle()
    var captionsOn by remember { mutableStateOf(false) }
    LaunchedEffect(playback.channelKey, bannerVisible) { captionsOn = viewModel.playback.captionsOn() }

    // Picture quality (for the guide's badges) and frame rate (for Match frame rate).
    var videoFps by remember { mutableStateOf(-1f) }
    LaunchedEffect(playback.channelKey, playback.catchupTitle) {
        while (true) {
            val f = viewModel.playback.player?.videoFormat
            if (f != null) {
                if (playback.catchupTitle == null) playback.channelKey?.let { viewModel.recordQuality(it, f.height) }
                if (f.frameRate > 0f) videoFps = f.frameRate
            }
            delay(3_000)
        }
    }
    MatchFrameRate(enabled = settings.matchFrameRate, fps = videoFps)

    // Sleep timer: the controller stops playback (on any screen); full screen then closes.
    val sleepAt by viewModel.sleepAtMs.collectAsStateWithLifecycle()
    val sleepFired by viewModel.playback.sleepFired.collectAsStateWithLifecycle()
    val sleepFiredAtStart = remember { viewModel.playback.sleepFired.value }
    LaunchedEffect(sleepFired) {
        if (sleepFired != sleepFiredAtStart) onBack()
    }

    // Catch-up seeking: Left/Right move a target time; the seek happens once you stop pressing.
    val catchupSession by viewModel.catchup.collectAsStateWithLifecycle()
    var scrubTargetMs by remember { mutableStateOf<Long?>(null) }
    var scrubToken by remember { mutableIntStateOf(0) }
    var positionMs by remember { mutableStateOf(0L) }
    LaunchedEffect(playback.catchupTitle, catchupSession) {
        while (playback.catchupTitle != null && catchupSession != null) {
            viewModel.followCatchup()
            viewModel.catchupPositionMs()?.let { positionMs = it }
            delay(500)
        }
    }
    LaunchedEffect(scrubToken) {
        val target = scrubTargetMs ?: return@LaunchedEffect
        delay(700)
        viewModel.seekCatchupTo(target)
        positionMs = target
        scrubTargetMs = null
    }
    fun scrub(forward: Boolean, repeat: Int) {
        val s = catchupSession ?: return
        val step = when {
            repeat > 20 -> 300_000L
            repeat > 8 -> 120_000L
            else -> 30_000L
        }
        val length = s.program.stopMs - s.program.startMs
        val from = scrubTargetMs ?: positionMs
        scrubTargetMs = (from + if (forward) step else -step).coerceIn(0L, length)
        scrubToken++
        showBanner()
    }

    LaunchedEffect(bannerToken, settings.infoBannerSeconds) {
        delay(settings.infoBannerSeconds.coerceAtLeast(2) * 1000L)
        bannerVisible = false
    }
    LaunchedEffect(playback.channelKey) { showBanner() }
    LaunchedEffect(toast) { if (toast != null) { delay(2_000); toast = null } }
    LaunchedEffect(Unit) { delay(100); runCatching { rootFocus.requestFocus() } }
    LaunchedEffect(numberBuffer) {
        if (numberBuffer.isEmpty()) return@LaunchedEffect
        delay(1_300)
        val n = numberBuffer.toIntOrNull()
        numberBuffer = ""
        n?.let { viewModel.channelByNumber(it) }?.let { viewModel.preview(it) } ?: run { if (n != null) toast = "No channel $n" }
    }
    LaunchedEffect(listVisible) {
        if (!listVisible) { delay(60); runCatching { rootFocus.requestFocus() } }
    }

    fun zap(direction: Int) {
        viewModel.zap(playback.channelKey, direction)?.let { viewModel.preview(it) }
    }

    // What's on now, and the poster Nuvio's catalogs would show for it (looked up ahead of time,
    // so it's ready when the info bar opens).
    val nowProgram = rememberShowDetails(current?.key, current?.let { ch -> programs[ch.key]?.firstOrNull { now >= it.startMs && now < it.stopMs } })
    val watchingTitle = playback.catchupTitle ?: nowProgram?.title
    val poster by androidx.compose.runtime.produceState<String?>(initialValue = null, watchingTitle) {
        value = null
        // In archive playback the guide entry isn't the live one, so no movie/series hint.
        val hintProgram = if (playback.catchupTitle == null) nowProgram else null
        value = watchingTitle?.let { viewModel.posterFor(it, hintProgram, current) }
            ?: hintProgram?.takeIf { !LiveTvRepository.isPlaceholderTitle(it.title) }?.icon
    }

    BackHandler(enabled = listVisible) { listVisible = false }

    CompositionLocalProvider(LocalLiveSolidHighlight provides settings.solidHighlight) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (embedded) Color.Transparent else Color.Black)
            .focusRequester(rootFocus)
            .onPreviewKeyEvent { e ->
                if (listVisible || dialog != PlayerDialog.NONE) return@onPreviewKeyEvent false
                val isOk = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                val archive = playback.catchupTitle != null
                val shifting = shiftStartedAt != null
                // ---- the panel's controls (entered with Down while the panel shows)
                val cf = controlFocus
                if (cf != null) {
                    val mode = if (archive) PanelMode.CATCHUP else if (shifting) PanelMode.SHIFT else PanelMode.LIVE
                    val paused = !playback.isPlaying && !playback.isBuffering
                    val list = panelControls(mode, paused, canSkip = archive || viewModel.playback.shiftCanSeek())
                    if (e.type == KeyEventType.KeyUp) return@onPreviewKeyEvent isOk || e.key == Key.Back
                    showBanner()
                    val buttons = list.filter { it != PanelControl.TIMELINE }
                    val onTimeline = cf == PanelControl.TIMELINE && PanelControl.TIMELINE in list
                    val i = buttons.indexOf(cf).coerceAtLeast(0)
                    when {
                        // The timeline, grabbed: ◀ / ▶ move through the recording or show (faster
                        // the longer you hold), OK plays/pauses, Down back to the buttons.
                        onTimeline && (e.key == Key.DirectionLeft || e.key == Key.DirectionRight) -> {
                            val forward = e.key == Key.DirectionRight
                            if (archive) scrub(forward = forward, repeat = e.nativeKeyEvent.repeatCount)
                            else {
                                val r = e.nativeKeyEvent.repeatCount
                                val step = when { r > 20 -> 120_000L; r > 8 -> 60_000L; r > 2 -> 30_000L; else -> 10_000L }
                                viewModel.playback.shiftSeekBy(if (forward) step else -step)
                            }
                            return@onPreviewKeyEvent true
                        }
                        onTimeline && e.key == Key.DirectionDown -> { controlFocus = PanelControl.PLAY; return@onPreviewKeyEvent true }
                        onTimeline && e.key == Key.DirectionUp -> return@onPreviewKeyEvent true
                        onTimeline && isOk -> {
                            if (e.nativeKeyEvent.repeatCount == 0) viewModel.playback.togglePause()
                            return@onPreviewKeyEvent true
                        }
                        e.key == Key.DirectionLeft -> { controlFocus = buttons[(i - 1).coerceAtLeast(0)]; return@onPreviewKeyEvent true }
                        e.key == Key.DirectionRight -> { controlFocus = buttons[(i + 1).coerceAtMost(buttons.lastIndex)]; return@onPreviewKeyEvent true }
                        e.key == Key.DirectionUp -> {
                            // Up from the buttons: grab the timeline (when it can be moved).
                            if (PanelControl.TIMELINE in list) controlFocus = PanelControl.TIMELINE
                            return@onPreviewKeyEvent true
                        }
                        e.key == Key.Back -> {
                            (context as? com.nuvio.tv.MainActivity)?.longPressBackHeld?.value = true
                            closeFull(); bannerVisible = false; return@onPreviewKeyEvent true
                        }
                        e.key == Key.DirectionDown -> return@onPreviewKeyEvent true
                        isOk -> {
                            if (e.nativeKeyEvent.repeatCount > 0) return@onPreviewKeyEvent true
                            when (buttons.getOrNull(i)) {
                                PanelControl.BACK -> if (archive) scrub(forward = false, repeat = 0) else viewModel.playback.shiftSeekBy(-30_000)
                                PanelControl.FORWARD -> if (archive) scrub(forward = true, repeat = 0) else viewModel.playback.shiftSeekBy(30_000)
                                PanelControl.PLAY -> viewModel.playback.togglePause()
                                PanelControl.LIVE -> {
                                    if (archive) current?.let { viewModel.backToLive(it) } else viewModel.playback.goLive()
                                    controlFocus = PanelControl.PLAY
                                }
                                PanelControl.CC -> {
                                    captionsOn = viewModel.playback.toggleCaptions()
                                    toast = if (captionsOn) "Captions on" else if (viewModel.playback.subtitleTracks().isEmpty()) "No captions on this channel" else "Captions off"
                                }
                                PanelControl.AUDIO -> dialog = PlayerDialog.AUDIO
                                PanelControl.SIZE -> dialog = PlayerDialog.SCREEN_SIZE
                                PanelControl.SLEEP -> dialog = PlayerDialog.SLEEP
                                PanelControl.MORE -> dialog = PlayerDialog.OPTIONS
                                PanelControl.TIMELINE, null -> Unit
                            }
                            return@onPreviewKeyEvent true
                        }
                        else -> Unit
                    }
                }
                if (isOk) {
                    if (e.type == KeyEventType.KeyDown) {
                        if (e.nativeKeyEvent.repeatCount == 0) {
                            // A new press: forget a long press whose release went to a dialog.
                            longPressFired = false
                            // Long press by time held too (remotes that never repeat, like CEC).
                            okHoldJob?.cancel()
                            okHoldJob = scope.launch {
                                delay(LONG_PRESS_MS)
                                if (!longPressFired) { longPressFired = true; dialog = PlayerDialog.OPTIONS }
                            }
                        } else if (!longPressFired) {
                            okHoldJob?.cancel()
                            longPressFired = true
                            dialog = PlayerDialog.OPTIONS
                        }
                        return@onPreviewKeyEvent true
                    }
                    if (e.type == KeyEventType.KeyUp) {
                        okHoldJob?.cancel()
                        val heldMs = e.nativeKeyEvent.eventTime - e.nativeKeyEvent.downTime
                        if (longPressFired) {
                            longPressFired = false
                        } else if (heldMs >= LONG_PRESS_MS) {
                            dialog = PlayerDialog.OPTIONS
                        } else when {
                            playback.error != null && playback.reconnectAttempt > 8 -> viewModel.playback.retry()
                            // OK: the full panel (timeline, buttons, options), on Play/Pause.
                            current != null -> { panelFull = true; controlFocus = PanelControl.PLAY; showBanner() }
                            else -> showBanner()
                        }
                        return@onPreviewKeyEvent true
                    }
                }
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val code = e.nativeKeyEvent.keyCode
                when {
                    e.key == Key.DirectionUp || e.key == Key.ChannelUp -> { zap(1); true }
                    e.key == Key.DirectionDown || e.key == Key.ChannelDown -> { zap(-1); true }
                    e.key == Key.DirectionLeft -> {
                        when {
                            archive -> scrub(forward = false, repeat = e.nativeKeyEvent.repeatCount)
                            shifting && viewModel.playback.shiftCanSeek() -> { viewModel.playback.shiftSeekBy(-30_000); showBanner() }
                            settings.overlayMode -> listVisible = true
                            else -> { viewModel.requestGroupsOnReturn(); onBackToGroups() }
                        }
                        true
                    }
                    e.key == Key.DirectionRight -> {
                        when {
                            archive -> scrub(forward = true, repeat = e.nativeKeyEvent.repeatCount)
                            shifting && viewModel.playback.shiftCanSeek() -> { viewModel.playback.shiftSeekBy(30_000); showBanner() }
                            else -> showBanner()
                        }
                        true
                    }
                    e.key == Key.Info -> { showBanner(); true }
                    e.key == Key.Menu -> { dialog = PlayerDialog.OPTIONS; true }
                    e.key == Key.MediaPlayPause || e.key == Key.MediaPlay || e.key == Key.MediaPause -> {
                        viewModel.playback.togglePause(); true
                    }
                    code == AndroidKeyEvent.KEYCODE_LAST_CHANNEL -> {
                        viewModel.previousChannel()?.let { viewModel.preview(it) }
                        true
                    }
                    code in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> {
                        if (numberBuffer.length < 5) numberBuffer += (code - AndroidKeyEvent.KEYCODE_0).toString()
                        true
                    }
                    e.key == Key.Back -> {
                        // Swallow the rest of this Back press (held repeats and letting go), so
                        // holding Back here can't reach the guide and bounce back to full screen.
                        (context as? com.nuvio.tv.MainActivity)?.longPressBackHeld?.value = true
                        onBack()
                        true
                    }
                    else -> false
                }
            }
            .focusable()
    ) {
        if (!embedded) LivePlayerSurface(
            player = viewModel.playback.player,
            modifier = Modifier.fillMaxSize(),
            useSurfaceView = true,
            onAttached = { viewModel.playback.onSurfaceAttached() },
            aspectMode = aspectModeOf(settings.aspectMode)
        )

        // Status in the middle of the screen. "Loading…" only for channels that are actually
        // slow to start (not the moment every channel change takes).
        val slowLoading = rememberDelayedTrue(playback.isBuffering, 1_200)
        val centerMessage = when {
            current == null -> "Nothing playing"
            playback.error != null && playback.reconnectAttempt > 8 -> "Stream unavailable · press OK to retry"
            playback.error != null -> "Reconnecting… (${playback.reconnectAttempt})"
            slowLoading -> "Loading…"
            else -> null
        }
        centerMessage?.let {
            LiveText(
                it,
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                size = 18.sp
            )
        }

        // The bottom panel: poster, the show, its timeline (live, catch-up or a pause-and-rewind
        // recording), playback buttons and quick options. Stays up while paused.
        val pausedNow = !playback.isPlaying && !playback.isBuffering && current != null && playback.error == null
        // The full panel closes by itself after a while without a button press (not while paused).
        LaunchedEffect(bannerToken, panelFull, pausedNow) {
            if (panelFull && !pausedNow) { delay(10_000); closeFull(); bannerVisible = false }
        }
        AnimatedVisibility(
            visible = (bannerVisible || panelFull || pausedNow) && current != null,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            current?.let { ch ->
                val use24h = settings.use24HourClock
                val archive = playback.catchupTitle != null
                val cs = catchupSession
                val mode = when {
                    archive -> PanelMode.CATCHUP
                    shiftStartedAt != null -> PanelMode.SHIFT
                    else -> PanelMode.LIVE
                }
                // 24/7 channels with no episode info: what the channel plays, from your addons.
                val about = if (!archive) rememberChannelAbout(ch, nowProgram) else null
                val showProgram = if (archive) cs?.program else nowProgram
                // Ticks while visible, so the timeline moves.
                var tick by remember { mutableStateOf(System.currentTimeMillis()) }
                LaunchedEffect(Unit) { while (true) { delay(1_000); tick = System.currentTimeMillis() } }
                val timeline: PanelTimeline? = when (mode) {
                    PanelMode.CATCHUP -> cs?.let { session ->
                        val pos = scrubTargetMs ?: positionMs
                        val len = session.program.stopMs - session.program.startMs
                        PanelTimeline(
                            startMs = session.program.startMs,
                            endMs = session.program.stopMs,
                            positionMs = session.program.startMs + pos,
                            leftLabel = formatClock(session.program.startMs, use24h),
                            rightLabel = formatClock(session.program.stopMs, use24h),
                            status = formatDuration(pos) + " / " + formatDuration(len)
                        )
                    }
                    PanelMode.SHIFT -> {
                        val start = shiftStartedAt ?: tick
                        val pos = viewModel.playback.shiftPositionWallMs() ?: start
                        val behind = (tick - pos).coerceAtLeast(0L)
                        PanelTimeline(
                            startMs = start,
                            endMs = maxOf(tick, start + 60_000),
                            positionMs = pos,
                            liveMs = tick,
                            recordedUntilMs = tick,
                            leftLabel = "Recorded from " + formatClock(start, use24h),
                            rightLabel = "Live " + formatClock(tick, use24h),
                            status = formatDuration(behind) + " behind live",
                            statusLive = true
                        )
                    }
                    PanelMode.LIVE -> nowProgram?.let { p ->
                        PanelTimeline(
                            startMs = p.startMs,
                            endMs = p.stopMs,
                            positionMs = tick.coerceIn(p.startMs, p.stopMs),
                            leftLabel = formatClock(p.startMs, use24h),
                            rightLabel = formatClock(p.stopMs, use24h),
                            status = minutesLeftLabel(p.stopMs, tick)
                        )
                    }
                }
                val paused = !playback.isPlaying && !playback.isBuffering
                LiveTvPlayerPanel(
                    channel = ch,
                    mode = mode,
                    full = panelFull || pausedNow,
                    title = watchingTitle ?: ch.name,
                    meta = listOfNotNull(
                        showProgram?.episode,
                        showProgram?.category,
                        about?.let { aboutLine(it) }?.takeIf { it.isNotBlank() }
                    ).joinToString("  ·  "),
                    description = about?.description?.takeIf { it.isNotBlank() } ?: showProgram?.description,
                    poster = poster,
                    showPosters = settings.showPosters,
                    timeline = timeline,
                    paused = paused,
                    controls = panelControls(mode, paused, canSkip = archive || viewModel.playback.shiftCanSeek()),
                    focused = controlFocus,
                    captionsOn = captionsOn,
                    audioLabel = remember(playback.channelKey, bannerVisible) { viewModel.playback.audioLabel() },
                    sizeLabel = aspectLabel(settings.aspectMode),
                    sleepLabel = sleepAt?.let { at -> "Sleep " + ((at - tick).coerceAtLeast(0) / 60_000 + 1) + "m" },
                    clock = formatClock(tick, use24h),
                    resolution = if (playback.videoHeight > 0) "${playback.videoHeight}p" else null,
                    showNumber = settings.showChannelNumbers
                )
            }
        }

        // Overlay mode (TiviMate style): channels, groups and each channel's schedule over the video.
        if (listVisible) {
            LiveTvOverlayMode(
                viewModel = viewModel,
                onClose = { listVisible = false },
                onFindInNuvio = { title ->
                    listVisible = false
                    LiveTvSearchBridge.request(title)
                    onFindInNuvio()
                }
            )
        }

        if (numberBuffer.isNotEmpty()) {
            LiveText(
                numberBuffer,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(28.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 22.dp, vertical = 10.dp),
                size = 40.sp,
                weight = FontWeight.Bold
            )
        }
        toast?.let {
            LiveText(
                it,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 28.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 18.dp, vertical = 8.dp)
            )
        }
    }

    when (dialog) {
        PlayerDialog.OPTIONS -> current?.let { ch ->
            PlayerOptionsDialog(
                channel = ch,
                isFavorite = ch.key in user.favorites,
                aspectLabel = context.getString(aspectModeOf(settings.aspectMode).labelResId),
                archive = playback.catchupTitle != null,
                canRestart = playback.catchupTitle == null && viewModel.canWatchFromStart(ch),
                onRestart = { dialog = PlayerDialog.NONE; viewModel.watchFromStart(ch); showBanner() },
                onDismiss = { dialog = PlayerDialog.NONE; scope.launch { delay(60); runCatching { rootFocus.requestFocus() } } },
                onAudio = { dialog = PlayerDialog.AUDIO },
                onSubtitles = { dialog = PlayerDialog.SUBTITLES },
                onAspect = { dialog = PlayerDialog.SCREEN_SIZE },
                onFavorite = { viewModel.toggleFavorite(ch); dialog = PlayerDialog.NONE },
                onHide = {
                    viewModel.hideChannel(ch)
                    dialog = PlayerDialog.NONE
                    zap(1)
                },
                onPrevious = {
                    dialog = PlayerDialog.NONE
                    viewModel.previousChannel()?.let { viewModel.preview(it) }
                },
                onBackToLive = { dialog = PlayerDialog.NONE; viewModel.backToLive(ch) },
                onRetry = { dialog = PlayerDialog.NONE; viewModel.playback.retry() },
                onStreamInfo = { dialog = PlayerDialog.STREAM_INFO },
                sleepLabel = sleepAt?.let { "${((it - System.currentTimeMillis()) / 60_000 + 1).coerceAtLeast(1)} min left" } ?: "Off",
                onSleep = { dialog = PlayerDialog.SLEEP },
                currentTitle = viewModel.currentProgram(ch.key)?.let { com.nuvio.tv.livetv.data.LiveTvPosterResolver.searchTitleFor(it, ch) },
                onFind = { title ->
                    dialog = PlayerDialog.NONE
                    LiveTvSearchBridge.request(title)
                    onFindInNuvio()
                }
            )
        } ?: run { dialog = PlayerDialog.NONE }
        PlayerDialog.AUDIO -> TrackDialog(
            title = "Audio",
            options = viewModel.playback.audioTracks(),
            allowOff = false,
            onPick = { viewModel.playback.selectTrack(C.TRACK_TYPE_AUDIO, it); dialog = PlayerDialog.NONE },
            onDismiss = { dialog = PlayerDialog.NONE }
        )
        PlayerDialog.SUBTITLES -> TrackDialog(
            title = "Subtitles",
            options = viewModel.playback.subtitleTracks(),
            allowOff = true,
            onPick = { viewModel.playback.selectTrack(C.TRACK_TYPE_TEXT, it); dialog = PlayerDialog.NONE },
            onDismiss = { dialog = PlayerDialog.NONE }
        )
        PlayerDialog.STREAM_INFO -> viewModel.channelByKey(playback.channelKey)?.let { current ->
            StreamInfoDialog(
                player = viewModel.playback.player,
                channel = current,
                onDismiss = { dialog = PlayerDialog.NONE; scope.launch { delay(60); runCatching { rootFocus.requestFocus() } } }
            )
        }
        PlayerDialog.SLEEP -> SleepTimerDialog(
            onPick = { minutes ->
                viewModel.setSleepTimer(minutes)
                toast = if (minutes == null) "Sleep timer off" else "Sleep timer: $minutes min"
                dialog = PlayerDialog.NONE
                scope.launch { delay(60); runCatching { rootFocus.requestFocus() } }
            },
            onDismiss = { dialog = PlayerDialog.NONE }
        )
        PlayerDialog.SCREEN_SIZE -> ScreenSizeDialog(
            selected = aspectModeOf(settings.aspectMode),
            label = { context.getString(it.labelResId) },
            onPick = { mode ->
                // Remembered for every channel, until you change it again.
                viewModel.updateSettings { it.copy(aspectMode = mode.name) }
                toast = "Screen size: ${context.getString(mode.labelResId)}"
                dialog = PlayerDialog.NONE
                scope.launch { delay(60); runCatching { rootFocus.requestFocus() } }
            },
            onDismiss = { dialog = PlayerDialog.NONE }
        )
        PlayerDialog.NONE -> Unit
    }
    }
}

@Composable
private fun InfoBanner(
    channel: LiveChannel,
    title: String,
    isArchive: Boolean,
    meta: String,
    progress: Float?,
    description: String?,
    nextLine: String?,
    poster: String?,
    showPosters: Boolean = true,
    isFavorite: Boolean,
    clock: String,
    resolution: String?,
    showNumber: Boolean,
    showLogo: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f), Color.Black.copy(alpha = 0.95f))))
            .padding(start = 48.dp, end = 48.dp, top = 72.dp, bottom = 32.dp)
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            // Posters off: just the channel logo, beside the details (like a TV box).
            if (!showPosters) {
                Box(modifier = Modifier.width(124.dp).height(78.dp), contentAlignment = Alignment.Center) {
                    ChannelLogo(channel.logo, 72.dp)
                }
            } else
            // Poster, as Nuvio would show it in a catalog. Falls back to the channel logo.
            Box(
                modifier = Modifier
                    .width(124.dp)
                    .height(186.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(guideSurface()),
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
                    ChannelLogo(channel.logo, 56.dp)
                }
            }
            Spacer(Modifier.width(26.dp))
            Column(modifier = Modifier.weight(1f)) {
                // What you're watching: big and bold, with the clock on the right.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LiveText(
                        (if (isArchive) "Archive · " else "") + title,
                        modifier = Modifier.weight(1f),
                        size = 32.sp,
                        weight = FontWeight.Bold,
                        marquee = true
                    )
                    Spacer(Modifier.width(20.dp))
                    resolution?.let { LiveText(it, color = NuvioTheme.colors.TextSecondary, size = 14.sp, modifier = Modifier.padding(end = 14.dp)) }
                    LiveText(clock, size = 20.sp, weight = FontWeight.SemiBold)
                }
                if (meta.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    LiveText(meta, color = NuvioTheme.colors.TextSecondary, size = 15.sp)
                }
                progress?.let {
                    Spacer(Modifier.height(10.dp))
                    ProgressBar(it, Modifier.fillMaxWidth(0.55f))
                }
                description?.let {
                    Spacer(Modifier.height(10.dp))
                    LiveText(it, color = NuvioTheme.colors.TextSecondary, size = 14.sp, maxLines = 2)
                }
                Spacer(Modifier.height(16.dp))
                // Bottom line: channel logo, then what's next.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!channel.logo.isNullOrBlank()) {
                        ChannelLogo(channel.logo, 30.dp)
                    } else {
                        // No logo in the playlist: show the name so you still know the channel.
                        LiveText(channel.name, size = 16.sp, weight = FontWeight.Medium)
                    }
                    nextLine?.let {
                        Spacer(Modifier.width(14.dp))
                        LiveText(it, color = NuvioTheme.colors.TextSecondary, size = 15.sp, marquee = true, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerOptionsDialog(
    channel: LiveChannel,
    isFavorite: Boolean,
    aspectLabel: String,
    archive: Boolean,
    canRestart: Boolean,
    onRestart: () -> Unit,
    onDismiss: () -> Unit,
    onAudio: () -> Unit,
    onSubtitles: () -> Unit,
    onAspect: () -> Unit,
    onFavorite: () -> Unit,
    onHide: () -> Unit,
    onPrevious: () -> Unit,
    onBackToLive: () -> Unit,
    onRetry: () -> Unit,
    currentTitle: String?,
    onFind: (String) -> Unit,
    onStreamInfo: () -> Unit,
    sleepLabel: String,
    onSleep: () -> Unit
) {
    val first = remember { FocusRequester() }
    LiveDialog(onDismiss = onDismiss, width = 440.dp) {
        LiveText("${channel.number}  ${channel.name}", size = 20.sp, weight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        // Scrolls when the list is taller than the screen.
        Column(
            modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (archive) MenuItem("Back to live", Modifier.focusRequester(first), onBackToLive)
            if (canRestart) MenuItem("Watch from the beginning", Modifier.focusRequester(first), onRestart)
            MenuItem("Audio track", if (archive || canRestart) Modifier else Modifier.focusRequester(first), onAudio)
            currentTitle?.let { t -> MenuItem("Find & stream \"$t\" in Nuvio", onClick = { onFind(t) }) }
            MenuItem("Subtitles", onClick = onSubtitles)
            MenuItem("Screen size: $aspectLabel", onClick = onAspect)
            MenuItem(if (isFavorite) "Remove from favorites" else "Add to favorites", onClick = onFavorite)
            MenuItem("Previous channel", onClick = onPrevious)
            MenuItem("Stream info", onClick = onStreamInfo)
            MenuItem("Sleep timer: $sleepLabel", onClick = onSleep)
            MenuItem("Reload stream", onClick = onRetry)
            MenuItem("Hide channel", onClick = onHide)
        }
    }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
}

@Composable
private fun TrackDialog(
    title: String,
    options: List<LiveTrackOption>,
    allowOff: Boolean,
    onPick: (LiveTrackOption?) -> Unit,
    onDismiss: () -> Unit
) {
    val first = remember { FocusRequester() }
    LiveDialog(onDismiss = onDismiss, width = 480.dp) {
        LiveText(title, size = 20.sp, weight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        if (options.isEmpty() && !allowOff) {
            LiveText("No tracks available", color = NuvioTheme.colors.TextSecondary)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (allowOff) {
                item {
                    LiveFocusRow(modifier = Modifier.fillMaxWidth().focusRequester(first), onClick = { onPick(null) }) { f ->
                        LiveText("Off", color = focusedTextColor(f))
                    }
                }
            }
            items(options) { o ->
                val isFirst = !allowOff && o == options.first()
                LiveFocusRow(
                    modifier = Modifier.fillMaxWidth().then(if (isFirst) Modifier.focusRequester(first) else Modifier),
                    selected = o.selected,
                    onClick = { onPick(o) }
                ) { f ->
                    LiveText((if (o.selected) "✓  " else "") + o.label, color = focusedTextColor(f))
                }
            }
        }
    }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
}

/** Picture size, with the same choices as Nuvio's movie player (Fit, Crop, Stretch, Cinema Zoom, …). */
@Composable
private fun ScreenSizeDialog(
    selected: com.nuvio.tv.ui.screens.player.AspectMode,
    label: (com.nuvio.tv.ui.screens.player.AspectMode) -> String,
    onPick: (com.nuvio.tv.ui.screens.player.AspectMode) -> Unit,
    onDismiss: () -> Unit
) {
    val first = remember { FocusRequester() }
    LiveDialog(onDismiss = onDismiss, width = 420.dp) {
        LiveText("Screen size", size = 20.sp, weight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            com.nuvio.tv.ui.screens.player.AspectMode.entries.forEach { mode ->
                val isSelected = mode == selected
                LiveFocusRow(
                    modifier = Modifier.fillMaxWidth().then(if (isSelected) Modifier.focusRequester(first) else Modifier),
                    selected = isSelected,
                    onClick = { onPick(mode) }
                ) { f ->
                    LiveText((if (isSelected) "✓  " else "") + label(mode), color = focusedTextColor(f))
                }
            }
        }
    }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
}

/** Where you are in a catch-up program: time into it, a bar you can scrub, and the end time. */
@Composable
private fun CatchupSeekBar(
    program: com.nuvio.tv.livetv.model.EpgProgram,
    positionMs: Long,
    scrubbing: Boolean,
    paused: Boolean,
    use24h: Boolean,
    modifier: Modifier = Modifier
) {
    val length = (program.stopMs - program.startMs).coerceAtLeast(1)
    val fraction = (positionMs.toFloat() / length).coerceIn(0f, 1f)
    val nowOnAir = System.currentTimeMillis()
    val availableFraction = ((nowOnAir - program.startMs).toFloat() / length).coerceIn(0f, 1f)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LiveText(
                (if (paused) "❚❚  " else "") + program.title,
                size = 16.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
            )
            LiveText(
                if (scrubbing) "Jump to ${formatClock(program.startMs + positionMs, use24h)}" else "Catch-up",
                color = NuvioTheme.colors.Secondary, size = 13.sp, weight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(10.dp))
        Box(modifier = Modifier.fillMaxWidth().height(8.dp)) {
            // Whole program, then what's been broadcast so far, then how far you are.
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.18f)))
            Box(Modifier.fillMaxWidth(availableFraction).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.32f)))
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(NuvioTheme.colors.Secondary))
        }
        Spacer(Modifier.height(6.dp))
        Row {
            LiveText(formatDuration(positionMs), color = NuvioTheme.colors.TextSecondary, size = 13.sp, modifier = Modifier.weight(1f))
            LiveText(
                "${formatClock(program.startMs, use24h)} – ${formatClock(program.stopMs, use24h)}  ·  -${formatDuration(length - positionMs)}",
                color = NuvioTheme.colors.TextSecondary, size = 13.sp
            )
        }
        LiveText(
            "◀ ▶ skip  ·  OK pause  ·  hold to skip faster",
            color = NuvioTheme.colors.TextTertiary, size = 11.sp, modifier = Modifier.padding(top = 4.dp)
        )
    }
}

private fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** Resolution, frame rate, codecs and bitrate of what's playing. */
@Composable
private fun StreamInfoDialog(player: androidx.media3.common.Player?, channel: LiveChannel, onDismiss: () -> Unit) {
    val exo = player as? androidx.media3.exoplayer.ExoPlayer
    val v = exo?.videoFormat
    val a = exo?.audioFormat
    val rows = buildList {
        add("Channel" to "${channel.number}  ${channel.name}")
        v?.let { f ->
            if (f.width > 0) add("Resolution" to "${f.width} × ${f.height}" + qualityName(f.height)?.let { "  ($it)" }.orEmpty())
            if (f.frameRate > 0f) add("Frame rate" to "%.2f fps".format(f.frameRate).replace(".00", ""))
            add("Video" to listOfNotNull(f.sampleMimeType?.substringAfter('/'), f.codecs).joinToString(" · ").ifBlank { "Unknown" })
            val br = if (f.bitrate > 0) f.bitrate else f.averageBitrate
            if (br > 0) add("Video bitrate" to "%.1f Mbps".format(br / 1_000_000f))
        } ?: add("Video" to "Not known yet")
        a?.let { f ->
            add(
                "Audio" to listOfNotNull(
                    f.sampleMimeType?.substringAfter('/'),
                    f.channelCount.takeIf { it > 0 }?.let { c -> when (c) { 1 -> "mono"; 2 -> "stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "$c ch" } },
                    f.sampleRate.takeIf { it > 0 }?.let { r -> "${r / 1000} kHz" },
                    f.language
                ).joinToString(" · ")
            )
        }
        exo?.let { p -> add("Buffered" to "${p.totalBufferedDuration / 1000} s") }
        add("Source" to (runCatching { android.net.Uri.parse(channel.url).host }.getOrNull() ?: "—"))
    }
    val first = remember { FocusRequester() }
    LiveDialog(onDismiss = onDismiss, width = 520.dp) {
        LiveText("Stream info", size = 20.sp, weight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        rows.forEach { (k, value) ->
            Row(modifier = Modifier.padding(vertical = 3.dp)) {
                LiveText(k, color = NuvioTheme.colors.TextSecondary, size = 14.sp, modifier = Modifier.width(130.dp))
                LiveText(value, size = 14.sp, maxLines = 2, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(12.dp))
        MenuItem("Close", Modifier.focusRequester(first), onDismiss)
    }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
}

private fun qualityName(height: Int): String? = when {
    height >= 1800 -> "4K"
    height >= 1000 -> "Full HD"
    height >= 700 -> "HD"
    height > 0 -> "SD"
    else -> null
}

@Composable
private fun SleepTimerDialog(onPick: (Int?) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    LiveDialog(onDismiss = onDismiss, width = 380.dp) {
        LiveText("Sleep timer", size = 20.sp, weight = FontWeight.Bold)
        LiveText("Stops playback after", color = NuvioTheme.colors.TextSecondary, size = 13.sp)
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MenuItem("Off", Modifier.focusRequester(first)) { onPick(null) }
            listOf(15, 30, 45, 60, 90, 120).forEach { m ->
                MenuItem(if (m < 60) "$m minutes" else if (m == 60) "1 hour" else "${m / 60.0} hours".replace(".0 ", " ")) { onPick(m) }
            }
        }
    }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
}

/**
 * Match frame rate: while the player is on screen, asks the TV for a display mode whose refresh
 * rate fits the video (24 → 24 Hz, 25 → 50 Hz, 30 → 60 Hz…). Puts the original mode back after.
 */
@Composable
private fun MatchFrameRate(enabled: Boolean, fps: Float) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = remember(context) {
        var c: android.content.Context? = context
        while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
        c as? android.app.Activity
    } ?: return
    val originalMode = remember { activity.window.attributes.preferredDisplayModeId }
    DisposableEffect(Unit) {
        onDispose {
            val attrs = activity.window.attributes
            if (attrs.preferredDisplayModeId != originalMode) {
                attrs.preferredDisplayModeId = originalMode
                activity.window.attributes = attrs
            }
        }
    }
    LaunchedEffect(enabled, fps) {
        if (!enabled || fps <= 0f) return@LaunchedEffect
        @Suppress("DEPRECATION")
        val display = activity.windowManager.defaultDisplay ?: return@LaunchedEffect
        val current = display.mode
        val candidates = display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }
        fun fit(refresh: Float): Float = (1..4).minOf { k -> kotlin.math.abs(refresh - fps * k) }
        val best = candidates.minByOrNull { fit(it.refreshRate) } ?: return@LaunchedEffect
        if (fit(best.refreshRate) > 0.6f || best.modeId == current.modeId) return@LaunchedEffect
        val attrs = activity.window.attributes
        attrs.preferredDisplayModeId = best.modeId
        activity.window.attributes = attrs
    }
}

/** The picture-size setting, short, for the panel. */
private fun aspectLabel(name: String): String = when (name.lowercase()) {
    "fit", "" -> "Fit"
    "fill", "stretch" -> "Fill"
    "zoom", "crop" -> "Zoom"
    else -> name.replaceFirstChar { it.uppercase() }.take(10)
}
