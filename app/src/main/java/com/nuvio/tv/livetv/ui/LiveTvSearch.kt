package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.data.LiveTvRepository
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.produceState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.ui.theme.NuvioTheme

data class LiveSearchHit(
    val channel: LiveChannel,
    /** The program to show on the card: what's on now, or the upcoming show that matched. */
    val program: EpgProgram?,
    val matchedProgram: Boolean
)

class LiveTvSearchResults(
    val hits: List<LiveSearchHit>,
    val use24h: Boolean,
    val open: (LiveSearchHit) -> Unit,
    val posterFor: suspend (LiveSearchHit) -> String?,
    internal val viewModel: LiveTvViewModel? = null,
    /** Playlist id -> name, shown on each card when you have more than one playlist. */
    val playlistNames: Map<String, String> = emptyMap()
)

private const val MAX_HITS = 40
private const val UPCOMING_WINDOW_MS = 6L * 60 * 60 * 1000

/**
 * Live TV matches for Nuvio's search screen: channels whose name matches, then channels
 * showing (now or in the next few hours) a program whose title matches.
 * [onOpened] is called after the channel starts playing, to show the full-screen player.
 */
@Composable
fun rememberLiveTvSearchResults(
    query: String,
    onOpened: () -> Unit
): LiveTvSearchResults {
    // Live TV turned off for search (or hidden from the menu): Nuvio's search stays exactly as
    // it is without the fork — movies and series only. Live TV isn't even loaded here, so it
    // can't slow Nuvio's own search down.
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext, com.nuvio.tv.livetv.startup.LiveTvStartupEntryPoint::class.java
        ).liveTvPreferences()
    }
    val liveSettings by prefs.settings.collectAsStateWithLifecycle(initialValue = null)
    val s = liveSettings
    val liveOnly by LiveTvSearchBridge.liveOnly.collectAsStateWithLifecycle()
    // Opened from Live TV: always search Live TV, whatever the "Show in Nuvio search" setting.
    if (s == null || (!liveOnly && (!s.showInSearch || !s.showInSidebar))) {
        return remember { LiveTvSearchResults(emptyList(), false, {}, { null }) }
    }
    return rememberLiveTvSearchResultsEnabled(query, onOpened)
}

@Composable
private fun rememberLiveTvSearchResultsEnabled(
    query: String,
    onOpened: () -> Unit,
    viewModel: LiveTvViewModel = hiltViewModel()
): LiveTvSearchResults {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val programs by viewModel.programs.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()
    val playlistNames by viewModel.playlistNames.collectAsStateWithLifecycle()
    val q = query.trim()

    // Search once typing pauses (not on every letter), and off the main thread, so typing
    // stays smooth on slower devices with big playlists.
    val settled by produceState(initialValue = q, q) {
        if (q.length >= 2) delay(300)
        value = q
    }
    val hits by produceState(initialValue = emptyList<LiveSearchHit>(), settled, ui.allVisibleChannels, programs, now, settings.showInSearch) {
        val q = settled
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            if (!settings.showInSearch || q.length < 2) return@withContext emptyList()
            val out = ArrayList<LiveSearchHit>()
            val seen = HashSet<String>()
            for (c in ui.allVisibleChannels) {
                if (out.size >= MAX_HITS) break
                if (c.name.contains(q, ignoreCase = true) || c.number.toString() == q) {
                    val current = programs[c.key]?.firstOrNull { now >= it.startMs && now < it.stopMs }
                    out += LiveSearchHit(c, current, matchedProgram = false)
                    seen += c.key
                }
            }
            if (out.size < MAX_HITS) {
                for (c in ui.allVisibleChannels) {
                    if (out.size >= MAX_HITS) break
                    if (c.key in seen) continue
                    val match = programs[c.key]?.firstOrNull {
                        it.stopMs > now && it.startMs < now + UPCOMING_WINDOW_MS && it.title.contains(q, ignoreCase = true)
                    } ?: continue
                    out += LiveSearchHit(c, match, matchedProgram = true)
                }
            }
            out
        }
    }

    return LiveTvSearchResults(
        hits = hits,
        use24h = settings.use24HourClock,
        open = { hit ->
            viewModel.preview(hit.channel)
            viewModel.markFullscreenOpened()
            onOpened()
        },
        posterFor = { hit ->
            hit.program?.takeIf { !LiveTvRepository.isPlaceholderTitle(it.title) }
                ?.takeIf { viewModel.settings.value.showPosters }
                ?.let { viewModel.posterFor(it.title, it, hit.channel) ?: it.icon }
        },
        viewModel = viewModel,
        playlistNames = playlistNames
    )
}

