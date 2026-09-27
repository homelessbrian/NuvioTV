package com.nuvio.tv.livetv.ui

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

private val resizeModes = listOf(
    AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit",
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom",
    AspectRatioFrameLayout.RESIZE_MODE_FILL to "Stretch"
)

private enum class PlayerDialog { NONE, OPTIONS, AUDIO, SUBTITLES }

@Composable
fun LiveTvPlayerScreen(
    onBack: () -> Unit,
    onFindInNuvio: () -> Unit = {},
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
    var resizeIndex by remember { mutableIntStateOf(0) }
    var numberBuffer by remember { mutableStateOf("") }
    var longPressFired by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    val current: LiveChannel? = viewModel.channelByKey(playback.channelKey)

    fun showBanner() { bannerVisible = true; bannerToken++ }

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
        if (listVisible) { delay(80); runCatching { listFocus.requestFocus() } }
        else { delay(60); runCatching { rootFocus.requestFocus() } }
    }

    fun zap(direction: Int) {
        viewModel.zap(playback.channelKey, direction)?.let { viewModel.preview(it) }
    }

    // What's on now, and the poster Nuvio's catalogs would show for it (looked up ahead of time,
    // so it's ready when the info bar opens).
    val nowProgram = current?.let { ch -> programs[ch.key]?.firstOrNull { now >= it.startMs && now < it.stopMs } }
    val watchingTitle = playback.catchupTitle ?: nowProgram?.title
    val poster by androidx.compose.runtime.produceState<String?>(initialValue = null, watchingTitle) {
        value = null
        // In archive playback the guide entry isn't the live one, so no movie/series hint.
        val hintProgram = if (playback.catchupTitle == null) nowProgram else null
        value = watchingTitle?.let { viewModel.posterFor(it, hintProgram, current) }
    }

    BackHandler(enabled = listVisible) { listVisible = false }

    CompositionLocalProvider(LocalLiveSolidHighlight provides settings.solidHighlight) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .onPreviewKeyEvent { e ->
                if (listVisible || dialog != PlayerDialog.NONE) return@onPreviewKeyEvent false
                val isOk = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                val archive = playback.catchupTitle != null
                if (isOk) {
                    if (e.type == KeyEventType.KeyDown) {
                        // A new press: forget a long press whose release went to a dialog.
                        if (e.nativeKeyEvent.repeatCount == 0) longPressFired = false
                        if (e.nativeKeyEvent.repeatCount > 0 && !longPressFired) {
                            longPressFired = true
                            dialog = PlayerDialog.OPTIONS
                        }
                        return@onPreviewKeyEvent true
                    }
                    if (e.type == KeyEventType.KeyUp) {
                        if (longPressFired) {
                            longPressFired = false
                        } else when {
                            playback.error != null && playback.reconnectAttempt > 8 -> viewModel.playback.retry()
                            // OK shows the info bar; OK again hides it.
                            bannerVisible -> bannerVisible = false
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
                        if (archive) { viewModel.playback.seekBy(-30_000); showBanner() } else listVisible = true
                        true
                    }
                    e.key == Key.DirectionRight -> {
                        if (archive) viewModel.playback.seekBy(30_000)
                        showBanner()
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
                    e.key == Key.Back -> { onBack(); true }
                    else -> false
                }
            }
            .focusable()
    ) {
        LivePlayerSurface(
            player = viewModel.playback.player,
            modifier = Modifier.fillMaxSize(),
            useSurfaceView = true,
            resizeMode = resizeModes[resizeIndex].first
        )

        // Status in the middle of the screen
        val centerMessage = when {
            current == null -> "Nothing playing"
            playback.error != null && playback.reconnectAttempt > 8 -> "Stream unavailable · press OK to retry"
            playback.error != null -> "Reconnecting… (${playback.reconnectAttempt})"
            playback.isBuffering -> "Loading…"
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

        // Info banner (bottom)
        AnimatedVisibility(
            visible = bannerVisible && current != null,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            current?.let { ch ->
                val list = programs[ch.key].orEmpty()
                val next = list.firstOrNull { it.startMs >= (nowProgram?.stopMs ?: now) }
                val use24h = settings.use24HourClock
                InfoBanner(
                    channel = ch,
                    title = watchingTitle ?: ch.name,
                    isArchive = playback.catchupTitle != null,
                    meta = listOfNotNull(
                        nowProgram?.let { formatRange(it.startMs, it.stopMs, use24h) },
                        nowProgram?.let { minutesLeftLabel(it.stopMs, now) },
                        nowProgram?.episode,
                        nowProgram?.category
                    ).joinToString("  ·  "),
                    progress = if (playback.catchupTitle == null) nowProgram?.progress(now) else null,
                    description = if (playback.catchupTitle == null) nowProgram?.description else null,
                    nextLine = next?.let { "Next: ${formatClock(it.startMs, use24h)}  ${it.title}" },
                    poster = poster,
                    isFavorite = ch.key in user.favorites,
                    clock = formatClock(now, use24h),
                    resolution = if (playback.videoHeight > 0) "${playback.videoHeight}p" else null,
                    showNumber = settings.showChannelNumbers,
                    showLogo = settings.showChannelLogos
                )
            }
        }

        // Channel list (left)
        AnimatedVisibility(
            visible = listVisible,
            enter = slideInHorizontally { -it } + fadeIn(),
            exit = slideOutHorizontally { -it } + fadeOut()
        ) {
            val zapList = viewModel.zapList()
            val startIdx = zapList.indexOfFirst { it.key == playback.channelKey }.coerceAtLeast(0)
            val state = rememberLazyListState(initialFirstVisibleItemIndex = (startIdx - 4).coerceAtLeast(0))
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(460.dp)
                    .background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.92f), Color.Black.copy(alpha = 0.75f))))
                    .padding(vertical = 24.dp, horizontal = 14.dp)
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) { listVisible = false; true } else false
                    }
            ) {
                LiveText(
                    ui.groups.firstOrNull { it.id == ui.selectedGroupId }?.title ?: "Channels",
                    size = 18.sp, weight = FontWeight.Bold, modifier = Modifier.padding(start = 14.dp, bottom = 10.dp)
                )
                LazyColumn(state = state) {
                    items(zapList, key = { it.key }) { ch ->
                        val p = programs[ch.key]?.firstOrNull { now >= it.startMs && now < it.stopMs }
                        LiveFocusRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (ch.key == playback.channelKey) Modifier.focusRequester(listFocus) else Modifier),
                            selected = ch.key == playback.channelKey,
                            onClick = { viewModel.preview(ch); listVisible = false },
                            onLongClick = { viewModel.toggleFavorite(ch) }
                        ) { f ->
                            if (settings.showChannelNumbers) {
                                LiveText(ch.number.toString(), modifier = Modifier.width(44.dp), color = focusedTextColor(f), size = 14.sp)
                            }
                            if (settings.showChannelLogos) ChannelLogo(ch.logo, 26.dp)
                            Column(modifier = Modifier.weight(1f)) {
                                if (settings.showChannelNames) LiveText(ch.name, color = focusedTextColor(f), size = 15.sp, marquee = f)
                                p?.let {
                                    LiveText(it.title, color = focusedSecondaryTextColor(f), size = 12.sp, marquee = f)
                                }
                            }
                        }
                    }
                }
            }
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
                aspectLabel = resizeModes[resizeIndex].second,
                archive = playback.catchupTitle != null,
                onDismiss = { dialog = PlayerDialog.NONE; scope.launch { delay(60); runCatching { rootFocus.requestFocus() } } },
                onAudio = { dialog = PlayerDialog.AUDIO },
                onSubtitles = { dialog = PlayerDialog.SUBTITLES },
                onAspect = { resizeIndex = (resizeIndex + 1) % resizeModes.size; toast = "Aspect: ${resizeModes[resizeIndex].second}" },
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
                onBackToLive = { dialog = PlayerDialog.NONE; viewModel.playback.play(ch) },
                onRetry = { dialog = PlayerDialog.NONE; viewModel.playback.retry() },
                currentTitle = viewModel.currentProgram(ch.key)?.title,
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
    onFind: (String) -> Unit
) {
    val first = remember { FocusRequester() }
    LiveDialog(onDismiss = onDismiss, width = 440.dp) {
        LiveText("${channel.number}  ${channel.name}", size = 20.sp, weight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (archive) MenuItem("Back to live", Modifier.focusRequester(first), onBackToLive)
            MenuItem("Audio track", if (archive) Modifier else Modifier.focusRequester(first), onAudio)
            currentTitle?.let { t -> MenuItem("Find & stream \"$t\" in Nuvio", onClick = { onFind(t) }) }
            MenuItem("Subtitles", onClick = onSubtitles)
            MenuItem("Aspect ratio: $aspectLabel", onClick = onAspect)
            MenuItem(if (isFavorite) "Remove from favourites" else "Add to favourites", onClick = onFavorite)
            MenuItem("Previous channel", onClick = onPrevious)
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
