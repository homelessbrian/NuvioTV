package com.nuvio.tv.livetv.data

import android.content.Context
import android.util.Log
import com.nuvio.tv.livetv.model.EpgChannelEntry
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.EpgSourceChannels
import com.nuvio.tv.livetv.model.EpgSource
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.PlaylistSource
import dagger.hilt.android.qualifiers.ApplicationContext
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
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

data class LiveTvStatus(
    val loading: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val loadedOnce: Boolean = false
)

@Singleton
class LiveTvRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: LiveTvPreferences
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    private val baseDir = File(context.filesDir, "livetv")
    private val playlistDir = File(baseDir, "playlists")
    private val epgDir = File(baseDir, "epg")

    private val _channels = MutableStateFlow<List<LiveChannel>>(emptyList())
    val channels: StateFlow<List<LiveChannel>> = _channels.asStateFlow()

    /** Programs keyed by [LiveChannel.key], sorted by start. Offset is not applied here. */
    private val _programs = MutableStateFlow<Map<String, List<EpgProgram>>>(emptyMap())
    val programs: StateFlow<Map<String, List<EpgProgram>>> = _programs.asStateFlow()

    /** Every loaded EPG source with its channel list (for "Change EPG"). */
    private val _epgSources = MutableStateFlow<List<EpgSourceChannels>>(emptyList())
    val epgSources: StateFlow<List<EpgSourceChannels>> = _epgSources.asStateFlow()

    private val _status = MutableStateFlow(LiveTvStatus())
    val status: StateFlow<LiveTvStatus> = _status.asStateFlow()

    private var initialized = false

    /** Loads cached data immediately, then refreshes anything older than the configured interval. */
    fun ensureLoaded() {
        if (initialized) return
        initialized = true
        scope.launch {
            loadFromCache()
            refreshStale()
        }
    }

    fun refreshAll(force: Boolean = true) {
        scope.launch { refreshInternal(forcePlaylists = force, forceEpg = force) }
    }

    fun refreshEpgOnly() {
        scope.launch { refreshInternal(forcePlaylists = false, forceEpg = true) }
    }

    private suspend fun refreshStale() = refreshInternal(forcePlaylists = false, forceEpg = false)

    // ---------------------------------------------------------------- sources CRUD

    suspend fun addPlaylist(name: String, url: String, userAgent: String = ""): String {
        val id = UUID.randomUUID().toString()
        prefs.updatePlaylists { it + PlaylistSource(id = id, name = name.ifBlank { "Playlist" }, url = url.trim(), userAgent = userAgent.trim()) }
        scope.launch { refreshInternal(forcePlaylists = false, forceEpg = false, forceIds = setOf(id)) }
        return id
    }

    suspend fun addXtream(name: String, server: String, username: String, password: String): String {
        val id = UUID.randomUUID().toString()
        // Accept a pasted full link: keep just the server, and take the login from it if needed.
        val fromLink = PlaylistSource.credentialsFromLink(server)
        val user = username.trim().ifBlank { fromLink?.first.orEmpty() }
        val pass = password.trim().ifBlank { fromLink?.second.orEmpty() }
        prefs.updatePlaylists {
            it + PlaylistSource(
                id = id, name = name.ifBlank { "Xtream" }, url = "",
                xtreamServer = PlaylistSource.cleanXtreamServer(server),
                xtreamUsername = user, xtreamPassword = pass
            )
        }
        scope.launch { refreshInternal(forcePlaylists = false, forceEpg = false, forceIds = setOf(id)) }
        return id
    }

    suspend fun updatePlaylist(input: PlaylistSource) {
        val source = if (input.isXtream) {
            val fromLink = PlaylistSource.credentialsFromLink(input.xtreamServer)
            input.copy(
                xtreamServer = PlaylistSource.cleanXtreamServer(input.xtreamServer),
                xtreamUsername = input.xtreamUsername.trim().ifBlank { fromLink?.first.orEmpty() },
                xtreamPassword = input.xtreamPassword.trim().ifBlank { fromLink?.second.orEmpty() }
            )
        } else input
        prefs.updatePlaylists { list -> list.map { if (it.id == source.id) source else it } }
        scope.launch { refreshInternal(forcePlaylists = false, forceEpg = false, forceIds = setOf(source.id)) }
    }

    suspend fun removePlaylist(id: String) {
        prefs.updatePlaylists { list -> list.filterNot { it.id == id } }
        File(playlistDir, "$id.m3u").delete()
        scope.launch { rebuild() }
    }

    suspend fun setPlaylistEnabled(id: String, enabled: Boolean) {
        prefs.updatePlaylists { list -> list.map { if (it.id == id) it.copy(enabled = enabled) else it } }
        scope.launch { refreshInternal(forcePlaylists = false, forceEpg = false) }
    }

    suspend fun movePlaylist(id: String, delta: Int) {
        prefs.updatePlaylists { list ->
            val m = list.toMutableList()
            val i = m.indexOfFirst { it.id == id }
            if (i < 0) return@updatePlaylists list
            val item = m.removeAt(i)
            m.add((i + delta).coerceIn(0, m.size), item)
            m
        }
        scope.launch { rebuild() }
    }

    suspend fun addEpg(name: String, url: String): String {
        val id = UUID.randomUUID().toString()
        prefs.updateEpgSources { it + EpgSource(id = id, name = name.ifBlank { "EPG" }, url = url.trim()) }
        scope.launch { refreshInternal(forcePlaylists = false, forceEpg = false, forceIds = setOf(id)) }
        return id
    }

    suspend fun updateEpg(source: EpgSource) {
        prefs.updateEpgSources { list -> list.map { if (it.id == source.id) source else it } }
        scope.launch { refreshInternal(forcePlaylists = false, forceEpg = false, forceIds = setOf(source.id)) }
    }

    suspend fun removeEpg(id: String) {
        prefs.updateEpgSources { list -> list.filterNot { it.id == id } }
        File(epgDir, "$id.xml").delete()
        scope.launch { rebuild() }
    }

    suspend fun setEpgEnabled(id: String, enabled: Boolean) {
        prefs.updateEpgSources { list -> list.map { if (it.id == id) it.copy(enabled = enabled) else it } }
        scope.launch { refreshInternal(forcePlaylists = false, forceEpg = false) }
    }

    // ---------------------------------------------------------------- core

    private suspend fun loadFromCache() {
        mutex.withLock { rebuildLocked() }
    }

    private suspend fun rebuild() {
        mutex.withLock { rebuildLocked() }
    }

    private suspend fun refreshInternal(
        forcePlaylists: Boolean,
        forceEpg: Boolean,
        forceIds: Set<String> = emptySet()
    ) = mutex.withLock {
        val settings = prefs.currentSettings()
        val now = System.currentTimeMillis()
        val playlists = prefs.currentPlaylists().filter { it.enabled }
        var changed = false

        for (pl in playlists) {
            val file = playlistFile(pl.id)
            val stale = !file.exists() ||
                now - file.lastModified() > settings.playlistRefreshHours.coerceAtLeast(1) * HOUR
            if (forcePlaylists || stale || pl.id in forceIds) {
                setStatus(loading = true, message = "Updating playlist \"${pl.name}\"…")
                val result = runCatching {
                    if (pl.isXtream) downloadXtream(pl, file) else download(pl.resolvedUrl(), file, pl.userAgent)
                }
                val err = result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
                prefs.updatePlaylists { list ->
                    list.map {
                        if (it.id == pl.id) it.copy(lastUpdatedMs = if (err == null) now else it.lastUpdatedMs, lastError = err) else it
                    }
                }
                if (err != null) Log.w(TAG, "Playlist ${pl.name} failed: $err")
                changed = true
            }
        }

        // Build channels first so EPG matching knows which channels exist.
        if (changed || _channels.value.isEmpty()) {
            setStatus(loading = true, message = "Reading channels…")
            buildChannels()
        }

        val epgTargets = collectEpgTargets()
        for (target in epgTargets) {
            val file = epgFile(target.fileId)
            val stale = !file.exists() ||
                now - file.lastModified() > settings.epgRefreshHours.coerceAtLeast(1) * HOUR
            if (forceEpg || stale || target.sourceId in forceIds || (target.sourceId == null && changed && !file.exists())) {
                setStatus(loading = true, message = "Downloading guide \"${target.name}\"…")
                val result = runCatching { download(target.url, file, target.userAgent) }
                val err = result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
                if (target.sourceId != null) {
                    prefs.updateEpgSources { list ->
                        list.map { if (it.id == target.sourceId) it.copy(lastUpdatedMs = if (err == null) now else it.lastUpdatedMs, lastError = err) else it }
                    }
                }
                if (err != null) Log.w(TAG, "EPG ${target.name} failed: $err")
                changed = true
            }
        }

        if (changed || _programs.value.isEmpty()) {
            setStatus(loading = true, message = "Matching guide data…")
            buildPrograms(settings, epgTargets)
        }
        _status.value = LiveTvStatus(loading = false, loadedOnce = true)
    }

    private suspend fun rebuildLocked() {
        setStatus(loading = true, message = "Loading channels…")
        buildChannels()
        val targets = collectEpgTargets()
        buildPrograms(prefs.currentSettings(), targets)
        _status.value = LiveTvStatus(loading = false, loadedOnce = true)
    }

    private fun setStatus(loading: Boolean, message: String?) {
        _status.value = _status.value.copy(loading = loading, message = message)
    }

    private data class EpgTarget(
        val fileId: String,
        val sourceId: String?,
        val name: String,
        val url: String,
        val userAgent: String
    )

    /** Named EPG sources first (they win on conflicts), then EPG URLs embedded in playlists. */
    private suspend fun collectEpgTargets(): List<EpgTarget> {
        val targets = mutableListOf<EpgTarget>()
        prefs.currentEpgSources().filter { it.enabled && it.url.isNotBlank() }.forEach {
            targets += EpgTarget(it.id, it.id, it.name, it.url, "")
        }
        val playlists = prefs.currentPlaylists().filter { it.enabled && it.useEmbeddedEpg }
        for (pl in playlists) {
            val urls = mutableListOf<String>()
            pl.xtreamEpgUrl()?.let { urls += it }
            urls += embeddedEpgUrls[pl.id].orEmpty()
            urls.distinct().forEach { url ->
                if (targets.none { it.url == url }) {
                    targets += EpgTarget("auto_" + sha1(url), null, "${pl.name} (built-in)", url, pl.userAgent)
                }
            }
        }
        return targets
    }

    private val embeddedEpgUrls = HashMap<String, List<String>>()

    private suspend fun buildChannels() = withContext(Dispatchers.IO) {
        val playlists = prefs.currentPlaylists().filter { it.enabled }
        val multi = playlists.size > 1
        val out = ArrayList<LiveChannel>()
        val usedKeys = HashSet<String>()
        var counter = 1
        for (pl in playlists) {
            val file = playlistFile(pl.id)
            if (!file.exists()) continue
            val parsed = runCatching { file.bufferedReader().use { M3uParser.parse(it) } }
                .onFailure { Log.w(TAG, "Parse ${pl.name} failed", it) }
                .getOrNull() ?: continue
            embeddedEpgUrls[pl.id] = parsed.epgUrls
            for (e in parsed.entries) {
                val groupTitle = e.group?.ifBlank { null } ?: "Uncategorized"
                val baseKey = "${pl.id}|${e.tvgId ?: ""}|${e.name}"
                var key = baseKey
                var n = 2
                while (!usedKeys.add(key)) { key = "$baseKey#${n++}" }
                val number = e.chno ?: counter
                counter = maxOf(counter, number) + 1
                val headers = if (pl.userAgent.isNotBlank() && "User-Agent" !in e.headers) {
                    e.headers + ("User-Agent" to pl.userAgent)
                } else e.headers
                out += LiveChannel(
                    key = key,
                    sourceId = pl.id,
                    sourceName = pl.name,
                    name = e.name,
                    tvgId = e.tvgId,
                    tvgName = e.tvgName,
                    logo = e.logo,
                    groupId = if (multi) "${pl.id}::$groupTitle" else groupTitle,
                    group = if (multi) "$groupTitle · ${pl.name}" else groupTitle,
                    number = number,
                    url = e.url,
                    headers = headers,
                    catchup = e.catchup
                )
            }
            prefs.updatePlaylists { list -> list.map { if (it.id == pl.id) it.copy(channelCount = parsed.entries.size) else it } }
        }
        _channels.value = out
    }

    private suspend fun buildPrograms(settings: LiveTvSettings, targets: List<EpgTarget>) = withContext(Dispatchers.IO) {
        val channels = _channels.value
        if (channels.isEmpty()) {
            _programs.value = emptyMap()
            return@withContext
        }
        val now = System.currentTimeMillis()
        val windowStart = now - settings.epgPastHours.coerceAtLeast(1) * HOUR
        val windowEnd = now + settings.epgFutureDays.coerceAtLeast(1) * 24 * HOUR

        // Hand-picked guides (long-press > Change EPG) win over automatic matching.
        val overrides = prefs.userState.first().epgOverrides
            .filterKeys { key -> channels.any { it.key == key } }

        val byTvgId = HashMap<String, MutableList<LiveChannel>>()
        val byName = HashMap<String, MutableList<LiveChannel>>()
        channels.forEach { c ->
            if (c.key in overrides) return@forEach
            c.tvgId?.lowercase()?.let { byTvgId.getOrPut(it) { ArrayList() }.add(c) }
            normalize(c.tvgName ?: "").takeIf { it.isNotEmpty() }?.let { byName.getOrPut(it) { ArrayList() }.add(c) }
            normalize(c.name).takeIf { it.isNotEmpty() }?.let { byName.getOrPut(it) { ArrayList() }.add(c) }
        }

        val result = HashMap<String, List<EpgProgram>>()
        val sourceLists = ArrayList<EpgSourceChannels>()
        for (target in targets) {
            val file = epgFile(target.fileId)
            if (!file.exists()) continue
            val pinnedHere = overrides.filterValues { it.sourceId == target.fileId }
            // xmltv id -> channels it feeds
            val mapping = HashMap<String, MutableSet<LiveChannel>>()
            val parsed = runCatching {
                openMaybeGzip(file).use { input ->
                    XmltvParser.parse(input, windowStart, windowEnd) { xmlChannels ->
                        xmlChannels.values.forEach { xc ->
                            val hits = LinkedHashSet<LiveChannel>()
                            byTvgId[xc.id.lowercase()]?.let { hits.addAll(it) }
                            if (hits.isEmpty()) {
                                (xc.displayNames + xc.id).forEach { dn ->
                                    byName[normalize(dn)]?.let { hits.addAll(it) }
                                }
                            }
                            hits.removeAll { it.key in result }
                            if (hits.isNotEmpty()) mapping[xc.id] = hits
                        }
                        if (xmlChannels.isEmpty()) null
                        else mapping.keys + pinnedHere.values.map { it.xmltvId }
                    }
                }
            }.onFailure { Log.w(TAG, "EPG parse ${target.name} failed", it) }.getOrNull() ?: continue

            // Keep this guide's channel list for the picker.
            val entries = if (parsed.channels.isNotEmpty()) {
                parsed.channels.values.map { EpgChannelEntry(it.id, it.displayNames, it.icon) }
            } else {
                parsed.programs.keys.map { EpgChannelEntry(it, emptyList(), null) }
            }
            val label = runCatching { java.net.URI(target.url).host?.removePrefix("www.") }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: target.name
            val sortedEntries = entries.sortedBy { it.displayName.lowercase() }

            pinnedHere.forEach { (key, a) -> result[key] = parsed.programs[a.xmltvId].orEmpty() }

            if (mapping.isEmpty()) {
                // Guide had no <channel> list before programs: match program channel ids directly.
                parsed.programs.keys.forEach { id ->
                    val hits = byTvgId[id.lowercase()] ?: byName[normalize(id)]
                    hits?.filter { it.key !in result }?.let { if (it.isNotEmpty()) mapping[id] = it.toMutableSet() }
                }
            }
            var count = 0
            val used = HashSet<String>(pinnedHere.values.map { it.xmltvId })
            mapping.forEach { (xmlId, chans) ->
                val list = parsed.programs[xmlId] ?: return@forEach
                count += list.size
                var fed = false
                chans.forEach { c -> if (c.key !in result) { result[c.key] = list; fed = true } }
                if (fed) used += xmlId
            }
            sourceLists += EpgSourceChannels(target.fileId, target.name, label, sortedEntries, used)
            if (target.sourceId != null) {
                prefs.updateEpgSources { l -> l.map { if (it.id == target.sourceId) it.copy(programCount = count) else it } }
            }
        }
        _epgSources.value = sourceLists
        _programs.value = result
    }

    /** Re-reads the cached guides, e.g. after an EPG assignment changed. No downloads. */
    fun rematchEpg() {
        scope.launch {
            mutex.withLock {
                setStatus(loading = true, message = "Updating guide…")
                buildPrograms(prefs.currentSettings(), collectEpgTargets())
                _status.value = LiveTvStatus(loading = false, loadedOnce = true)
            }
        }
    }

    // ---------------------------------------------------------------- IO helpers

    private fun playlistFile(id: String) = File(playlistDir, "$id.m3u")
    private fun epgFile(id: String) = File(epgDir, "$id.xml")

    private suspend fun download(url: String, target: File, userAgent: String) = withContext(Dispatchers.IO) {
        require(url.isNotBlank()) { "No URL set" }
        target.parentFile?.mkdirs()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent.ifBlank { DEFAULT_UA })
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error(httpError(response.code))
            val body = response.body ?: error("Empty response")
            val tmp = File(target.parentFile, target.name + ".tmp")
            body.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
            if (tmp.length() == 0L) {
                tmp.delete()
                error("Empty response")
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
    }

    // ---------------------------------------------------------------- Xtream Codes API

    /**
     * Loads an Xtream login through the Xtream API (player_api.php), the way TiviMate does:
     * account check, categories, then live streams, written out as an M3U file so the rest of
     * Live TV treats it like any playlist. Much lighter for the provider than the one-shot
     * get.php playlist, which many panels time out on (HTTP 502) or switch off. Falls back to
     * get.php if the API isn't available.
     */
    private suspend fun downloadXtream(pl: PlaylistSource, target: File) = withContext(Dispatchers.IO) {
        require(pl.xtreamBase().isNotBlank()) { "No server set" }
        require(pl.xtreamUsername.isNotBlank() && pl.xtreamPassword.isNotBlank()) { "Username and password are required" }
        val api = pl.xtreamApiUrl()
        val apiResult = runCatching {
            // 1. Account
            val info = JSONObject(getText(api, pl.userAgent))
            info.optJSONObject("user_info")?.let { ui ->
                if (ui.optString("auth") == "0") throw XtreamLoginError("Login failed: check the username and password")
                val status = ui.optString("status")
                if (status.isNotBlank() && !status.equals("Active", ignoreCase = true)) {
                    throw XtreamLoginError("Account $status")
                }
                ui.optString("exp_date").toLongOrNull()?.let { exp ->
                    if (exp > 0 && exp * 1000 < System.currentTimeMillis()) {
                        val date = java.text.DateFormat.getDateInstance().format(java.util.Date(exp * 1000))
                        throw XtreamLoginError("Account expired on $date")
                    }
                }
            } ?: if (info.has("user_info")) Unit else error("Not an Xtream API response")

            // 2. Categories
            val categories = HashMap<String, String>()
            runCatching {
                val arr = org.json.JSONArray(getText("$api&action=get_live_categories", pl.userAgent))
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i) ?: continue
                    categories[c.optString("category_id")] = c.optString("category_name")
                }
            }

            // 3. Live streams, streamed straight into an M3U file
            writeXtreamPlaylist(pl, "$api&action=get_live_streams", categories, target)
        }
        val error = apiResult.exceptionOrNull() ?: return@withContext
        if (error is XtreamLoginError) throw error
        Log.w(TAG, "Xtream API failed for ${pl.name}, trying get.php: ${error.message}")
        // Fallback: the classic one-shot playlist. If that fails too, report the API error.
        runCatching { download(pl.resolvedUrl(), target, pl.userAgent) }
            .onFailure { throw IllegalStateException(error.message ?: it.message) }
    }

    private class XtreamLoginError(message: String) : Exception(message)

    private fun writeXtreamPlaylist(pl: PlaylistSource, url: String, categories: Map<String, String>, target: File) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        val base = pl.xtreamBase()
        val user = java.net.URLEncoder.encode(pl.xtreamUsername.trim(), "UTF-8")
        val pass = java.net.URLEncoder.encode(pl.xtreamPassword.trim(), "UTF-8")
        var count = 0
        val request = Request.Builder().url(url).header("User-Agent", pl.userAgent.ifBlank { DEFAULT_UA }).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error(httpError(response.code))
            val body = response.body ?: error("Empty response")
            tmp.bufferedWriter().use { out ->
                out.write("#EXTM3U\n")
                android.util.JsonReader(body.charStream()).use { reader ->
                    reader.beginArray()
                    while (reader.hasNext()) {
                        val f = HashMap<String, String>()
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val name = reader.nextName()
                            when (reader.peek()) {
                                android.util.JsonToken.STRING -> f[name] = reader.nextString()
                                android.util.JsonToken.NUMBER -> f[name] = reader.nextString()
                                android.util.JsonToken.BOOLEAN -> f[name] = reader.nextBoolean().toString()
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                        val streamId = f["stream_id"]
                        if (streamId.isNullOrBlank()) continue
                        val title = (f["name"] ?: "Channel $streamId").replace("\n", " ").trim()
                        val attrs = buildString {
                            fun attr(k: String, v: String?) {
                                if (!v.isNullOrBlank()) append(" $k=\"${v.replace("\"", "'")}\"")
                            }
                            attr("tvg-id", f["epg_channel_id"])
                            attr("tvg-name", title)
                            attr("tvg-logo", f["stream_icon"])
                            attr("group-title", categories[f["category_id"]] ?: "Uncategorized")
                            attr("tvg-chno", f["num"])
                            if (f["tv_archive"] == "1") {
                                attr("catchup", "xc")
                                attr("catchup-days", f["tv_archive_duration"]?.takeIf { it != "0" } ?: "1")
                            }
                        }
                        out.write("#EXTINF:-1$attrs,$title\n")
                        out.write("$base/live/$user/$pass/$streamId.ts\n")
                        count++
                    }
                    reader.endArray()
                }
            }
        }
        if (count == 0) {
            tmp.delete()
            error("The provider returned no live channels")
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    private fun getText(url: String, userAgent: String): String {
        val request = Request.Builder().url(url).header("User-Agent", userAgent.ifBlank { DEFAULT_UA }).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error(httpError(response.code))
            return response.body?.string() ?: error("Empty response")
        }
    }

    /** Plain-English reasons for the HTTP errors people actually hit. */
    private fun httpError(code: Int): String = when (code) {
        401, 403 -> "HTTP $code: the provider refused the request (wrong login, or it blocks this app; try setting a user agent)"
        404 -> "HTTP 404: nothing at that address (check the server, port and link)"
        429 -> "HTTP 429: too many requests, try again in a few minutes"
        502, 503, 504, 520, 521, 522, 523, 524 -> "HTTP $code: the provider's server didn't respond, try again later"
        884 -> "HTTP 884: the provider blocked this app (try setting a user agent)"
        else -> "HTTP $code"
    }

    private fun openMaybeGzip(file: File): InputStream {
        val input = BufferedInputStream(file.inputStream(), 64 * 1024)
        input.mark(2)
        val b1 = input.read()
        val b2 = input.read()
        input.reset()
        return if (b1 == 0x1f && b2 == 0x8b) BufferedInputStream(GZIPInputStream(input, 64 * 1024)) else input
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    companion object {
        private const val TAG = "LiveTvRepository"
        private const val HOUR = 60L * 60L * 1000L
        const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

        private val qualityTokens = Regex("""\b(fhd|uhd|hd|sd|4k|8k|hevc|h265|h264|1080p|720p|backup|raw)\b""")
        private val prefixToken = Regex("""^[a-z]{2,3}\s*[:|]\s*""")

        internal fun normalize(name: String): String {
            var n = name.lowercase().trim()
            n = n.replace(prefixToken, "")
            n = n.replace(Regex("""\[.*?]|\(.*?\)"""), " ")
            n = n.replace(qualityTokens, " ")
            return n.filter { it.isLetterOrDigit() }
        }
    }
}
