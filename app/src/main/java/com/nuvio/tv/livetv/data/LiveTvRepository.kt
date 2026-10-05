package com.nuvio.tv.livetv.data

import android.content.Context
import android.util.Log
import com.nuvio.tv.livetv.model.EpgAssignment
import com.nuvio.tv.livetv.model.EpgChannelEntry
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.EpgSourceChannels
import com.nuvio.tv.livetv.model.EpgSource
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.LiveTvLoadReport as LIVE_REPORT
import com.nuvio.tv.livetv.model.CatchupInfo
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
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
    private val prefs: LiveTvPreferences,
    private val guideDb: EpgDatabase
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
    fun ensureLoaded(updateCheckDelayMs: Long = 0L) {
        if (initialized) return
        initialized = true
        scope.launch {
            // Saved channels and guide first: reading them only uses the device's storage.
            loadFromCache()
            // Checking for playlist / guide updates uses the network and a lot of processing,
            // so when this starts with the app it waits a little, out of Nuvio's way.
            if (updateCheckDelayMs > 0) delay(updateCheckDelayMs)
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
                LIVE_REPORT.add("Checking playlist \"${pl.name}\" for updates")
                setStatus(loading = true, message = "Updating playlist \"${pl.name}\"…")
                val before = if (file.exists()) file.length() to file.lastModified() else null
                val result = runCatching {
                    if (pl.isXtream) {
                        val old = if (file.exists()) File(file.parentFile, file.name + ".prev").also { file.copyTo(it, overwrite = true) } else null
                        downloadXtream(pl, file)
                        // Xtream lists are rebuilt each time: unchanged if identical to before.
                        val unchanged = old != null && old.length() == file.length() && sameContent(old, file)
                        old?.delete()
                        if (!unchanged) markChanged(file)
                        LIVE_REPORT.add(if (unchanged) "Playlist \"${pl.name}\": no changes" else "Playlist \"${pl.name}\": new content")
                        !unchanged
                    } else download(pl.resolvedUrl(), file, pl.userAgent)
                }
                val err = result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
                prefs.updatePlaylists { list ->
                    list.map {
                        if (it.id == pl.id) it.copy(lastUpdatedMs = if (err == null) now else it.lastUpdatedMs, lastError = err) else it
                    }
                }
                if (err != null) Log.w(TAG, "Playlist ${pl.name} failed: $err")
                if (result.getOrNull() == true || (before == null && file.exists())) changed = true
            }
        }

        // Build channels first so EPG matching knows which channels exist.
        if (changed || _channels.value.isEmpty()) {
            setStatus(loading = true, message = "Reading channels…")
            buildChannels()
            saveChannels() // so the next start uses it straight away
        }

        val epgTargets = collectEpgTargets()
        for (target in epgTargets) {
            val file = epgFile(target.fileId)
            val stale = !file.exists() ||
                now - file.lastModified() > settings.epgRefreshHours.coerceAtLeast(1) * HOUR
            if (forceEpg || stale || target.sourceId in forceIds || (target.sourceId == null && changed && !file.exists())) {
                setStatus(loading = true, message = "Downloading guide \"${target.name}\"…")
                val result = runCatching { download(target.url, file, target.userAgent) }
                if (result.getOrNull() == true) changed = true
                val err = result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
                if (target.sourceId != null) {
                    prefs.updateEpgSources { list ->
                        list.map { if (it.id == target.sourceId) it.copy(lastUpdatedMs = if (err == null) now else it.lastUpdatedMs, lastError = err) else it }
                    }
                }
                if (err != null) Log.w(TAG, "EPG ${target.name} failed: $err")
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
        // The channel list as it was last read, when no playlist has changed since: Live TV
        // (Favorites included) appears straight away instead of re-reading every playlist file.
        var t0 = System.currentTimeMillis()
        // The stored guide is read at the same time as the channel list (it only has to be
        // checked against the channels afterwards), so the two waits overlap.
        val guidePreRead = scope.async(Dispatchers.IO) { preReadGuide() }
        if (!loadSavedChannels()) {
            buildChannels()
            saveChannels()
            LIVE_REPORT.add("Channels re-read from playlists: ${_channels.value.size} in ${System.currentTimeMillis() - t0} ms")
        } else {
            LIVE_REPORT.add("Saved channel list used: ${_channels.value.size} channels in ${System.currentTimeMillis() - t0} ms")
        }
        val targets = collectEpgTargets()
        val settings = prefs.currentSettings()
        // The guide as it was last matched, if nothing it depends on has changed: the guide
        // fills in straight away instead of re-reading every guide file at each start.
        t0 = System.currentTimeMillis()
        if (!loadGuideFromDb(settings, targets, guidePreRead.await())) {
            buildPrograms(settings, targets)
            LIVE_REPORT.add("Guide re-read from guide files in ${System.currentTimeMillis() - t0} ms")
        } else {
            LIVE_REPORT.add("Stored guide used: ${_programs.value.size} channels with listings in ${System.currentTimeMillis() - t0} ms")
        }
        _status.value = LiveTvStatus(loading = false, loadedOnce = true)
    }

    /** Which parts of a saved copy's fingerprint differ (for the start-up report). */
    private fun fingerprintDiff(old: String, new: String): String {
        val a = old.split('|', ',').toSet()
        val b = new.split('|', ',').toSet()
        val changed = (b - a).take(4).joinToString("; ") { it.take(60) }
        return if (changed.isBlank()) "format changed" else "changed: $changed"
    }

    // ---------------------------------------------------------------- saved channel list

    private val savedChannelsFile get() = File(baseDir, "channels_cache3.bin")

    /** Everything the channel list is built from; a different value means it must be re-read. */
    private suspend fun channelsFingerprint(): String {
        val sb = StringBuilder().append(CHANNELS_CACHE_VERSION).append('|')
        prefs.currentPlaylists().filter { it.liveEnabled }.forEach { pl ->
            val f = playlistFile(pl.id)
            sb.append(pl.id).append(':').append(pl.name.hashCode()).append(':').append(pl.userAgent.hashCode())
                .append(':').append(f.length()).append(':').append(contentStamp(f)).append(',')
        }
        return sb.toString()
    }

    /**
     * Saved channel list, format 3: not zipped (unzipping cost more than reading a slightly
     * bigger file on TV boxes), and text that repeats across channels (playlist, group,
     * catch-up type) stored once and referred to by number. Faster to read, less memory.
     */
    private suspend fun loadSavedChannels(): Boolean = withContext(Dispatchers.IO) {
        runCatching { File(baseDir, "channels_cache.bin").delete() } // the old zipped format
        val file = savedChannelsFile
        if (!file.exists()) return@withContext false
        val expected = channelsFingerprint()
        runCatching {
            java.io.DataInputStream(java.io.BufferedInputStream(file.inputStream(), 256 * 1024)).use { input ->
                val stored = input.readUTF()
                if (stored != expected) {
                    LIVE_REPORT.add("Saved channel list out of date: ${fingerprintDiff(stored, expected)}")
                    return@withContext false
                }
                val epg = HashMap<String, List<String>>()
                repeat(input.readInt()) {
                    val id = input.readUTF()
                    epg[id] = List(input.readInt()) { input.readUTF() }
                }
                val table = Array(input.readInt()) { input.readUTF() }
                fun shared(): String = table[input.readInt()]
                fun opt(): String? = if (input.readBoolean()) input.readUTF() else null
                val count = input.readInt()
                val list = ArrayList<LiveChannel>(count)
                repeat(count) {
                    val key = input.readUTF()
                    val sourceId = shared()
                    val sourceName = shared()
                    val name = input.readUTF()
                    val tvgId = opt()
                    val tvgName = opt()
                    val logo = opt()
                    val groupId = shared()
                    val group = shared()
                    val number = input.readInt()
                    val url = input.readUTF()
                    val headerCount = input.readInt()
                    val headers: Map<String, String> = if (headerCount == 0) emptyMap()
                    else HashMap<String, String>(headerCount * 2).apply { repeat(headerCount) { put(shared(), input.readUTF()) } }
                    val catchup = if (input.readBoolean()) CatchupInfo(shared(), opt(), input.readInt()) else null
                    val drm = if (input.readBoolean()) {
                        val scheme = shared()
                        val license = input.readUTF()
                        val lh = HashMap<String, String>().apply { repeat(input.readInt()) { put(input.readUTF(), input.readUTF()) } }
                        com.nuvio.tv.livetv.model.DrmInfo(scheme, license, lh, opt())
                    } else null
                    list += LiveChannel(key, sourceId, sourceName, name, tvgId, tvgName, logo, groupId, group, number, url, headers, catchup, drm)
                }
                embeddedEpgUrls.clear()
                embeddedEpgUrls.putAll(epg)
                _channels.value = list
                true
            }
        }.getOrElse {
            Log.w(TAG, "Saved channel list unreadable", it)
            false
        }
    }

    private suspend fun saveChannels() = withContext(Dispatchers.IO) {
        val fingerprint = channelsFingerprint()
        runCatching {
            val list = _channels.value
            // Text shared by many channels, stored once.
            val index = LinkedHashMap<String, Int>()
            fun id(v: String): Int = index.getOrPut(v) { index.size }
            list.forEach { c ->
                id(c.sourceId); id(c.sourceName); id(c.groupId); id(c.group)
                c.headers.keys.forEach { id(it) }
                c.catchup?.let { id(it.type) }
                c.drm?.let { id(it.scheme) }
            }
            val tmp = File(baseDir, "channels_cache3.tmp")
            java.io.DataOutputStream(java.io.BufferedOutputStream(tmp.outputStream(), 256 * 1024)).use { out ->
                fun opt(v: String?) { out.writeBoolean(v != null); if (v != null) out.writeUTF(v) }
                out.writeUTF(fingerprint)
                out.writeInt(embeddedEpgUrls.size)
                embeddedEpgUrls.forEach { (eid, urls) -> out.writeUTF(eid); out.writeInt(urls.size); urls.forEach { out.writeUTF(it) } }
                out.writeInt(index.size)
                index.keys.forEach { out.writeUTF(it) }
                out.writeInt(list.size)
                list.forEach { c ->
                    out.writeUTF(c.key)
                    out.writeInt(index.getValue(c.sourceId)); out.writeInt(index.getValue(c.sourceName))
                    out.writeUTF(c.name)
                    opt(c.tvgId); opt(c.tvgName); opt(c.logo)
                    out.writeInt(index.getValue(c.groupId)); out.writeInt(index.getValue(c.group))
                    out.writeInt(c.number)
                    out.writeUTF(c.url)
                    out.writeInt(c.headers.size)
                    c.headers.forEach { (k, v) -> out.writeInt(index.getValue(k)); out.writeUTF(v) }
                    out.writeBoolean(c.catchup != null)
                    c.catchup?.let { cu -> out.writeInt(index.getValue(cu.type)); opt(cu.source); out.writeInt(cu.days) }
                    out.writeBoolean(c.drm != null)
                    c.drm?.let { d ->
                        out.writeInt(index.getValue(d.scheme)); out.writeUTF(d.license)
                        out.writeInt(d.licenseHeaders.size); d.licenseHeaders.forEach { (k, v) -> out.writeUTF(k); out.writeUTF(v) }
                        opt(d.manifestType)
                    }
                }
            }
            if (!tmp.renameTo(savedChannelsFile)) { tmp.copyTo(savedChannelsFile, overwrite = true); tmp.delete() }
        }.onFailure { Log.w(TAG, "Couldn't save the channel list", it) }
    }

    // ---------------------------------------------------------------- saved guide

    /** Assign EPG needs the full guide-channel lists, which the saved guide skips. */
    @Volatile private var epgDetailsPending = false

    /** Builds the full guide details (for Assign EPG) if the saved guide was used. */
    fun ensureEpgDetails() {
        if (!epgDetailsPending) return
        epgDetailsPending = false
        scope.launch {
            mutex.withLock { buildPrograms(prefs.currentSettings(), collectEpgTargets()) }
        }
    }

    private val savedGuideFile get() = File(baseDir, "guide_cache.bin")

    // ---------------------------------------------------------------- guide database

    /** The time span currently loaded from the guide database (all of it if the database isn't used). */
    @Volatile private var loadedFrom = Long.MAX_VALUE
    @Volatile private var loadedTo = Long.MIN_VALUE
    @Volatile private var guideInDb = false
    private val rangeMutex = Mutex()

    private fun defaultWindow(now: Long = System.currentTimeMillis()) = (now - 3 * HOUR) to (now + 12 * HOUR)

    /**
     * Opening Live TV: if the guide database matches the current guide and channels, read just
     * the hours around now. Instant, however big the guide is.
     */
    /** What [preReadGuide] read ahead: the stored fingerprint, and the priority channels' listings. */
    private class GuidePreRead(val fingerprint: String?, val from: Long, val to: Long, val window: Map<String, List<EpgProgram>>)

    /** Channels to fill in first: the group you were last looking at, and your Favorites. */
    private val priorityFile get() = File(baseDir, "guide_priority.txt")

    private fun priorityKeys(): List<String> =
        runCatching { priorityFile.readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())

    /** Remembers which channels to load first next time, and loads them now if needed. */
    fun rememberPriority(keys: List<String>) {
        val list = keys.distinct().take(600)
        if (list.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            runCatching { priorityFile.writeText(list.joinToString("\n")) }
            if (!guideInDb) return@launch
            val missing = list.filter { it !in _programs.value }
            if (missing.isEmpty()) return@launch
            val got = runCatching { guideDb.range(loadedFrom, loadedTo, light = true, channels = missing) }.getOrNull() ?: return@launch
            rangeMutex.withLock { _programs.value = _programs.value + got }
        }
    }

    private fun preReadGuide(): GuidePreRead? = runCatching {
        val now = System.currentTimeMillis()
        val from = now - 60 * 60 * 1000L
        val to = now + 4 * HOUR
        val fp = guideDb.fingerprint() ?: return@runCatching null
        val keys = priorityKeys()
        val window = if (keys.isEmpty()) emptyMap() else guideDb.range(from, to, light = true, channels = keys)
        GuidePreRead(fp, from, to, window)
    }.getOrNull()

    /**
     * Opening Live TV: if the guide database matches the current guide and channels, show the
     * channels you were last looking at straight away (read ahead, short form), then fill in all
     * the others in the background. Instant, however big the guide is.
     */
    private suspend fun loadGuideFromDb(settings: LiveTvSettings, targets: List<EpgTarget>, pre: GuidePreRead?): Boolean = withContext(Dispatchers.IO) {
        if (_channels.value.isEmpty()) return@withContext false
        runCatching { savedGuideFile.delete() } // the old single-file copy isn't used any more
        val expected = guideFingerprint(settings, targets)
        val stored = pre?.fingerprint ?: runCatching { guideDb.fingerprint() }.getOrNull()
        if (stored != expected) {
            LIVE_REPORT.add(if (stored == null) "No stored guide yet" else "Stored guide out of date: ${fingerprintDiff(stored, expected)}")
            return@withContext false
        }
        val now = System.currentTimeMillis()
        val from = pre?.from ?: (now - 60 * 60 * 1000L)
        val to = pre?.to ?: (now + 4 * HOUR)
        _programs.value = pre?.window.orEmpty()
        _autoMatches.value = runCatching { guideDb.autoMatches() }.getOrDefault(emptyMap())
        loadedFrom = from
        loadedTo = to
        guideInDb = true
        lightPrograms = true
        epgDetailsPending = true
        LIVE_REPORT.add("First channels' listings ready: ${_programs.value.size} channels")
        // Everything else (short form), then the rest of the usual hours, in the background.
        scope.launch(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            val rest = runCatching { guideDb.range(from, to, light = true) }.getOrNull()
            if (rest != null) rangeMutex.withLock {
                val merged = HashMap<String, List<EpgProgram>>(rest)
                _programs.value.forEach { (k, v) -> if ((merged[k]?.size ?: 0) < v.size) merged[k] = v }
                _programs.value = merged
            }
            LIVE_REPORT.add("All channels' listings ready: ${_programs.value.size} channels in ${System.currentTimeMillis() - t0} ms")
            val (fullFrom, fullTo) = defaultWindow()
            ensureRange(fullFrom, fullTo)
        }
        true
    }

    /** Programs in memory may be the short form (no descriptions): see [programDetails]. */
    @Volatile var lightPrograms = false
        private set

    /** One show's full details (description, cast…), from the stored guide. */
    suspend fun programDetails(channelKey: String, startMs: Long): EpgProgram? = withContext(Dispatchers.IO) {
        if (!guideInDb) null else runCatching { guideDb.program(channelKey, startMs) }.getOrNull()
    }

    /**
     * Makes sure listings for [fromMs]..[toMs] are loaded (scrolling the guide ahead or back,
     * catch-up). Reads only what's missing; does nothing when the database isn't in use.
     */
    fun ensureRange(fromMs: Long, toMs: Long) {
        if (!guideInDb || (fromMs >= loadedFrom && toMs <= loadedTo)) return
        scope.launch {
            rangeMutex.withLock {
                if (fromMs >= loadedFrom && toMs <= loadedTo) return@withLock
                val from = minOf(fromMs, loadedFrom)
                val to = maxOf(toMs, loadedTo)
                val loaded = runCatching { guideDb.range(from, to, light = lightPrograms) }.getOrNull() ?: return@withLock
                // Keep any full single-channel schedules already loaded (overlay mode, catch-up).
                val merged = HashMap<String, List<EpgProgram>>(loaded)
                _programs.value.forEach { (ch, list) ->
                    val fromDb = merged[ch]
                    if (fromDb == null || list.size > fromDb.size) merged[ch] = list
                }
                _programs.value = merged
                loadedFrom = from
                loadedTo = to
            }
        }
    }

    // ---------------------------------------------------------------- Xtream catch-up archive

    /** Channels whose archive listings were already fetched this session. */
    private val archiveFetched = java.util.Collections.synchronizedSet(HashSet<String>())
    private val archivePermits = kotlinx.coroutines.sync.Semaphore(3)

    /**
     * Past listings for Xtream catch-up channels, the way TiviMate gets them: many providers'
     * guide file only covers about a day back, while the Xtream API keeps the full catch-up
     * archive (up to 7 days or more) per channel. Fetched for the channels you're looking at,
     * once per session, and stored with the rest of the guide.
     */
    fun loadXtreamArchive(channels: List<LiveChannel>) {
        val wanted = channels.filter { it.catchup != null && it.key !in archiveFetched }
        if (wanted.isEmpty()) return
        wanted.forEach { archiveFetched += it.key }
        scope.launch {
            val playlists = prefs.currentPlaylists().associateBy { it.id }
            val settings = prefs.currentSettings()
            wanted.forEach { ch ->
                val pl = playlists[ch.sourceId]?.takeIf { it.isXtream } ?: return@forEach
                val streamId = Regex("""/(\d+)(?:\.[a-z0-9]+)?(?:\?.*)?$""", RegexOption.IGNORE_CASE)
                    .find(ch.url)?.groupValues?.get(1) ?: return@forEach
                archivePermits.withPermit {
                    runCatching {
                        val url = "${pl.xtreamBase()}/player_api.php?username=" +
                            java.net.URLEncoder.encode(pl.xtreamUsername.trim(), "UTF-8") +
                            "&password=" + java.net.URLEncoder.encode(pl.xtreamPassword.trim(), "UTF-8") +
                            "&action=get_simple_data_table&stream_id=$streamId"
                        val json = JSONObject(getText(url, pl.userAgent.ifBlank { DEFAULT_UA }))
                        val arr = json.optJSONArray("epg_listings") ?: return@runCatching
                        fun b64(v: String): String = runCatching {
                            String(android.util.Base64.decode(v, android.util.Base64.DEFAULT), Charsets.UTF_8)
                        }.getOrDefault(v)
                        val list = ArrayList<EpgProgram>()
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val start = o.optString("start_timestamp").toLongOrNull()?.times(1000) ?: continue
                            val stop = o.optString("stop_timestamp").toLongOrNull()?.times(1000)
                                ?: o.optString("end_timestamp").toLongOrNull()?.times(1000) ?: continue
                            if (stop <= start) continue
                            val title = b64(o.optString("title")).trim()
                            if (title.isEmpty()) continue
                            list += EpgProgram(
                                startMs = start,
                                stopMs = stop,
                                title = title,
                                description = b64(o.optString("description")).trim().takeIf { it.isNotEmpty() }
                            )
                        }
                        if (list.isEmpty()) return@runCatching
                        list.sortBy { it.startMs }
                        val now = System.currentTimeMillis()
                        // Keep what the guide file already has from about now on; the archive
                        // fills in the past.
                        val pastEnd = minOf(now, list.last().stopMs)
                        val past = list.filter { it.startMs < pastEnd }
                        val existing = _programs.value[ch.key].orEmpty()
                        // Beyond the end of the guide file's listings, the archive's later
                        // shows are used too (some guide files only cover a day ahead).
                        val existingEnd = existing.maxOfOrNull { it.stopMs } ?: now
                        val later = list.filter { it.startMs >= maxOf(existingEnd, now) }
                        if (past.isEmpty() && later.isEmpty()) return@runCatching
                        val from = past.firstOrNull()?.startMs ?: pastEnd
                        val keep = existing.filter { it.startMs >= pastEnd || it.startMs < from }
                        val merged = (past + keep + later).sortedBy { it.startMs }
                            .fold(ArrayList<EpgProgram>()) { acc, p -> if (acc.isEmpty() || p.startMs >= acc.last().stopMs - 60_000) acc += p; acc }
                        if (guideInDb) runCatching {
                            if (past.isNotEmpty()) guideDb.putChannelRange(ch.key, from, pastEnd, past)
                            if (later.isNotEmpty()) guideDb.putChannelRange(ch.key, later.first().startMs, later.last().stopMs, later)
                        }
                        _programs.value = _programs.value + (ch.key to merged)
                        loadedFrom = minOf(loadedFrom, from)
                    }.onFailure { Log.w(TAG, "Catch-up archive for ${ch.name} unavailable", it) }
                }
            }
        }
    }

    /** One channel's whole schedule (overlay mode's days, catch-up following into later shows). */
    fun ensureChannelSchedule(channelKey: String) {
        if (!guideInDb) return
        scope.launch {
            val now = System.currentTimeMillis()
            val settings = prefs.currentSettings()
            val list = runCatching {
                guideDb.channelRange(channelKey, now - settings.epgPastHours.coerceAtLeast(1) * HOUR,
                    now + settings.epgFutureDays.coerceAtLeast(1) * 24 * HOUR)
            }.getOrNull() ?: return@launch
            val cur = _programs.value
            if ((cur[channelKey]?.size ?: 0) >= list.size) return@launch
            _programs.value = cur + (channelKey to list)
        }
    }

    /** Everything the matched guide depends on; a different value means the cache is stale. */
    private suspend fun guideFingerprint(settings: LiveTvSettings, targets: List<EpgTarget>): String {
        val overrides = prefs.userState.first().epgOverrides
        val sb = StringBuilder()
        sb.append(GUIDE_CACHE_VERSION).append('|')
        sb.append(settings.epgPastHours).append('|').append(settings.epgFutureDays).append('|')
        targets.forEach { t ->
            val f = epgFile(t.fileId)
            sb.append(t.fileId).append(':').append(f.length()).append(':').append(contentStamp(f)).append(',')
        }
        sb.append('|').append(overrides.hashCode())
        sb.append('|').append(_channels.value.size).append(':').append(_channels.value.sumOf { it.key.hashCode().toLong() })
        return sb.toString()
    }

    private suspend fun loadSavedGuide(settings: LiveTvSettings, targets: List<EpgTarget>): Boolean = withContext(Dispatchers.IO) {
        val file = savedGuideFile
        if (!file.exists() || _channels.value.isEmpty()) return@withContext false
        val expected = guideFingerprint(settings, targets)
        runCatching {
            java.io.DataInputStream(java.io.BufferedInputStream(java.util.zip.GZIPInputStream(file.inputStream()), 64 * 1024)).use { input ->
                if (input.readUTF() != expected) return@withContext false
                val programs = HashMap<String, List<EpgProgram>>()
                repeat(input.readInt()) {
                    val key = input.readUTF()
                    val list = ArrayList<EpgProgram>()
                    repeat(input.readInt()) {
                        val start = input.readLong()
                        val stop = input.readLong()
                        val title = input.readUTF()
                        val desc = readOpt(input)
                        val category = readOpt(input)
                        val episode = readOpt(input)
                        val icon = readOpt(input)
                        val year = input.readInt().takeIf { it > 0 }
                        val people = List(input.readInt()) { input.readUTF() }
                        list += EpgProgram(start, stop, title, desc, category, episode, icon, year, people)
                    }
                    programs[key] = list
                }
                val auto = HashMap<String, EpgAssignment>()
                repeat(input.readInt()) {
                    auto[input.readUTF()] = EpgAssignment(input.readUTF(), input.readUTF())
                }
                // Drop shows that have ended since the cache was saved (outside the window).
                val windowStart = System.currentTimeMillis() - settings.epgPastHours.coerceAtLeast(1) * HOUR
                _programs.value = programs.mapValues { (_, l) -> l.filter { it.stopMs > windowStart } }
                _autoMatches.value = auto
                epgDetailsPending = true
                true
            }
        }.getOrElse {
            Log.w(TAG, "Saved guide unreadable", it)
            false
        }
    }

    private fun saveGuide(fingerprint: String, programs: Map<String, List<EpgProgram>>, auto: Map<String, EpgAssignment>) {
        // Huge guides (hundreds of thousands of listings) would make a very large file that's
        // slow to write and read; those are simply re-matched at start as before.
        if (programs.values.sumOf { it.size } > MAX_SAVED_LISTINGS) {
            savedGuideFile.delete()
            return
        }
        runCatching {
            val tmp = File(baseDir, "guide_cache.tmp")
            java.io.DataOutputStream(java.io.BufferedOutputStream(java.util.zip.GZIPOutputStream(tmp.outputStream()), 64 * 1024)).use { out ->
                out.writeUTF(fingerprint)
                out.writeInt(programs.size)
                programs.forEach { (key, list) ->
                    out.writeUTF(key)
                    out.writeInt(list.size)
                    list.forEach { p ->
                        out.writeLong(p.startMs)
                        out.writeLong(p.stopMs)
                        out.writeUTF(p.title.take(500))
                        writeOpt(out, p.description?.take(4000))
                        writeOpt(out, p.category?.take(200))
                        writeOpt(out, p.episode?.take(200))
                        writeOpt(out, p.icon?.take(1000))
                        out.writeInt(p.year ?: 0)
                        out.writeInt(p.people.size.coerceAtMost(20))
                        p.people.take(20).forEach { out.writeUTF(it.take(200)) }
                    }
                }
                out.writeInt(auto.size)
                auto.forEach { (key, a) -> out.writeUTF(key); out.writeUTF(a.sourceId); out.writeUTF(a.xmltvId) }
            }
            if (!tmp.renameTo(savedGuideFile)) { tmp.copyTo(savedGuideFile, overwrite = true); tmp.delete() }
        }.onFailure { Log.w(TAG, "Couldn't save the guide", it) }
    }

    private fun readOpt(input: java.io.DataInputStream): String? = if (input.readBoolean()) input.readUTF() else null

    private fun writeOpt(out: java.io.DataOutputStream, value: String?) {
        out.writeBoolean(value != null)
        if (value != null) out.writeUTF(value)
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
                // Many providers' M3U links ("m3u_plus") also list every movie and episode,
                // often 100,000+ entries. Those aren't TV channels, and loading them all into the
                // guide used enough memory to crash Live TV on some devices.
                if (isVodEntry(e.url)) continue
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
                    // Names stay exactly as the playlist has them (the playlist is shown, if you want
                    // it, with "Group by playlist").
                    group = groupTitle,
                    number = number,
                    url = e.url,
                    headers = headers,
                    catchup = e.catchup,
                    drm = e.drm
                )
            }
            prefs.updatePlaylists { list -> list.map { if (it.id == pl.id) it.copy(channelCount = parsed.entries.size) else it } }
        }
        _channels.value = out
    }

    private suspend fun buildPrograms(settings: LiveTvSettings, targets: List<EpgTarget>) {
        val t0 = System.currentTimeMillis()
        buildProgramsInner(settings, targets)
        LIVE_REPORT.add("Guide matched and stored: ${_programs.value.size} channels in ${System.currentTimeMillis() - t0} ms")
    }

    private suspend fun buildProgramsInner(settings: LiveTvSettings, targets: List<EpgTarget>) = withContext(Dispatchers.IO) {
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
        epgDetailsPending = false
        // Store the whole guide in the database and keep only the hours around now in memory.
        val stored = runCatching { guideDb.replaceAll(guideFingerprint(settings, targets), result, auto) }
            .onFailure { Log.w(TAG, "Couldn't store the guide", it) }.isSuccess
        if (stored) {
            lightPrograms = false
            val (from, to) = defaultWindow()
            _programs.value = result.mapValues { (_, l) -> l.filter { it.stopMs > from && it.startMs < to } }.filterValues { it.isNotEmpty() }
            loadedFrom = from
            loadedTo = to
            guideInDb = true
        } else {
            guideInDb = false
            loadedFrom = Long.MIN_VALUE
            loadedTo = Long.MAX_VALUE
        }
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

    /**
     * Downloads [url] to [target]. Returns false when nothing changed: the server said "not
     * modified" (we send the ETag / Last-Modified from last time), or the new file is identical.
     * Unchanged files skip the re-reading and re-matching that follows a download.
     */
    private suspend fun download(url: String, target: File, userAgent: String): Boolean = withContext(Dispatchers.IO) {
        require(url.isNotBlank()) { "No URL set" }
        target.parentFile?.mkdirs()
        val meta = File(target.parentFile, target.name + ".meta")
        val (etag, lastModified) = if (target.exists() && meta.exists()) {
            meta.readLines().let { it.getOrNull(0).orEmpty() to it.getOrNull(1).orEmpty() }
        } else "" to ""
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent.ifBlank { DEFAULT_UA })
            .apply {
                if (etag.isNotBlank()) header("If-None-Match", etag)
                if (lastModified.isNotBlank()) header("If-Modified-Since", lastModified)
            }
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 304 && target.exists()) {
                target.setLastModified(System.currentTimeMillis()) // checked again after the usual interval
                return@withContext false
            }
            if (!response.isSuccessful) error(httpError(response.code))
            val body = response.body ?: error("Empty response")
            val tmp = File(target.parentFile, target.name + ".tmp")
            body.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
            if (tmp.length() == 0L) {
                tmp.delete()
                error("Empty response")
            }
            runCatching {
                meta.writeText(response.header("ETag").orEmpty() + "\n" + response.header("Last-Modified").orEmpty())
            }
            val same = target.exists() && target.length() == tmp.length() && sameContent(target, tmp)
            if (same) {
                tmp.delete()
                target.setLastModified(System.currentTimeMillis())
                return@withContext false
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            markChanged(target)
            true
        }
    }

    /**
     * When a downloaded file last really changed. The saved channel list and guide are checked
     * against this, not the file's date: the date is also bumped every time an update finds
     * nothing new, which used to make every restart re-read all playlists and guides.
     */
    private fun stampFile(f: File) = File(f.parentFile, f.name + ".stamp")

    private fun contentStamp(f: File): String =
        runCatching { stampFile(f).readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: "${f.length()}"

    private fun markChanged(f: File) {
        runCatching { stampFile(f).writeText(System.currentTimeMillis().toString()) }
    }

    private fun sameContent(a: File, b: File): Boolean = runCatching {
        fun digest(f: File): ByteArray {
            val md = java.security.MessageDigest.getInstance("MD5")
            f.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest()
        }
        digest(a).contentEquals(digest(b))
    }.getOrDefault(false)

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
            // Which stream formats this account may use (some panels turn off .ts and only
            // allow .m3u8; asking for .ts then fails on every channel with "bad http status").
            allowedFormats = info.optJSONObject("user_info")?.optJSONArray("allowed_output_formats")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it).lowercase() }.toSet()
            } ?: emptySet()
            // Catch-up links are in the server's local time. When the server's time zone differs
            // from the TV's (say the server runs on UTC and the TV is in London in summer),
            // every replay started an hour off. Remember the server's zone for catch-up.
            info.optJSONObject("server_info")?.optString("timezone")?.takeIf { it.isNotBlank() }?.let { tz ->
                if (tz != pl.serverTimezone) {
                    prefs.updatePlaylists { list -> list.map { if (it.id == pl.id) it.copy(serverTimezone = tz) else it } }
                }
            }
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

    /** Stream formats the provider allows for the account being loaded (from user_info). */
    @Volatile private var allowedFormats: Set<String> = emptySet()

    private fun xtreamExtension(pl: PlaylistSource): String = when (pl.streamFormat) {
        "ts" -> "ts"
        "m3u8" -> "m3u8"
        // Auto: .ts unless the provider only allows HLS.
        else -> if (allowedFormats.isNotEmpty() && "ts" !in allowedFormats && "m3u8" in allowedFormats) "m3u8" else "ts"
    }

    private fun writeXtreamPlaylist(pl: PlaylistSource, url: String, categories: Map<String, String>, target: File) {
        val ext = xtreamExtension(pl)
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
                        out.write("$base/live/$user/$pass/$streamId.$ext\n")
                        count++
                        if (count % 500 == 0) setStatus(loading = true, message = "Loading channels from ${pl.name}… ${"%,d".format(count)}")
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
        private const val GUIDE_CACHE_VERSION = 1
        private const val CHANNELS_CACHE_VERSION = 3
        private const val MAX_SAVED_LISTINGS = 300_000
        /** Titles guides use when they have no real listing. */
        private val PLACEHOLDER_TITLES = Regex(
            """(programming|program|programme|no information|no info|no program information|no programme information|""" +
                """to be announced|tba|tbd|n/?a|off air|no data|no epg|not available|information not available|""" +
                """regular programming|scheduled programming|paid programming|coming soon)\.?"""
        )
        private val VOD_PATH = Regex("""/(movie|movies|series|vod)/""", RegexOption.IGNORE_CASE)
        private val VOD_EXT = Regex("""\.(mp4|mkv|avi|mov|wmv|m4v|webm|flv)(\?|$)""", RegexOption.IGNORE_CASE)

        /** A movie or episode in an M3U (by its link), not a live channel. */
        fun isVodEntry(url: String): Boolean = VOD_PATH.containsMatchIn(url) || VOD_EXT.containsMatchIn(url)

        /** "Programming", "No information", "To Be Announced"… (no real show to show a poster for). */
        fun isPlaceholderTitle(title: String): Boolean = PLACEHOLDER_TITLES.matches(title.trim().lowercase())

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
