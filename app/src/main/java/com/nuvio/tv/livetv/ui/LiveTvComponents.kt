package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.model.EpgProgram

import android.view.LayoutInflater
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.luminance
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.scale
import coil3.request.allowHardware
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** True = focused items are filled with the accent color; false = tinted fill with an accent outline. */
internal val LocalLiveSolidHighlight = staticCompositionLocalOf { false }

/**
 * @param marquee scrolls single-line text that doesn't fit, like TiviMate does for the
 * focused program. Only turn it on for the focused item.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LiveText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = NuvioTheme.colors.TextPrimary,
    size: TextUnit = 16.sp,
    weight: FontWeight = FontWeight.Normal,
    maxLines: Int = 1,
    align: TextAlign? = null,
    marquee: Boolean = false
) {
    Text(
        text = text,
        modifier = if (marquee && maxLines == 1) {
            modifier.basicMarquee(
                iterations = Int.MAX_VALUE,
                initialDelayMillis = 1_200,
                repeatDelayMillis = 1_500
            )
        } else modifier,
        color = color,
        fontSize = size,
        fontWeight = weight,
        maxLines = maxLines,
        softWrap = !(marquee && maxLines == 1),
        overflow = if (marquee && maxLines == 1) TextOverflow.Clip else TextOverflow.Ellipsis,
        textAlign = align
    )
}

/**
 * Guide tiles. The theme's raised and card surfaces, which carry its tint (warm for Amber, cool
 * for Ocean…), instead of its plain neutral greys, which looked flat. When "pure black surfaces"
 * makes them black, fixed dark greys keep the tiles visible.
 */
@Composable
internal fun guideSurface(): Color {
    val c = NuvioTheme.colors.BackgroundElevated
    return if (c.luminance() < 0.003f) Color(0xFF1C1C1F) else c
}

/** Shows on now, and the group on screen: a step lighter than [guideSurface]. */
@Composable
internal fun guideSurfaceVariant(): Color {
    val c = NuvioTheme.colors.BackgroundCard
    return if (c.luminance() < 0.003f) Color(0xFF26262B) else c
}

/** Background / border / text colors for a focused or unfocused guide cell. */
internal data class LiveCellColors(val background: Color, val border: Color, val text: Color)

@Composable
internal fun liveCellColors(focused: Boolean, idle: Color, idleText: Color = NuvioTheme.colors.TextPrimary): LiveCellColors {
    if (!focused) return LiveCellColors(idle, Color.Transparent, idleText)
    return if (LocalLiveSolidHighlight.current) {
        LiveCellColors(NuvioTheme.colors.Secondary, Color.Transparent, NuvioTheme.colors.OnSecondary)
    } else {
        LiveCellColors(NuvioTheme.colors.FocusBackground, NuvioTheme.colors.FocusRing, NuvioTheme.colors.TextPrimary)
    }
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
    val colors = liveCellColors(focused, if (selected) guideSurfaceVariant() else Color.Transparent)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.background)
            .border(2.dp, colors.border, RoundedCornerShape(8.dp))
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
    if (focused && LocalLiveSolidHighlight.current) NuvioTheme.colors.OnSecondary else NuvioTheme.colors.TextPrimary

@Composable
internal fun focusedSecondaryTextColor(focused: Boolean): Color =
    if (focused && LocalLiveSolidHighlight.current) NuvioTheme.colors.OnSecondary.copy(alpha = 0.8f)
    else NuvioTheme.colors.TextSecondary

/** Logos already checked (by address): true = mostly dark, so it gets a light backing. */
private val darkLogos = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

/**
 * True when a see-through logo is mostly dark (black or navy lettering), which would disappear
 * on the guide's dark background. Looks at a grid of points, counting only the visible ones.
 */
private fun isMostlyDark(bitmap: android.graphics.Bitmap?): Boolean {
    if (bitmap == null || bitmap.width < 2 || bitmap.height < 2) return false
    val steps = 24
    var seen = 0
    var lum = 0.0
    var transparent = 0
    for (yi in 0 until steps) for (xi in 0 until steps) {
        val px = bitmap.getPixel(xi * (bitmap.width - 1) / (steps - 1), yi * (bitmap.height - 1) / (steps - 1))
        val a = android.graphics.Color.alpha(px)
        if (a < 64) { transparent++; continue }
        seen++
        lum += (0.2126 * android.graphics.Color.red(px) + 0.7152 * android.graphics.Color.green(px) + 0.0722 * android.graphics.Color.blue(px)) / 255.0
    }
    // Logos on their own solid background (no see-through parts) already show fine.
    if (seen < 12 || transparent < steps) return false
    return lum / seen < 0.30
}

/**
 * A channel logo, fitted (not cropped) into a wide box: logos are usually see-through pictures
 * of any shape. Dark ones get a soft light backing so they stay visible.
 */
