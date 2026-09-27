package com.nuvio.tv.livetv.ui

import android.view.LayoutInflater
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun LiveText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = NuvioTheme.colors.TextPrimary,
    size: TextUnit = 16.sp,
    weight: FontWeight = FontWeight.Normal,
    maxLines: Int = 1,
    align: TextAlign? = null
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = size,
        fontWeight = weight,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = align
    )
}

/** A focusable row that highlights like TiviMate menus. Supports long press. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LiveFocusRow(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null,
    content: @Composable RowScope.(focused: Boolean) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val bg = when {
        focused -> NuvioTheme.colors.Secondary
        selected -> NuvioTheme.colors.FocusBackground
        else -> Color.Transparent
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused?.invoke()
            }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        content(focused)
    }
}

@Composable
internal fun focusedTextColor(focused: Boolean): Color =
    if (focused) NuvioTheme.colors.OnSecondary else NuvioTheme.colors.TextPrimary

@Composable
internal fun ChannelLogo(url: String?, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(width = size * 1.6f, height = size)
            .clip(RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(width = size * 1.6f, height = size)
            )
        }
    }
}

/** Centered modal panel used by the context menu, program info and text input dialogs. */
@Composable
internal fun LiveDialog(
    onDismiss: () -> Unit,
    width: Dp = 520.dp,
    content: @Composable () -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .widthIn(min = 320.dp, max = width)
                .heightIn(max = 620.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(NuvioTheme.colors.BackgroundElevated)
                .border(1.dp, NuvioTheme.colors.Border, RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            content()
        }
    }
}

@Composable
internal fun LivePlayerSurface(
    player: Player?,
    modifier: Modifier = Modifier,
    useSurfaceView: Boolean,
    resizeMode: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT
) {
    val layout = if (useSurfaceView) R.layout.live_tv_surface_player_view else R.layout.live_tv_texture_player_view
    var viewRef by remember { mutableStateOf<PlayerView?>(null) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            (LayoutInflater.from(ctx).inflate(layout, null) as PlayerView).also { viewRef = it }
        },
        update = { view ->
            if (view.player !== player) view.player = player
            view.resizeMode = resizeMode
        }
    )
    DisposableEffect(Unit) {
        onDispose { viewRef?.player = null }
    }
}

internal fun formatClock(ms: Long, use24h: Boolean): String =
    SimpleDateFormat(if (use24h) "HH:mm" else "h:mm a", Locale.getDefault()).format(Date(ms))

internal fun formatRange(startMs: Long, stopMs: Long, use24h: Boolean): String =
    "${formatClock(startMs, use24h)} – ${formatClock(stopMs, use24h)}"

internal fun formatDayClock(ms: Long, now: Long, use24h: Boolean): String {
    val day = SimpleDateFormat("yyyyMMdd", Locale.US)
    val sameDay = day.format(Date(ms)) == day.format(Date(now))
    val prefix = if (sameDay) "" else SimpleDateFormat("EEE ", Locale.getDefault()).format(Date(ms))
    return prefix + formatClock(ms, use24h)
}

internal fun minutesLeftLabel(stopMs: Long, now: Long): String {
    val min = ((stopMs - now) / 60_000L).coerceAtLeast(0)
    return if (min >= 60) "${min / 60}h ${min % 60}m left" else "${min}m left"
}
