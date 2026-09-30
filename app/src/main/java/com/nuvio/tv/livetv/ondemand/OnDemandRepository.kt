package com.nuvio.tv.livetv.ondemand

import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.model.PlaylistSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** One episode of a provider series, ready to play. */
data class VodEpisode(
    val season: Int,
    val episode: Int,
    val id: String,
    val ext: String,
    val title: String,
    val plot: String?,
    val image: String?,
    val url: String
)

/** What the provider says about a movie or series (for the fallback detail screen). */
data class VodInfo(
    val plot: String?,
    val cover: String?,
    val backdrop: String?,
    val genre: String?,
    val releaseDate: String?,
    val durationText: String?,
    val episodes: List<VodEpisode> = emptyList()
)

data class OnDemandStatus(val loading: Boolean = false, val message: String? = null)

/**
 * Imports movies and series from Xtream logins that have "Import movies & series" on, keeps them
 * in [OnDemandDatabase], and turns them into playable links, including the "Watch On Demand"
 * streams that appear on Nuvio's own movie and episode screens.
 */
@Singleton
class OnDemandRepository @Inject constructor(
    private val db: OnDemandDatabase,
    private val prefs: LiveTvPreferences
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.MINUTES)
        .build()

    private val _hasContent = MutableStateFlow(false)
    /** True when at least one provider's movies or series are imported (shows the menu item). */
    val hasContent: StateFlow<Boolean> = _hasContent.asStateFlow()

    private val _version = MutableStateFlow(0)
    /** Goes up after every import, so screens reload. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private val _status = MutableStateFlow(OnDemandStatus())
    val status: StateFlow<OnDemandStatus> = _status.asStateFlow()

    private val infoCache = ConcurrentHashMap<String, VodInfo>()
    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch {
            _hasContent.value = runCatching { db.hasAny() }.getOrDefault(false)
            refresh(force = false)
        }
    }

    /** Imports new providers and drops ones that were turned off (no re-download of the rest). */
    fun refreshSoon() {
        scope.launch { kotlinx.coroutines.delay(1_500); refresh(force = false) }
    }

    fun refreshNow() {
        scope.launch { refresh(force = true) }
    }

    /** Imports providers that are due (or all, when [force]); drops ones that were turned off. */
    suspend fun refresh(force: Boolean) = mutex.withLock {
        val playlists = prefs.playlists.first().filter { it.enabled && it.isXtream && it.importVod }
        val keep = playlists.map { it.id }.toSet()
        runCatching { db.removeExcept(keep) }
        val stamps = prefs.onDemandImportTimes()
        val now = System.currentTimeMillis()
        for (pl in playlists) {
            val last = stamps[pl.id] ?: 0L
            if (!force && now - last < REFRESH_MS) continue
            _status.value = OnDemandStatus(true, "Importing movies and series from ${pl.name}…")
            val ok = runCatching {
                importKind(pl, VodKind.MOVIE)
                importKind(pl, VodKind.SERIES)
            }.onFailure { Log.w(TAG, "On Demand import for ${pl.name} failed", it) }.isSuccess
            if (ok) prefs.setOnDemandImportTime(pl.id, System.currentTimeMillis())
            _version.value++
        }
        _hasContent.value = runCatching { db.hasAny() }.getOrDefault(false)
        _status.value = OnDemandStatus(false, null)
    }

    private fun importKind(pl: PlaylistSource, kind: VodKind) {
        val api = pl.xtreamApiUrl()
        val catAction = if (kind == VodKind.MOVIE) "get_vod_categories" else "get_series_categories"
        val listAction = if (kind == VodKind.MOVIE) "get_vod_streams" else "get_series"
        val categories = ArrayList<Pair<String, String>>()
        runCatching {
            val arr = org.json.JSONArray(getText("$api&action=$catAction", pl.userAgent))
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                categories += c.optString("category_id") to c.optString("category_name").ifBlank { "Other" }
            }
        }
        val request = Request.Builder().url("$api&action=$listAction")
            .header("User-Agent", pl.userAgent.ifBlank { LiveTvRepository.DEFAULT_UA }).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body ?: error("Empty response")
            JsonReader(body.charStream()).use { reader ->
                if (reader.peek() != JsonToken.BEGIN_ARRAY) {
                    reader.skipValue()
                    db.replace(pl.id, kind, categories, emptySequence())
                    return
                }
                reader.beginArray()
                val items = sequence {
                    while (reader.hasNext()) {
                        val f = readFlat(reader)
                        val id = (if (kind == VodKind.MOVIE) f["stream_id"] else f["series_id"]) ?: continue
                        val name = f["name"]?.trim().orEmpty().ifBlank { f["title"].orEmpty() }
                        if (name.isBlank()) continue
                        yield(
                            VodItem(
                                playlistId = pl.id,
                                kind = kind,
                                id = id,
                                name = name,
                                icon = (if (kind == VodKind.MOVIE) f["stream_icon"] else f["cover"])?.takeIf { it.startsWith("http") },
                                categoryId = f["category_id"].orEmpty(),
                                ext = f["container_extension"]?.takeIf { it.isNotBlank() },
                                tmdbId = (f["tmdb"] ?: f["tmdb_id"])?.takeIf { it.isNotBlank() && it != "0" },
                                year = yearOf(f["year"] ?: f["releaseDate"] ?: f["release_date"] ?: name),
                                rating = f["rating"]?.takeIf { it.isNotBlank() && it != "0" },
                                addedSec = (f["added"] ?: f["last_modified"])?.toLongOrNull() ?: 0L
                            )
                        )
                    }
                }
                db.replace(pl.id, kind, categories, items)
                runCatching { reader.endArray() }
            }
        }
    }

    /** One JSON object's simple fields as strings (nested values are skipped). */
    private fun readFlat(reader: JsonReader): Map<String, String> {
        val f = HashMap<String, String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            when (reader.peek()) {
                JsonToken.STRING, JsonToken.NUMBER -> f[name] = reader.nextString()
                JsonToken.BOOLEAN -> f[name] = reader.nextBoolean().toString()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return f
    }

    // ------------------------------------------------------------------ reading

    suspend fun categories(kind: VodKind): List<VodCategory> = withContext(Dispatchers.IO) {
        runCatching { db.categories(kind) }.getOrDefault(emptyList())
    }

    suspend fun items(kind: VodKind, category: VodCategory?, hidden: Set<String>, newestFirst: Boolean, limit: Int, offset: Int) =
        withContext(Dispatchers.IO) {
            runCatching { db.items(kind, category, hidden, newestFirst, limit, offset) }.getOrDefault(emptyList())
        }

    suspend fun search(kind: VodKind, text: String, hidden: Set<String>) = withContext(Dispatchers.IO) {
        runCatching { db.search(kind, text, hidden) }.getOrDefault(emptyList())
    }

    private suspend fun playlist(id: String): PlaylistSource? = prefs.playlists.first().firstOrNull { it.id == id }

    /** Movie link: /movie/user/pass/<id>.<ext> */
    suspend fun movieUrl(item: VodItem): String? {
        val pl = playlist(item.playlistId) ?: return null
        return "${pl.xtreamBase()}/movie/${enc(pl.xtreamUsername)}/${enc(pl.xtreamPassword)}/${item.id}.${item.ext ?: "mp4"}"
    }

    /** Provider details for a movie or series (series include every episode). Cached. */
    suspend fun info(item: VodItem): VodInfo? = withContext(Dispatchers.IO) {
        infoCache[item.uid]?.let { return@withContext it }
        val pl = playlist(item.playlistId) ?: return@withContext null
        val api = pl.xtreamApiUrl()
        val action = if (item.kind == VodKind.MOVIE) "get_vod_info&vod_id=${item.id}" else "get_series_info&series_id=${item.id}"
        val json = runCatching { JSONObject(getText("$api&action=$action", pl.userAgent)) }.getOrNull() ?: return@withContext null
        val info = json.optJSONObject("info") ?: JSONObject()
        val episodes = ArrayList<VodEpisode>()
        json.optJSONObject("episodes")?.let { byseason ->
            byseason.keys().forEach { seasonKey ->
                val arr = byseason.optJSONArray(seasonKey) ?: return@forEach
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    val id = e.optString("id").ifBlank { continue }
                    val ext = e.optString("container_extension").ifBlank { "mp4" }
                    val season = e.optString("season").toIntOrNull() ?: seasonKey.toIntOrNull() ?: 1
                    val num = e.optString("episode_num").toIntOrNull() ?: (i + 1)
                    val einfo = e.optJSONObject("info")
                    episodes += VodEpisode(
                        season = season,
                        episode = num,
                        id = id,
                        ext = ext,
                        title = e.optString("title").ifBlank { "Episode $num" },
                        plot = einfo?.optString("plot")?.takeIf { it.isNotBlank() },
                        image = einfo?.optString("movie_image")?.takeIf { it.startsWith("http") },
                        url = "${pl.xtreamBase()}/series/${enc(pl.xtreamUsername)}/${enc(pl.xtreamPassword)}/$id.$ext"
                    )
                }
            }
        }
        val result = VodInfo(
            plot = info.optString("plot").ifBlank { info.optString("description") }.takeIf { it.isNotBlank() },
            cover = (info.optString("movie_image").ifBlank { info.optString("cover") }).takeIf { it.startsWith("http") },
            backdrop = info.optJSONArray("backdrop_path")?.optString(0)?.takeIf { it.startsWith("http") },
            genre = info.optString("genre").takeIf { it.isNotBlank() },
            releaseDate = (info.optString("releasedate").ifBlank { info.optString("releaseDate") }).takeIf { it.isNotBlank() },
            durationText = info.optString("duration").takeIf { it.isNotBlank() },
            episodes = episodes.sortedWith(compareBy({ it.season }, { it.episode }))
        )
        infoCache[item.uid] = result
        result
    }

    // ------------------------------------------------------------------ "Watch On Demand"

    /** A playable On Demand copy of something you opened in Nuvio. */
    data class OnDemandSource(val label: String, val url: String, val headers: Map<String, String>)

    /**
     * Finds [title] in your providers' catalogs: by TMDB id when both sides have one, otherwise
     * by name (and year when known). For series, [season]/[episode] pick the episode.
     */
    suspend fun sourcesFor(
        type: String,
        tmdbId: String?,
        title: String,
        year: Int?,
        season: Int?,
        episode: Int?
    ): List<OnDemandSource> = withContext(Dispatchers.IO) {
        val kind = when (type.lowercase()) {
            "movie" -> VodKind.MOVIE
            "series", "tv" -> VodKind.SERIES
            else -> return@withContext emptyList()
        }
        if (!_hasContent.value) return@withContext emptyList()
        val candidates = LinkedHashMap<String, VodItem>()
        tmdbId?.let { id -> runCatching { db.byTmdb(kind, id) }.getOrDefault(emptyList()).forEach { candidates[it.uid] = it } }
        if (title.isNotBlank()) {
            runCatching { db.byTitle(kind, title) }.getOrDefault(emptyList())
                .filter { year == null || it.year == null || kotlin.math.abs(it.year - year) <= 1 }
                .forEach { candidates.putIfAbsent(it.uid, it) }
        }
        val out = ArrayList<OnDemandSource>()
        for (item in candidates.values.take(6)) {
            val pl = playlist(item.playlistId) ?: continue
            val headers = if (pl.userAgent.isNotBlank()) mapOf("User-Agent" to pl.userAgent) else emptyMap()
            if (kind == VodKind.MOVIE) {
                movieUrl(item)?.let { out += OnDemandSource("${pl.name} · ${(item.ext ?: "mp4").uppercase()}", it, headers) }
            } else if (season != null && episode != null) {
                val ep = info(item)?.episodes?.firstOrNull { it.season == season && it.episode == episode } ?: continue
                out += OnDemandSource("${pl.name} · S${season}E${episode} · ${ep.ext.uppercase()}", ep.url, headers)
            }
        }
        out
    }

    // ------------------------------------------------------------------ helpers

    private fun getText(url: String, userAgent: String): String {
        val request = Request.Builder().url(url).header("User-Agent", userAgent.ifBlank { LiveTvRepository.DEFAULT_UA }).build()
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            return r.body?.string() ?: error("Empty response")
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8")

    private fun yearOf(s: String?): Int? =
        s?.let { Regex("""(19|20)\d{2}""").find(it)?.value?.toIntOrNull() }

    companion object {
        private const val TAG = "OnDemand"
        /** Re-import each provider once a day. */
        private const val REFRESH_MS = 24L * 60 * 60 * 1000
    }
}
