package com.nuvio.tv.livetv.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.ui.ChannelLogo
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.LiveTvViewModel
import com.nuvio.tv.livetv.ui.formatClock
import com.nuvio.tv.livetv.ui.liveCellColors
import com.nuvio.tv.livetv.ui.minutesLeftLabel
import com.nuvio.tv.ui.theme.NuvioTheme

/** What the home screen row can do (provided by the navigation, so the home screen needn't know). */
data class LiveTvHomeActions(
    /** True only on Nuvio's real home screen (the same layouts are reused for collections). */
    val onHome: Boolean = false,
    /** The channel is now playing in Live TV: open it full screen. */
    val openFullscreen: () -> Unit = {},
    /** Open the Live TV guide. */
    val openGuide: () -> Unit = {}
)

val LocalLiveTvHomeActions = compositionLocalOf { LiveTvHomeActions() }

/**
 * Where the Live TV row goes on the home screen: true = above Continue watching, false =
 * below, null = hidden (turned off, or Live TV hidden from the menu).
 */
@Composable
fun rememberLiveTvHomeRowPosition(): Boolean? {
    // Collections reuse the home screen's layouts: the Live TV row belongs on Home only.
    if (!LocalLiveTvHomeActions.current.onHome) return null
    val context = LocalContext.current
    val prefs = remember {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext, com.nuvio.tv.livetv.startup.LiveTvStartupEntryPoint::class.java
        ).liveTvPreferences()
    }
    val s by prefs.settings.collectAsStateWithLifecycle(initialValue = null)
    val settings = s ?: return null
    if (!settings.homeRowEnabled || !settings.showInSidebar) return null
    return settings.homeRowAboveContinueWatching
}

/**
 * "Live TV" row on Nuvio's home screen: your favorites (or a group) with what's on now. Half
 * the height of Continue watching; OK plays full screen (or opens the guide, by setting).
 * Placed above or below Continue watching; hidden entirely when turned off.
 */
@Composable
fun LiveTvHomeRow(
    above: Boolean,
    /**
     * Modern layout: hide the row while the highlight is elsewhere (that layout scrolls rows
     * above the highlighted one out of view, and a thin sliver of this row stayed visible).
     */
    hideUnlessFocused: Boolean = false,
    /** Line up with the home layout's own rows (Classic 48, Modern 52, Grid 0 as the grid pads). */
    startPadding: androidx.compose.ui.unit.Dp = 48.dp,
    /** The home layout's row-title style, so the title matches the other rows. */
    titleStyle: androidx.compose.ui.text.TextStyle? = null
) {
    val context = LocalContext.current
    val prefs = remember {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext, com.nuvio.tv.livetv.startup.LiveTvStartupEntryPoint::class.java
        ).liveTvPreferences()
    }
    val s by prefs.settings.collectAsStateWithLifecycle(initialValue = null)
    val settings = s ?: return
    // Off, or not this spot: draw nothing (and don't load Live TV at all).
    if (!settings.homeRowEnabled || !settings.showInSidebar || settings.homeRowAboveContinueWatching != above) return
    if (!LocalLiveTvHomeActions.current.onHome) return
    if (HomeRowFocus.homeShownAt == 0L) HomeRowFocus.homeShownAt = android.os.SystemClock.uptimeMillis()
    LiveTvHomeRowContent(settings, takeInitialFocus = above, startPadding = startPadding, titleStyle = titleStyle, hideUnlessFocused = hideUnlessFocused && above)
}

/** The home screen opens on the Live TV row (when it's on top) once per app start. */
private object HomeRowFocus {
    @Volatile var done = false
    /** When the home screen first appeared this app start (after any profile picker). */
    @Volatile var homeShownAt = 0L
}

/**
 * Picking Home in Nuvio's side menu asks the Live TV row (when it's on top) to take the
 * highlight, instead of Nuvio jumping to Continue watching.
 */
object LiveTvHomeFocus {
    val requests = kotlinx.coroutines.flow.MutableStateFlow(0)
    @Volatile var pending = false
    fun request() {
        pending = true
        requests.value++
    }

    /** When the remote was last used (set by the app's key handling). */
    @Volatile var lastKeyAt = 0L