@Composable
internal fun ChannelLogo(url: String?, size: Dp, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var dark by remember(url) { mutableStateOf(url?.let { darkLogos[it] } ?: false) }
    Box(
        modifier = modifier.size(width = size * 1.6f, height = size),
        contentAlignment = Alignment.Center
    ) {
        if (!url.isNullOrBlank()) {
            // A plain (not hardware) picture, so its colors can be checked once.
            val request = remember(url) {
                coil3.request.ImageRequest.Builder(context).data(url).allowHardware(false).build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onSuccess = { state ->
                    if (darkLogos[url] == null) {
                        val isDark = runCatching { isMostlyDark((state.result.image as? coil3.BitmapImage)?.bitmap) }.getOrDefault(false)
                        darkLogos[url] = isDark
                        dark = isDark
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (dark) Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.White.copy(alpha = 0.9f))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                        else Modifier
                    )
                    .padding(2.dp)
            )
        }
    }
}

/**
 * A soft glow in the theme's color behind the top of a screen (instead of show artwork, which
 * isn't always right): strongest at the top right, fading out before the bottom edge. Follows
 * the theme, kept fainter for the white theme (a white glow looks like grey haze).
 */
@Composable
internal fun ThemeGlow(modifier: Modifier = Modifier) {
    val accent = NuvioTheme.colors.Secondary
    val top = NuvioTheme.colors.BackgroundElevated
    Box(modifier = modifier.drawBehind { drawThemeGlow(accent, top, size.width, size.height) })
}

/**
 * The glow itself, for an area [w] x [h] from the top left. Also used to paint the preview's
 * rounded corners in exactly the colors behind them.
 */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawThemeGlow(accent: Color, top: Color, w: Float, h: Float) {
    val strength = if (accent.luminance() > 0.8f) 0.08f else 0.16f
    drawRect(
        Brush.verticalGradient(listOf(top.copy(alpha = 0.85f), Color.Transparent), startY = 0f, endY = h),
        size = androidx.compose.ui.geometry.Size(w, h)
    )
    val right = Offset(w * 0.80f, h * 0.04f)
    val r1 = h * 0.94f
    scale(scaleX = 2.4f, scaleY = 1f, pivot = right) {
        drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = strength), Color.Transparent), center = right, radius = r1), radius = r1, center = right)
    }
    val left = Offset(w * 0.10f, 0f)
    val r2 = h * 0.80f
    scale(scaleX = 2.2f, scaleY = 1f, pivot = left) {
        drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = strength * 0.4f), Color.Transparent), center = left, radius = r2), radius = r2, center = left)
    }
}

/** What a show's title says about it: on live, or new. Many guides only mark these in the title. */
internal data class TitleMarks(val title: String, val live: Boolean, val isNew: Boolean)

private val LIVE_MARK = Regex("""^\s*live\s*[:\-–|]\s*""", RegexOption.IGNORE_CASE)
private val NEW_MARK = Regex("""^\s*new\s*[:\-–|]\s*|\s*[(\[]\s*new\s*[)\]]\s*""", RegexOption.IGNORE_CASE)

internal fun titleMarks(title: String): TitleMarks {
    var t = title
    val live = LIVE_MARK.containsMatchIn(t)
    if (live) t = t.replace(LIVE_MARK, "")
    val isNew = NEW_MARK.containsMatchIn(t)
    if (isNew) t = t.replace(NEW_MARK, " ").trim()
    return TitleMarks(t.ifBlank { title }, live, isNew)
}

/** The red of the LIVE tag (the same everywhere, whatever the theme). */
internal val LIVE_RED = Color(0xFFE53935)

/** A small rounded tag on a program or in the info panel (LIVE, NEW, HD…). */
@Composable
internal fun LiveTag(text: String, background: Color, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(background)
            .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        Text(text = text, color = color, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
}

/** Centered modal panel used by the context menu, program info and text input dialogs. */
@Composable
internal fun LiveDialog(
    onDismiss: () -> Unit,
    width: Dp = 520.dp,
    content: @Composable () -> Unit
) {
    // A dialog opened by a long press appears while OK is still held. Swallow the rest of that
    // press (its repeats and its release) so it can't click the first menu item and close the menu.
    var sawKeyDown by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .onPreviewKeyEvent { e ->
                    when (e.type) {
                        KeyEventType.KeyDown -> {
                            if (!sawKeyDown && e.nativeKeyEvent.repeatCount > 0) {
                                true
                            } else {
                                sawKeyDown = true
                                false
                            }
                        }
                        KeyEventType.KeyUp -> !sawKeyDown
                        else -> false
                    }
                }
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
    resizeMode: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT,
    /** Nuvio's picture-size mode (full screen only); null keeps the plain [resizeMode]. */
    aspectMode: com.nuvio.tv.ui.screens.player.AspectMode? = null,
    /** Called when this view takes over the picture (the player's watchdog checks it starts). */
    onAttached: () -> Unit = {}
) {
    val layout = if (useSurfaceView) R.layout.live_tv_surface_player_view else R.layout.live_tv_texture_player_view
    var viewRef by remember { mutableStateOf<PlayerView?>(null) }
    val currentMode = androidx.compose.runtime.rememberUpdatedState(aspectMode)
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            (LayoutInflater.from(ctx).inflate(layout, null) as PlayerView).also { viewRef = it }
        },
        update = { view ->
            if (view.player !== player) {
                view.player = player
                if (player != null) onAttached()
            }
            view.resizeMode = if (aspectMode != null) AspectRatioFrameLayout.RESIZE_MODE_FIT else resizeMode
            aspectMode?.let { mode -> view.post { com.nuvio.tv.ui.screens.player.applyExoAspectMode(view, mode) } }
        }
    )
    // Re-apply the picture size when the video's shape changes (new channel, different resolution).
    DisposableEffect(viewRef, aspectMode != null) {
        val view = viewRef
        if (view == null || aspectMode == null) return@DisposableEffect onDispose { }
        val remove = com.nuvio.tv.ui.screens.player.addExoAspectLayoutChangeListener(view) { _, _, _, _, _, _, _, _, _ ->
            currentMode.value?.let { m -> view.post { com.nuvio.tv.ui.screens.player.applyExoAspectMode(view, m) } }
        }
        onDispose { remove() }
    }
    DisposableEffect(Unit) {
        onDispose { viewRef?.player = null }
    }
}

