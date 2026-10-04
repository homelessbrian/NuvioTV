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
import androidx.compose.foundation.lazy.items
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
fun LiveTvHomeRow(above: Boolean) {
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
    LiveTvHomeRowContent(settings)
}

@Composable
private fun LiveTvHomeRowContent(
    settings: com.nuvio.tv.livetv.model.LiveTvSettings,
    viewModel: LiveTvViewModel = hiltViewModel()
) {
    val actions = LocalLiveTvHomeActions.current
    val entries by viewModel.homeRow.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    if (entries.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(modifier = Modifier.padding(start = 48.dp, end = 48.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            LiveText(settings.homeRowTitle.ifBlank { "Live TV" }, size = 16.sp, weight = FontWeight.SemiBold)
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(entries, key = { it.channel.key }) { e ->
                HomeCard(
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
        modifier = Modifier
            .width(if (compact) 180.dp else 230.dp)
            .height(if (compact) 54.dp else 72.dp)
            .clip(shape)
            .background(colors.background)
            .border(2.dp, if (focused) colors.border else Color.Transparent, shape)
            .onFocusChanged { focused = it.isFocused }
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

