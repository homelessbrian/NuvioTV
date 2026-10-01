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

/** What the provider reports about the file itself (from its own media scan). */
data class VodTech(
    val width: Int?,
    val height: Int?,
    val videoCodec: String?,
    val fps: Double?,
    val hdr: String?,
    val audioCodec: String?,
    val channels: Int?,
    val language: String?,
    val bitrateKbps: Int?,
    val durationSecs: Int?
) {
    /** "4K", "1080p", "720p" or "SD". */
    val quality: String?
        get() {
            val h = height ?: return null
            val w = width ?: 0
            return when {
                h >= 1600 || w >= 3200 -> "4K"
                h >= 1000 || w >= 1800 -> "1080p"
                h >= 700 || w >= 1200 -> "720p"
                h > 0 -> "SD"
                else -> null
            }
        }

    /** Size worked out from bitrate × length (providers rarely give the file size). */
    val estimatedBytes: Long?
        get() {
            val br = bitrateKbps ?: return null
            val d = durationSecs ?: return null
            if (br <= 0 || d <= 0) return null
            return br.toLong() * 1000L / 8L * d
        }
}

/** One episode of a provider series, ready to play. */
data class VodEpisode(
    val season: Int,
    val episode: Int,
    val id: String,
    val ext: String,
    val title: String,
    val plot: String?,
    val image: String?,
    val url: String,
    val tech: VodTech? = null
)

