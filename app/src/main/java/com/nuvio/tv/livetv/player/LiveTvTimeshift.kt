package com.nuvio.tv.livetv.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pause and rewind live TV, for channels without catch-up: when you pause, the channel is
 * recorded to the device (one connection to the provider, the same one playback used), and
 * play continues from the recording, behind live. "Go live" drops it.
 *
 *  - TS streams: the stream is written to one growing file, played as it grows.
 *  - HLS (m3u8) streams: each segment is saved as it appears, with a local playlist; older
 *    segments are dropped past the time limit.
 *
 * Everything lives in the app's cache folder and is deleted when you go live, change channel
 * or leave Live TV.
 */
class LiveTvTimeshift(private val context: Context) {

    enum class Kind { TS, HLS }

    /** What's being recorded: where it started (wall clock) and how to play it. */
    data class Session(
        val kind: Kind,
        val startedAtMs: Long,
        val playUri: Uri,
        val dir: File
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile var session: Session? = null
        private set
    @Volatile private var recording = false

    /** Time dropped from the start of an HLS recording (past the time limit). */
    @Volatile var trimmedMs: Long = 0L
        private set

    val isRecording: Boolean get() = recording

    /** Enough room on the device for [minutes] of recording (rough: about 35 MB a minute). */
    fun hasRoomFor(minutes: Int): Boolean =
        runCatching { context.cacheDir.usableSpace > (minutes * 35L + 300L) * 1024 * 1024 }.getOrDefault(false)

    /**
     * Starts recording [url] (with [headers]). Returns the session to play, or null if this
     * stream can't be recorded (encrypted HLS, a link that won't open…).
     */
    fun start(url: String, headers: Map<String, String>, keepMinutes: Int): Session? {
        stop()
        val dir = File(context.cacheDir, "timeshift/" + System.currentTimeMillis()).apply { mkdirs() }
        val isHls = url.lowercase().substringBefore('?').endsWith(".m3u8") || url.contains("m3u8", ignoreCase = true)
        val now = System.currentTimeMillis()
        trimmedMs = 0L
        recording = true
        val s = if (isHls) {
            val local = File(dir, "index.m3u8")
            Session(Kind.HLS, now, Uri.fromFile(local), dir).also { job = scope.launch { recordHls(url, headers, dir, local, keepMinutes) } }
        } else {
            val file = File(dir, "stream.ts")
            file.createNewFile()
            Session(Kind.TS, now, Uri.fromFile(file), dir).also { job = scope.launch { recordTs(url, headers, file, keepMinutes) } }
        }
        session = s
        return s
    }

    /** Stops recording and deletes it. */
    fun stop() {
        recording = false
        job?.cancel()
        job = null
        session?.dir?.let { d -> scope.launch { runCatching { d.deleteRecursively() } } }
        session = null
        trimmedMs = 0L
        // Leftovers from earlier (a crash, the app being closed while paused).
        scope.launch {
            runCatching {
                File(context.cacheDir, "timeshift").listFiles()?.forEach { f ->
                    if (f.lastModified() < System.currentTimeMillis() - 6 * 60 * 60 * 1000L) f.deleteRecursively()
                }
            }
        }
    }

    private fun open(url: String, headers: Map<String, String>): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.instanceFollowRedirects = true
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (headers.keys.none { it.equals("User-Agent", true) }) {
            c.setRequestProperty("User-Agent", com.nuvio.tv.livetv.data.LiveTvRepository.DEFAULT_UA)
        }
        return c
    }

    // ------------------------------------------------------------------ TS

