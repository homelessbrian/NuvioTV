package com.nuvio.tv.livetv.ondemand

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.livetv.data.LiveTvPosterResolver
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.LiveUserState
import com.nuvio.tv.livetv.parental.ParentalControls
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/** The left-hand list: "All", "Recently added", then the providers' categories. */
sealed interface OnDemandSection {
    val key: String
    data object All : OnDemandSection { override val key = "__all__" }
    data object Recent : OnDemandSection { override val key = "__recent__" }
    data class Category(val category: VodCategory, val playlistName: String?) : OnDemandSection {
        override val key: String get() = category.uid
    }
    /** Browsing by genre (from the provider's genre tags). */
    data class Genre(val name: String, val count: Int) : OnDemandSection {
        override val key: String get() = "genre:$name"
    }
}

data class OnDemandUiState(
    val kind: VodKind = VodKind.MOVIE,
    val movieCount: Int = 0,
    val seriesCount: Int = 0,
    val sections: List<OnDemandSection> = listOf(OnDemandSection.All, OnDemandSection.Recent),
    val selectedKey: String = OnDemandSection.All.key,
    val items: List<VodItem> = emptyList(),
    val loading: Boolean = true,
    val endReached: Boolean = false,
    val query: String = "",
    val sort: VodSort = VodSort.DEFAULT,
    /** Left list shows genres instead of the provider's categories. */
    val byGenre: Boolean = false,
    /** Whether this kind has genre information at all (shows the Genres switch). */
    val hasGenres: Boolean = false
)

/** Where selecting a title leads. */
sealed interface OnDemandTarget {
    /** Found in one of your addon catalogs: open it on Nuvio's details page. */
    data class Details(val itemId: String, val itemType: String, val addonBaseUrl: String?, val uid: String) : OnDemandTarget
    /** Not in your catalogs: show the provider's own details. */
    data class Provider(val item: VodItem) : OnDemandTarget
}

