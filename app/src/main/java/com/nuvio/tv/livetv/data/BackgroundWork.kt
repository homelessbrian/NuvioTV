package com.nuvio.tv.livetv.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * Keeps Live TV and On Demand's heavy background jobs (playlist, guide and catalog downloads,
 * reading and saving them) out of the way while Nuvio's own player is on screen. On slower TV
 * boxes they competed with the video for the processor, storage and network, and movies and
 * episodes stuttered. Jobs already under way pause at their next step and carry on once the
 * player closes. Updates you start yourself (Update now) aren't held back.
 */
object BackgroundWork {
    /** Nuvio's player is open (set from the app's navigation). */
    val nuvioPlaying = MutableStateFlow(false)

    /** Waits while Nuvio's player is open. */
    suspend fun awaitIdle() {
        if (nuvioPlaying.value) nuvioPlaying.first { !it }
    }
}