    /** Whether the Live TV row has the highlight right now, and when it last lost it. */
    @Volatile var rowHasFocus = false
    @Volatile var lostAt = 0L
    @Volatile private var restoreAfterMenu = false

    /** The side menu opened: remember whether it was opened from the Live TV row. */
    fun menuOpened() {
        restoreAfterMenu = rowHasFocus || android.os.SystemClock.uptimeMillis() - lostAt < 700
    }

    /**
     * The side menu closed over Home: Nuvio puts the highlight back on its own rows, which
     * don't include the Live TV row. Only if the menu was opened from the Live TV row does the
     * highlight go back to it; otherwise Nuvio's own choice (the row you were on) stands.
     */
    fun menuClosed() {
        if (restoreAfterMenu) request()
        restoreAfterMenu = false
    }
}

@Composable
private fun LiveTvHomeRowContent(
    settings: com.nuvio.tv.livetv.model.LiveTvSettings,
    takeInitialFocus: Boolean,
    startPadding: androidx.compose.ui.unit.Dp,
    titleStyle: androidx.compose.ui.text.TextStyle?,
    hideUnlessFocused: Boolean,
    viewModel: LiveTvViewModel = hiltViewModel()
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val firstCard = remember { androidx.compose.ui.focus.FocusRequester() }
    // Each card's focus handle, and the one you were last on (to come back to it).
    val cardFocus = remember { mutableMapOf<String, androidx.compose.ui.focus.FocusRequester>() }
    var lastFocusedKey by remember { mutableStateOf<String?>(null) }
    val actions = LocalLiveTvHomeActions.current
    val entries by viewModel.homeRow.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    if (entries.isEmpty()) return
    // Row above Continue watching: the home screen opens here (Nuvio would otherwise jump down
    // to Continue watching). Once per app start, so coming back keeps your place.
    androidx.compose.runtime.LaunchedEffect(takeInitialFocus) {
        if (!takeInitialFocus || HomeRowFocus.done) return@LaunchedEffect
        HomeRowFocus.done = true
        val claimedAt = android.os.SystemClock.uptimeMillis()
        // "Untouched" = no button pressed since the home screen appeared (keys pressed on the
        // profile picker don't count; any press on Home itself ends this at once).
        val homeAt = HomeRowFocus.homeShownAt.takeIf { it > 0 } ?: claimedAt
        // Nuvio puts the first highlight on Continue watching, sometimes several seconds in, as
        // its rows finish loading. Until the remote is first used (for up to 15 seconds), the
        // highlight is brought back to the Live TV row whenever that happens.
        while (android.os.SystemClock.uptimeMillis() - claimedAt < 15_000) {
            val untouched = LiveTvHomeFocus.lastKeyAt < homeAt
            if (!untouched) break
            if (!LiveTvHomeFocus.rowHasFocus) {
                runCatching { listState.scrollToItem(0) }
                runCatching { firstCard.requestFocus() }
            }
            kotlinx.coroutines.delay(300)
        }
    }
    // Home chosen from Nuvio's side menu: the row takes the highlight (after Nuvio's own).
    val focusRequests by LiveTvHomeFocus.requests.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(focusRequests) {
        if (!LiveTvHomeFocus.pending) return@LaunchedEffect
        LiveTvHomeFocus.pending = false
        // Back from the menu: the card you were on (Nuvio restores its own rows first).
        kotlinx.coroutines.delay(250)
        val target = lastFocusedKey?.let { cardFocus[it] }
        runCatching { (target ?: firstCard).requestFocus() }.onFailure { runCatching { firstCard.requestFocus() } }
    }
    var rowHasFocus by remember { mutableStateOf(false) }
    val alpha by androidx.compose.animation.core.animateFloatAsState(
        if (!hideUnlessFocused || rowHasFocus) 1f else 0f, label = "liveRowAlpha"
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged {
                if (rowHasFocus && !it.hasFocus) LiveTvHomeFocus.lostAt = android.os.SystemClock.uptimeMillis()
                rowHasFocus = it.hasFocus
                LiveTvHomeFocus.rowHasFocus = it.hasFocus
            }
            .graphicsLayer { this.alpha = alpha }
            .padding(vertical = 6.dp)
    ) {
        Row(modifier = Modifier.padding(start = startPadding, end = startPadding, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val title = settings.homeRowTitle.ifBlank { "Live TV" }
            if (titleStyle != null) {
                androidx.tv.material3.Text(text = title, style = titleStyle, color = NuvioTheme.colors.TextPrimary)
            } else {
                LiveText(title, size = 16.sp, weight = FontWeight.SemiBold)
            }
        }
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(start = startPadding, end = maxOf(startPadding, 24.dp)),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(entries, key = { _, it -> it.channel.key }) { index, e ->
                val requester = cardFocus.getOrPut(e.channel.key) { androidx.compose.ui.focus.FocusRequester() }
                HomeCard(
                    modifier = (if (index == 0) Modifier.focusRequester(firstCard) else Modifier).focusRequester(requester),
                    // Keep the highlighted card fully on screen, and the very start of the row
                    // in view at the first card.
                    onFocused = {
                        lastFocusedKey = e.channel.key
                        scope.launch {
                            val info = listState.layoutInfo
                            val visible = info.visibleItemsInfo
                            val fullyVisible = visible.filter {
                                it.offset >= 0 && it.offset + it.size <= info.viewportEndOffset - info.afterContentPadding
                            }.map { it.index }
                            when {
                                index == 0 -> listState.animateScrollToItem(0)
                                fullyVisible.isEmpty() || index < fullyVisible.first() -> listState.animateScrollToItem(index)
                                index > fullyVisible.last() ->
                                    listState.animateScrollToItem((index - (fullyVisible.size - 1).coerceAtLeast(0)).coerceAtLeast(0))
                            }
                        }
                    },
                    entry = e,
                    now = now,
                    playing = e.channel.key == playback.channelKey,
                    compact = settings.homeRowCompact,
                    showNext = settings.homeRowShowNext,
                    use24h = settings.use24HourClock,
                    onClick = {
                        viewModel.playFromHome(e.channel)
                        if (settings.homeRowOpensGuide) actions.openGuide() else {
                            viewModel.markFullscreenOpened()
                            actions.openFullscreen()
                        }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeCard(
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    entry: LiveTvViewModel.HomeRowEntry,
    now: Long,
    playing: Boolean,
    compact: Boolean,
    showNext: Boolean,
    use24h: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val colors = liveCellColors(focused = focused, idle = NuvioTheme.colors.BackgroundElevated)
    val shape = RoundedCornerShape(10.dp)
    val p = entry.now
    Row(
        modifier = modifier
            .width(if (compact) 180.dp else 230.dp)
            .height(if (compact) 54.dp else 72.dp)
            .clip(shape)
            .background(colors.background)
            // A light white outline so cards stand out from the background; the highlight color
            // takes over when a card is highlighted.
            .border(
                if (focused) 2.dp else 1.dp,
                if (focused) colors.border else Color.White.copy(alpha = 0.22f),
                shape
            )
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.width(if (compact) 40.dp else 48.dp), contentAlignment = Alignment.Center) {
            ChannelLogo(entry.channel.logo, if (compact) 34.dp else 42.dp)
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (playing) {
                    LiveText("● ", color = NuvioTheme.colors.Error, size = 10.sp)
                }
                LiveText(
                    p?.title ?: entry.channel.name,
                    color = colors.text, size = 13.sp, weight = FontWeight.SemiBold, marquee = focused
                )
            }
            if (!compact) {
                if (p != null) {
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.18f))
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth(p.progress(now)).height(3.dp)
                                .background(if (focused) colors.text else NuvioTheme.colors.Secondary)
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                }
                val sub = if (showNext && entry.next != null) {
                    "Next: ${formatClock(entry.next.startMs, use24h)} ${entry.next.title}"
                } else {
                    listOfNotNull("${entry.channel.number}  ${entry.channel.name}", p?.let { minutesLeftLabel(it.stopMs, now) })
                        .joinToString(" · ")
                }
                LiveText(sub, color = if (focused) colors.text else NuvioTheme.colors.TextSecondary, size = 10.sp, marquee = focused)
            }
        }
    }
}

