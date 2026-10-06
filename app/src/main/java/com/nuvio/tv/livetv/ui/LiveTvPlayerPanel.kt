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
internal enum class PanelControl { BACK, PLAY, FORWARD, LIVE, CC, AUDIO, SIZE, SLEEP, MORE }

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
    if (transport && canSkip) add(PanelControl.BACK)
    add(PanelControl.PLAY)
    if (transport && canSkip) add(PanelControl.FORWARD)
    if (mode != PanelMode.LIVE) add(PanelControl.LIVE)
    add(PanelControl.CC); add(PanelControl.AUDIO); add(PanelControl.SIZE); add(PanelControl.SLEEP); add(PanelControl.MORE)
}

/**
 * The full-screen player's single bottom panel: poster on the left, the show and its timeline
 * (live progress, catch-up, or a pause-and-rewind recording), the playback buttons and quick
 * options. [focused] is the highlighted control while the controls are in use (Down), else null.
 */
@Composable
internal fun LiveTvPlayerPanel(
    channel: LiveChannel,
    mode: PanelMode,
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
    modifier: Modifier = Modifier
) {
    val panelBg = Color(0xF20E1014)
    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 56.dp)
                .background(panelBg)
                .padding(start = 40.dp, end = 40.dp, top = 16.dp, bottom = 22.dp)
        ) {
            // Room for the poster, which rises above the panel's top edge.
            Spacer(Modifier.width(if (showPosters) 136.dp else 96.dp))
            Spacer(Modifier.width(20.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LiveText(title, size = 22.sp, weight = FontWeight.Bold, maxLines = 1, marquee = true, modifier = Modifier.weight(1f), color = Color.White)
                    Spacer(Modifier.width(16.dp))
                    timeline?.let {
                        LiveText(
                            it.status,
                            size = 14.sp,
                            color = if (it.statusLive) Color(0xFFF09595) else Color(0xFF9FC5F5),
                            modifier = Modifier.padding(end = 14.dp)
                        )
                    }
                    resolution?.let { LiveText(it, color = Color(0xFF9AA1AD), size = 13.sp, modifier = Modifier.padding(end = 14.dp)) }
                    LiveText(clock, size = 18.sp, weight = FontWeight.SemiBold, color = Color.White)
                }
                Spacer(Modifier.height(4.dp))
                val channelLine = (if (showNumber) "${channel.number}  " else "") + channel.name
                LiveText(listOf(channelLine, meta).filter { it.isNotBlank() }.joinToString("  ·  "), size = 14.sp, color = Color(0xFF9AA1AD), maxLines = 1)
                description?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(6.dp))
                    LiveText(it, size = 14.sp, color = Color(0xFFB9BFC9), maxLines = 2)
                }
                timeline?.let { t ->
                    Spacer(Modifier.height(12.dp))
                    PanelTimelineBar(t)
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    controls.forEach { c ->
                        if (c == PanelControl.CC) Spacer(Modifier.weight(1f))
                        PanelButton(c, focused == c, paused, captionsOn, audioLabel, sizeLabel, sleepLabel, mode)
                    }
                }
            }
        }
        // The poster (or the channel logo when posters are off).
        Box(
            modifier = Modifier
                .padding(start = 40.dp)
                .width(if (showPosters) 136.dp else 96.dp)
                .height(if (showPosters) 204.dp else 96.dp)
                .offset(y = if (showPosters) 0.dp else 70.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF2A2F38))
                .border(1.dp, Color(0xFF3A404B), RoundedCornerShape(10.dp)),
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
                ChannelLogo(channel.logo, if (showPosters) 72.dp else 64.dp)
            }
        }
    }
}

@Composable
private fun PanelTimelineBar(t: PanelTimeline) {
    val span = (t.endMs - t.startMs).coerceAtLeast(1L).toFloat()
    fun frac(ms: Long) = ((ms - t.startMs) / span).coerceIn(0f, 1f)
    Column {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(18.dp)) {
            val w = maxWidth
            Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF2A2F38)))
            t.recordedUntilMs?.let { r ->
                Box(Modifier.align(Alignment.CenterStart).width(w * frac(r)).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF4A5160)))
            }
            Box(Modifier.align(Alignment.CenterStart).width(w * frac(t.positionMs)).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF5B9BF0)))
            t.liveMs?.let { l ->
                Box(Modifier.align(Alignment.CenterStart).offset(x = (w * frac(l)) - 1.dp).width(2.dp).fillMaxHeight().background(Color(0xFFE24B4A)))
            }
            Box(
                Modifier.align(Alignment.CenterStart).offset(x = (w * frac(t.positionMs)) - 7.dp).size(14.dp)
                    .clip(CircleShape).background(Color.White).border(2.dp, Color(0xFF5B9BF0), CircleShape)
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            LiveText(t.leftLabel, size = 12.sp, color = Color(0xFF9AA1AD))
            Spacer(Modifier.weight(1f))
            LiveText(t.rightLabel, size = 12.sp, color = if (t.liveMs != null) Color(0xFFF09595) else Color(0xFF9AA1AD))
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
    val ring = if (focused) Modifier.border(2.dp, Color(0xFF5B9BF0), CircleShape) else Modifier
    when (c) {
        PanelControl.BACK, PanelControl.FORWARD, PanelControl.PLAY -> {
            val big = c == PanelControl.PLAY
            val icon: ImageVector = when (c) {
                PanelControl.BACK -> Icons.Default.Replay30
                PanelControl.FORWARD -> Icons.Default.Forward30
                else -> if (paused) Icons.Default.PlayArrow else Icons.Default.Pause
            }
            Box(
                modifier = Modifier
                    .size(if (big) 46.dp else 38.dp)
                    .then(ring)
                    .padding(if (focused) 3.dp else 0.dp)
                    .clip(CircleShape)
                    .background(if (big || focused) Color.White else Color(0xFF2A2F38)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = if (big || focused) Color(0xFF0E1014) else Color(0xFFE6E8EC), modifier = Modifier.size(if (big) 24.dp else 20.dp))
            }
        }
        PanelControl.LIVE -> Pill(
            text = if (mode == PanelMode.CATCHUP) "Back to live" else "Go live",
            icon = null, focused = focused,
            bg = Color(0xFF5A1D1D), fg = Color(0xFFF7C1C1), dot = true
        )
        PanelControl.CC -> Pill(
            text = if (captionsOn) "CC on" else "CC off",
            icon = if (captionsOn) Icons.Default.ClosedCaption else Icons.Default.ClosedCaptionDisabled,
            focused = focused,
            bg = if (captionsOn) Color(0xFF1D3A5C) else Color(0xFF2A2F38),
            fg = if (captionsOn) Color(0xFFB5D4F4) else Color(0xFFE6E8EC)
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
    bg: Color = Color(0xFF2A2F38),
    fg: Color = Color(0xFFE6E8EC),
    dot: Boolean = false
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .height(36.dp)
            .clip(shape)
            .background(if (focused) Color.White else bg)
            .border(if (focused) 2.dp else 0.dp, if (focused) Color(0xFF5B9BF0) else Color.Transparent, shape)
            .padding(horizontal = if (text.isEmpty()) 9.dp else 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val color = if (focused) Color(0xFF0E1014) else fg
        if (dot) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFE24B4A)))
            Spacer(Modifier.width(7.dp))
        }
        icon?.let {
            Icon(it, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            if (text.isNotEmpty()) Spacer(Modifier.width(6.dp))
        }
        if (text.isNotEmpty()) LiveText(text, size = 13.sp, color = color, weight = FontWeight.Medium)
    }
}
