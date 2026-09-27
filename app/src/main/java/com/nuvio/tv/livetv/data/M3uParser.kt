package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.CatchupInfo
import java.io.BufferedReader

/** Raw channel entry as read from a playlist, before numbering and keying. */
data class M3uEntry(
    val name: String,
    val tvgId: String?,
    val tvgName: String?,
    val logo: String?,
    val group: String?,
    val chno: Int?,
    val url: String,
    val headers: Map<String, String>,
    val catchup: CatchupInfo?
)

data class M3uPlaylist(
    val epgUrls: List<String>,
    val entries: List<M3uEntry>
)

/**
 * Streaming M3U / M3U8 (extended) parser. Handles the attributes IPTV providers
 * actually use: tvg-id, tvg-name, tvg-logo, group-title, tvg-chno, catchup*,
 * #EXTGRP, #EXTVLCOPT user agent / referrer and Kodi style "url|User-Agent=..".
 */
object M3uParser {

    private val attrRegex = Regex("""([A-Za-z0-9_\-]+)\s*=\s*"([^"]*)"""")
    private val attrRegexUnquoted = Regex("""([A-Za-z0-9_\-]+)\s*=\s*([^\s",]+)""")

    fun parse(reader: BufferedReader): M3uPlaylist {
        val epgUrls = mutableListOf<String>()
        val entries = ArrayList<M3uEntry>(4096)

        var pendingInfo: String? = null
        var pendingGroup: String? = null
        val pendingHeaders = mutableMapOf<String, String>()

        reader.lineSequence().forEach { rawLine ->
            val line = rawLine.trim().removePrefix("\uFEFF")
            if (line.isEmpty()) return@forEach
            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val attrs = attributes(line)
                    listOf("url-tvg", "x-tvg-url", "tvg-url").forEach { key ->
                        attrs[key]?.split(',')?.map { it.trim() }?.filter { it.startsWith("http") }
                            ?.let { epgUrls.addAll(it) }
                    }
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pendingInfo = line
                    pendingGroup = null
                    pendingHeaders.clear()
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    pendingGroup = line.substringAfter(':').trim().ifEmpty { null }
                }
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = line.substringAfter(':')
                    val key = opt.substringBefore('=').trim().lowercase()
                    val value = opt.substringAfter('=', "").trim()
                    when (key) {
                        "http-user-agent" -> pendingHeaders["User-Agent"] = value
                        "http-referrer", "http-referer" -> pendingHeaders["Referer"] = value
                        "http-origin" -> pendingHeaders["Origin"] = value
                    }
                }
                line.startsWith("#") -> Unit
                else -> {
                    val info = pendingInfo
                    if (info != null) {
                        entries.add(buildEntry(info, pendingGroup, line, pendingHeaders.toMap()))
                    }
                    pendingInfo = null
                    pendingGroup = null
                    pendingHeaders.clear()
                }
            }
        }
        return M3uPlaylist(epgUrls.distinct(), entries)
    }

    private fun buildEntry(
        info: String,
        extGroup: String?,
        urlLine: String,
        vlcHeaders: Map<String, String>
    ): M3uEntry {
        val attrs = attributes(info)
        // Display name follows the first comma that is outside quotes.
        val name = displayName(info).ifBlank { attrs["tvg-name"].orEmpty() }.ifBlank { "Channel" }

        val headers = LinkedHashMap(vlcHeaders)
        var url = urlLine
        val pipe = urlLine.indexOf('|')
        if (pipe > 0) {
            url = urlLine.substring(0, pipe)
            urlLine.substring(pipe + 1).split('&').forEach { part ->
                val k = part.substringBefore('=').trim()
                val v = java.net.URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
                if (k.isNotEmpty()) headers[normalizeHeader(k)] = v
            }
        }
        attrs["user-agent"]?.let { headers["User-Agent"] = it }
        attrs["http-user-agent"]?.let { headers["User-Agent"] = it }
        attrs["http-referrer"]?.let { headers["Referer"] = it }

        val catchupType = attrs["catchup"] ?: attrs["catchup-type"]
        val catchupDays = (attrs["catchup-days"] ?: attrs["tvg-rec"] ?: attrs["timeshift"])?.toIntOrNull()
        val catchup = if (catchupType != null || (catchupDays ?: 0) > 0) {
            CatchupInfo(
                type = (catchupType ?: "default").lowercase(),
                source = attrs["catchup-source"],
                days = catchupDays ?: 1
            )
        } else null

        return M3uEntry(
            name = name.trim(),
            tvgId = attrs["tvg-id"]?.trim()?.ifEmpty { null },
            tvgName = attrs["tvg-name"]?.trim()?.ifEmpty { null },
            logo = (attrs["tvg-logo"] ?: attrs["logo"])?.trim()?.ifEmpty { null },
            group = (attrs["group-title"]?.trim()?.ifEmpty { null } ?: extGroup)
                ?.split(';')?.firstOrNull()?.trim(),
            chno = (attrs["tvg-chno"] ?: attrs["channel-number"] ?: attrs["tvg-num"])?.trim()?.toIntOrNull(),
            url = url.trim(),
            headers = headers,
            catchup = catchup
        )
    }

    private fun normalizeHeader(key: String): String = when (key.lowercase()) {
        "user-agent" -> "User-Agent"
        "referer", "referrer" -> "Referer"
        "origin" -> "Origin"
        "cookie" -> "Cookie"
        else -> key
    }

    internal fun attributes(line: String): Map<String, String> {
        val map = HashMap<String, String>()
        attrRegex.findAll(line).forEach { m -> map[m.groupValues[1].lowercase()] = m.groupValues[2] }
        attrRegexUnquoted.findAll(line).forEach { m ->
            val key = m.groupValues[1].lowercase()
            if (key !in map && key != "extinf") map[key] = m.groupValues[2]
        }
        return map
    }

    internal fun displayName(info: String): String {
        var inQuotes = false
        for (i in info.indices) {
            val c = info[i]
            if (c == '"') inQuotes = !inQuotes
            else if (c == ',' && !inQuotes) return info.substring(i + 1).trim()
        }
        return ""
    }
}
