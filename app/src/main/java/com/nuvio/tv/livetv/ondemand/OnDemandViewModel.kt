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
    val query: String = ""
)

/** Where selecting a title leads. */
sealed interface OnDemandTarget {
    /** Found in one of your addon catalogs: open it on Nuvio's details page. */
    data class Details(val itemId: String, val itemType: String, val addonBaseUrl: String?) : OnDemandTarget
    /** Not in your catalogs: show the provider's own details. */
    data class Provider(val item: VodItem) : OnDemandTarget
}

@HiltViewModel
class OnDemandViewModel @Inject constructor(
    private val repository: OnDemandRepository,
    private val prefs: LiveTvPreferences,
    private val resolver: LiveTvPosterResolver,
    private val tmdb: com.nuvio.tv.core.tmdb.TmdbService
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

    private val _ui = MutableStateFlow(OnDemandUiState())
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
        val hit = resolver.matchFor(matchTitle(item), item.kind == VodKind.SERIES, item.year)
        repository.cachePoster(item, hit?.meta?.poster)
        val poster = hit?.meta?.poster ?: item.icon
        if (poster != null) posters[item.uid] = poster
        return poster
    }

    suspend fun targetFor(item: VodItem): OnDemandTarget {
        val type = if (item.kind == VodKind.SERIES) "series" else "movie"
        val hit = resolver.matchFor(matchTitle(item), item.kind == VodKind.SERIES, item.year)
        // One TMDB call per title you open (remembered), to confirm the match or open by id.
        val imdb = imdbFor(item)
        return when {
            hit != null && (imdb == null || hit.meta.imdbId == imdb || hit.meta.id == imdb) ->
                OnDemandTarget.Details(hit.meta.id, hit.meta.rawType.ifBlank { hit.type }, hit.addonBaseUrl)
            // Known by id: Nuvio opens it with your own metadata addons.
            imdb != null -> OnDemandTarget.Details(imdb, type, null)
            hit != null -> OnDemandTarget.Details(hit.meta.id, hit.meta.rawType.ifBlank { hit.type }, hit.addonBaseUrl)
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
            val sections = listOf(OnDemandSection.All, OnDemandSection.Recent) + visible
            val s = prefs.settings.first()
            excludedNow = user.vodHiddenCategories + all.filter { c ->
                ParentalControls.isLocked("vod:${c.uid}", c.name, s, user.lockedGroups)
            }.map { it.uid }
            val selected = _ui.value.selectedKey.takeIf { k -> keepSelection && sections.any { it.key == k } }
                ?: OnDemandSection.All.key
            _ui.value = _ui.value.copy(
                sections = sections,
                selectedKey = selected,
                movieCount = runCatching { repository.categories(VodKind.MOVIE).sumOf { it.count } }.getOrDefault(0),
                seriesCount = runCatching { repository.categories(VodKind.SERIES).sumOf { it.count } }.getOrDefault(0)
            )
            loadItems(reset = true)
        }
    }

    /** Hidden categories, plus locked ones (kept out of All, Recently added and search). */
    private fun excluded(): Set<String> = excludedNow

    private fun loadItems(reset: Boolean) {
        loadJob?.cancel()
        val state = _ui.value
        val offset = if (reset) 0 else state.items.size
        _ui.value = state.copy(loading = true, items = if (reset) emptyList() else state.items, endReached = if (reset) false else state.endReached)
        loadJob = viewModelScope.launch {
            val section = state.sections.firstOrNull { it.key == state.selectedKey } ?: OnDemandSection.All
            val page = when {
                state.query.isNotBlank() -> repository.search(state.kind, state.query, excluded())
                section is OnDemandSection.Category -> repository.items(state.kind, section.category, emptySet(), false, PAGE, offset)
                else -> repository.items(state.kind, null, excluded(), section == OnDemandSection.Recent, PAGE, offset)
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
        const val PAGE = 120
        const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
    }
}
