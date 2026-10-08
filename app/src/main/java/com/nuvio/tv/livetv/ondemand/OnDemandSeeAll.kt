@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.livetv.ondemand

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * "See all" for an On Demand row, in Nuvio's own See all style (the same grid of poster cards
 * Nuvio shows for an addon catalog). More titles load as you scroll down; Back returns to the row.
 */
@Composable
internal fun OnDemandSeeAll(
    row: CatalogRow?,
    posterCardStyle: PosterCardStyle,
    showLabels: Boolean,
    onOpen: (metaId: String) -> Unit,
    onFocus: (MetaPreview) -> Unit,
    onLoadMore: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }

    val gridState = rememberLazyGridState()
    val firstFocus = remember { FocusRequester() }
    var focusedOnce by remember { mutableStateOf(false) }
    val items = row?.items.orEmpty()

    // More titles when you get near the end.
    LaunchedEffect(gridState, items.size, row?.hasMore) {
        snapshotFlow {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible to gridState.layoutInfo.totalItemsCount
        }
            .distinctUntilChanged()
            .collect { (lastVisible, total) ->
                if (total > 0 && lastVisible >= total - 10 && row?.hasMore == true) onLoadMore()
            }
    }

    // Start on the first poster.
    LaunchedEffect(items.isNotEmpty()) {
        if (items.isEmpty() || focusedOnce) return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        runCatching { firstFocus.requestFocus(); focusedOnce = true }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
            .padding(vertical = NuvioTheme.spacing.xl)
    ) {
        Text(
            text = row?.catalogName ?: "On Demand",
            style = MaterialTheme.typography.headlineLarge,
            color = NuvioTheme.colors.TextPrimary,
            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.xxxl)
        )
        Spacer(Modifier.height(NuvioTheme.spacing.xl))

        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
            return@Column
        }

        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = posterCardStyle.width),
            contentPadding = PaddingValues(
                start = NuvioTheme.spacing.xxxl,
                end = NuvioTheme.spacing.xl,
                top = NuvioTheme.spacing.md,
                bottom = NuvioTheme.spacing.xxl
            ),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
        ) {
            itemsIndexed(items, key = { index, item -> "$index:${item.id}" }) { index, item ->
                GridContentCard(
                    item = item,
                    posterCardStyle = posterCardStyle,
                    showLabel = showLabels,
                    focusRequester = if (index == 0) firstFocus else null,
                    onFocused = { onFocus(item) },
                    onClick = { onOpen(item.id) }
                )
            }
            if (row?.hasMore == true) {
                item(key = "loading_more") {
                    Box(
                        modifier = Modifier.padding(top = NuvioTheme.spacing.lg),
                        contentAlignment = Alignment.Center
                    ) { LoadingIndicator() }
                }
            }
        }
    }
}