/** What the provider says about a movie or series (for the fallback detail screen). */
data class VodInfo(
    val plot: String?,
    val cover: String?,
    val backdrop: String?,
    val genre: String?,
    val releaseDate: String?,
    val durationText: String?,
    val episodes: List<VodEpisode> = emptyList(),
    val tech: VodTech? = null
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
        // Big catalogs (100,000+ titles) on slow panels can take a long time to send.
        .callTimeout(40, TimeUnit.MINUTES)
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
    /** The last import problem, shown in settings (cleared by a clean import). */
    @Volatile private var lastProblem: String? = null
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
            lastProblem = null
            // Movies and series import separately, so a problem with one keeps the other.
            val movies = runCatching { importKind(pl, VodKind.MOVIE) }
                .onFailure { Log.w(TAG, "Movies for ${pl.name} failed", it); lastProblem = "Couldn't import movies from ${pl.name}: ${it.message ?: it.javaClass.simpleName}" }
            val series = runCatching { importKind(pl, VodKind.SERIES) }
                .onFailure { Log.w(TAG, "Series for ${pl.name} failed", it); lastProblem = "Couldn't import series from ${pl.name}: ${it.message ?: it.javaClass.simpleName}" }
            val ok = movies.isSuccess && series.isSuccess && lastProblem == null
            // Success: next import in a day. Failure: try again in an hour, not on every start.
            prefs.setOnDemandImportTime(
                pl.id,
                if (ok) System.currentTimeMillis() else System.currentTimeMillis() - REFRESH_MS + 60 * 60 * 1000L
            )
            _version.value++
        }
        _hasContent.value = runCatching { db.hasAny() }.getOrDefault(false)
        // A problem stays visible in Settings → Live TV → On Demand (no more silent failures).
        _status.value = OnDemandStatus(false, lastProblem)
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
                // Panels don't always send perfectly valid JSON; be forgiving.
                reader.isLenient = true
                if (reader.peek() != JsonToken.BEGIN_ARRAY) {
                    reader.skipValue()
                    db.replace(pl.id, kind, categories, emptySequence())
                    return
                }
                reader.beginArray()
                val what = if (kind == VodKind.MOVIE) "movies" else "series"
                var n = 0
                _status.value = OnDemandStatus(true, "Importing $what from ${pl.name}…")
                var stoppedEarly: Throwable? = null
                val items = sequence {
                    while (true) {
                        // If the provider stops sending part-way (timeout, broken data), keep
                        // everything received so far instead of throwing it all away.
                        val f = try {
                            if (!reader.hasNext()) break
                            readFlat(reader)
                        } catch (e: Exception) {
                            stoppedEarly = e
                            break
                        }
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
                        if (++n % 500 == 0) _status.value = OnDemandStatus(true, "Importing $what from ${pl.name}… ${"%,d".format(n)}")
                    }
                }
                val saved = db.replace(pl.id, kind, categories, items)
                if (stoppedEarly == null) runCatching { reader.endArray() }
                stoppedEarly?.let { e ->
                    Log.w(TAG, "${pl.name}: $what stopped after $saved", e)
                    lastProblem = "${pl.name} stopped sending $what after ${"%,d".format(saved)} " +
                        "(${e.message ?: e.javaClass.simpleName}). Those were saved; it will try again in an hour."
                    if (saved == 0) throw e
                }
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
                        url = "${pl.xtreamBase()}/series/${enc(pl.xtreamUsername)}/${enc(pl.xtreamPassword)}/$id.$ext",
                        tech = einfo?.let { parseTech(it) }
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
            episodes = episodes.sortedWith(compareBy({ it.season }, { it.episode })),
            tech = parseTech(info)
        )
        infoCache[item.uid] = result
        result
    }

    // ------------------------------------------------------------------ poster cache

    suspend fun cachedPoster(item: VodItem): Pair<String, Long>? = withContext(Dispatchers.IO) {
        runCatching { db.cachedPoster(item.uid) }.getOrNull()
    }

    suspend fun cachePoster(item: VodItem, poster: String?) = withContext(Dispatchers.IO) {
        runCatching { db.cachePoster(item.uid, poster) }
        Unit
    }

    // ------------------------------------------------------------------ "Watch On Demand"

    /** A playable On Demand copy of something you opened in Nuvio. */
    data class OnDemandSource(
        val label: String,
        val url: String,
        val headers: Map<String, String>,
        val title: String = "",
        val year: Int? = null,
        val providerName: String = "",
        val ext: String? = null,
        val tech: VodTech? = null,
        val episodeTag: String? = null
    )

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
        // Titles in categories you hid (or that are locked) never show up in Nuvio.
        val user = prefs.userState.first()
        val settings = prefs.settings.first()
        val allowed = candidates.values.filter { item ->
            val uid = "${item.kind.key}:${item.playlistId}:${item.categoryId}"
            if (uid in user.vodHiddenCategories) return@filter false
            val name = runCatching { db.categoryName(item.playlistId, item.kind, item.categoryId) }.getOrNull().orEmpty()
            !com.nuvio.tv.livetv.parental.ParentalControls.isLocked("vod:$uid", name, settings, user.lockedGroups)
        }
        val out = ArrayList<OnDemandSource>()
        for (item in allowed.take(6)) {
            val pl = playlist(item.playlistId) ?: continue
            val headers = if (pl.userAgent.isNotBlank()) mapOf("User-Agent" to pl.userAgent) else emptyMap()
            if (kind == VodKind.MOVIE) {
                val url = movieUrl(item) ?: continue
                val tech = runCatching { info(item)?.tech }.getOrNull()
                out += OnDemandSource(
                    "${pl.name} · ${(item.ext ?: "mp4").uppercase()}", url, headers,
                    title = item.name, year = item.year, providerName = pl.name, ext = item.ext ?: "mp4", tech = tech
                )
            } else if (season != null && episode != null) {
                val ep = info(item)?.episodes?.firstOrNull { it.season == season && it.episode == episode } ?: continue
                out += OnDemandSource(
                    "${pl.name} · S${season}E${episode} · ${ep.ext.uppercase()}", ep.url, headers,
                    title = item.name, year = item.year, providerName = pl.name, ext = ep.ext, tech = ep.tech,
                    episodeTag = "S%02dE%02d".format(season, episode)
                )
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

    /** Reads the provider's media scan ("video" / "audio" / "bitrate" / "duration_secs"). */
    private fun parseTech(info: JSONObject): VodTech? {
        val v = info.optJSONObject("video")
        val a = info.optJSONObject("audio")
        val bitrate = info.optString("bitrate").toIntOrNull()?.takeIf { it > 0 }
        val duration = info.optString("duration_secs").toIntOrNull()?.takeIf { it > 0 }
            ?: info.optString("duration").split(':').takeIf { it.size == 3 }?.let { p ->
                (p[0].toIntOrNull() ?: 0) * 3600 + (p[1].toIntOrNull() ?: 0) * 60 + (p[2].toIntOrNull() ?: 0)
            }?.takeIf { it > 0 }
        if (v == null && a == null && bitrate == null && duration == null) return null
        val fps = v?.optString("r_frame_rate")?.let { r ->
            val parts = r.split('/')
            val n = parts.getOrNull(0)?.toDoubleOrNull()
            val d = parts.getOrNull(1)?.toDoubleOrNull() ?: 1.0
            if (n != null && d > 0) n / d else null
        }?.takeIf { it in 1.0..240.0 }
        val transfer = v?.optString("color_transfer").orEmpty()
        val hdr = when {
            v?.optJSONArray("side_data_list")?.toString()?.contains("DOVI", ignoreCase = true) == true -> "Dolby Vision"
            transfer.contains("smpte2084") -> "HDR10"
            transfer.contains("arib-std-b67") -> "HLG"
            else -> null
        }
        val lang = a?.optJSONObject("tags")?.optString("language")?.takeIf { it.isNotBlank() && it != "und" }?.let { code ->
            runCatching { java.util.Locale(code).displayLanguage }.getOrNull()?.takeIf { it.isNotBlank() && it != code } ?: code.uppercase()
        }
        return VodTech(
            width = v?.optInt("width")?.takeIf { it > 0 },
            height = v?.optInt("height")?.takeIf { it > 0 },
            videoCodec = v?.optString("codec_name")?.takeIf { it.isNotBlank() },
            fps = fps,
            hdr = hdr,
            audioCodec = a?.optString("codec_name")?.takeIf { it.isNotBlank() },
            channels = a?.optInt("channels")?.takeIf { it > 0 },
            language = lang,
            bitrateKbps = bitrate,
            durationSecs = duration
        )
    }

    private fun yearOf(s: String?): Int? =
        s?.let { Regex("""(19|20)\d{2}""").find(it)?.value?.toIntOrNull() }

    companion object {
        private const val TAG = "OnDemand"
        /** Re-import each provider once a day. */
        private const val REFRESH_MS = 24L * 60 * 60 * 1000
    }
}
