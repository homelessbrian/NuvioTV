package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.CatchupInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Builds archive (catch-up) URLs for the common provider formats:
 * default / append (template in catchup-source), shift, flussonic and xtream codes.
 */
object CatchupUrlBuilder {

    fun isAvailable(catchup: CatchupInfo?, programStartMs: Long, nowMs: Long): Boolean {
        if (catchup == null) return false
        if (programStartMs >= nowMs) return false
        val maxAgeMs = catchup.days.coerceAtLeast(1) * 24L * 60 * 60 * 1000
        return nowMs - programStartMs <= maxAgeMs
    }

    fun build(
        liveUrl: String,
        catchup: CatchupInfo,
        startMs: Long,
        stopMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ): String? {
        val start = startMs / 1000
        val end = stopMs / 1000
        val now = nowMs / 1000
        val duration = (end - start).coerceAtLeast(60)
        return when (catchup.type) {
            "append" -> catchup.source?.let { liveUrl + fill(it, start, end, now, duration) }
            "default" -> {
                val src = catchup.source
                when {
                    src.isNullOrBlank() -> shift(liveUrl, start, now)
                    src.startsWith("http", ignoreCase = true) -> fill(src, start, end, now, duration)
                    else -> liveUrl + fill(src, start, end, now, duration)
                }
            }
            "shift", "timeshift" -> shift(liveUrl, start, now)
            "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> flussonic(liveUrl, start, duration)
            "xc", "xtream" -> xtream(liveUrl, startMs, duration)
            else -> catchup.source?.let { fill(it, start, end, now, duration) }
        }
    }

    private fun shift(url: String, start: Long, now: Long): String {
        val sep = if (url.contains('?')) '&' else '?'
        return "$url${sep}utc=$start&lutc=$now"
    }

    private fun flussonic(url: String, start: Long, duration: Long): String? {
        val m = Regex("""^(https?://[^/]+)/(.*)/([^/?]*)(\?.*)?$""").find(url) ?: return null
        val host = m.groupValues[1]
        val stream = m.groupValues[2]
        val file = m.groupValues[3]
        val query = m.groupValues[4]
        return if (file.endsWith(".m3u8") || file.isEmpty()) {
            val base = file.removeSuffix(".m3u8").ifEmpty { "index" }
            "$host/$stream/$base-$start-$duration.m3u8$query"
        } else {
            "$host/$stream/timeshift_abs-$start.ts$query"
        }
    }

    private fun xtream(url: String, startMs: Long, duration: Long): String? {
        // http://host:port/(live/)?user/pass/id(.ext)
        val m = Regex("""^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\d+)(\.[a-z0-9]+)?$""", RegexOption.IGNORE_CASE)
            .find(url) ?: return null
        val (host, user, pass, id) = m.destructured
        val ext = m.groupValues[5].ifEmpty { ".ts" }
        val fmt = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US)
        val minutes = (duration / 60).coerceAtLeast(1)
        return "$host/timeshift/$user/$pass/$minutes/${fmt.format(Date(startMs))}/$id$ext"
    }

    internal fun fill(template: String, start: Long, end: Long, now: Long, duration: Long): String {
        var out = template
        // {utc:YmdHMS} / ${start:Y-m-d} style formatted dates.
        out = Regex("""\$?\{(utc|start|utcend|end|lutc|now|timestamp):([^}]+)}""").replace(out) { m ->
            val epoch = when (m.groupValues[1]) {
                "utcend", "end" -> end
                "lutc", "now", "timestamp" -> now
                else -> start
            }
            formatPattern(m.groupValues[2], epoch)
        }
        out = Regex("""\$?\{duration:(\d+)}""").replace(out) { m ->
            val divisor = m.groupValues[1].toLongOrNull()?.coerceAtLeast(1) ?: 1
            (duration / divisor).toString()
        }
        out = Regex("""\$?\{offset:(\d+)}""").replace(out) { m ->
            val divisor = m.groupValues[1].toLongOrNull()?.coerceAtLeast(1) ?: 1
            ((now - start) / divisor).toString()
        }
        val simple = mapOf(
            "utc" to start, "start" to start, "timestamp" to now,
            "utcend" to end, "end" to end,
            "lutc" to now, "now" to now,
            "duration" to duration, "offset" to (now - start)
        )
        simple.forEach { (k, v) ->
            out = out.replace("\${$k}", v.toString()).replace("{$k}", v.toString())
        }
        // Bare {Y}{m}{d}{H}{M}{S} refer to the program start in local time.
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = start * 1000 }
        out = out
            .replace("{Y}", "%04d".format(cal.get(java.util.Calendar.YEAR)))
            .replace("{m}", "%02d".format(cal.get(java.util.Calendar.MONTH) + 1))
            .replace("{d}", "%02d".format(cal.get(java.util.Calendar.DAY_OF_MONTH)))
            .replace("{H}", "%02d".format(cal.get(java.util.Calendar.HOUR_OF_DAY)))
            .replace("{M}", "%02d".format(cal.get(java.util.Calendar.MINUTE)))
            .replace("{S}", "%02d".format(cal.get(java.util.Calendar.SECOND)))
        return out
    }

    private fun formatPattern(pattern: String, epochSec: Long): String {
        val javaPattern = buildString {
            pattern.forEach { c ->
                append(
                    when (c) {
                        'Y' -> "yyyy"; 'm' -> "MM"; 'd' -> "dd"
                        'H' -> "HH"; 'M' -> "mm"; 'S' -> "ss"
                        else -> if (c.isLetter()) "'$c'" else c.toString()
                    }
                )
            }
        }
        return runCatching {
            SimpleDateFormat(javaPattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(epochSec * 1000))
        }.getOrDefault(epochSec.toString())
    }
}
