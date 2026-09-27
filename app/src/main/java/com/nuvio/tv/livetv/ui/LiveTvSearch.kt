package com.nuvio.tv.livetv.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
    /** The programme to show on the card: what's on now, or the upcoming show that matched. */
    val program: EpgProgram?,
    val matchedProgram: Boolean
)

class LiveTvSearchResults(
    val hits: List<LiveSearchHit>,
    val use24h: Boolean,
    val open: (LiveSearchHit) -> Unit
)

private const val MAX_HITS = 40
private const val UPCOMING_WINDOW_MS = 6L * 60 * 60 * 1000

/**
 * Live TV matches for Nuvio's search screen: channels whose name matches, then channels
 * showing (now or in the next few hours) a programme whose title matches.
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
            onOpened()
        }
    )
}

/** A search-results row in Nuvio's style: title, then a horizontal list of channel cards. */
@Composable
fun LiveTvSearchRow(
    results: LiveTvSearchResults,
    modifier: Modifier = Modifier
) {
    if (results.hits.isEmpty()) return
    val use24h = results.use24h
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        LiveText(
            "Live TV",
            modifier = Modifier.padding(start = 52.dp, bottom = 10.dp),
            size = 20.sp,
            weight = FontWeight.SemiBold
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 52.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(results.hits, key = { it.channel.key }) { hit ->
                LiveSearchCard(hit = hit, use24h = use24h, onClick = { results.open(hit) })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiveSearchCard(hit: LiveSearchHit, use24h: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val colors = liveCellColors(focused = focused, idle = guideSurface())
    val now = System.currentTimeMillis()
    val p = hit.program
    val programLine = when {
        p == null -> "No programme information"
        now >= p.startMs && now < p.stopMs -> "Now · ${p.title}"
        else -> "${formatDayClock(p.startMs, now, use24h)} · ${p.title}"
    }
    Column(
        modifier = Modifier
            .width(250.dp)
            .height(128.dp)
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
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(hit.channel.logo, 36.dp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                LiveText(hit.channel.name, color = colors.text, size = 15.sp, weight = FontWeight.SemiBold, marquee = focused)
                LiveText(
                    "Ch ${hit.channel.number} · ${hit.channel.group}",
                    color = focusedSecondaryTextColor(focused),
                    size = 12.sp,
                    marquee = focused
                )
            }
        }
        Column {
            LiveText(programLine, color = colors.text, size = 13.sp, marquee = focused)
            if (p != null && now >= p.startMs && now < p.stopMs) {
                Spacer(Modifier.height(6.dp))
                ProgressBar(p.progress(now), Modifier.fillMaxWidth())
            } else {
                Spacer(Modifier.size(0.dp, 10.dp))
            }
        }
    }
}
