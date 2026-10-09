package com.nuvio.tv.livetv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ClosedCaptionDisabled
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.ui.theme.NuvioTheme

/** What the bottom panel is showing. */
internal enum class PanelMode { LIVE, CATCHUP, SHIFT }

/** The panel's buttons and quick options, in the order the highlight moves through them. */
internal enum class PanelControl { TIMELINE, BACK, PLAY, FORWARD, LIVE, CC, AUDIO, SIZE, SLEEP, MORE }

/** Timeline: everything in wall-clock milliseconds. */
internal data class PanelTimeline(
    val startMs: Long,
    val endMs: Long,
    val positionMs: Long,
    /** Where "live" is on the line (pause and rewind), or null. */
    val liveMs: Long? = null,
    /** The recorded stretch ends here (pause and rewind), or null. */
    val recordedUntilMs: Long? = null,
    val leftLabel: String,
    val rightLabel: String,
    val status: String,
    val statusLive: Boolean = false
)

/** Which controls the panel offers in each mode. */
internal fun panelControls(mode: PanelMode, paused: Boolean, canSkip: Boolean): List<PanelControl> = buildList {
    val transport = mode != PanelMode.LIVE || paused
    // The timeline itself can be grabbed (Up from the buttons) and moved with ◀ / ▶.
    if (transport && canSkip) add(PanelControl.TIMELINE)
    if (transport && canSkip) add(PanelControl.BACK)
    add(PanelControl.PLAY)
    if (transport && canSkip) add(PanelControl.FORWARD)
    if (mode != PanelMode.LIVE) add(PanelControl.LIVE)
    add(PanelControl.CC); add(PanelControl.AUDIO); add(PanelControl.SIZE); add(PanelControl.SLEEP); add(PanelControl.MORE)
}

/**
 * The full-screen player's bottom panel, in two sizes:
 *  - compact ([full] = false): channel, show and progress, shown while changing channels;
 *  - full (OK): also the description, the timeline (live, catch-up, or a pause-and-rewind
 *    recording), the playback buttons and quick options.
 * [focused] is the highlighted control in the full panel, else null. Colors follow Nuvio's theme.
 */
