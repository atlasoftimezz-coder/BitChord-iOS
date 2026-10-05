package com.music.bitchord.playback

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What is playing and what plays next. P1: a flat queue built from the list a
 * track was tapped in, auto-advance, and one retry with a fresh URL when
 * googlevideo refuses mid-track. Radio / autoplay queues come with later phases.
 */
class PlayerController(private val engine: AudioEngine) : AudioEngineListener {

    data class State(
        val queue: List<Song> = emptyList(),
        val index: Int = -1,
        val isPlaying: Boolean = false,
        val isLoading: Boolean = false,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val error: String? = null,
        /** e.g. "AAC 128 kbps · IOS" — the Android app's stats-for-nerds line, abridged. */
        val streamInfo: String? = null,
    ) {
        val current: Song? get() = queue.getOrNull(index)
        val hasNext: Boolean get() = index + 1 < queue.size
        val hasPrevious: Boolean get() = index > 0
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var currentStream: StreamResolver.Stream? = null
    private var retriedRefusal = false

    init {
        engine.setListener(this)
    }

    fun playQueue(songs: List<Song>, startIndex: Int) {
        if (songs.isEmpty()) return
        _state.update { it.copy(queue = songs, index = startIndex.coerceIn(songs.indices)) }
        load()
    }

    fun togglePlay() {
        val s = _state.value
        when {
            s.current == null -> Unit
            s.isPlaying -> engine.pause()
            s.isLoading -> Unit
            currentStream == null -> load()
            else -> engine.resume()
        }
    }

    fun next() {
        if (!_state.value.hasNext) return
        _state.update { it.copy(index = it.index + 1) }
        load()
    }

    fun previous() {
        val s = _state.value
        // Like every music app: back restarts the track unless it has barely begun.
        if (s.positionMs > 3_000 || !s.hasPrevious) {
            engine.seekTo(0)
            return
        }
        _state.update { it.copy(index = it.index - 1) }
        load()
    }

    fun seekTo(positionMs: Long) {
        _state.update { it.copy(positionMs = positionMs) }
        engine.seekTo(positionMs)
    }

    private fun load() {
        val song = _state.value.current ?: return
        loadJob?.cancel()
        engine.stop()
        currentStream = null
        retriedRefusal = false
        _state.update {
            it.copy(isLoading = true, isPlaying = false, positionMs = 0, durationMs = 0, error = null, streamInfo = null)
        }
        loadJob = scope.launch { startStream(song) }
    }

    private suspend fun startStream(song: Song) {
        try {
            val stream = StreamResolver.resolve(song.videoId)
            if (_state.value.current?.videoId != song.videoId) return
            currentStream = stream
            _state.update { it.copy(streamInfo = describe(stream)) }
            engine.play(
                url = stream.url,
                headers = stream.headers,
                mimeType = stream.mimeType,
                contentLength = stream.contentLength ?: -1L,
                chunkBytes = stream.chunkBytes,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "could not start ${song.videoId}: ${e.message}")
            _state.update { it.copy(isLoading = false, error = e.message ?: "Playback failed") }
        }
    }

    private fun describe(stream: StreamResolver.Stream): String {
        val codec = when {
            "mp4a" in stream.mimeType -> "AAC"
            "opus" in stream.mimeType -> "Opus"
            else -> stream.mimeType.substringBefore(';')
        }
        return "$codec ${stream.kbps} kbps · ${stream.profileId}"
    }

    // ---- AudioEngineListener --------------------------------------------------

    override fun onProgress(positionMs: Long, durationMs: Long, isPlaying: Boolean, isBuffering: Boolean) {
        _state.update {
            it.copy(
                positionMs = positionMs,
                durationMs = durationMs,
                isPlaying = isPlaying,
                isLoading = isBuffering,
            )
        }
    }

    override fun onEnded() {
        if (_state.value.hasNext) next() else _state.update { it.copy(isPlaying = false, positionMs = 0) }
    }

    override fun onError(message: String, httpStatus: Int) {
        val stream = currentStream
        val song = _state.value.current
        Log.w(TAG, "playback error ($httpStatus): $message")
        // A URL that passed the probe can still be refused later; mint another
        // from a different client once before giving up, as the Android app does.
        if (stream != null && song != null && httpStatus in setOf(403, 404, 410) && !retriedRefusal) {
            retriedRefusal = true
            val resumeAt = _state.value.positionMs
            loadJob?.cancel()
            loadJob = scope.launch {
                StreamResolver.onPlaybackRefused(stream)
                startStream(song)
                if (resumeAt > 0) engine.seekTo(resumeAt)
            }
            return
        }
        _state.update { it.copy(isPlaying = false, isLoading = false, error = message) }
    }

    private companion object {
        const val TAG = "BitChord"
    }
}