/** Pauses Live TV when the app goes to the background (Home button) and resumes on return. */
@Composable
internal fun PauseLiveTvInBackground(playback: com.nuvio.tv.livetv.player.LiveTvPlaybackController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    DisposableEffect(context, playback) {
        var c: android.content.Context? = context
        while (c is android.content.ContextWrapper && c !is androidx.lifecycle.LifecycleOwner) c = c.baseContext
        val lifecycle = (c as? androidx.lifecycle.LifecycleOwner)?.lifecycle
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                // Pause as soon as the app stops being the one in front (not only once it's fully
                // hidden): some launchers, notably ones showing a live wallpaper, leave the app
                // "visible behind" them, so it never got the fully-hidden signal and kept playing.
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE, androidx.lifecycle.Lifecycle.Event.ON_STOP -> playback.onAppBackground()
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> playback.onAppForeground()
                else -> Unit
            }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
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

/**
 * True only once [value] has stayed true for [delayMs]: "Loading…" then shows only for channels
 * that are actually slow, not for the second or so every channel change takes.
 */
@Composable
internal fun rememberDelayedTrue(value: Boolean, delayMs: Long): Boolean {
    var shown by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(value) {
        if (!value) { shown = false; return@LaunchedEffect }
        kotlinx.coroutines.delay(delayMs)
        shown = true
    }
    return value && shown
}

/** Fetches a show's full details (description, cast…) when the guide only has its short form. */
internal val LocalShowDetails = androidx.compose.runtime.staticCompositionLocalOf<suspend (String, EpgProgram) -> EpgProgram> { { _, p -> p } }

/** [program] with its details filled in (shown straight away, details a moment later if needed). */
@Composable
internal fun rememberShowDetails(channelKey: String?, program: EpgProgram?): EpgProgram? {
    val loader = LocalShowDetails.current
    val state = androidx.compose.runtime.produceState(initialValue = program, channelKey, program) {
        value = program
        if (channelKey != null && program != null) value = loader(channelKey, program)
    }
    return state.value
}

/** Looks up what a 24/7 channel plays (see LiveTvViewModel.channelAbout). */
internal val LocalChannelAbout = androidx.compose.runtime.staticCompositionLocalOf<suspend (com.nuvio.tv.livetv.model.LiveChannel, String?) -> com.nuvio.tv.domain.model.MetaPreview?> { { _, _ -> null } }

/**
 * What a 24/7 channel plays (description, year, genres), when the channel has no real listing
 * for now. Null for normal channels and while looking it up.
 */
@Composable
internal fun rememberChannelAbout(channel: com.nuvio.tv.livetv.model.LiveChannel?, program: EpgProgram?): com.nuvio.tv.domain.model.MetaPreview? {
    val loader = LocalChannelAbout.current
    val noListing = channel != null && com.nuvio.tv.livetv.data.LiveTvPosterResolver.isChannelFiller(channel, program)
    val state = androidx.compose.runtime.produceState<com.nuvio.tv.domain.model.MetaPreview?>(null, channel?.key, noListing, program?.title) {
        value = if (channel != null && noListing) loader(channel, program?.title) else null
    }
    return state.value
}

/** "2014– · Comedy, Drama · ★ 8.1" for a 24/7 channel's show. */
internal fun aboutLine(m: com.nuvio.tv.domain.model.MetaPreview): String =
    listOfNotNull(
        (m.releaseInfo ?: m.released)?.takeIf { it.isNotBlank() },
        m.genres.take(2).joinToString(", ").takeIf { it.isNotBlank() },
        m.imdbRating?.let { "★ " + String.format(java.util.Locale.US, "%.1f", it) }
    ).joinToString("  ·  ")
