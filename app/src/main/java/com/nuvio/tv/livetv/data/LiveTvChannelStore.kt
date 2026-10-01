package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.LiveChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The channel list with your renames, numbers, group names and channel name editor applied.
 * One shared copy for the whole app: before, every screen built its own, so each change was
 * worked out two or three times over.
 */
@Singleton
class LiveTvChannelStore @Inject constructor(
    private val repository: LiveTvRepository,
    private val prefs: LiveTvPreferences
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Channels with the user's renames and renumbering applied. */
    val displayChannels: StateFlow<List<LiveChannel>> = combine(repository.channels, prefs.userState, prefs.settings) { channels, user, s ->
        // Channel name editor: prefixes/suffixes removed from names you haven't renamed yourself.
        val terms = com.nuvio.tv.livetv.model.ChannelNameEditor.terms(s.nameRemovals)
        val cleaned = cleanedNames(channels, s.nameRemovals, terms)
        if (user.channelNames.isEmpty() && user.channelNumbers.isEmpty() && user.groupNames.isEmpty() && terms.isEmpty()) channels
        else {
            // Renames and numbers are saved against the channel's key (playlist + tvg-id + name).
            // Providers often change channel names (event titles, "HD"/"ᴴᴰ" tags, status
            // markers), which changes the key. So also match by playlist + tvg-id when the
            // channel has one, which stays the same when the name changes.
            // Only saved entries whose exact channel no longer exists are carried over, so two
            // channels sharing a tvg-id (ESPN and ESPN HD) never pick up each other's rename.
            val currentKeys = channels.mapTo(HashSet()) { it.key }
            val namesById = stableIndex(user.channelNames.filterKeys { it !in currentKeys })
            val numbersById = stableIndex(user.channelNumbers.filterKeys { it !in currentKeys })
            channels.map { c ->
                val id = stableId(c.key)
                c.copy(
                    name = user.channelNames[c.key] ?: id?.let { namesById[it] }
                        ?: cleaned[c.key] ?: c.name,
                    number = user.channelNumbers[c.key] ?: id?.let { numbersById[it] } ?: c.number,
                    group = user.groupNames[c.groupId] ?: c.group
                )
            }
        }
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, emptyList())

    // Channel name editor results, kept until the channel list or the terms change. Cleaning
    // tens of thousands of names took long enough that renames seemed to take 20-30 seconds.
    @Volatile private var cleanedFor: Pair<List<LiveChannel>, String>? = null
    @Volatile private var cleanedCache: Map<String, String> = emptyMap()

    private fun cleanedNames(channels: List<LiveChannel>, raw: String, terms: List<String>): Map<String, String> {
        if (terms.isEmpty()) return emptyMap()
        val key = cleanedFor
        if (key != null && key.first === channels && key.second == raw) return cleanedCache
        val out = HashMap<String, String>(channels.size)
        channels.forEach { c ->
            val n = com.nuvio.tv.livetv.model.ChannelNameEditor.clean(c.name, terms)
            if (n != c.name) out[c.key] = n
        }
        cleanedCache = out
        cleanedFor = channels to raw
        return out
    }

    /** "playlist|tvg-id" from a channel key, or null when the channel has no tvg-id. */
    private fun stableId(key: String): String? {
        val parts = key.split('|', limit = 3)
        if (parts.size < 3 || parts[1].isBlank()) return null
        return parts[0] + "|" + parts[1]
    }

    private fun <T> stableIndex(byKey: Map<String, T>): Map<String, T> {
        val out = HashMap<String, T>()
        byKey.forEach { (k, v) -> stableId(k)?.let { out.putIfAbsent(it, v) } }
        return out
    }
}
