package com.nuvio.tv.livetv.ondemand

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.tv.livetv.parental.ParentalControls
import com.nuvio.tv.livetv.ui.LiveDialog
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.MenuItem
import com.nuvio.tv.livetv.ui.TextInputDialog
import com.nuvio.tv.livetv.ui.focusedSecondaryTextColor
import com.nuvio.tv.livetv.ui.guideSurface
import com.nuvio.tv.livetv.ui.guideSurfaceVariant
import com.nuvio.tv.livetv.ui.liveCellColors
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * On Demand: your IPTV providers' movies and series. Categories on the left (long-press for
 * Manage visibility and parental locks), posters on the right. Titles your own addons know open
 * on Nuvio's details page with your metadata; others open the provider's details.
 */
@Composable
fun OnDemandScreen(
    onOpenDetail: (itemId: String, itemType: String, addonBaseUrl: String?, onDemandUid: String) -> Unit,
    onPlay: (url: String, title: String, type: String, poster: String?) -> Unit,
    viewModel: OnDemandViewModel = hiltViewModel()
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val user by viewModel.user.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val unlocked by ParentalControls.unlocked.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val gridFocus = remember { FocusRequester() }
    val gridStateHolder = remember { androidx.compose.runtime.mutableStateOf<androidx.compose.foundation.lazy.grid.LazyGridState?>(null) }

    var searchOpen by remember { mutableStateOf(false) }
    var sectionMenu by remember { mutableStateOf<OnDemandSection?>(null) }
    var pinFor by remember { mutableStateOf<OnDemandSection?>(null) }
    var pinError by remember { mutableStateOf(false) }
    var pinRemovesLock by remember { mutableStateOf(false) }
    var providerItem by remember { mutableStateOf<VodItem?>(null) }
    // Opening a title: shows "Opening…" and ignores other presses until it's ready (Back cancels).
    var opening by remember { mutableStateOf<VodItem?>(null) }
    var openJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // Manage visibility: every category (hidden ones too) and which are set to hidden.
    var visibilityList by remember { mutableStateOf<List<OnDemandSection.Category>?>(null) }
    var hiddenPending by remember { mutableStateOf<Set<String>>(emptySet()) }

    val firstFocus = remember { FocusRequester() }
    val selectedFocus = remember { FocusRequester() }
    // The category list slides away when you move into the posters (Right), and comes back
    // when you press Left from the first column.
    var categoriesOpen by remember { mutableStateOf(true) }
    var focusedPosterIndex by remember { mutableStateOf(0) }
    // Back from the posters: bring the categories back first.
    androidx.activity.compose.BackHandler(enabled = !categoriesOpen) {
        categoriesOpen = true
        scope.launch { delay(80); runCatching { selectedFocus.requestFocus() } }
    }
    LaunchedEffect(Unit) { delay(200); runCatching { firstFocus.requestFocus() } }

    fun openSection(section: OnDemandSection) {
        if (viewModel.isLocked(section)) {
            pinError = false
            pinRemovesLock = false
            pinFor = section
        } else viewModel.select(section)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
            .padding(start = 48.dp, end = 24.dp, top = 20.dp)
    ) {
        // Header: title, Movies / Series, Search
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LiveText("On Demand", size = 26.sp, weight = FontWeight.Bold, modifier = Modifier.padding(end = 14.dp))
            Pill("Movies${if (ui.movieCount > 0) " · ${ui.movieCount}" else ""}", ui.kind == VodKind.MOVIE) { viewModel.selectKind(VodKind.MOVIE) }
            Pill("Series${if (ui.seriesCount > 0) " · ${ui.seriesCount}" else ""}", ui.kind == VodKind.SERIES) { viewModel.selectKind(VodKind.SERIES) }
            Pill(if (ui.query.isNotBlank()) "Search: ${ui.query}" else "Search", ui.query.isNotBlank(), icon = true) { searchOpen = true }
            Spacer(Modifier.weight(1f))
            status.message?.let { LiveText(it, color = NuvioTheme.colors.TextSecondary, size = 12.sp) }
        }
        Spacer(Modifier.height(14.dp))

        Row(modifier = Modifier.fillMaxSize()) {
            // ---------------------------------------------------------- categories
            val sections: List<OnDemandSection> = visibilityList ?: ui.sections
            val listState = rememberLazyListState()
            var focusedKey by remember { mutableStateOf<String?>(null) }
            // Like the Live TV groups: highlighting a category opens it (after a short pause,
            // so scrolling past doesn't load every one). Locked ones still wait for OK and the PIN.
            LaunchedEffect(focusedKey) {
                val key = focusedKey ?: return@LaunchedEffect
                if (visibilityList != null || key == ui.selectedKey) return@LaunchedEffect
                delay(350)
                val section = ui.sections.firstOrNull { it.key == key } ?: return@LaunchedEffect
                if (!viewModel.isLocked(section)) viewModel.select(section)
            }
            val panelWidth by androidx.compose.animation.core.animateDpAsState(
                if (categoriesOpen || visibilityList != null) 250.dp else 0.dp,
                androidx.compose.animation.core.tween(220), label = "categories"
            )
            if (panelWidth > 1.dp) LazyColumn(
                state = listState,
                modifier = Modifier
                    .width(panelWidth)
                    .fillMaxHeight()
                    .onPreviewKeyEvent { e ->
                        if (visibilityList == null) {
                            // Right: into the posters; the category list slides away.
                            if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight && ui.items.isNotEmpty()) {
                                categoriesOpen = false
                                scope.launch { delay(60); runCatching { gridFocus.requestFocus() } }
                                return@onPreviewKeyEvent true
                            }
                            return@onPreviewKeyEvent false
                        }
                        val isOk = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                        when {
                            isOk && e.type == KeyEventType.KeyUp -> {
                                focusedKey?.let { k -> hiddenPending = if (k in hiddenPending) hiddenPending - k else hiddenPending + k }
                                true
                            }
                            isOk -> true
                            e.type != KeyEventType.KeyDown -> e.key == Key.Back || e.key == Key.DirectionLeft || e.key == Key.DirectionRight
                            e.key == Key.DirectionLeft -> { hiddenPending = visibilityList.orEmpty().map { it.key }.toSet(); true }
                            e.key == Key.DirectionRight -> { hiddenPending = emptySet(); true }
                            e.key == Key.Back -> {
                                viewModel.saveVisibility(visibilityList.orEmpty(), hiddenPending)
                                visibilityList = null
                                true
                            }
                            else -> false
                        }
                    },
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(sections, key = { it.key }) { section ->
                    val label = when (section) {
                        OnDemandSection.All -> if (ui.kind == VodKind.MOVIE) "All movies" else "All series"
                        OnDemandSection.Recent -> "Recently added"
                        is OnDemandSection.Category -> section.category.name
                    }
                    val sub = (section as? OnDemandSection.Category)?.playlistName
                    val count = (section as? OnDemandSection.Category)?.category?.count
                    // (reading [unlocked] here keeps the lock icons up to date after a PIN is entered)
                    val locked = visibilityList == null && unlocked.size >= 0 && viewModel.isLocked(section)
                    CategoryRow(
                        label = label,
                        sub = sub,
                        count = count,
                        selected = section.key == ui.selectedKey && visibilityList == null,
                        locked = locked,
                        visible = if (visibilityList != null) section.key !in hiddenPending else null,
                        modifier = when {
                            section == sections.first() && section.key == ui.selectedKey ->
                                Modifier.focusRequester(firstFocus).focusRequester(selectedFocus)
                            section == sections.first() -> Modifier.focusRequester(firstFocus)
                            section.key == ui.selectedKey -> Modifier.focusRequester(selectedFocus)
                            else -> Modifier
                        },
                        onFocused = { focusedKey = section.key },
                        onClick = { if (visibilityList == null) openSection(section) },
                        onLongClick = { if (visibilityList == null) sectionMenu = section }
                    )
                }
            }

            if (panelWidth > 1.dp) Spacer(Modifier.width(18.dp))

            // ---------------------------------------------------------- posters
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .onPreviewKeyEvent { e ->
                        // Left from the first column: bring the categories back.
                        if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft && !categoriesOpen) {
                            val info = gridStateHolder.value?.layoutInfo
                            val firstRowY = info?.visibleItemsInfo?.firstOrNull()?.offset?.y
                            val columns = info?.visibleItemsInfo?.count { it.offset.y == firstRowY }?.coerceAtLeast(1) ?: 1
                            if (focusedPosterIndex % columns == 0) {
                                categoriesOpen = true
                                scope.launch { delay(80); runCatching { selectedFocus.requestFocus() } }
                                return@onPreviewKeyEvent true
                            }
                        }
                        false
                    }
            ) {
                val gridState = rememberLazyGridState()
                gridStateHolder.value = gridState
                LaunchedEffect(gridState, ui.items.size) {
                    snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                        .collect { last -> if (last >= ui.items.size - 24) viewModel.loadMore() }
                }
                LaunchedEffect(ui.selectedKey, ui.kind, ui.query, ui.sort) { runCatching { gridState.scrollToItem(0) } }
                when {
                    visibilityList != null -> VisibilityHelp(Modifier.align(Alignment.Center))
                    ui.items.isEmpty() && ui.loading -> LiveText("Loading…", color = NuvioTheme.colors.TextSecondary, modifier = Modifier.align(Alignment.Center))
                    ui.items.isEmpty() -> LiveText(
                        when {
                            status.loading -> "Importing your provider's catalog. This can take a minute the first time."
                            ui.query.isNotBlank() -> "Nothing matches \"${ui.query}\"."
                            else -> "Nothing here yet."
                        },
                        color = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.align(Alignment.Center),
                        maxLines = 3
                    )
                    else -> Column(Modifier.fillMaxSize()) {
                      // Sort bar for the list on screen.
                      LazyRow(
                          horizontalArrangement = Arrangement.spacedBy(8.dp),
                          modifier = Modifier.padding(bottom = 10.dp)
                      ) {
                          items(VodSort.values().toList()) { option ->
                              Pill(option.label, option == ui.sort) { viewModel.setSort(option) }
                          }
                      }
                      LazyVerticalGrid(
                        state = gridState,
                        modifier = Modifier.focusRequester(gridFocus),
                        // Smaller posters, so more fit on screen.
                        columns = GridCells.Adaptive(96.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(bottom = 32.dp, end = 12.dp, top = 4.dp)
                    ) {
                        itemsIndexed(ui.items, key = { _, it -> it.uid }) { index, item ->
                            PosterCard(item, viewModel, onFocused = {
                                focusedPosterIndex = index
                                categoriesOpen = false
                            }) {
                                if (opening != null) return@PosterCard
                                opening = item
                                openJob = scope.launch {
                                    try {
                                        when (val t = viewModel.targetFor(item)) {
                                            is OnDemandTarget.Details -> onOpenDetail(t.itemId, t.itemType, t.addonBaseUrl, t.uid)
                                            is OnDemandTarget.Provider -> providerItem = t.item
                                        }
                                    } finally {
                                        opening = null
                                    }
                                }
                            }
                        }
                    }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ dialogs

    if (searchOpen) {
        TextInputDialog(
            title = if (ui.kind == VodKind.MOVIE) "Search movies" else "Search series",
            initial = ui.query,
            hint = "Title",
            confirmLabel = "Search",
            onDismiss = { searchOpen = false },
            onConfirm = { viewModel.search(it); searchOpen = false }
        )
    }

    sectionMenu?.let { section ->
        val first = remember { FocusRequester() }
        LiveDialog(onDismiss = { sectionMenu = null }, width = 420.dp) {
            LiveText(
                (section as? OnDemandSection.Category)?.category?.name ?: "Categories",
                size = 20.sp, weight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MenuItem("Manage visibility", Modifier.focusRequester(first)) {
                    sectionMenu = null
                    scope.launch {
                        val all = viewModel.allCategories()
                        visibilityList = all
                        hiddenPending = user.vodHiddenCategories intersect all.map { it.key }.toSet()
                        delay(80)
                        runCatching { firstFocus.requestFocus() }
                    }
                }
                if (section is OnDemandSection.Category && ParentalControls.enabled(settings)) {
                    val lockedNow = "vod:${section.category.uid}" in user.lockedGroups
                    MenuItem(if (lockedNow) "Remove parental lock" else "Lock with PIN") {
                        if (lockedNow) { pinError = false; pinRemovesLock = true; pinFor = section; sectionMenu = null }
                        else { viewModel.setLocked(section, true); sectionMenu = null }
                    }
                }
            }
        }
        LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
    }

    pinFor?.let { section ->
        TextInputDialog(
            title = if (pinError) "Wrong PIN, try again" else "Enter your PIN",
            initial = "",
            hint = "4-digit PIN",
            confirmLabel = "Unlock",
            numeric = true,
            onDismiss = { pinFor = null },
            onConfirm = { pin ->
                if (pinRemovesLock) {
                    if (ParentalControls.checkPin(pin, settings)) { viewModel.setLocked(section, false); pinFor = null } else pinError = true
                } else if (viewModel.unlock(section, pin)) {
                    pinFor = null
                    viewModel.select(section)
                } else pinError = true
            }
        )
    }

    opening?.let { item ->
        androidx.activity.compose.BackHandler { openJob?.cancel(); opening = null }
        // Take focus so presses can't reach the posters underneath while opening.
        val blockFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { blockFocus.requestFocus() } }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .focusRequester(blockFocus)
                .focusable()
                .onPreviewKeyEvent { e ->
                    // Swallow everything except Back while opening.
                    e.key != Key.Back
                },
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.8f))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                com.nuvio.tv.livetv.ui.LoadingSpinner()
                Spacer(Modifier.width(10.dp))
                LiveText("Opening ${OnDemandDatabase.displayTitle(item.name)}…", color = Color.White, size = 14.sp)
            }
        }
    }

    providerItem?.let { item ->
        ProviderDetailDialog(
            item = item,
            viewModel = viewModel,
            onDismiss = { providerItem = null },
            onPlay = { url, title, poster ->
                providerItem = null
                onPlay(url, title, if (item.kind == VodKind.MOVIE) "movie" else "series", poster)
            }
        )
    }
}