/**
 * A search-results row in Nuvio's style: title, then a horizontal list of cards.
 * [entryFocusRequester] goes on the first card so the search screen can move focus into the row;
 * [upFocusRequester] is where D-pad up goes from the row (the search field).
 */
@Composable
fun LiveTvSearchRow(
    results: LiveTvSearchResults,
    modifier: Modifier = Modifier,
    entryFocusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null
) {
    if (results.hits.isEmpty()) return
    var menuFor by remember { mutableStateOf<LiveChannel?>(null) }
    menuFor?.let { ch ->
        results.viewModel?.let { vm -> ChannelQuickMenu(ch, vm, onDismiss = { menuFor = null }) }
    }
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        LiveText(
            "Live TV",
            modifier = Modifier.padding(start = 52.dp, bottom = 10.dp),
            size = 20.sp,
            weight = FontWeight.SemiBold
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 52.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            itemsIndexed(results.hits, key = { _, h -> h.channel.key }) { index, hit ->
                LiveSearchCard(
                    hit = hit,
                    playlistName = results.playlistNames[hit.channel.sourceId],
                    use24h = results.use24h,
                    posterFor = results.posterFor,
                    modifier = Modifier
                        .then(if (index == 0 && entryFocusRequester != null) Modifier.focusRequester(entryFocusRequester) else Modifier)
                        .focusProperties { if (upFocusRequester != null) up = upFocusRequester },
                    onClick = { results.open(hit) },
                    onLongClick = { menuFor = hit.channel }
                )
            }
        }
    }
}

/**
 * A narrow poster card (about 6 fit across): poster with a LIVE / UPCOMING badge, then the show,
 * when it's on, the channel, and the playlist it comes from.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiveSearchCard(
    hit: LiveSearchHit,
    playlistName: String?,
    use24h: Boolean,
    posterFor: suspend (LiveSearchHit) -> String?,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    val colors = liveCellColors(focused = focused, idle = guideSurface())
    val now = System.currentTimeMillis()
    val p = hit.program
    val live = p != null && now >= p.startMs && now < p.stopMs
    val poster by produceState<String?>(initialValue = p?.icon, p?.title) {
        // Give Nuvio's own addon search a head start, so poster lookups never compete with it.
        kotlinx.coroutines.delay(2_500)
        posterFor(hit)?.let { value = it }
    }
    val upcoming = p != null && p.startMs > now
    val whenLine = when {
        p == null -> null
        live -> "${minutesLeftLabel(p.stopMs, now)} · ends ${formatClock(p.stopMs, use24h)}"
        else -> "${formatDayClock(p.startMs, now, use24h)} · ${startsInLabel(p.startMs - now)}"
    }
    Column(
        modifier = modifier
            .width(150.dp)
            .clip(shape)
            .background(colors.background)
            .border(2.dp, colors.border, shape)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(6.dp)
    ) {
        // Poster as Nuvio's catalogs would show it; the channel logo when there's no match.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(206.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(guideSurfaceVariant()),
            contentAlignment = Alignment.Center
        ) {
            if (!poster.isNullOrBlank()) {
                AsyncImage(
                    model = poster,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                ChannelLogo(hit.channel.logo, 48.dp)
            }
            if (p != null && (live || upcoming)) {
                LiveText(
                    if (live) "LIVE" else "UPCOMING",
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(5.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (live) NuvioTheme.colors.Error else Color.Black.copy(alpha = 0.75f))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                    color = Color.White,
                    size = 10.sp,
                    weight = FontWeight.Bold
                )
            }
            if (live) {
                ProgressBar(p!!.progress(now), Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(6.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        LiveText(
            p?.title ?: hit.channel.name,
            modifier = Modifier.fillMaxWidth(),
            color = colors.text,
            size = 14.sp,
            weight = FontWeight.Bold,
            marquee = focused
        )
        whenLine?.let {
            LiveText(
                it,
                color = if (upcoming) NuvioTheme.colors.Secondary else focusedSecondaryTextColor(focused),
                size = 11.sp,
                weight = if (upcoming) FontWeight.SemiBold else FontWeight.Normal,
                marquee = focused
            )
        }
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(hit.channel.logo, 14.dp)
            Spacer(Modifier.width(4.dp))
            LiveText(
                "${hit.channel.number}  ${hit.channel.name}",
                color = focusedSecondaryTextColor(focused),
                size = 11.sp,
                marquee = focused
            )
        }
        playlistName?.let {
            LiveText(
                it,
                color = if (focused) focusedSecondaryTextColor(true) else NuvioTheme.colors.TextTertiary,
                size = 10.sp,
                marquee = focused
            )
        }
    }
}

private fun startsInLabel(ms: Long): String {
    val min = (ms / 60_000L).coerceAtLeast(1)
    return when {
        min < 60 -> "in $min min"
        min < 24 * 60 -> "in ${min / 60}h ${min % 60}m"
        else -> "in ${min / (24 * 60)} days"
    }
}

/**
 * Hands a program title from Live TV to Nuvio's search screen, which picks it up once and
 * runs the search. From there the normal detail page streams it through your addons / debrid.
 */
