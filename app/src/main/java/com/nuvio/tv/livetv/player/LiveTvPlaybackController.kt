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
    val catchupTitle: String? = null,
    /**
     * You paused it (play/pause), as opposed to it just not playing yet: between channels and
     * while reconnecting it isn't playing either, and that must not count as paused.
     */
    val userPaused: Boolean = false
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

    // ---------------------------------------------------------------- pause and rewind

    private val timeshift = LiveTvTimeshift(context)
    var timeshiftEnabled: Boolean = true
    var timeshiftMinutes: Int = 30

    private val _shiftStartedAt = MutableStateFlow<Long?>(null)
    /** Non-null while playing from a pause-and-rewind recording: when the recording started. */
    val shiftStartedAt: StateFlow<Long?> = _shiftStartedAt.asStateFlow()
    private var shiftPrepared = false
    private var shiftJob: Job? = null

    /** Wall-clock time of what's on screen while playing a recording, or null. */
    fun shiftPositionWallMs(): Long? {
        val start = _shiftStartedAt.value ?: return null
        val p = _player ?: return start
        return start + timeshift.trimmedMs + if (shiftPrepared) p.currentPosition.coerceAtLeast(0) else 0L
    }

    /** Skipping back and forward works on HLS recordings (TS recordings can only pause and resume). */
    fun shiftCanSeek(): Boolean = timeshift.session?.kind == LiveTvTimeshift.Kind.HLS && shiftPrepared

    /**
     * Pause on a live channel without catch-up: record it from here (pause and rewind live TV),
     * so play carries on from this moment. Falls back to a plain pause if recording isn't
     * possible (turned off, not enough storage, encrypted stream).
     */
    fun pauseLive() {
        val p = _player ?: return
        val ch = currentChannel
        if (_shiftStartedAt.value != null) { p.pause(); return }
        if (ch == null || _state.value.catchupTitle != null || !timeshiftEnabled || !timeshift.hasRoomFor(timeshiftMinutes)) {
            p.pause(); return
        }
        val url = currentUrl ?: ch.url
        p.pause()
        p.stop() // hand the provider connection over to the recording
        val session = timeshift.start(url, ch.headers, timeshiftMinutes)
        if (session == null) {
            // Couldn't record: back to the live stream, paused.
            p.setMediaSource(buildMediaSource(url, ch.headers, isLive = true)); p.prepare(); p.playWhenReady = false
            return
        }
        shiftPrepared = false
        _shiftStartedAt.value = session.startedAtMs
        reconnectJob?.cancel()
    }

    /** Play from the recording (first time: once there's something recorded to play). */
    private fun resumeShift() {
        val p = _player ?: return
        val session = timeshift.session ?: return
        if (shiftPrepared) { p.play(); return }
        shiftJob?.cancel()
        shiftJob = scope.launch {
            // Wait until the first part is saved (a second or two for HLS).
            var waited = 0
            while (waited < 15_000) {
                val f = java.io.File(session.playUri.path ?: "")
                if (f.exists() && f.length() > 0) break
                delay(250); waited += 250
            }
            val source = if (session.kind == LiveTvTimeshift.Kind.HLS) {
                androidx.media3.exoplayer.hls.HlsMediaSource.Factory(androidx.media3.datasource.DefaultDataSource.Factory(context))
                    .createMediaSource(MediaItem.Builder().setUri(session.playUri).setMimeType(MimeTypes.APPLICATION_M3U8).build())
            } else {
                androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(timeshift.dataSourceFactory())
                    .createMediaSource(MediaItem.fromUri(session.playUri))
            }
            p.setMediaSource(source)
            p.prepare()
            if (session.kind == LiveTvTimeshift.Kind.HLS) p.seekTo(0)
            p.playWhenReady = true
            shiftPrepared = true
        }
    }

    /** Skip back or forward in the recording (HLS recordings). */
    fun shiftSeekBy(deltaMs: Long) {
        val p = _player ?: return
        if (!shiftCanSeek()) return
        val max = if (p.duration > 0) p.duration - 3_000 else p.currentPosition
        p.seekTo((p.currentPosition + deltaMs).coerceIn(0L, max.coerceAtLeast(0L)))
    }

    /** Go live: drop the recording and play the channel live again. */
    fun goLive() {
        val ch = currentChannel
        val url = currentUrl
        stopShift()
        if (ch != null) {
            currentUrl = null
            play(ch, overrideUrl = url.takeIf { it != ch.url })
        }
    }

    private fun stopShift() {
        shiftJob?.cancel()
        shiftJob = null
        if (_shiftStartedAt.value != null || timeshift.session != null) timeshift.stop()
        _shiftStartedAt.value = null
        shiftPrepared = false
    }

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
    /** Live TV buffer size ("small", "normal", "large", "xlarge"); changing it rebuilds the player. */
    var bufferSize: String = "normal"
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

    /** Other links to try, in turn, if the current one can't be played (catch-up variants). */
    private val fallbackUrls = ArrayDeque<String>()

    fun play(
        channel: LiveChannel,
        overrideUrl: String? = null,
        catchupTitle: String? = null,
        fallback: String? = null,
        fallbacks: List<String> = emptyList()
    ) {
        if (_shiftStartedAt.value != null) stopShift()
        if (currentChannel?.key != channel.key) captionsHandled = false
        val url = overrideUrl ?: channel.url
        if (currentChannel?.key != channel.key || currentUrl != url) {
            forcedMime = null
            formatGuess = 0
        }
        fallbackUrls.clear()
        fallback?.let { fallbackUrls += it }
        fallbacks.forEach { if (it !in fallbackUrls) fallbackUrls += it }
        if (catchupTitle != null) com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Catch-up \"$catchupTitle\" on ${channel.name}: ${redact(url)}")
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
        archiveStartPending = catchupTitle != null
        p.setMediaSource(buildMediaSource(url, channel.headers, isLive = catchupTitle == null))
        p.prepare()
        p.playWhenReady = true
    }

    /**
     * Catch-up just started: begin at the start of what we asked for. Some providers send a
     * show that's still airing as a growing "live" stream, and the player starts those at the
     * live edge (so "Watch from start" landed near now, and you had to rewind by hand).
     */
    @Volatile private var archiveStartPending = false

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
        if (_shiftStartedAt.value != null) { lastProgressAt = android.os.SystemClock.elapsedRealtime(); return }
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
        archiveStartPending = false
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
        when {
            _shiftStartedAt.value != null -> if (p.isPlaying) p.pause() else resumeShift()
            p.isPlaying && _state.value.catchupTitle == null -> pauseLive()
            p.isPlaying -> p.pause()
            else -> p.play()
        }
    }

    fun seekBy(ms: Long) {
        val p = _player ?: return
        p.seekTo((p.currentPosition + ms).coerceAtLeast(0))
    }

    /** Captions are showing (a text track is selected and text isn't switched off). */
    fun captionsOn(): Boolean {
        val p = _player ?: return false
        if (C.TRACK_TYPE_TEXT in p.trackSelectionParameters.disabledTrackTypes) return false
        return tracksOf(C.TRACK_TYPE_TEXT).any { it.selected }
    }

    /** Settings: captions on automatically when a channel has them. */
    var captionsByDefault: Boolean = false
    /** Captions were already switched on (or you chose) for the channel now playing. */
    private var captionsHandled = false

    /** One press: captions off if on; otherwise on, with the first available track. */
    fun toggleCaptions(): Boolean {
        captionsHandled = true // your choice for this channel stands
        val tracks = tracksOf(C.TRACK_TYPE_TEXT)
        return if (captionsOn()) { selectTrack(C.TRACK_TYPE_TEXT, null); false }
        else {
            val pick = tracks.firstOrNull() ?: return false
            selectTrack(C.TRACK_TYPE_TEXT, pick); true
        }
    }

    /** The audio track playing, short ("English 5.1"), for the panel. */
    fun audioLabel(): String = tracksOf(C.TRACK_TYPE_AUDIO).firstOrNull { it.selected }?.label?.take(18) ?: "Audio"

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
        // Buffer size setting: how much is downloaded ahead. Bigger rides out a shaky connection or
        // a slow provider; smaller starts channels a little faster.
        val (minMs, maxMs, startMs, rebufferMs) = when (bufferSize) {
            "small" -> listOf(10_000, 30_000, 1_000, 2_000)
            "large" -> listOf(40_000, 90_000, 3_000, 6_000)
            "xlarge" -> listOf(60_000, 150_000, 5_000, 10_000)
            else -> listOf(25_000, 60_000, 2_000, 4_000)
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minMs, maxMs, startMs, rebufferMs)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        return ExoPlayer.Builder(context, renderers)
            .setLoadControl(loadControl)
            // Take part in Android's audio focus like other media apps: when something else starts
            // playing sound (another app, a launcher's video wallpaper with sound…), Live TV pauses.
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true
            )
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
            archiveStartPending = false
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
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            _state.value = _state.value.copy(userPaused = !playWhenReady)
        }

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            // "Captions on by default": once the channel's caption tracks are known, switch them
            // on (once per channel, and not if you've turned them off yourself).
            if (!captionsByDefault || captionsHandled) return
            if (tracks.groups.none { it.type == C.TRACK_TYPE_TEXT && it.length > 0 }) return
            captionsHandled = true
            if (!captionsOn()) tracksOf(C.TRACK_TYPE_TEXT).firstOrNull()?.let { selectTrack(C.TRACK_TYPE_TEXT, it) }
        }

        override fun onRenderedFirstFrame() {
            awaitingFrame = false
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            if (!archiveStartPending || timeline.isEmpty) return
            val p = _player ?: return
            archiveStartPending = false
            if (_state.value.catchupTitle == null) return
            // A replay sent as a growing live stream: the player would start it at the live
            // edge. Go to the start of the window (the start of the show we asked for).
            val window = timeline.getWindow(p.currentMediaItemIndex.coerceIn(0, timeline.windowCount - 1), androidx.media3.common.Timeline.Window())
            if (window.isDynamic || window.isLive()) {
                com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Catch-up came as a live stream; starting from the beginning")
                p.seekTo(0L)
            }
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
            // A recording that failed: back to live rather than reconnecting to nowhere.
            if (_shiftStartedAt.value != null) { Log.w(TAG, "Recording playback failed", error); goLive(); return }
            val p = _player
            // The provider doesn't offer this as HLS: switch to the TS link once.
            val ch = currentChannel
            val fb = fallbackUrls.removeFirstOrNull()
            if (fb != null && ch != null && p != null) {
                com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Catch-up failed (${error.errorCodeName}); trying ${redact(fb)}")
                currentUrl = fb
                archiveStartPending = _state.value.catchupTitle != null
                p.setMediaSource(buildMediaSource(fb, ch.headers, isLive = _state.value.catchupTitle == null))
                p.prepare()
                p.playWhenReady = true
                return
            }
            // "Container unsupported": the link didn't say what kind of stream it is and it was
            // read as a plain video file. Try it as HLS, then as DASH, before giving up.
            if (ch != null && p != null && isUnrecognizedFormat(error) && formatGuess < FORMAT_GUESSES.size) {
                forcedMime = FORMAT_GUESSES[formatGuess++]
                Log.w(TAG, "Unrecognized stream format for ${ch.name}; trying $forcedMime")
                val url = currentUrl ?: ch.url
                p.setMediaSource(buildMediaSource(url, ch.headers, isLive = _state.value.catchupTitle == null))
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

    /** Format tried for a link that doesn't say what it is (after "container unsupported"). */
    @Volatile private var forcedMime: String? = null
    @Volatile private var formatGuess = 0

    private fun isUnrecognizedFormat(error: PlaybackException): Boolean {
        var c: Throwable? = error
        while (c != null) {
            if (c is androidx.media3.exoplayer.source.UnrecognizedInputFormatException) return true
            if (c is androidx.media3.common.ParserException && c.message?.contains("ontainer", true) == true) return true
            c = c.cause
        }
        return error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
    }

    /** A link for the start-up report, with the username and password hidden. */
    private fun redact(url: String): String =
        url.replace(Regex("""(/(?:timeshift|live)/)[^/]+/[^/]+/"""), "$1***/***/")
            .replace(Regex("""(username|password)=[^&]*"""), "$1=***")

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
        // Protected (DRM) streams, e.g. MPEG-DASH with a ClearKey or Widevine license.
        currentChannel?.drm?.let { drm -> buildDrmMediaSource(url, headers, isLive, drm)?.let { return it } }
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

    /**
     * DRM playback, as TiviMate and Kodi do it from "#KODIPROP" playlist lines:
     *  - Widevine / PlayReady: the license server URL (with any headers the playlist gives).
     *  - ClearKey: keys given in the playlist ("kid:key" pairs or a JSON key set), or a ClearKey
     *    license URL. Widevine needs a device that supports it (most Android TV devices do).
     */
    private fun buildDrmMediaSource(
        url: String,
        headers: Map<String, String>,
        isLive: Boolean,
        drm: com.nuvio.tv.livetv.model.DrmInfo
    ): androidx.media3.exoplayer.source.MediaSource? = runCatching {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(headers["User-Agent"] ?: LiveTvRepository.DEFAULT_UA)
            .setDefaultRequestProperties(headers.filterKeys { it != "User-Agent" })
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val itemBuilder = buildMediaItem(url, isLive).buildUpon()
        if (drm.manifestType == "mpd" || url.lowercase().contains(".mpd")) itemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
        if (drm.manifestType == "hls") itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
        val uuid = when (drm.scheme) {
            "widevine" -> androidx.media3.common.C.WIDEVINE_UUID
            "playready" -> androidx.media3.common.C.PLAYREADY_UUID
            else -> androidx.media3.common.C.CLEARKEY_UUID
        }
        val factory = DefaultMediaSourceFactory(http)
        val license = drm.license.trim()
        if (drm.scheme == "clearkey" && !license.startsWith("http", ignoreCase = true)) {
            // Keys in the playlist: answer license requests locally with them.
            val keySet = clearKeyJson(license) ?: return@runCatching null
            factory.setDrmSessionManagerProvider {
                androidx.media3.exoplayer.drm.DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(androidx.media3.common.C.CLEARKEY_UUID, androidx.media3.exoplayer.drm.FrameworkMediaDrm.DEFAULT_PROVIDER)
                    .setMultiSession(false)
                    .build(androidx.media3.exoplayer.drm.LocalMediaDrmCallback(keySet.toByteArray(Charsets.UTF_8)))
            }
            itemBuilder.setDrmConfiguration(MediaItem.DrmConfiguration.Builder(androidx.media3.common.C.CLEARKEY_UUID).build())
        } else {
            // License requests go out with the same User-Agent / Referer as the stream (like
            // TiviMate and Kodi), plus any license headers from the playlist. Without them many
            // license servers refuse the request.
            val licenseHeaders = LinkedHashMap<String, String>()
            headers.forEach { (k, v) -> if (k.equals("User-Agent", true) || k.equals("Referer", true) || k.equals("Origin", true)) licenseHeaders[k] = v }
            licenseHeaders.putAll(drm.licenseHeaders)
            factory.setDrmSessionManagerProvider(
                androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider().apply { setDrmHttpDataSourceFactory(http) }
            )
            itemBuilder.setDrmConfiguration(
                MediaItem.DrmConfiguration.Builder(uuid)
                    .setLicenseUri(license)
                    .setLicenseRequestHeaders(licenseHeaders)
                    .setMultiSession(true)
                    .build()
            )
        }
        factory.createMediaSource(itemBuilder.build())
    }.onFailure { Log.w(TAG, "Couldn't set up DRM for this stream", it) }.getOrNull()

    /**
     * A ClearKey key set (JSON) from what playlists give: "kid:key" pairs (hex or base64,
     * comma-separated), or a JSON key set as is.
     */
    private fun clearKeyJson(raw: String): String? {
        val t = raw.trim()
        if (t.startsWith("{")) return t
        fun b64url(v: String): String {
            val s = v.trim()
            val bytes = if (Regex("^[0-9a-fA-F]+$").matches(s) && s.length % 2 == 0) {
                ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
            } else {
                runCatching { android.util.Base64.decode(s.replace('-', '+').replace('_', '/'), android.util.Base64.DEFAULT) }
                    .getOrElse { return "" }
            }
            return android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
        }
        val keys = t.split(',').mapNotNull { pair ->
            val kid = pair.substringBefore(':').trim()
            val key = pair.substringAfter(':', "").trim()
            if (kid.isEmpty() || key.isEmpty()) null
            else org.json.JSONObject().put("kty", "oct").put("kid", b64url(kid)).put("k", b64url(key))
        }
        if (keys.isEmpty()) return null
        return org.json.JSONObject().put("keys", org.json.JSONArray(keys)).put("type", "temporary").toString()
    }

    private fun buildMediaItem(url: String, isLive: Boolean): MediaItem {
        val builder = MediaItem.Builder().setUri(Uri.parse(url))
        val lower = url.lowercase().substringBefore('?')
        when {
            forcedMime != null -> builder.setMimeType(forcedMime)
            lower.endsWith(".m3u8") || lower.contains("/hls/") || lower.contains("m3u8") -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            lower.endsWith(".mpd") -> builder.setMimeType(MimeTypes.APPLICATION_MPD)
            lower.endsWith(".ts") -> builder.setMimeType(MimeTypes.VIDEO_MP2T)
        }
        if (isLive) {
            // How far behind "live" to play. Playing very close to live (it was 8 seconds) left
            // almost no room for a slow segment, so some providers kept buffering; now it
            // follows the stream's own recommendation, with more room for bigger buffers.
            val offset = when (bufferSize) {
                "small" -> 10_000L
                "large" -> 30_000L
                "xlarge" -> 45_000L
                else -> null
            }
            builder.setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .apply { if (offset != null) setTargetOffsetMs(offset) }
                    // No speeding up / slowing down to chase the live edge (can cause stutter).
                    .setMinPlaybackSpeed(1f)
                    .setMaxPlaybackSpeed(1f)
                    .build()
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
        stopShift()
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
        private val FORMAT_GUESSES = listOf(MimeTypes.APPLICATION_M3U8, MimeTypes.APPLICATION_MPD)
        /** No picture this long after the video moved to a new screen = stuck. */
        private const val FRAME_TIMEOUT_MS = 2_500L
        /** Still loading this long after the video moved to a new screen = stuck. */
        private const val LOADING_AFTER_MOVE_MS = 7_000L
        private const val C_TIME_UNSET = Long.MIN_VALUE + 1
        private const val TAG = "LiveTvPlayback"
    }
}
