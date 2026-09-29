package com.nuvio.tv.livetv.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
    val posterFor: suspend (LiveSearchHit) -> String?
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
    onOpened: () -> Unit,
    viewModel: LiveTvViewModel = hiltViewModel()
): LiveTvSearchResults {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val programs by viewModel.programs.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()
    val q = query.trim()

    val hits = remember(q, ui.allVisibleChannels, programs, now, settings.showInSearch) {
        if (!settings.showInSearch || q.length < 2) return@remember emptyList()
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

    return LiveTvSearchResults(
        hits = hits,
        use24h = settings.use24HourClock,
        open = { hit ->
            viewModel.preview(hit.channel)
            viewModel.markFullscreenOpened()
            onOpened()
        },
        posterFor = { hit -> hit.program?.let { viewModel.posterFor(it.title, it, hit.channel) } }
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
                    use24h = results.use24h,
                    posterFor = results.posterFor,
                    modifier = Modifier
                        .then(if (index == 0 && entryFocusRequester != null) Modifier.focusRequester(entryFocusRequester) else Modifier)
                        .focusProperties { if (upFocusRequester != null) up = upFocusRequester },
                    onClick = { results.open(hit) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiveSearchCard(
    hit: LiveSearchHit,
    use24h: Boolean,
    posterFor: suspend (LiveSearchHit) -> String?,
    modifier: Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val colors = liveCellColors(focused = focused, idle = guideSurface())
    val now = System.currentTimeMillis()
    val p = hit.program
    val live = p != null && now >= p.startMs && now < p.stopMs
    val poster by produceState<String?>(initialValue = null, p?.title) {
        value = posterFor(hit)
    }
    val upcoming = p != null && p.startMs > now
    val when_ = when {
        p == null -> null
        live -> "On now · ${formatRange(p.startMs, p.stopMs, use24h)}"
        else -> "Starts ${formatDayClock(p.startMs, now, use24h)} · ${startsInLabel(p.startMs - now)}"
    }
    Row(
        modifier = modifier
            .width(440.dp)
            .height(168.dp)
            .clip(shape)
            .background(colors.background)
            .border(2.dp, colors.border, shape)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Poster as Nuvio's catalogs would show it; the channel logo when there's no match.
        Box(
            modifier = Modifier
                .width(96.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(8.dp))
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
                ChannelLogo(hit.channel.logo, 40.dp)
            }
            // LIVE / UPCOMING badge so it's obvious at a glance whether you can watch it now.
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
        }
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            LiveText(
                p?.title ?: hit.channel.name,
                modifier = Modifier.fillMaxWidth(),
                color = colors.text,
                size = 17.sp,
                weight = FontWeight.Bold,
                marquee = focused
            )
            val meta = listOfNotNull(when_, p?.episode).joinToString("  ·  ")
            if (meta.isNotBlank()) {
                LiveText(
                    meta,
                    // Upcoming start times stand out in the accent color.
                    color = if (upcoming) NuvioTheme.colors.Secondary else focusedSecondaryTextColor(focused),
                    size = 12.sp,
                    weight = if (upcoming) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.padding(top = 2.dp),
                    marquee = focused
                )
            }
            if (live) {
                Spacer(Modifier.height(6.dp))
                ProgressBar(p!!.progress(now), Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(6.dp))
            LiveText(
                p?.description ?: "",
                color = focusedSecondaryTextColor(focused),
                size = 13.sp,
                maxLines = 3,
                modifier = Modifier.weight(1f)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelLogo(hit.channel.logo, 18.dp)
                Spacer(Modifier.width(6.dp))
                LiveText(
                    "${hit.channel.number}  ${hit.channel.name}",
                    color = focusedSecondaryTextColor(focused),
                    size = 12.sp,
                    marquee = focused
                )
            }
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
