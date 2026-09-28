package com.nuvio.tv.livetv.data

import android.util.Xml
import com.nuvio.tv.livetv.model.EpgProgram
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.Calendar
import java.util.TimeZone

data class XmltvChannel(
    val id: String,
    val displayNames: List<String>,
    val icon: String?
)

data class XmltvResult(
    val channels: Map<String, XmltvChannel>,
    /** Programs keyed by XMLTV channel id, sorted by start time. */
    val programs: Map<String, List<EpgProgram>>
)

/**
 * Streaming XMLTV parser. Large provider guides are often 50–200 MB, so only
 * programs inside [windowStartMs, windowEndMs] and (once the channel list has
 * been read) only channels that match the playlist are kept in memory.
 */
object XmltvParser {

    fun parse(
        input: InputStream,
        windowStartMs: Long,
        windowEndMs: Long,
        selectChannels: (Map<String, XmltvChannel>) -> Set<String>?
    ): XmltvResult {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        val channels = LinkedHashMap<String, XmltvChannel>()
        val programs = HashMap<String, MutableList<EpgProgram>>()
        var wanted: Set<String>? = null
        var wantedResolved = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> readChannel(parser)?.let { channels[it.id] = it }
                    "programme" -> { // XMLTV's element name: keep this spelling
                        if (!wantedResolved) {
                            wanted = selectChannels(channels)
                            wantedResolved = true
                        }
                        val channelId = parser.getAttributeValue(null, "channel")
                        val keep = channelId != null && (wanted == null || channelId in wanted!!)
                        if (keep) {
                            val program = readProgramme(parser, windowStartMs, windowEndMs)
                            if (program != null) {
                                programs.getOrPut(channelId!!) { ArrayList() }.add(program)
                            }
                        } else {
                            skip(parser)
                        }
                    }
                }
            }
            event = parser.next()
        }
        val sorted = programs.mapValues { (_, list) -> list.sortedBy { it.startMs } }
        return XmltvResult(channels, sorted)
    }

    private fun readChannel(parser: XmlPullParser): XmltvChannel? {
        val id = parser.getAttributeValue(null, "id") ?: run { skip(parser); return null }
        val names = mutableListOf<String>()
        var icon: String? = null
        val depth = parser.depth
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "display-name" -> readText(parser).takeIf { it.isNotBlank() }?.let { names.add(it.trim()) }
                    "icon" -> {
                        icon = parser.getAttributeValue(null, "src")
                        skip(parser)
                    }
                    else -> skip(parser)
                }
            }
            if (event == XmlPullParser.END_DOCUMENT) break
            event = parser.next()
        }
        return XmltvChannel(id, names, icon)
    }

    private fun readProgramme(parser: XmlPullParser, windowStart: Long, windowEnd: Long): EpgProgram? {
        val start = parseTime(parser.getAttributeValue(null, "start"))
        val stop = parseTime(parser.getAttributeValue(null, "stop"))
        if (start == null || stop == null || stop <= windowStart || start >= windowEnd) {
            skip(parser)
            return null
        }
        var title: String? = null
        var desc: String? = null
        var category: String? = null
        var episode: String? = null
        var subTitle: String? = null
        var icon: String? = null
        var date: String? = null
        val people = ArrayList<String>()
        val depth = parser.depth
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "title" -> { val t = readText(parser); if (title == null) title = t }
                    "sub-title" -> { val t = readText(parser); if (subTitle == null) subTitle = t }
                    "desc" -> { val t = readText(parser); if (desc == null) desc = t }
                    "category" -> { val t = readText(parser); if (category == null) category = t }
                    "episode-num" -> {
                        val system = parser.getAttributeValue(null, "system")
                        val t = readText(parser)
                        if (system == "onscreen" || episode == null) episode = formatEpisode(system, t)
                    }
                    "icon" -> { icon = parser.getAttributeValue(null, "src"); skip(parser) }
                    "date" -> { val t = readText(parser); if (date == null) date = t }
                    "credits" -> readCredits(parser, people)
                    else -> skip(parser)
                }
            }
            if (event == XmlPullParser.END_DOCUMENT) break
            event = parser.next()
        }
        val year = date?.trim()?.take(4)?.toIntOrNull()?.takeIf { it in 1900..2100 }
            ?: yearIn(title) ?: yearIn(desc)
        val fullDesc = listOfNotNull(subTitle?.takeIf { it.isNotBlank() }, desc?.takeIf { it.isNotBlank() })
            .joinToString("\n").ifBlank { null }
        return EpgProgram(
            startMs = start,
            stopMs = stop,
            title = title?.trim().orEmpty().ifBlank { "No information" },
            description = fullDesc,
            category = category?.trim(),
            episode = episode,
            icon = icon,
            year = year,
            people = people.take(12)
        )
    }

    private val yearInParens = Regex("""\((19\d{2}|20\d{2})\)""")

    /** "(1990)" in a title or description. Bare numbers are ignored: too easy to misread. */
    private fun yearIn(text: String?): Int? =
        text?.let { yearInParens.find(it)?.groupValues?.get(1)?.toIntOrNull() }

    private fun readCredits(parser: XmlPullParser, out: MutableList<String>) {
        val depth = parser.depth
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "actor", "director" -> readText(parser).trim().takeIf { it.isNotEmpty() }?.let { out += it }
                    else -> skip(parser)
                }
            }
            if (event == XmlPullParser.END_DOCUMENT) break
            event = parser.next()
        }
    }

    private fun formatEpisode(system: String?, raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        if (system == "xmltv_ns") {
            val parts = text.split('.')
            val season = parts.getOrNull(0)?.substringBefore('/')?.trim()?.toIntOrNull()
            val ep = parts.getOrNull(1)?.substringBefore('/')?.trim()?.toIntOrNull()
            return when {
                season != null && ep != null -> "S${season + 1} E${ep + 1}"
                ep != null -> "E${ep + 1}"
                else -> null
            }
        }
        return text
    }

    private fun readText(parser: XmlPullParser): String {
        val sb = StringBuilder()
        val depth = parser.depth
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (event == XmlPullParser.TEXT) sb.append(parser.text)
            if (event == XmlPullParser.END_DOCUMENT) break
            event = parser.next()
        }
        return sb.toString()
    }

    private fun skip(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) return
        var depth = 1
        while (depth != 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    /** Parses "YYYYMMDDhhmmss +zzzz" (seconds and offset optional). */
    internal fun parseTime(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val v = value.trim()
        val digits = v.takeWhile { it.isDigit() }
        if (digits.length < 12) return null
        val year = digits.substring(0, 4).toInt()
        val month = digits.substring(4, 6).toInt()
        val day = digits.substring(6, 8).toInt()
        val hour = digits.substring(8, 10).toInt()
        val minute = digits.substring(10, 12).toInt()
        val second = if (digits.length >= 14) digits.substring(12, 14).toInt() else 0
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(year, month - 1, day, hour, minute, second)
        var ms = cal.timeInMillis
        val offset = v.substring(digits.length).trim()
        if (offset.length >= 5 && (offset[0] == '+' || offset[0] == '-')) {
            val sign = if (offset[0] == '-') -1 else 1
            val oh = offset.substring(1, 3).toIntOrNull() ?: 0
            val om = offset.substring(3, 5).toIntOrNull() ?: 0
            ms -= sign * (oh * 3_600_000L + om * 60_000L)
        }
        return ms
    }
}