@Composable
internal fun LiveTvPlayerPanel(
    channel: LiveChannel,
    mode: PanelMode,
    full: Boolean,
    title: String,
    meta: String,
    description: String?,
    poster: String?,
    showPosters: Boolean,
    timeline: PanelTimeline?,
    paused: Boolean,
    controls: List<PanelControl>,
    focused: PanelControl?,
    captionsOn: Boolean,
    audioLabel: String,
    sizeLabel: String,
    sleepLabel: String?,
    clock: String,
    resolution: String?,
    showNumber: Boolean,
    /** What's on next ("World Tonight") and when ("7:00 – 8:00 PM"), shown on the right. */
    nextTitle: String? = null,
    nextTime: String? = null,
    /** The channel offers catch-up (a tag beside the channel). */
    catchupAvailable: Boolean = false,
    modifier: Modifier = Modifier
) {
    val colors = NuvioTheme.colors
    val accent = colors.Secondary
    val posterW = if (full) 116.dp else 110.dp
    val posterH = if (full) 174.dp else 165.dp
    // Fades up from the bottom over the picture, in the theme's background color.
    val scrim = androidx.compose.ui.graphics.Brush.verticalGradient(
        0f to Color.Transparent,
        0.38f to colors.Background.copy(alpha = 0.86f),
        1f to colors.Background.copy(alpha = 0.97f)
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(scrim)
            .padding(start = 48.dp, end = 48.dp, top = 64.dp, bottom = if (full) 18.dp else 24.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        // Poster (or the channel logo when posters are off or there isn't one).
        val posterShape = RoundedCornerShape(12.dp)
        Box(
            modifier = Modifier
                .width(if (showPosters) posterW else 72.dp)
                .height(if (showPosters) posterH else 72.dp)
                .clip(posterShape)
                .background(colors.BackgroundCard.copy(alpha = 0.85f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), posterShape),
            contentAlignment = Alignment.Center
        ) {
            if (showPosters && !poster.isNullOrBlank()) {
                coil3.compose.AsyncImage(
                    model = poster,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                ChannelLogo(channel.logo, if (showPosters) 56.dp else 48.dp)
            }
        }
        Spacer(Modifier.width(24.dp))
        Column(modifier = Modifier.weight(1f)) {
            // Channel line: logo, number and name, tags, and the clock on the right.
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!channel.logo.isNullOrBlank()) {
                    ChannelLogo(channel.logo, 24.dp)
                    Spacer(Modifier.width(10.dp))
                }
                LiveText(
                    (if (showNumber) "${channel.number} · " else "") + channel.name,
                    size = 14.sp, weight = FontWeight.SemiBold, color = colors.TextSecondary
                )
                when (mode) {
                    PanelMode.LIVE -> { Spacer(Modifier.width(10.dp)); LiveTag("LIVE", LIVE_RED, Color.White) }
                    PanelMode.CATCHUP -> { Spacer(Modifier.width(10.dp)); LiveTag("ARCHIVE", accent, colors.OnSecondary) }
                    PanelMode.SHIFT -> { Spacer(Modifier.width(10.dp)); LiveTag("PAUSED LIVE", accent, colors.OnSecondary) }
                }
                resolution?.let {
                    Spacer(Modifier.width(6.dp))
                    LiveTag(it.uppercase(), Color.Transparent, colors.TextSecondary,
                        Modifier.border(1.dp, colors.Border, RoundedCornerShape(5.dp)))
                }
                if (catchupAvailable && mode == PanelMode.LIVE) {
                    Spacer(Modifier.width(6.dp))
                    LiveTag("CATCH-UP", Color.Transparent, colors.TextSecondary,
                        Modifier.border(1.dp, colors.Border, RoundedCornerShape(5.dp)))
                }
                Spacer(Modifier.weight(1f))
                if (full) timeline?.let {
                    LiveText(it.status, size = 13.sp, color = if (it.statusLive) colors.Error else accent, modifier = Modifier.padding(end = 14.dp))
                }
                LiveText(clock, size = 18.sp, weight = FontWeight.Bold, color = colors.TextPrimary)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    LiveText(title, size = 30.sp, weight = FontWeight.ExtraBold, maxLines = 1, marquee = true, color = colors.TextPrimary)
                    if (meta.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        LiveText(meta, size = 14.sp, color = colors.TextSecondary, maxLines = 1)
                    }
                    description?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(4.dp))
                        LiveText(it, size = 14.sp, color = colors.TextSecondary, maxLines = if (full) 2 else 1)
                    }
                }
                // What's on next, on the right.
                if (nextTitle != null) {
                    Spacer(Modifier.width(24.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        LiveText("NEXT", size = 11.sp, weight = FontWeight.Bold, color = colors.TextTertiary)
                        LiveText(nextTitle, size = 15.sp, weight = FontWeight.Bold, color = colors.TextPrimary, modifier = Modifier.widthIn(max = 260.dp))
                        nextTime?.let { LiveText(it, size = 13.sp, color = colors.TextSecondary) }
                    }
                }
            }
            timeline?.let { t ->
                Spacer(Modifier.height(if (full) 10.dp else 10.dp))
                PanelTimelineBar(t, grabbed = focused == PanelControl.TIMELINE, compact = !full)
            }
            if (full) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    controls.filter { it != PanelControl.TIMELINE }.forEach { c ->
                        if (c == PanelControl.CC) Spacer(Modifier.weight(1f))
                        PanelButton(c, focused == c, paused, captionsOn, audioLabel, sizeLabel, sleepLabel, mode)
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelTimelineBar(t: PanelTimeline, grabbed: Boolean, compact: Boolean) {
    val colors = NuvioTheme.colors
    val accent = colors.Secondary
    val span = (t.endMs - t.startMs).coerceAtLeast(1L).toFloat()
    fun frac(ms: Long) = ((ms - t.startMs) / span).coerceIn(0f, 1f)
    val track = if (compact) 4.dp else 4.dp
    if (compact) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LiveText(t.leftLabel, size = 13.sp, color = colors.TextSecondary)
            Spacer(Modifier.width(12.dp))
            BoxWithConstraints(modifier = Modifier.weight(1f).height(8.dp)) {
                val w = maxWidth
                Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(track).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.18f)))
                t.recordedUntilMs?.let { r ->
                    Box(Modifier.align(Alignment.CenterStart).width(w * frac(r)).height(track).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.35f)))
                }
                Box(Modifier.align(Alignment.CenterStart).width(w * frac(t.positionMs)).height(track).clip(RoundedCornerShape(2.dp)).background(accent))
                t.liveMs?.let { l ->
                    Box(Modifier.align(Alignment.CenterStart).offset(x = (w * frac(l)) - 1.dp).width(2.dp).fillMaxHeight().background(colors.Error))
                }
            }
            Spacer(Modifier.width(12.dp))
            LiveText(t.rightLabel, size = 13.sp, color = if (t.liveMs != null) colors.Error else colors.TextSecondary)
            Spacer(Modifier.width(14.dp))
            LiveText(t.status, size = 13.sp, color = if (t.statusLive) colors.Error else colors.TextSecondary)
        }
        return
    }
    Column {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(if (compact) 8.dp else 16.dp)) {
            val w = maxWidth
            Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(track).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.18f)))
            t.recordedUntilMs?.let { r ->
                Box(Modifier.align(Alignment.CenterStart).width(w * frac(r)).height(track).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.35f)))
            }
            Box(Modifier.align(Alignment.CenterStart).width(w * frac(t.positionMs)).height(track).clip(RoundedCornerShape(2.dp)).background(accent))
            t.liveMs?.let { l ->
                Box(Modifier.align(Alignment.CenterStart).offset(x = (w * frac(l)) - 1.dp).width(2.dp).fillMaxHeight().background(colors.Error))
            }
            if (!compact) {
                // The handle: bigger, with a ring, while the timeline is grabbed.
                val knob = if (grabbed) 18.dp else 12.dp
                Box(
                    Modifier.align(Alignment.CenterStart).offset(x = (w * frac(t.positionMs)) - knob / 2).size(knob)
                        .clip(CircleShape).background(Color.White)
                        .border(if (grabbed) 3.dp else 2.dp, if (grabbed) colors.FocusRing else accent, CircleShape)
                )
            }
        }
        if (!compact) Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            LiveText(t.leftLabel, size = 11.sp, color = colors.TextSecondary)
            Spacer(Modifier.weight(1f))
            if (grabbed) LiveText("◀ ▶ to move · OK to play/pause", size = 11.sp, color = colors.TextSecondary)
            Spacer(Modifier.weight(1f))
            LiveText(t.rightLabel, size = 11.sp, color = if (t.liveMs != null) colors.Error else colors.TextSecondary)
        }
    }
}

