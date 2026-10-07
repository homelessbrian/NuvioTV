package com.nuvio.tv.livetv.ondemand

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.screens.home.ClassicHomeContent
import com.nuvio.tv.ui.screens.home.GridHomeContent
import com.nuvio.tv.ui.screens.home.GridItem
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.ui.screens.home.HomeRow
import com.nuvio.tv.ui.screens.home.HomeScreenFocusState
import com.nuvio.tv.ui.screens.home.HomeUiState
import com.nuvio.tv.ui.screens.home.ModernCarouselRowBuildCache
import com.nuvio.tv.ui.screens.home.ModernHomeContent
import com.nuvio.tv.ui.screens.home.ModernHomePresentationInput
import com.nuvio.tv.ui.screens.home.ModernHomePresentationState
import com.nuvio.tv.ui.screens.home.buildModernHomePresentation
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * On Demand, Nuvio-home style: your provider's groups as rows, in whichever home layout Nuvio is
 * set to (Classic, Grid or Modern), with the same look and behaviour as Home — plus a settings
 * button (top right, or the Menu button) for Manage VOD Groups.
 *
 * [homeSettings] is Home's own state: every layout option (poster size, hero, labels…) comes from
 * it, so this page always matches Home.
 */
@Composable
fun OnDemandHomeScreen(
    homeSettings: HomeUiState?,
    onOpenDetail: (itemId: String, itemType: String, addonBaseUrl: String?, onDemandUid: String) -> Unit,
    onPlay: (url: String, title: String, type: String, poster: String?) -> Unit,
    onOpenBrowse: () -> Unit,
    /** Home's own trailer lookup and results (trailers follow Nuvio's trailer settings). */
    trailers: OnDemandTrailers? = null,
    viewModel: OnDemandViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.home.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadHome() }

    var showManage by remember { mutableStateOf(false) }
    var providerItem by remember { mutableStateOf<VodItem?>(null) }
    var opening by remember { mutableStateOf(false) }
    var focusState by remember { mutableStateOf(HomeScreenFocusState()) }
    var gridFocusState by remember { mutableStateOf(HomeScreenFocusState()) }

    val base = homeSettings ?: HomeUiState()
    val rows = state.rows
    val homeRows = remember(rows) { rows.map { HomeRow.Catalog(it) } }
    val gridItems = remember(rows) {
        buildList {
            rows.forEach { r ->
                add(GridItem.SectionDivider(r.catalogName, r.catalogId, r.addonBaseUrl, r.addonId, r.apiType))
                r.items.forEach { add(GridItem.Content(it, r.addonBaseUrl, r.catalogId, r.catalogName, r.addonId)) }
                if (r.hasMore) add(GridItem.SeeAll(r.catalogId, r.addonId, r.addonBaseUrl, r.apiType))
            }
        }
    }
    val modernCache = remember { ModernCarouselRowBuildCache() }
    val modernPresentation by produceState(ModernHomePresentationState(), rows, base.homeLayout) {
        if (base.homeLayout != HomeLayout.MODERN) return@produceState
        value = withContext(Dispatchers.Default) {
            buildModernHomePresentation(
                input = ModernHomePresentationInput(
                    homeRows = homeRows,
                    catalogRows = rows,
                    continueWatchingItems = emptyList(),
                    upcomingItems = emptyList(),
                    useLandscapePosters = base.modernLandscapePostersEnabled,
                    showCatalogTypeSuffix = false,
                    showFullReleaseDate = base.showFullReleaseDate,
                    showImdbRatings = base.homeImdbRatingsVisibility.showRatings,
                    localeTag = com.nuvio.tv.LocaleCache.localeTag
                ),
                cache = modernCache,
                context = context
            )
        }
    }
    // Home's settings, with this page's rows (no Continue Watching, no addon hero, no trailers:
    // those belong to Home).
    val ui = base.copy(
        catalogRows = rows,
        homeRows = homeRows,
        gridItems = gridItems,
        continueWatchingItems = emptyList(),
        upcomingItems = emptyList(),
        heroItems = emptyList(),
        heroSectionEnabled = false,
        isLoading = state.loading,
        error = null,
        modernHomePresentation = modernPresentation,
        heroEnrichmentEnabled = false,
        catalogAddonNameEnabled = false,
        catalogTypeSuffixEnabled = false
    )
    val posterCardStyle = remember(ui.posterCardWidthDp, ui.posterCardCornerRadiusDp) {
        PosterCardStyle(
            width = ui.posterCardWidthDp.dp,
            height = (ui.posterCardWidthDp * 1.5f).roundToInt().dp,
            cornerRadius = ui.posterCardCornerRadiusDp.dp,
            focusedBorderWidth = PosterCardDefaults.Style.focusedBorderWidth,
            focusedScale = PosterCardDefaults.Style.focusedScale
        )
    }

    // Opening a card: Nuvio's details page (Play there plays your provider's copy), or the
    // provider's own details for titles your addons don't know.
    val open: (String) -> Unit = { metaId ->
        val item = viewModel.homeItem(metaId)
        if (item != null && !opening) {
            opening = true
            scope.launch {
                try {
                    when (val t = viewModel.targetFor(item)) {
                        is OnDemandTarget.Details -> onOpenDetail(t.itemId, t.itemType, t.addonBaseUrl, t.uid)
                        is OnDemandTarget.Provider -> providerItem = item
                    }
                } finally { opening = false }
            }
        }
    }
    val onNavigateToDetail: (String, String, String) -> Unit = { id, _, _ -> open(id) }
    val onItemFocus: (MetaPreview) -> Unit = { viewModel.focusHome(it.id) }
    val loadMore: (String, String, String) -> Unit = { catalogId, _, _ -> viewModel.loadMoreHome(catalogId) }
    val notWatched: (MetaPreview) -> Boolean = { false }

    // Trailers, exactly as on Home: asked for with the matched title's real id, then handed to
    // the layout under each card's own id. Titles your addons don't know have no trailer.
    val trailerUrls: Map<String, String> = run {
        val found = trailers?.urls ?: return@run emptyMap()
        if (found.isEmpty()) return@run emptyMap()
        buildMap {
            rows.forEach { r -> r.items.forEach { m -> viewModel.matchedMeta(m.id)?.id?.let { real -> found[real]?.let { put(m.id, it) } } } }
        }
    }
    val trailerAudioUrls: Map<String, String> = run {
        val found = trailers?.audioUrls ?: return@run emptyMap()
        if (found.isEmpty()) return@run emptyMap()
        buildMap {
            rows.forEach { r -> r.items.forEach { m -> viewModel.matchedMeta(m.id)?.id?.let { real -> found[real]?.let { put(m.id, it) } } } }
        }
    }
    val requestTrailerById: (String, String, String?, String) -> Unit = { itemId, title, releaseInfo, apiType ->
        viewModel.matchedMeta(itemId)?.let { real -> trailers?.requestById?.invoke(real.id, title, releaseInfo, apiType) }
    }
    val requestTrailerForItem: (MetaPreview) -> Unit = { item ->
        viewModel.matchedMeta(item.id)?.let { real -> trailers?.requestItem?.invoke(real) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { e ->
                // Menu button: Manage VOD Groups, from anywhere on the page.
                if (e.type == KeyEventType.KeyDown && e.key == Key.Menu) { showManage = true; true } else false
            }
    ) {
        when {
            state.loading && rows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
            rows.isEmpty() -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                LiveText("Nothing to show yet", size = 20.sp, weight = FontWeight.SemiBold, color = NuvioTheme.colors.TextPrimary)
                Spacer(Modifier.height(6.dp))
                LiveText(
                    "Turn on On Demand for a playlist in Settings → Live TV, or show some groups with the settings button.",
                    size = 14.sp, color = NuvioTheme.colors.TextSecondary
                )
            }
            else -> when (ui.homeLayout) {
                HomeLayout.CLASSIC -> ClassicHomeContent(
                    uiState = ui,
                    posterCardStyle = posterCardStyle,
                    focusState = focusState,
                    trailerPreviewUrls = trailerUrls,
                    trailerPreviewAudioUrls = trailerAudioUrls,
                    onNavigateToDetail = onNavigateToDetail,
                    onContinueWatchingClick = {},
                    onContinueWatchingStartFromBeginning = {},
                    onContinueWatchingPlayManually = {},
                    showContinueWatchingManualPlayOption = false,
                    onNavigateToCatalogSeeAll = { _, _, _ -> onOpenBrowse() },
                    onNavigateToFolderDetail = { _, _ -> },
                    onRemoveContinueWatching = { _, _, _, _ -> },
                    isCatalogItemWatched = notWatched,
                    onCatalogItemLongPress = { _, _ -> },
                    onRequestTrailerPreview = requestTrailerForItem,
                    onItemFocus = onItemFocus,
                    catalogSeeAllLabel = "See all",
                    onSaveFocusState = { vi, vo, rk, ikm, m, ma, ri, ii ->
                        focusState = focusState.copy(
                            verticalScrollIndex = vi, verticalScrollOffset = vo, focusedRowKey = rk,
                            focusedItemKeyByRow = ikm, catalogRowScrollStates = m, catalogRowScrollAnchors = ma,
                            focusedRowIndex = ri, focusedItemIndex = ii, hasSavedFocus = true
                        )
                    },
                    onFocusedRowKeyChanged = {},
                    scrollToTopTrigger = 0,
                    onRequestLazyCatalogLoad = {}
                )
                HomeLayout.GRID -> GridHomeContent(
                    uiState = ui,
                    gridFocusState = gridFocusState,
                    onNavigateToDetail = onNavigateToDetail,
                    onContinueWatchingClick = {},
                    onContinueWatchingStartFromBeginning = {},
                    onContinueWatchingPlayManually = {},
                    showContinueWatchingManualPlayOption = false,
                    onNavigateToCatalogSeeAll = { _, _, _ -> onOpenBrowse() },
                    onNavigateToFolderDetail = { _, _ -> },
                    onRemoveContinueWatching = { _, _, _, _ -> },
                    isCatalogItemWatched = notWatched,
                    onCatalogItemLongPress = { _, _ -> },
                    posterCardStyle = posterCardStyle,
                    onItemFocus = onItemFocus,
                    catalogSeeAllLabel = "See all",
                    onSaveGridFocusState = { vi, vo, _ ->
                        gridFocusState = gridFocusState.copy(verticalScrollIndex = vi, verticalScrollOffset = vo, hasSavedFocus = true)
                    },
                    onFocusedRowKeyChanged = {},
                    scrollToTopTrigger = 0
                )
                HomeLayout.MODERN -> ModernHomeContent(
                    uiState = ui,
                    modernPresentation = modernPresentation,
                    focusState = focusState,
                    trailerPreviewUrls = trailerUrls,
                    trailerPreviewAudioUrls = trailerAudioUrls,
                    onNavigateToDetail = onNavigateToDetail,
                    onContinueWatchingClick = {},
                    onContinueWatchingStartFromBeginning = {},
                    onContinueWatchingPlayManually = {},
                    showContinueWatchingManualPlayOption = false,
                    onRequestTrailerPreview = requestTrailerById,
                    onLoadMoreCatalog = loadMore,
                    onRemoveContinueWatching = { _, _, _, _ -> },
                    isCatalogItemWatched = notWatched,
                    onCatalogItemLongPress = { _, _ -> },
                    onNavigateToFolderDetail = { _, _ -> },
                    onItemFocus = onItemFocus,
                    onPreloadAdjacentItem = {},
                    onSaveFocusState = { vi, vo, rk, ikm, m, ma, ri, ii ->
                        focusState = focusState.copy(
                            verticalScrollIndex = vi, verticalScrollOffset = vo, focusedRowKey = rk,
                            focusedItemKeyByRow = ikm, catalogRowScrollStates = m, catalogRowScrollAnchors = ma,
                            focusedRowIndex = ri, focusedItemIndex = ii, hasSavedFocus = true
                        )
                    },
                    onFocusedRowKeyChanged = {},
                    scrollToTopTrigger = 0,
                    onRequestLazyCatalogLoad = {}
                )
            }
        }

        // Top right: search everything (the full On Demand browser) and Manage VOD Groups.
        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TopButton(Icons.Default.Search, onClick = onOpenBrowse)
            TopButton(Icons.Default.Tune, onClick = { showManage = true })
        }
    }

    if (showManage) {
        ManageVodGroupsPanel(
            groups = state.groups,
            onDone = { edited -> showManage = false; viewModel.saveGroups(edited) },
            onReset = { showManage = false; viewModel.resetGroups() },
            onDismiss = { showManage = false }
        )
    }
    providerItem?.let { item ->
        ProviderDetailDialog(
            item = item,
            viewModel = viewModel,
            onDismiss = { providerItem = null },
            onPlay = { url, title, poster ->
                providerItem = null
                onPlay(url, title, if (item.kind == VodKind.SERIES) "series" else "movie", poster)
            }
        )
    }
}

@Composable
private fun TopButton(icon: ImageVector, onClick: () -> Unit) {
    val colors = NuvioTheme.colors
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(shape)
            .background(if (focused) colors.FocusRing else Color.Black.copy(alpha = 0.45f))
            .border(1.dp, Color.White.copy(alpha = if (focused) 0f else 0.18f), shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = if (focused) colors.Background else colors.TextPrimary, modifier = Modifier.size(24.dp))
    }
}

/** Home's trailer lookup, shared with the On Demand page (results keyed by the real title id). */
class OnDemandTrailers(
    val urls: Map<String, String>,
    val audioUrls: Map<String, String>,
    val requestById: (itemId: String, title: String, releaseInfo: String?, apiType: String) -> Unit,
    val requestItem: (MetaPreview) -> Unit
)