@HiltViewModel
class OnDemandViewModel @Inject constructor(
    private val repository: OnDemandRepository,
    private val prefs: LiveTvPreferences,
    private val resolver: LiveTvPosterResolver,
    private val tmdb: com.nuvio.tv.core.tmdb.TmdbService,
    private val metaRepository: com.nuvio.tv.domain.repository.MetaRepository,
    private val trailerService: com.nuvio.tv.data.trailer.TrailerService
) : ViewModel() {

    private val imdbIds = com.nuvio.tv.livetv.data.boundedCache<String, String>(2_000)

    /** The provider's TMDB id as an IMDb id (what Nuvio and its addons use), if it has one. */
    private suspend fun imdbFor(item: VodItem): String? {
        imdbIds[item.uid]?.let { return it.ifBlank { null } }
        val id = item.tmdbId?.toIntOrNull()?.let { t ->
            runCatching { tmdb.tmdbToImdb(t, if (item.kind == VodKind.SERIES) "tv" else "movie") }.getOrNull()
        }
        imdbIds[item.uid] = id.orEmpty()
        return id
    }

    private fun matchTitle(item: VodItem) = OnDemandDatabase.displayTitle(item.name)

    /**
     * Titles to try in your addons: the cleaned title, then the part after a " - " or ": " when
     * there is one ("007 - A View to a Kill" -> "A View to a Kill", "Marvel - Iron Man" ->
     * "Iron Man"), for series-style names the provider puts in front.
     */
    private fun matchTitles(item: VodItem): List<String> {
        val clean = matchTitle(item)
        val after = Regex("""^.{1,40}?\s(?:-|–|—|:)\s+(.+)$""").find(clean)?.groupValues?.get(1)?.trim()
        return listOfNotNull(clean, after?.takeIf { it.length >= 2 }).distinct()
    }

    private suspend fun matchAny(item: VodItem, urgent: Boolean): LiveTvPosterResolver.Hit? {
        for (t in matchTitles(item)) {
            resolver.matchFor(t, item.kind == VodKind.SERIES, item.year, urgent = urgent)?.let { return it }
        }
        return null
    }

    private val names = com.nuvio.tv.livetv.data.boundedCache<String, String>(3_000)

    /** The proper name from your addons once matched ("A View to a Kill"), else the cleaned title. */
    suspend fun nameFor(item: VodItem): String =
        names[item.uid] ?: repository.cachedTitle(item)?.also { names[item.uid] = it } ?: OnDemandDatabase.displayTitle(item.name)

    /** Every version of a title (qualities, categories, providers), best quality first. */
    suspend fun versions(item: VodItem) = repository.versions(item)

    private val _ui = MutableStateFlow(OnDemandUiState(sort = lastSort))
    val ui: StateFlow<OnDemandUiState> = _ui.asStateFlow()

    val settings: StateFlow<LiveTvSettings> =
        prefs.settings.stateIn(viewModelScope, SharingStarted.Eagerly, LiveTvSettings())
    val user: StateFlow<LiveUserState> =
        prefs.userState.stateIn(viewModelScope, SharingStarted.Eagerly, LiveUserState())
    val status = repository.status
    private val playlistNames: StateFlow<Map<String, String>> = prefs.playlists
        .map { l -> l.associate { it.id to it.name } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private var loadJob: Job? = null
    /** Hidden and locked categories as of the last reload (the settings flow can lag behind). */
    @Volatile private var excludedNow: Set<String> = emptySet()
    private val posters = com.nuvio.tv.livetv.data.boundedCache<String, String>(3_000)

    init {
        repository.start()
        viewModelScope.launch { repository.version.collect { reload() } }
        // New genres from the background fill: refresh the lists, keep your place.
        viewModelScope.launch { repository.genreUpdates.drop(1).collect { refreshGenres() } }
        // Only reload when hidden or locked categories actually change (not on every saved setting).
        viewModelScope.launch {
            prefs.userState.map { it.vodHiddenCategories to it.lockedGroups }
                .distinctUntilChanged()
                .drop(1)
                .collect { reload(keepSelection = true) }
        }
        viewModelScope.launch { ParentalControls.unlocked.drop(1).collect { reload(keepSelection = true) } }
    }

    fun selectKind(kind: VodKind) {
        if (kind == _ui.value.kind) return
        _ui.value = _ui.value.copy(kind = kind, selectedKey = OnDemandSection.All.key, query = "")
        reload()
    }

    fun select(section: OnDemandSection) {
        _ui.value = _ui.value.copy(selectedKey = section.key, query = "")
        loadItems(reset = true)
    }

    fun search(text: String) {
        _ui.value = _ui.value.copy(query = text.trim())
        loadItems(reset = true)
    }

    fun loadMore() {
        val s = _ui.value
        if (s.loading || s.endReached || s.query.isNotBlank()) return
        loadItems(reset = false)
    }

    fun refreshNow() = repository.refreshNow()

    /** New genres arrived: update the genre list (and counts) without reloading the posters. */
    private fun refreshGenres() {
        viewModelScope.launch {
            val genres = repository.genres(_ui.value.kind)
            val cur = _ui.value
            if (!cur.byGenre) {
                _ui.value = cur.copy(hasGenres = genres.isNotEmpty())
                return@launch
            }
            val head = cur.sections.filter { it !is OnDemandSection.Genre }
            _ui.value = cur.copy(
                hasGenres = genres.isNotEmpty(),
                sections = head + genres.map { (g, n) -> OnDemandSection.Genre(g, n) }
            )
        }
    }

    fun toggleGenres() {
        _ui.value = _ui.value.copy(byGenre = !_ui.value.byGenre, selectedKey = OnDemandSection.All.key)
        reload()
    }

    fun setSort(sort: VodSort) {
        if (sort == _ui.value.sort) return
        lastSort = sort
        _ui.value = _ui.value.copy(sort = sort)
        loadItems(reset = true)
    }

    // ------------------------------------------------------------------ locks and hiding

    /** Categories that need the PIN right now. */
    fun isLocked(section: OnDemandSection): Boolean {
        val c = (section as? OnDemandSection.Category)?.category ?: return false
        return ParentalControls.isLocked("vod:${c.uid}", c.name, settings.value, user.value.lockedGroups)
    }

    fun unlock(section: OnDemandSection, pin: String): Boolean {
        val c = (section as? OnDemandSection.Category)?.category ?: return true
        return ParentalControls.unlock("vod:${c.uid}", pin, settings.value)
    }

    fun setLocked(section: OnDemandSection, locked: Boolean) {
        val c = (section as? OnDemandSection.Category)?.category ?: return
        viewModelScope.launch { prefs.setGroupLocked("vod:${c.uid}", locked) }
    }

    /** Every category of this kind, hidden ones included (Manage visibility). */
    suspend fun allCategories(): List<OnDemandSection.Category> {
        val names = playlistNames.value
        val multi = repository.categories(_ui.value.kind).map { it.playlistId }.distinct().size > 1
        return repository.categories(_ui.value.kind).map { OnDemandSection.Category(it, if (multi) names[it.playlistId] else null) }
    }

    fun saveVisibility(all: List<OnDemandSection.Category>, hidden: Set<String>) {
        val ids = all.map { it.key }.toSet()
        viewModelScope.launch { prefs.updateVodHiddenCategories(show = ids - hidden, hide = hidden intersect ids) }
    }

    // ------------------------------------------------------------------ opening and posters

    /** Your own addon's poster when it has the title; otherwise the provider's. */
    suspend fun posterFor(item: VodItem): String? {
        posters[item.uid]?.let { return it }
        // "Use posters from my addons" off: the provider's image, no lookups at all.
        if (!settings.value.onDemandAddonPosters) return item.icon
        // Remembered from an earlier visit (kept between app starts).
        repository.cachedPoster(item)?.let { (cached, checked) ->
            val fresh = System.currentTimeMillis() - checked < WEEK_MS
            if (cached.isNotBlank()) { posters[item.uid] = cached; return cached }
            if (fresh) return item.icon
        }
        // Posters only need the cleaned title (no TMDB call per poster); the id is looked up
        // once, when you actually open a title.
        val hit = matchAny(item, urgent = false)
        repository.cachePoster(item, hit?.meta?.poster, hit?.meta?.name)
        hit?.meta?.name?.let { names[item.uid] = it }
        // The same match carries the title's genres: keep them for "By genre".
        hit?.meta?.genres?.takeIf { it.isNotEmpty() }?.let { repository.fillGenres(item, it) }
        val poster = hit?.meta?.poster ?: item.icon
        if (poster != null) posters[item.uid] = poster
        return poster
    }

    suspend fun targetFor(item: VodItem): OnDemandTarget {
        val type = if (item.kind == VodKind.SERIES) "series" else "movie"
        // The catalog search and the TMDB id lookup run at the same time, and neither may hold
        // the screen up for long: past a few seconds we open with whatever we have.
        val (hit, imdb) = kotlinx.coroutines.coroutineScope {
            val idJob = async { kotlinx.coroutines.withTimeoutOrNull(3_000) { imdbFor(item) } }
            val hitJob = async {
                kotlinx.coroutines.withTimeoutOrNull(4_000) { matchAny(item, urgent = true) }
            }
            hitJob.await() to idJob.await()
        }
        hit?.meta?.genres?.takeIf { it.isNotEmpty() }?.let { repository.fillGenres(item, it) }
        return when {
            hit != null && (imdb == null || hit.meta.imdbId == imdb || hit.meta.id == imdb) ->
                OnDemandTarget.Details(hit.meta.id, hit.meta.rawType.ifBlank { hit.type }, hit.addonBaseUrl, item.uid)
            // Known by id: Nuvio opens it with your own metadata addons.
            imdb != null -> OnDemandTarget.Details(imdb, type, null, item.uid)
            hit != null -> OnDemandTarget.Details(hit.meta.id, hit.meta.rawType.ifBlank { hit.type }, hit.addonBaseUrl, item.uid)
            else -> OnDemandTarget.Provider(item)
        }
    }

    suspend fun info(item: VodItem): VodInfo? = repository.info(item)
    suspend fun movieUrl(item: VodItem): String? = repository.movieUrl(item)

    // ------------------------------------------------------------------ loading

    private fun reload(keepSelection: Boolean = false) {
        viewModelScope.launch {
            val kind = _ui.value.kind
            val user = prefs.userState.first()
            val names = playlistNames.value
            val all = repository.categories(kind)
            val multi = all.map { it.playlistId }.distinct().size > 1
            val visible = all.filter { it.uid !in user.vodHiddenCategories }
                .map { OnDemandSection.Category(it, if (multi) names[it.playlistId] else null) }
            val genres = repository.genres(kind)
            val byGenre = _ui.value.byGenre && genres.isNotEmpty()
            val sections = listOf(OnDemandSection.All, OnDemandSection.Recent) +
                if (byGenre) genres.map { (g, n) -> OnDemandSection.Genre(g, n) } else visible
            val s = prefs.settings.first()
            excludedNow = user.vodHiddenCategories + all.filter { c ->
                ParentalControls.isLocked("vod:${c.uid}", c.name, s, user.lockedGroups)
            }.map { it.uid }
            val selected = _ui.value.selectedKey.takeIf { k -> keepSelection && sections.any { it.key == k } }
                ?: OnDemandSection.All.key
            _ui.value = _ui.value.copy(
                sections = sections,
                selectedKey = selected,
                byGenre = byGenre,
                hasGenres = genres.isNotEmpty(),
                movieCount = runCatching { repository.categories(VodKind.MOVIE).sumOf { it.count } }.getOrDefault(0),
                seriesCount = runCatching { repository.categories(VodKind.SERIES).sumOf { it.count } }.getOrDefault(0)
            )
            loadItems(reset = true)
        }
    }

    /** Hidden categories, plus locked ones (kept out of All, Recently added and search). */
    private fun excluded(): Set<String> = excludedNow

    // ================================================================ the Nuvio-style page

    private val _home = MutableStateFlow(VodHomeState())
    /** Rows (your provider's groups) for the On Demand page in Nuvio's home layouts. */
    val home: StateFlow<VodHomeState> = _home

    private val homeItems = java.util.concurrent.ConcurrentHashMap<String, List<VodItem>>()
    private val homeEnded = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val homeMatched = java.util.concurrent.ConcurrentHashMap<String, com.nuvio.tv.domain.model.MetaPreview>()
    private val homeTried = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var homeGroups: List<Pair<VodCategory, String>> = emptyList()
    private var homeEnrichJob: kotlinx.coroutines.Job? = null

    /** Builds the page: every group in your order, hidden and locked ones left out. */
    fun loadHome() {
        viewModelScope.launch {
            val user = prefs.userState.first()
            val cats = repository.categories(VodKind.MOVIE) + repository.categories(VodKind.SERIES)
            val orderIndex = user.vodGroupOrder.withIndex().associate { (i, uid) -> uid to i }
            val ordered = cats.withIndex()
                .sortedWith(compareBy({ orderIndex[it.value.uid] ?: Int.MAX_VALUE }, { it.index }))
                .map { it.value }
            val entries = ordered.map { c ->
                VodGroupEntry(
                    uid = c.uid,
                    defaultName = c.name,
                    name = user.vodGroupNames[c.uid]?.takeIf { it.isNotBlank() } ?: c.name,
                    visible = c.uid !in user.vodHiddenCategories,
                    kind = c.kind
                )
            }
            val visible = ordered.filter { c ->
                c.uid !in user.vodHiddenCategories &&
                    !ParentalControls.isLocked("vod:${c.uid}", c.name, settings.value, user.lockedGroups)
            }
            homeGroups = visible.map { it to (user.vodGroupNames[it.uid]?.takeIf { n -> n.isNotBlank() } ?: it.name) }
            homeItems.clear(); homeEnded.clear()
            // The first page of every group (each a quick read from the device's catalog).
            for ((c, _) in homeGroups) {
                val page = repository.items(c.kind, c, emptySet(), false, HOME_PAGE, 0, merge = settings.value.vodMergeDuplicates)
                primePosters(page)
                homeItems[c.uid] = page
                if (page.size < HOME_PAGE) homeEnded += c.uid
            }
            _home.value = VodHomeState(rows = buildHomeRows(), groups = entries, loading = false)
            enrichHome()
        }
    }

    /** More titles for one group, as you scroll along it. */
    fun loadMoreHome(uid: String) {
        if (uid in homeEnded) return
        val (c, _) = homeGroups.firstOrNull { it.first.uid == uid } ?: return
        viewModelScope.launch {
            val have = homeItems[uid].orEmpty()
            val more = repository.items(c.kind, c, emptySet(), false, HOME_PAGE, have.size, merge = settings.value.vodMergeDuplicates)
            if (more.size < HOME_PAGE) homeEnded += uid
            primePosters(more)
            homeItems[uid] = have + more
            _home.value = _home.value.copy(rows = buildHomeRows())
            enrichHome()
        }
    }

    /** Posters already found in earlier visits (saved on the device): shown straight away. */
    private suspend fun primePosters(items: List<VodItem>) {
        for (item in items) {
            if (posters[item.uid] != null) continue
            repository.cachedPoster(item)?.first?.takeIf { it.isNotBlank() }?.let { posters[item.uid] = it }
        }
    }

    private fun buildHomeRows(): List<com.nuvio.tv.domain.model.CatalogRow> = homeGroups.mapNotNull { (c, name) ->
        val items = homeItems[c.uid].orEmpty()
        if (items.isEmpty()) return@mapNotNull null
        VodHomeIds.row(
            uid = c.uid,
            name = name,
            kind = c.kind,
            items = items.map { item ->
                val matched = homeMatched[item.uid]
                VodHomeIds.toMeta(item, matched, posters[item.uid], names[item.uid] ?: OnDemandDatabase.displayTitle(item.name))
            },
            hasMore = c.uid !in homeEnded,
            page = (items.size + HOME_PAGE - 1) / HOME_PAGE
        )
    }

    /**
     * Fills in posters, backdrops, logos and details from your addons, a few rows at a time in
     * the background (what's on screen first), so the hero and cards look like Nuvio's own.
     */
    private fun enrichHome() {
        homeEnrichJob?.cancel()
        homeEnrichJob = viewModelScope.launch {
            var changed = 0
            for ((c, _) in homeGroups) {
                for (item in homeItems[c.uid].orEmpty().take(ENRICH_PER_ROW)) {
                    if (!homeTried.add(item.uid)) continue
                    enrichOne(item)
                    if (++changed % 6 == 0) _home.value = _home.value.copy(rows = buildHomeRows())
                }
            }
            if (changed > 0) _home.value = _home.value.copy(rows = buildHomeRows())
            // Full details (clear logos) for the first few titles of the first rows.
            var detailed = 0
            for ((c, _) in homeGroups.take(3)) {
                for (item in homeItems[c.uid].orEmpty().take(6)) {
                    if (!homeHits.containsKey(item.uid) || item.uid in homeDetailed) continue
                    fetchDetails(item.uid)
                    detailed++
                }
            }
            if (detailed > 0) _home.value = _home.value.copy(rows = buildHomeRows())
        }
    }

    /** Titles whose full details (clear logo etc.) were already fetched. */
    private val homeDetailed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** Each match's addon and type, to fetch its full details. */
    private val homeHits = java.util.concurrent.ConcurrentHashMap<String, com.nuvio.tv.livetv.data.LiveTvPosterResolver.Hit>()

    /**
     * The title's full details from your addons, the way Home gets them for its hero: search
     * results only sometimes include the clear logo (and backdrop, description, rating), the
     * full details almost always do.
     */
    private suspend fun fetchDetails(uid: String) {
        if (!homeDetailed.add(uid)) return
        val hit = homeHits[uid] ?: return
        val full = runCatching {
            kotlinx.coroutines.withTimeoutOrNull(5_000) {
                // The addon the match came from (one request, not one per addon).
                metaRepository.getMeta(addonBaseUrl = hit.addonBaseUrl, type = hit.type, id = hit.meta.id)
                    .first { it !is com.nuvio.tv.core.network.NetworkResult.Loading }
            }
        }.getOrNull()
        val meta = (full as? com.nuvio.tv.core.network.NetworkResult.Success<*>)?.data as? com.nuvio.tv.domain.model.Meta
        if (meta == null) { homeDetailed.remove(uid); return }
        val base = homeMatched[uid] ?: hit.meta
        homeMatched[uid] = base.copy(
            logo = meta.logo?.takeIf { it.isNotBlank() } ?: base.logo,
            background = meta.background?.takeIf { it.isNotBlank() } ?: base.background,
            description = meta.description?.takeIf { it.isNotBlank() } ?: base.description,
            releaseInfo = meta.releaseInfo?.takeIf { it.isNotBlank() } ?: base.releaseInfo,
            imdbRating = meta.imdbRating ?: base.imdbRating,
            genres = meta.genres.takeIf { it.isNotEmpty() } ?: base.genres
        )
    }

    private suspend fun enrichOne(item: VodItem, urgent: Boolean = false) {
        val hit = runCatching { matchAny(item, urgent = urgent) }.getOrNull() ?: return
        homeHits[item.uid] = hit
        homeMatched[item.uid] = hit.meta
        hit.meta.poster?.let { posters[item.uid] = it; repository.cachePoster(item, it, hit.meta.name) }
        hit.meta.name.takeIf { it.isNotBlank() }?.let { names[item.uid] = it }
        hit.meta.genres.takeIf { it.isNotEmpty() }?.let { repository.fillGenres(item, it) }
    }

    /** A card was highlighted: its details right away (the hero), if not found yet. */
    fun focusHome(metaId: String) {
        val uid = VodHomeIds.uidOf(metaId) ?: return
        if (uid in homeDetailed) return
        val item = homeItems.values.asSequence().flatten().firstOrNull { it.uid == uid } ?: return
        homeTried += uid
        viewModelScope.launch {
            if (!homeMatched.containsKey(uid)) enrichOne(item, urgent = true)
            if (!homeMatched.containsKey(uid)) return@launch
            _home.value = _home.value.copy(rows = buildHomeRows())
            // Then the full details (clear logo…) for the hero.
            fetchDetails(uid)
            _home.value = _home.value.copy(rows = buildHomeRows())
        }
    }

    /** The matching title in your addons behind a card (its real id: trailers, like on Home). */
    fun matchedMeta(metaId: String): com.nuvio.tv.domain.model.MetaPreview? =
        VodHomeIds.uidOf(metaId)?.let { homeMatched[it] }

    // ------------------------------------------------------------ trailers (On Demand's own)

    /**
     * Trailers for the On Demand page, found with Nuvio's own trailer service but kept here,
     * apart from Home's. (Borrowing Home's left Home holding On Demand's trailer: Home's hero
     * kept its picture, and two trailer players ran at once until Android closed the app.)
     * Keyed by the card's id.
     */
    val trailerUrls = androidx.compose.runtime.mutableStateMapOf<String, String>()
    val trailerAudioUrls = androidx.compose.runtime.mutableStateMapOf<String, String>()
    private val trailerMisses = java.util.Collections.synchronizedSet(HashSet<String>())
    private var trailerJob: kotlinx.coroutines.Job? = null

    fun requestTrailer(cardId: String) {
        if (trailerUrls.containsKey(cardId) || cardId in trailerMisses) return
        trailerJob?.cancel()
        trailerJob = viewModelScope.launch {
            kotlinx.coroutines.delay(180) // wait for the highlight to settle
            val uid = VodHomeIds.uidOf(cardId) ?: return@launch
            val hit = homeHits[uid] ?: return@launch
            val real = homeMatched[uid] ?: hit.meta
            val year = Regex("""\b(19|20)\d{2}\b""").find(real.releaseInfo.orEmpty())?.value
            val source = runCatching {
                val tmdbId = runCatching { tmdb.ensureTmdbId(real.id, hit.type) }.getOrNull()
                trailerService.getTrailerPlaybackSource(title = real.name, year = year, tmdbId = tmdbId, type = hit.type)
                    ?: real.trailerYtIds.firstOrNull()?.let { yt ->
                        trailerService.getTrailerPlaybackSourceFromYouTubeUrl("https://www.youtube.com/watch?v=$yt", real.name, year)
                    }
            }.getOrNull()
            if (source?.videoUrl.isNullOrBlank()) { trailerMisses += cardId; return@launch }
            trailerUrls[cardId] = source!!.videoUrl
            source.audioUrl?.takeIf { it.isNotBlank() }?.let { trailerAudioUrls[cardId] = it }
        }
    }

    /** Leaving the page: no trailer lookups carry on in the background. */
    fun stopTrailers() { trailerJob?.cancel() }

    /** The title behind a card (for opening it). */
    fun homeItem(metaId: String): VodItem? {
        val uid = VodHomeIds.uidOf(metaId) ?: return null
        return homeItems.values.asSequence().flatten().firstOrNull { it.uid == uid }
    }

    /** Manage VOD Groups: save which groups show, their order and names, then rebuild. */
    fun saveGroups(entries: List<VodGroupEntry>) {
        viewModelScope.launch {
            prefs.saveVodGroups(
                hidden = entries.filter { !it.visible }.map { it.uid }.toSet(),
                order = entries.map { it.uid },
                names = entries.filter { it.name.isNotBlank() && it.name != it.defaultName }.associate { it.uid to it.name }
            )
            loadHome()
        }
    }

    /** Manage VOD Groups → Reset to Default: every group shown, provider order and names. */
    fun resetGroups() {
        viewModelScope.launch {
            prefs.saveVodGroups(hidden = emptySet(), order = emptyList(), names = emptyMap())
            loadHome()
        }
    }

    private fun loadItems(reset: Boolean) {
        loadJob?.cancel()
        val state = _ui.value
        val offset = if (reset) 0 else state.items.size
        _ui.value = state.copy(loading = true, items = if (reset) emptyList() else state.items, endReached = if (reset) false else state.endReached)
        loadJob = viewModelScope.launch {
            val section = state.sections.firstOrNull { it.key == state.selectedKey } ?: OnDemandSection.All
            val merge = settings.value.vodMergeDuplicates
            val page = when {
                state.query.isNotBlank() -> repository.search(state.kind, state.query, excluded(), state.sort, merge = merge)
                section is OnDemandSection.Category -> repository.items(state.kind, section.category, emptySet(), false, PAGE, offset, state.sort, merge = merge)
                section is OnDemandSection.Genre -> repository.items(state.kind, null, excluded(), false, PAGE, offset, state.sort, genre = section.name, merge = merge)
                else -> repository.items(state.kind, null, excluded(), section == OnDemandSection.Recent, PAGE, offset, state.sort, merge = merge)
            }
            val cur = _ui.value
            _ui.value = cur.copy(
                items = if (reset) page else cur.items + page,
                loading = false,
                endReached = state.query.isNotBlank() || page.size < PAGE || (section == OnDemandSection.Recent && offset + page.size >= 200)
            )
        }
    }

    private companion object {
        const val HOME_PAGE = 30
        const val ENRICH_PER_ROW = 12
        /** The sort you picked, kept while the app is open. */
        @Volatile var lastSort: VodSort = VodSort.DEFAULT
        const val PAGE = 120
        const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
    }
}