@Composable
private fun PanelButton(
    c: PanelControl,
    focused: Boolean,
    paused: Boolean,
    captionsOn: Boolean,
    audioLabel: String,
    sizeLabel: String,
    sleepLabel: String?,
    mode: PanelMode
) {
    val colors = NuvioTheme.colors
    val idle = Color.White.copy(alpha = 0.12f)
    when (c) {
        PanelControl.TIMELINE -> Unit
        PanelControl.BACK, PanelControl.FORWARD, PanelControl.PLAY -> {
            val big = c == PanelControl.PLAY
            val icon: ImageVector = when (c) {
                PanelControl.BACK -> Icons.Default.Replay30
                PanelControl.FORWARD -> Icons.Default.Forward30
                else -> if (paused) Icons.Default.PlayArrow else Icons.Default.Pause
            }
            Box(
                modifier = Modifier
                    .size(if (big) 40.dp else 34.dp)
                    .clip(CircleShape)
                    .background(if (focused) colors.FocusRing else if (big) Color.White.copy(alpha = 0.22f) else idle),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = if (focused) colors.Background else colors.TextPrimary, modifier = Modifier.size(if (big) 22.dp else 18.dp))
            }
        }
        PanelControl.LIVE -> Pill(
            text = if (mode == PanelMode.CATCHUP) "Back to live" else "Go live",
            icon = null, focused = focused, dot = true
        )
        PanelControl.CC -> Pill(
            text = if (captionsOn) "CC on" else "CC off",
            icon = if (captionsOn) Icons.Default.ClosedCaption else Icons.Default.ClosedCaptionDisabled,
            focused = focused, active = captionsOn
        )
        PanelControl.AUDIO -> Pill(audioLabel, Icons.Default.VolumeUp, focused)
        PanelControl.SIZE -> Pill(sizeLabel, Icons.Default.AspectRatio, focused)
        PanelControl.SLEEP -> Pill(sleepLabel ?: "Sleep", Icons.Default.Bedtime, focused)
        PanelControl.MORE -> Pill("", Icons.Default.MoreHoriz, focused)
    }
}

@Composable
private fun Pill(
    text: String,
    icon: ImageVector?,
    focused: Boolean,
    active: Boolean = false,
    dot: Boolean = false
) {
    val colors = NuvioTheme.colors
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .height(32.dp)
            .clip(shape)
            .background(
                when {
                    focused -> colors.FocusRing
                    active -> colors.Secondary.copy(alpha = 0.30f)
                    else -> Color.White.copy(alpha = 0.12f)
                }
            )
            .padding(horizontal = if (text.isEmpty()) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val color = if (focused) colors.Background else colors.TextPrimary
        if (dot) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(colors.Error))
            Spacer(Modifier.width(6.dp))
        }
        icon?.let {
            Icon(it, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            if (text.isNotEmpty()) Spacer(Modifier.width(6.dp))
        }
        if (text.isNotEmpty()) LiveText(text, size = 12.sp, color = color, weight = FontWeight.Medium)
    }
}