// ==================================================================== pieces

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Pill(label: String, selected: Boolean, icon: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val colors = liveCellColors(focused = focused, idle = if (selected) guideSurfaceVariant() else Color.Transparent)
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(colors.background)
            .border(1.dp, if (focused) colors.border else NuvioTheme.colors.Border, shape)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon) {
            Icon(Icons.Default.Search, contentDescription = null, tint = colors.text, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
        }
        LiveText(label, color = colors.text, size = 14.sp, weight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CategoryRow(
    label: String,
    sub: String?,
    count: Int?,
    selected: Boolean,
    locked: Boolean,
    visible: Boolean?,
    modifier: Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val colors = liveCellColors(focused = focused, idle = if (selected) guideSurfaceVariant() else Color.Transparent)
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(colors.background)
            .border(2.dp, colors.border, shape)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            LiveText(label, color = colors.text, size = 14.sp, weight = if (selected) FontWeight.SemiBold else FontWeight.Normal, marquee = focused)
            sub?.let { LiveText(it, color = focusedSecondaryTextColor(focused), size = 10.sp) }
        }
        when {
            visible != null -> Icon(
                if (visible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                contentDescription = null,
                tint = if (visible) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextTertiary,
                modifier = Modifier.size(16.dp)
            )
            locked -> Icon(Icons.Default.Lock, contentDescription = "Locked", tint = focusedSecondaryTextColor(focused), modifier = Modifier.size(14.dp))
            count != null -> LiveText(count.toString(), color = focusedSecondaryTextColor(focused), size = 11.sp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PosterCard(item: VodItem, viewModel: OnDemandViewModel, onFocused: () -> Unit = {}, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    // The provider's image straight away; your addon's poster once the card has been on
    // screen for a moment (scrolling past doesn't trigger lookups).
    val poster by produceState<String?>(initialValue = item.icon, item.uid) {
        delay(600)
        viewModel.posterFor(item)?.let { value = it }
    }
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(shape)
                .background(guideSurface())
                .border(if (focused) 3.dp else 0.dp, if (focused) NuvioTheme.colors.FocusRing else Color.Transparent, shape),
            contentAlignment = Alignment.Center
        ) {
            LiveText(OnDemandDatabase.displayTitle(item.name), color = NuvioTheme.colors.TextSecondary, size = 12.sp, maxLines = 3, modifier = Modifier.padding(8.dp))
            if (!poster.isNullOrBlank()) {
                AsyncImage(model = poster, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.height(4.dp))
        LiveText(OnDemandDatabase.displayTitle(item.name), size = 11.sp, color = if (focused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary, marquee = focused)
        item.year?.let { LiveText(it.toString(), size = 9.sp, color = NuvioTheme.colors.TextTertiary) }
    }
}

@Composable
private fun VisibilityHelp(modifier: Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(NuvioTheme.colors.BackgroundElevated)
            .border(1.dp, NuvioTheme.colors.Border, RoundedCornerShape(10.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        LiveText("Manage visibility", size = 16.sp, weight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        LiveText("OK: show or hide category", color = NuvioTheme.colors.TextSecondary, size = 13.sp)
        LiveText("Left: hide all", color = NuvioTheme.colors.TextSecondary, size = 13.sp)
        LiveText("Right: show all", color = NuvioTheme.colors.TextSecondary, size = 13.sp)
        LiveText("Back: save and return", color = NuvioTheme.colors.TextSecondary, size = 13.sp)
    }
}

/** For titles none of your addons know: the provider's own details, and Play. */
@Composable
private fun ProviderDetailDialog(
    item: VodItem,
    viewModel: OnDemandViewModel,
    onDismiss: () -> Unit,
    onPlay: (url: String, title: String, poster: String?) -> Unit
) {
    val scope = rememberCoroutineScope()
    val info by produceState<VodInfo?>(initialValue = null, item.uid) { value = viewModel.info(item) }
    val poster by produceState<String?>(initialValue = item.icon, item.uid) { viewModel.posterFor(item)?.let { value = it } }
    var season by remember { mutableStateOf<Int?>(null) }
    val first = remember { FocusRequester() }
    LiveDialog(onDismiss = onDismiss, width = 760.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(
                modifier = Modifier.width(150.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(guideSurface()),
                contentAlignment = Alignment.Center
            ) {
                val img = poster ?: info?.cover
                if (!img.isNullOrBlank()) AsyncImage(model = img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Column(modifier = Modifier.weight(1f)) {
                LiveText(OnDemandDatabase.displayTitle(item.name), size = 22.sp, weight = FontWeight.Bold, maxLines = 2)
                val meta = listOfNotNull(
                    item.year?.toString() ?: info?.releaseDate?.take(4),
                    info?.genre,
                    info?.durationText,
                    item.rating?.let { "★ $it" }
                ).joinToString("  ·  ")
                if (meta.isNotBlank()) LiveText(meta, color = NuvioTheme.colors.TextSecondary, size = 13.sp)
                Spacer(Modifier.height(8.dp))
                LiveText(info?.plot ?: if (info == null) "Loading details…" else "", color = NuvioTheme.colors.TextSecondary, size = 13.sp, maxLines = 6)
                Spacer(Modifier.height(12.dp))
                if (item.kind == VodKind.MOVIE) {
                    MenuItem("Play", Modifier.focusRequester(first)) {
                        scope.launch { viewModel.movieUrl(item)?.let { onPlay(it, item.name, poster) } }
                    }
                } else {
                    val episodes = info?.episodes.orEmpty()
                    val seasons = episodes.map { it.season }.distinct()
                    val current = season ?: seasons.firstOrNull()
                    if (seasons.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(seasons) { s ->
                                Pill("Season $s", s == current) { season = s }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(modifier = Modifier.heightIn(max = 220.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            items(episodes.filter { it.season == current }, key = { it.id }) { ep ->
                                MenuItem(
                                    "${ep.episode}.  ${ep.title}",
                                    if (ep == episodes.firstOrNull { it.season == current }) Modifier.focusRequester(first) else Modifier
                                ) {
                                    onPlay(ep.url, "${item.name} · S${ep.season}E${ep.episode}", poster)
                                }
                            }
                        }
                    } else if (info != null) {
                        LiveText("The provider didn't list any episodes.", color = NuvioTheme.colors.TextSecondary, size = 13.sp)
                    }
                }
            }
        }
    }
    LaunchedEffect(info) { delay(80); runCatching { first.requestFocus() } }
}
