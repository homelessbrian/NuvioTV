package com.nuvio.tv.livetv.data

import android.content.Context
import android.util.Log
import com.nuvio.tv.livetv.model.EpgAssignment
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
        // A provider that trickles data forever would otherwise hold up every other update
        // (deleting a playlist, turning one off) until the app is restarted.
        .callTimeout(10, TimeUnit.MINUTES)
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

    /** Channel key -> the guide channel picked automatically (shown in Assign EPG). */
    private val _autoMatches = MutableStateFlow<Map<String, EpgAssignment>>(emptyMap())
    val autoMatches: StateFlow<Map<String, EpgAssignment>> = _autoMatches.asStateFlow()

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

    /** After a restore from Google Drive: rebuild from the new sources, downloading what's missing. */
    fun reloadAfterSync() {
        scope.launch {
            rebuild()
            refreshInternal(forcePlaylists = false, forceEpg = false)
        }
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

    suspend fun addXtream(
        name: String,
        server: String,
        username: String,
        password: String,
        importVod: Boolean = true,
        importLive: Boolean = true
    ): String {
        val id = UUID.randomUUID().toString()
        // Accept a pasted full link: keep just the server, and take the login from it if needed.
        val fromLink = PlaylistSource.credentialsFromLink(server)
        val user = username.trim().ifBlank { fromLink?.first.orEmpty() }
        val pass = password.trim().ifBlank { fromLink?.second.orEmpty() }
        prefs.updatePlaylists {
            it + PlaylistSource(
                id = id, name = name.ifBlank { "Xtream" }, url = "",
                xtreamServer = PlaylistSource.cleanXtreamServer(server),
                xtreamUsername = user, xtreamPassword = pass,
                importVod = importVod,
                importLive = importLive
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
        // TV channels switched off: take them out of the guide straight away.
        if (!source.liveEnabled) dropPlaylistNow(source.id)
        scope.launch {
            rebuild()
            refreshInternal(forcePlaylists = false, forceEpg = false, forceIds = setOf(source.id))
        }
    }

    suspend fun removePlaylist(id: String) {
        prefs.updatePlaylists { list -> list.filterNot { it.id == id } }
        File(playlistDir, "$id.m3u").delete()
        embeddedEpgUrls.remove(id)
        dropPlaylistNow(id)
        scope.launch { rebuild() }
    }

    /**
     * Takes a deleted or turned-off playlist's channels out of the guide straight away, without
     * waiting for the full rebuild (which can be queued behind a long guide download).
     */
    private fun dropPlaylistNow(id: String) {
        val remaining = _channels.value.filterNot { it.sourceId == id }
        if (remaining.size == _channels.value.size) return
        val keys = remaining.mapTo(HashSet()) { it.key }
        _channels.value = remaining
        _programs.value = _programs.value.filterKeys { it in keys }
    }

    suspend fun setPlaylistEnabled(id: String, enabled: Boolean) {
        prefs.updatePlaylists { list -> list.map { if (it.id == id) it.copy(enabled = enabled) else it } }
        if (!enabled) dropPlaylistNow(id)
        // Rebuild from the cached files first (turning a playlist on or off downloads nothing,
        // so the refresh alone wouldn't rebuild the channel list), then fetch anything missing.
        scope.launch {
            rebuild()
            refreshInternal(forcePlaylists = false, forceEpg = false)
        }
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
        scope.launch {
            rebuild()
            refreshInternal(forcePlaylists = false, forceEpg = false)
        }
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
        val playlists = prefs.currentPlaylists().filter { it.liveEnabled }
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
        val playlists = prefs.currentPlaylists().filter { it.liveEnabled && it.useEmbeddedEpg }
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
        val playlists = prefs.currentPlaylists().filter { it.liveEnabled }
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

        // Every guide is read; each channel keeps its best candidate:
        //   1. real listings beat placeholder listings ("Programming", "No information", …)
        //   2. a tvg-id match beats a name match
        //   3. then the earlier guide in the list
        // so a guide full of placeholders can't hide real listings from another guide.
        data class Candidate(val score: Int, val sourceId: String, val xmltvId: String, val programs: List<EpgProgram>)
        val best = HashMap<String, Candidate>()
        val pinned = HashMap<String, List<EpgProgram>>()
        val sourceLists = ArrayList<EpgSourceChannels>()
        val sourceMeta = ArrayList<Triple<EpgTarget, String, List<EpgChannelEntry>>>()

        for ((order, target) in targets.withIndex()) {
            val file = epgFile(target.fileId)
            if (!file.exists()) continue
            val pinnedHere = overrides.filterValues { it.sourceId == target.fileId }
            val idHits = HashMap<String, MutableSet<LiveChannel>>()
            val nameHits = HashMap<String, MutableSet<LiveChannel>>()
            val parsed = runCatching {
                openMaybeGzip(file).use { input ->
                    XmltvParser.parse(input, windowStart, windowEnd) { xmlChannels ->
                        xmlChannels.values.forEach { xc ->
                            byTvgId[xc.id.lowercase()]?.let { idHits.getOrPut(xc.id) { LinkedHashSet() }.addAll(it) }
                            (xc.displayNames + xc.id).forEach { dn ->
                                byName[normalize(dn)]?.let { nameHits.getOrPut(xc.id) { LinkedHashSet() }.addAll(it) }
                            }
                        }
                        if (xmlChannels.isEmpty()) null
                        else idHits.keys + nameHits.keys + pinnedHere.values.map { it.xmltvId }
                    }
                }
            }.onFailure { Log.w(TAG, "EPG parse ${target.name} failed", it) }.getOrNull() ?: continue

            val entries = if (parsed.channels.isNotEmpty()) {
                parsed.channels.values.map { EpgChannelEntry(it.id, it.displayNames, it.icon) }
            } else {
                parsed.programs.keys.map { EpgChannelEntry(it, emptyList(), null) }
            }
            val label = runCatching { java.net.URI(target.url).host?.removePrefix("www.") }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: target.name
            sourceMeta += Triple(target, label, entries.sortedBy { it.displayName.lowercase() })

            pinnedHere.forEach { (key, a) -> pinned[key] = cleanListings(parsed.programs[a.xmltvId].orEmpty()) }

            if (idHits.isEmpty() && nameHits.isEmpty()) {
                // Guide had no <channel> list before programs: match program channel ids directly.
                parsed.programs.keys.forEach { id ->
                    byTvgId[id.lowercase()]?.let { idHits.getOrPut(id) { LinkedHashSet() }.addAll(it) }
                    byName[normalize(id)]?.let { nameHits.getOrPut(id) { LinkedHashSet() }.addAll(it) }
                }
            }
            var count = 0
            fun offer(xmlId: String, chans: Set<LiveChannel>, idMatch: Boolean) {
                val raw = parsed.programs[xmlId] ?: return
                val list = cleanListings(raw)
                if (list.isEmpty()) return
                count += list.size
                val real = !isPlaceholderListing(list)
                // Higher is better; the guide's position only breaks ties.
                val score = (if (real) 4_000 else 0) + (if (idMatch) 2_000 else 0) - order
                chans.forEach { c ->
                    val cur = best[c.key]
                    if (cur == null || score > cur.score) best[c.key] = Candidate(score, target.fileId, xmlId, list)
                }
            }
            idHits.forEach { (xmlId, chans) -> offer(xmlId, chans, idMatch = true) }
            nameHits.forEach { (xmlId, chans) -> offer(xmlId, chans, idMatch = false) }
            if (target.sourceId != null) {
                prefs.updateEpgSources { l -> l.map { if (it.id == target.sourceId) it.copy(programCount = count) else it } }
            }
        }

        val result = HashMap<String, List<EpgProgram>>()
        val auto = HashMap<String, EpgAssignment>()
        best.forEach { (key, c) ->
            result[key] = c.programs
            auto[key] = EpgAssignment(c.sourceId, c.xmltvId)
        }
        result.putAll(pinned)
        // Which guide channels ended up feeding a playlist channel (for "Unassigned" in Assign EPG).
        val usedBySource = HashMap<String, MutableSet<String>>()
        auto.values.forEach { usedBySource.getOrPut(it.sourceId) { HashSet() } += it.xmltvId }
        overrides.values.forEach { usedBySource.getOrPut(it.sourceId) { HashSet() } += it.xmltvId }
        sourceMeta.forEach { (target, label, entries) ->
            sourceLists += EpgSourceChannels(target.fileId, target.name, label, entries, usedBySource[target.fileId].orEmpty())
        }
        _autoMatches.value = auto
        _epgSources.value = sourceLists
        _programs.value = result
    }

    /**
     * Some guides add a long placeholder ("Programming", "No information") that overlaps the real
     * listings for the same time. Keep the real ones wherever they overlap.
     */
    private fun cleanListings(list: List<EpgProgram>): List<EpgProgram> {
        if (list.size < 2) return list
        val real = list.filter { !isPlaceholder(it.title) }
        val kept = if (real.isEmpty() || real.size == list.size) list else list.filter { p ->
            !isPlaceholder(p.title) || real.none { r -> r.startMs < p.stopMs && r.stopMs > p.startMs }
        }
        return resolveOverlaps(kept)
    }

    /**
     * Makes sure only one program covers any moment. When a long entry (often a "Programming"
     * filler) runs across later shows, it's cut off where the next show starts. Otherwise the
     * filler, having started earlier, is what counts as "on now" even while a real show is airing.
     */
    private fun resolveOverlaps(list: List<EpgProgram>): List<EpgProgram> {
        val sorted = list.sortedWith(compareBy<EpgProgram>({ it.startMs }, { isPlaceholder(it.title) }, { it.stopMs - it.startMs }))
        val out = ArrayList<EpgProgram>(sorted.size)
        for (p in sorted) {
            val last = out.lastOrNull()
            if (last != null && last.stopMs > p.startMs) {
                if (last.startMs == p.startMs) continue // same start: keep the one sorted first
                out[out.lastIndex] = last.copy(stopMs = p.startMs)
            }
            if (p.stopMs > p.startMs) out += p
        }
        return out
    }

    /** True when (almost) everything in a channel's listings is a placeholder. */
    private fun isPlaceholderListing(list: List<EpgProgram>): Boolean {
        val placeholders = list.count { isPlaceholder(it.title) }
        return placeholders * 10 >= list.size * 8
    }

    private fun isPlaceholder(title: String): Boolean =
        PLACEHOLDER_TITLES.matches(title.trim().lowercase())

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
        /** Titles guides use when they have no real listing. */
        private val PLACEHOLDER_TITLES = Regex(
            """(programming|program|programme|no information|no info|no program information|no programme information|""" +
                """to be announced|tba|tbd|n/?a|off air|no data|no epg|not available|information not available|""" +
                """regular programming|scheduled programming|paid programming|coming soon)\.?"""
        )
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