object LiveTvSearchBridge {
    /**
     * True when search was opened from Live TV (its Search button, the Search key, or the
     * channel menu): only Live TV results are shown. Search from Nuvio's menu shows everything.
     * Cleared when the search screen closes.
     */
    val liveOnly = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** The title waiting to be searched, or null. The search screen collects this. */
    val pending = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    fun request(programTitle: String) {
        pending.value = cleanTitle(programTitle).ifBlank { programTitle.trim() }
    }

    fun consume(): String? = pending.value.also { pending.value = null }

    private val leadingTags = Regex("""^\s*(new|live|premiere|repeat|re-?run|encore|movie|film)\s*[:!\-–]\s*""", RegexOption.IGNORE_CASE)
    private val bracketed = Regex("""\s*[\[(][^\])]*[\])]\s*""")
    private val episodeCode = Regex("""\b[Ss]\d{1,2}\s*[Ee]\d{1,3}\b""")
    private val trailingEpisode = Regex("""\s*[-:–]\s*(episode|ep\.?|part)\s*\d+.*$""", RegexOption.IGNORE_CASE)

    internal fun cleanTitle(raw: String): String {
        var t = raw
        repeat(2) { t = t.replace(leadingTags, "") }
        t = t.replace(bracketed, " ")
            .replace(episodeCode, " ")
            .replace(trailingEpisode, "")
        return t.replace(Regex("""\s+"""), " ").trim().trimEnd(':', '-', '–').trim()
    }
}

/**
 * Long-press menu for a Live TV search result: favorites, groups, rename, renumber, hide.
 */
@Composable
private fun ChannelQuickMenu(channel: LiveChannel, viewModel: LiveTvViewModel, onDismiss: () -> Unit) {
    var step by remember { mutableStateOf("menu") }
    val first = remember { FocusRequester() }
    when (step) {
        "rename" -> TextInputDialog(
            title = "Rename channel",
            initial = channel.name,
            hint = "Leave empty to use the playlist name",
            confirmLabel = "Save",
            onDismiss = onDismiss,
            onConfirm = { viewModel.renameChannel(channel, it); onDismiss() }
        )
        "number" -> TextInputDialog(
            title = "Channel number",
            initial = channel.number.toString(),
            hint = "Leave empty to use the playlist number",
            confirmLabel = "Save",
            numeric = true,
            onDismiss = onDismiss,
            onConfirm = { viewModel.setChannelNumber(channel, it.trim().toIntOrNull()); onDismiss() }
        )
        "newgroup" -> TextInputDialog(
            title = "New group",
            initial = "",
            hint = "Group name",
            confirmLabel = "Save",
            onDismiss = onDismiss,
            onConfirm = { name -> viewModel.createGroupWith(name, channel); onDismiss() }
        )
        "group" -> GroupPickerDialog(
            channel = channel,
            groups = viewModel.uiState.value.customGroups,
            onDismiss = onDismiss,
            onPick = { g -> viewModel.addToGroup(g.id, channel); onDismiss() },
            onNewGroup = { step = "newgroup" }
        )
        else -> {
            val isFavorite = channel.key in viewModel.userState.value.favorites
            LiveDialog(onDismiss = onDismiss, width = 440.dp) {
                LiveText("${channel.number}  ${channel.name}", size = 20.sp, weight = FontWeight.Bold)
                LiveText(channel.group, color = NuvioTheme.colors.TextSecondary, size = 13.sp)
                Spacer(Modifier.height(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MenuItem(if (isFavorite) "Remove from favorites" else "Add to favorites", Modifier.focusRequester(first)) {
                        viewModel.toggleFavorite(channel)
                        onDismiss()
                    }
                    MenuItem("Add to group…") { step = "group" }
                    MenuItem("Rename channel") { step = "rename" }
                    MenuItem("Change channel number") { step = "number" }
                    MenuItem("Hide channel") {
                        viewModel.hideChannel(channel)
                        onDismiss()
                    }
                }
            }
            LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); runCatching { first.requestFocus() } }
        }
    }
}