    private suspend fun recordTs(url: String, headers: Map<String, String>, file: File, keepMinutes: Int) {
        val until = System.currentTimeMillis() + keepMinutes * 60_000L
        try {
            val c = open(url, headers)
            c.inputStream.use { input ->
                java.io.FileOutputStream(file, true).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (scope.isActive && recording && System.currentTimeMillis() < until) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Recording stopped: ${e.message}")
        } finally {
            recording = false
        }
    }

    // ------------------------------------------------------------------ HLS

    private data class Seg(val seq: Long, val durationSec: Double, val url: String)

    private suspend fun recordHls(url: String, headers: Map<String, String>, dir: File, local: File, keepMinutes: Int) {
        try {
            var mediaUrl = url
            var text = fetchText(mediaUrl, headers)
            // A master playlist: take the best-quality variant.
            if (text.contains("#EXT-X-STREAM-INF")) {
                val lines = text.lines()
                var best = -1L
                var bestUri: String? = null
                lines.forEachIndexed { i, l ->
                    if (l.startsWith("#EXT-X-STREAM-INF")) {
                        val bw = Regex("""BANDWIDTH=(\d+)""").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                        val uri = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                        if (uri != null && bw >= best) { best = bw; bestUri = uri }
                    }
                }
                mediaUrl = resolve(mediaUrl, bestUri ?: return)
                text = fetchText(mediaUrl, headers)
            }
            // Encrypted streams aren't recorded (the keys would have to be saved too).
            if (text.contains("#EXT-X-KEY") && !text.contains("METHOD=NONE")) {
                Log.w(TAG, "Encrypted HLS: not recorded")
                recording = false
                return
            }
            val saved = ArrayList<Seg>()
            var lastSeq = -1L
            var mapLine: String? = null
            var target = 6
            while (scope.isActive && recording) {
                val lines = text.lines()
                var seq = Regex("""#EXT-X-MEDIA-SEQUENCE:(\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                target = Regex("""#EXT-X-TARGETDURATION:(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: target
                // fMP4 streams: the init segment, once.
                if (mapLine == null) {
                    Regex("""#EXT-X-MAP:URI="([^"]+)"""").find(text)?.groupValues?.get(1)?.let { m ->
                        download(resolve(mediaUrl, m), headers, File(dir, "init.mp4"))
                        mapLine = "#EXT-X-MAP:URI=\"init.mp4\""
                    }
                }
                var dur = 0.0
                for (l in lines) {
                    when {
                        l.startsWith("#EXTINF:") -> dur = l.removePrefix("#EXTINF:").substringBefore(',').toDoubleOrNull() ?: target.toDouble()
                        l.isNotBlank() && !l.startsWith("#") -> {
                            if (seq > lastSeq) {
                                val segFile = File(dir, "seg$seq.ts")
                                if (download(resolve(mediaUrl, l), headers, segFile)) {
                                    saved += Seg(seq, dur, segFile.name)
                                    lastSeq = seq
                                }
                            }
                            seq++
                        }
                    }
                }
                // Keep only the time limit's worth.
                var total = saved.sumOf { it.durationSec }
                while (saved.size > 3 && total > keepMinutes * 60.0) {
                    val first = saved.removeAt(0)
                    trimmedMs += (first.durationSec * 1000).toLong()
                    total -= first.durationSec
                    runCatching { File(dir, first.url).delete() }
                }
                writeLocalPlaylist(local, saved, target, mapLine)
                delay((target * 1000L / 2).coerceIn(1_000L, 6_000L))
                text = runCatching { fetchText(mediaUrl, headers) }.getOrElse { delay(2_000); continue }
            }
        } catch (e: Exception) {
            Log.w(TAG, "HLS recording stopped: ${e.message}")
        } finally {
            recording = false
        }
    }

    private fun writeLocalPlaylist(file: File, segs: List<Seg>, target: Int, mapLine: String?) {
        if (segs.isEmpty()) return
        val sb = StringBuilder()
        sb.append("#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-TARGETDURATION:").append(target + 1).append('\n')
        sb.append("#EXT-X-MEDIA-SEQUENCE:").append(segs.first().seq).append('\n')
        sb.append("#EXT-X-PLAYLIST-TYPE:EVENT\n")
        mapLine?.let { sb.append(it).append('\n') }
        segs.forEach { s -> sb.append("#EXTINF:").append(String.format(java.util.Locale.US, "%.3f", s.durationSec)).append(",\n").append(s.url).append('\n') }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(sb.toString())
        if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
    }

    private fun fetchText(url: String, headers: Map<String, String>): String =
        open(url, headers).inputStream.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun download(url: String, headers: Map<String, String>, to: File): Boolean = runCatching {
        val tmp = File(to.parentFile, to.name + ".part")
        open(url, headers).inputStream.use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
        tmp.renameTo(to)
    }.getOrDefault(false)

    private fun resolve(base: String, ref: String): String = runCatching { URL(URL(base), ref).toString() }.getOrDefault(ref)

    /**
     * Plays a file that's still being written (the TS recording): at the end of what's written
     * so far it waits for more, instead of ending, while recording carries on.
     */
    inner class GrowingFileDataSource : BaseDataSource(false) {
        private var file: RandomAccessFile? = null
        private var uri: Uri? = null

        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            transferInitializing(dataSpec)
            val f = RandomAccessFile(File(dataSpec.uri.path ?: ""), "r")
            f.seek(dataSpec.position)
            file = f
            transferStarted(dataSpec)
            return C.LENGTH_UNSET.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val f = file ?: return C.RESULT_END_OF_INPUT
            var waited = 0
            while (true) {
                val n = f.read(buffer, offset, length)
                if (n > 0) { bytesTransferred(n); return n }
                if (!recording || waited > 30_000) return C.RESULT_END_OF_INPUT
                Thread.sleep(150)
                waited += 150
            }
        }

        override fun getUri(): Uri? = uri

        override fun close() {
            runCatching { file?.close() }
            file = null
            if (uri != null) { uri = null; transferEnded() }
        }
    }

    fun dataSourceFactory(): DataSource.Factory = DataSource.Factory { GrowingFileDataSource() }

    private companion object { const val TAG = "LiveTvTimeshift" }
}
