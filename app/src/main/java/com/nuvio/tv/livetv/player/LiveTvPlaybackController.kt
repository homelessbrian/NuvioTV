package com.nuvio.tv.livetv.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import android.util.Log
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.model.LiveChannel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class LivePlaybackState(
    val channelKey: String? = null,
    val isBuffering: Boolean = false,
    val isPlaying: Boolean = false,
    val error: String? = null,
    val reconnectAttempt: Int = 0,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    /** Non-null while an archive (catch-up) program is playing instead of the live stream. */
    val catchupTitle: String? = null
)

data class LiveTrackOption(
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    val selected: Boolean
)

/**
 * One ExoPlayer shared by the guide preview window and the full-screen player, so going
 * full screen (and back) never re-buffers the stream — just like TiviMate.
 */
@OptIn(UnstableApi::class)
@Singleton
class LiveTvPlaybackController @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var _player: ExoPlayer? = null
    val player: ExoPlayer? get() = _player

    private val _state = MutableStateFlow(LivePlaybackState())
    val state: StateFlow<LivePlaybackState> = _state.asStateFlow()

    private var currentChannel: LiveChannel? = null

    /** The channel the player is on (as it was when playback started). */
    val playingChannel: LiveChannel? get() = currentChannel
    private var currentUrl: String? = null
    private var attachCount = 0
    private var releaseJob: Job? = null
    private var reconnectJob: Job? = null
    var autoReconnect: Boolean = true

    /**
     * Audio passthrough. Off: Dolby / DTS audio is decoded by the app and sent as plain PCM,
     * which is far more robust with Alexa Home Theater / Echo speakers on Fire TV. Changing it
     * rebuilds the player (and carries on with the same channel).
     */
    var audioPassthrough: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            val p = _player ?: return
            val ch = currentChannel
            val url = currentUrl
            val title = _state.value.catchupTitle
            val wasPlaying = p.playWhenReady
            release()
            if (ch != null && wasPlaying) play(ch, overrideUrl = url.takeIf { it != ch.url }, catchupTitle = title)
        }

    fun attach(): ExoPlayer {
        releaseJob?.cancel()
        attachCount++
        return _player ?: buildPlayer().also { _player = it }
    }

    /** Releases the player shortly after the last screen detaches (lets route changes hand it over). */
    fun detach() {
        attachCount = (attachCount - 1).coerceAtLeast(0)
        if (attachCount == 0) {
            releaseJob?.cancel()
            releaseJob = scope.launch {
                delay(600)
                if (attachCount == 0) release()
            }
        }
    }

    /** A second link to try once if the first can't be played (HLS catch-up → TS). */
    private var fallbackUrl: String? = null

    fun play(channel: LiveChannel, overrideUrl: String? = null, catchupTitle: String? = null, fallback: String? = null) {
        val url = overrideUrl ?: channel.url
        fallbackUrl = fallback
        startStallWatch()
        val p = _player ?: attach().also { attachCount-- }
        if (currentChannel?.key == channel.key && currentUrl == url && _state.value.error == null &&
            p.playbackState != Player.STATE_IDLE
        ) {
            if (!p.isPlaying) p.play()
            return
        }
        reconnectJob?.cancel()
        currentChannel = channel
        currentUrl = url
        _state.value = LivePlaybackState(channelKey = channel.key, isBuffering = true, catchupTitle = catchupTitle)
        p.setMediaSource(buildMediaSource(url, channel.headers, isLive = catchupTitle == null))
        p.prepare()
        p.playWhenReady = true
    }

    /** Plain words for playback errors, with the provider's HTTP status when there is one. */
    private fun describe(error: PlaybackException): String {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                return when (cause.responseCode) {
                    401, 403 -> "Provider refused the stream (HTTP ${cause.responseCode}): account limit, expired, or blocked"
                    404 -> "Stream not found (HTTP 404). Try another stream format in the playlist's settings"
                    429, 456, 458, 509 -> "Too many connections on this account (HTTP ${cause.responseCode})"
                    in 500..599 -> "Provider's server error (HTTP ${cause.responseCode})"
                    else -> "Provider answered HTTP ${cause.responseCode}"
                }
            }
            cause = cause.cause
        }
        return error.errorCodeName.removePrefix("ERROR_CODE_").replace('_', ' ').lowercase()
            .replaceFirstChar { it.uppercase() }
    }

    // ---------------------------------------------------------------- sleep timer

    private val _sleepAtMs = MutableStateFlow<Long?>(null)
    /** When the sleep timer stops Live TV (null = off). Runs wherever you are in the app. */
    val sleepAtMs: StateFlow<Long?> = _sleepAtMs.asStateFlow()
    private val _sleepFired = MutableStateFlow(0)
    /** Goes up each time the sleep timer stops playback (full screen closes itself). */
    val sleepFired: StateFlow<Int> = _sleepFired.asStateFlow()
    private var sleepJob: Job? = null

    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        if (minutes == null) { _sleepAtMs.value = null; return }
        val at = System.currentTimeMillis() + minutes * 60_000L
        _sleepAtMs.value = at
        sleepJob = scope.launch {
            delay(at - System.currentTimeMillis())
            _sleepAtMs.value = null
            stop()
            _sleepFired.value++
        }
    }

    fun stop() {
        reconnectJob?.cancel()
        _player?.stop()
        _player?.clearMediaItems()
        currentChannel = null
        currentUrl = null
        _state.value = LivePlaybackState()
    }

    // ---------------------------------------------------------------- stall watchdog

    /**
     * Some streams stop sending pictures without ever reporting an error (the provider keeps
     * the connection open, or a live HLS list stops updating), leaving "Loading…" on screen
     * forever. If playback makes no progress for [STALL_MS], reload the stream, the same as
     * switching away and back.
     */
    private var stallJob: Job? = null
    private var lastPosition = -1L
    private var lastProgressAt = 0L

    private fun startStallWatch() {
        lastProgressAt = android.os.SystemClock.elapsedRealtime()
        lastPosition = -1L
        if (stallJob?.isActive == true) return
        stallJob = scope.launch {
            while (true) {
                delay(2_000)
                checkStall()
            }
        }
    }

    private fun checkStall() {
        val p = _player ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        val ch = currentChannel
        // Paused, in the background, nothing playing, or already showing an error: not a stall.
        if (ch == null || !p.playWhenReady || pausedForBackground || _state.value.error != null ||
            p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED
        ) {
            lastProgressAt = now
            lastPosition = p.currentPosition
            return
        }
        val pos = p.currentPosition
        val moving = p.playbackState == Player.STATE_READY && p.isPlaying && pos != lastPosition
        lastPosition = pos
        if (moving) {
            lastProgressAt = now
            return
        }
        if (now - lastProgressAt < STALL_MS || !autoReconnect) return
        lastProgressAt = now
        Log.w(TAG, "Stream stalled on ${ch.name}; reloading")
        val url = currentUrl ?: ch.url
        val archive = _state.value.catchupTitle != null
        val resumeAt = if (archive) pos else C_TIME_UNSET
        p.stop()
        p.setMediaSource(buildMediaSource(url, ch.headers, isLive = !archive))
        p.prepare()
        if (resumeAt > 0) p.seekTo(resumeAt)
        p.playWhenReady = true
    }

    /** Catch-up moved on to the next show: update the title shown. */
    fun setCatchupTitle(title: String) {
        if (_state.value.catchupTitle != null) _state.value = _state.value.copy(catchupTitle = title)
    }

    fun retry() {
        val ch = currentChannel ?: return
        val url = currentUrl
        currentUrl = null
        play(ch, url, _state.value.catchupTitle)
    }

    private var pausedForBackground = false

    /** The app left the screen (Home button, another app): stop the sound. */
    fun onAppBackground() {
        val p = _player ?: return
        if (p.playWhenReady) {
            pausedForBackground = true
            p.pause()
        }
    }

    /** Back in the app: carry on, jumping to the live edge for live channels. */
    fun onAppForeground() {
        if (!pausedForBackground) return
        pausedForBackground = false
        val p = _player ?: return
        if (_state.value.catchupTitle == null && p.isCurrentMediaItemLive) p.seekToDefaultPosition()
        p.play()
    }

    fun togglePause() {
        val p = _player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    fun seekBy(ms: Long) {
        val p = _player ?: return
        p.seekTo((p.currentPosition + ms).coerceAtLeast(0))
    }

    fun audioTracks(): List<LiveTrackOption> = tracksOf(C.TRACK_TYPE_AUDIO)
    fun subtitleTracks(): List<LiveTrackOption> = tracksOf(C.TRACK_TYPE_TEXT)

    fun selectTrack(type: Int, option: LiveTrackOption?) {
        val p = _player ?: return
        val builder = p.trackSelectionParameters.buildUpon()
        if (option == null) {
            builder.setTrackTypeDisabled(type, true)
        } else {
            val group = p.currentTracks.groups.getOrNull(option.groupIndex) ?: return
            builder.setTrackTypeDisabled(type, false)
            builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
        }
        p.trackSelectionParameters = builder.build()
    }

    private fun tracksOf(type: Int): List<LiveTrackOption> {
        val p = _player ?: return emptyList()
        val out = mutableListOf<LiveTrackOption>()
        p.currentTracks.groups.forEachIndexed { gi, group: Tracks.Group ->
            if (group.type != type) return@forEachIndexed
            for (ti in 0 until group.length) {
                if (!group.isTrackSupported(ti)) continue
                val f = group.getTrackFormat(ti)
                val captions = when (f.sampleMimeType) {
                    MimeTypes.APPLICATION_CEA608 -> "Closed captions"
                    MimeTypes.APPLICATION_CEA708 -> "Closed captions (708)"
                    else -> null
                }
                val parts = listOfNotNull(
                    captions ?: f.label,
                    f.language?.let { java.util.Locale.forLanguageTag(it).displayLanguage.ifBlank { it } },
                    f.sampleMimeType?.takeIf { captions == null }?.substringAfter('/')?.uppercase(),
                    if (f.channelCount > 0) "${f.channelCount}ch" else null
                )
                out += LiveTrackOption(gi, ti, parts.distinct().joinToString(" · ").ifBlank { "Track ${out.size + 1}" }, group.isTrackSelected(ti))
            }
        }
        return out
    }

    private fun buildPlayer(): ExoPlayer {
        val passthrough = audioPassthrough
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): androidx.media3.exoplayer.audio.AudioSink? {
                if (passthrough) return super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
                // Pretend the output only takes plain PCM: Dolby / DTS gets decoded here.
                @Suppress("DEPRECATION")
                return androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setAudioCapabilities(androidx.media3.exoplayer.audio.AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
            }
        }
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 1_500, 3_000)
            .build()
        return ExoPlayer.Builder(context, renderers)
            .setLoadControl(loadControl)
            .build()
            .apply {
                playWhenReady = true
                addListener(listener)
            }
    }

    // ---------------------------------------------------------------- picture watchdog

    /**
     * Moving the picture between the guide preview and full screen gives the player a new
     * screen to draw on. On some TV chips the video then freezes (sound keeps going) or never
     * starts. After each move, if no picture appears within a couple of seconds, quietly
     * restart the stream on the new screen (what "Reload stream" did by hand).
     */
    @Volatile private var awaitingFrame = false
    private var frameJob: Job? = null

    fun onSurfaceAttached() {
        val p = _player ?: return
        awaitingFrame = true
        frameJob?.cancel()
        frameJob = scope.launch {
            // Sound playing but no picture: frozen video. A channel that's simply still loading
            // gets longer before we step in (it may just be slow to start).
            delay(FRAME_TIMEOUT_MS)
            if (!stuck(p, requireReady = true)) {
                delay(LOADING_AFTER_MOVE_MS - FRAME_TIMEOUT_MS)
                if (!stuck(p, requireReady = false)) return@launch
            }
            Log.w(TAG, "No picture after moving the video; restarting the stream")
            val archive = _state.value.catchupTitle != null
            val pos = p.currentPosition
            p.stop()
            p.prepare()
            if (archive && pos > 0) p.seekTo(pos)
            p.playWhenReady = true
        }
    }

    private fun stuck(p: ExoPlayer, requireReady: Boolean): Boolean {
        if (!awaitingFrame || !p.playWhenReady || currentChannel == null || _state.value.error != null) return false
        return if (requireReady) p.playbackState == Player.STATE_READY && p.isPlaying
        else p.playbackState == Player.STATE_BUFFERING || p.playbackState == Player.STATE_READY
    }

    private val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            awaitingFrame = false
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.value = _state.value.copy(
                isBuffering = playbackState == Player.STATE_BUFFERING,
                error = if (playbackState == Player.STATE_READY) null else _state.value.error,
                reconnectAttempt = if (playbackState == Player.STATE_READY) 0 else _state.value.reconnectAttempt
            )
            if (playbackState == Player.STATE_ENDED && _state.value.catchupTitle == null) {
                // A live stream should never end; treat it as a dropped connection.
                scheduleReconnect("Stream ended")
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(isPlaying = isPlaying)
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            _state.value = _state.value.copy(videoWidth = videoSize.width, videoHeight = videoSize.height)
        }

        override fun onPlayerError(error: PlaybackException) {
            val p = _player
            // The provider doesn't offer this as HLS: switch to the TS link once.
            val fb = fallbackUrl
            val ch = currentChannel
            if (fb != null && ch != null && p != null) {
                fallbackUrl = null
                currentUrl = fb
                p.setMediaSource(buildMediaSource(fb, ch.headers, isLive = _state.value.catchupTitle == null))
                p.prepare()
                p.playWhenReady = true
                return
            }
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && p != null) {
                p.seekToDefaultPosition()
                p.prepare()
                return
            }
            scheduleReconnect(describe(error))
        }
    }

    private fun scheduleReconnect(reason: String) {
        val attempt = _state.value.reconnectAttempt + 1
        _state.value = _state.value.copy(error = reason, isBuffering = false, reconnectAttempt = attempt)
        if (!autoReconnect || attempt > MAX_RECONNECTS) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay((attempt * 2_000L).coerceAtMost(10_000L))
            val ch = currentChannel ?: return@launch
            val url = currentUrl ?: return@launch
            val p = _player ?: return@launch
            p.setMediaSource(buildMediaSource(url, ch.headers, isLive = _state.value.catchupTitle == null))
            p.prepare()
            p.playWhenReady = true
        }
    }

    private fun buildMediaSource(url: String, headers: Map<String, String>, isLive: Boolean): androidx.media3.exoplayer.source.MediaSource {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(headers["User-Agent"] ?: LiveTvRepository.DEFAULT_UA)
            .setDefaultRequestProperties(headers.filterKeys { it != "User-Agent" })
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val item = buildMediaItem(url, isLive)
        // Closed captions: TV channels usually carry them inside the video (CEA-608/708) without
        // announcing them, so the player never offered them and Subtitles only showed "Off".
        // Look for them anyway, the way TV apps do.
        if (item.localConfiguration?.mimeType == MimeTypes.APPLICATION_M3U8) {
            return androidx.media3.exoplayer.hls.HlsMediaSource.Factory(http)
                .setExtractorFactory(
                    androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory(
                        androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES,
                        /* exposeCea608WhenMissingDeclarations = */ true
                    )
                )
                .createMediaSource(item)
        }
        val extractors = androidx.media3.extractor.DefaultExtractorsFactory()
            .setTsExtractorFlags(
                androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_OVERRIDE_CAPTION_DESCRIPTORS
            )
            .setTsSubtitleFormats(
                listOf(
                    androidx.media3.common.Format.Builder()
                        .setSampleMimeType(MimeTypes.APPLICATION_CEA608)
                        .setAccessibilityChannel(1)
                        .build()
                )
            )
        return DefaultMediaSourceFactory(http, extractors).createMediaSource(item)
    }

    private fun buildMediaItem(url: String, isLive: Boolean): MediaItem {
        val builder = MediaItem.Builder().setUri(Uri.parse(url))
        val lower = url.lowercase().substringBefore('?')
        when {
            lower.endsWith(".m3u8") || lower.contains("/hls/") || lower.contains("m3u8") -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            lower.endsWith(".mpd") -> builder.setMimeType(MimeTypes.APPLICATION_MPD)
            lower.endsWith(".ts") -> builder.setMimeType(MimeTypes.VIDEO_MP2T)
        }
        if (isLive) {
            builder.setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(8_000).build()
            )
        }
        return builder.build()
    }

    /**
     * Lets go of the player (and its hold on the audio output) right away. Called when Nuvio's
     * own player opens, so the two never hold the audio at the same time.
     */
    fun releaseNow() {
        if (_player != null) release()
    }

    private fun release() {
        reconnectJob?.cancel()
        stallJob?.cancel()
        stallJob = null
        _player?.removeListener(listener)
        _player?.release()
        _player = null
        currentChannel = null
        currentUrl = null
        _state.value = LivePlaybackState()
    }

    companion object {
        private const val MAX_RECONNECTS = 8
        /** No progress for this long while it should be playing = stalled. */
        private const val STALL_MS = 15_000L
        /** No picture this long after the video moved to a new screen = stuck. */
        private const val FRAME_TIMEOUT_MS = 2_500L
        /** Still loading this long after the video moved to a new screen = stuck. */
        private const val LOADING_AFTER_MOVE_MS = 7_000L
        private const val C_TIME_UNSET = Long.MIN_VALUE + 1
        private const val TAG = "LiveTvPlayback"
    }
}
