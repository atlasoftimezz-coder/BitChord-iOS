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
 * What is playing and what plays next: a flat queue built from the list a
 * track was tapped in, with the following track resolved ahead of time and
 * handed to the engine so it starts by itself — gaplessly, and with the
 * screen locked, when the app may not get to run in between. Mid-track 403s
 * get one retry with a fresh URL from a different client.
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
    private var prefetchJob: Job? = null
    private var currentStream: StreamResolver.Stream? = null

    /** The stream handed to [AudioEngine.setNext], for the track at index + 1. */
    private var queuedStream: StreamResolver.Stream? = null
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
            seekTo(0)
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
        dropQueued()
        engine.stop()
        currentStream = null
        retriedRefusal = false
        _state.update {
            it.copy(isLoading = true, isPlaying = false, positionMs = 0, durationMs = 0, error = null, streamInfo = null)
        }
        publishNowPlaying()
        loadJob = scope.launch {
            if (startStream(song)) prefetchNext()
        }
    }

    /** @return true once [song] is handed to the engine. */
    private suspend fun startStream(song: Song): Boolean {
        try {
            val stream = StreamResolver.resolve(song.videoId)
            if (_state.value.current?.videoId != song.videoId) return false
            currentStream = stream
            _state.update { it.copy(streamInfo = describe(stream)) }
            engine.play(
                url = stream.url,
                headers = stream.headers,
                mimeType = stream.mimeType,
                contentLength = stream.contentLength ?: -1L,
                chunkBytes = stream.chunkBytes,
            )
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "could not start ${song.videoId}: ${e.message}")
            _state.update { it.copy(isLoading = false, error = e.message ?: "Playback failed") }
            return false
        }
    }

    /** Resolve the following track now and queue it in the engine. */
    private fun prefetchNext() {
        prefetchJob?.cancel()
        val s = _state.value
        val nextSong = s.queue.getOrNull(s.index + 1) ?: return
        val expectedIndex = s.index
        prefetchJob = scope.launch {
            try {
                val stream = StreamResolver.resolve(nextSong.videoId)
                // The queue moved on while this was resolving.
                if (_state.value.index != expectedIndex || _state.value.queue.getOrNull(expectedIndex + 1) != nextSong) {
                    return@launch
                }
                queuedStream = stream
                engine.setNext(
                    url = stream.url,
                    headers = stream.headers,
                    mimeType = stream.mimeType,
                    contentLength = stream.contentLength ?: -1L,
                    chunkBytes = stream.chunkBytes,
                )
                Log.d(TAG, "queued ${nextSong.videoId} after the current track")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not fatal: onEnded falls back to resolving it on the spot.
                Log.w(TAG, "could not pre-resolve ${nextSong.videoId}: ${e.message}")
            }
        }
    }

    private fun dropQueued() {
        prefetchJob?.cancel()
        prefetchJob = null
        if (queuedStream != null) engine.clearNext()
        queuedStream = null
    }

    private fun publishNowPlaying() {
        val s = _state.value
        val song = s.current ?: return
        engine.setNowPlaying(song.title, song.artist, song.albumName, song.thumbnailUrl?.let(::largeArtwork))
        engine.setQueueCapabilities(hasNext = s.hasNext, hasPrevious = true)
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

    override fun onAdvancedToNext() {
        val stream = queuedStream ?: return
        queuedStream = null
        currentStream = stream
        retriedRefusal = false
        _state.update {
            it.copy(index = it.index + 1, positionMs = 0, durationMs = 0, error = null, streamInfo = describe(stream))
        }
        publishNowPlaying()
        prefetchNext()
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
            dropQueued()
            loadJob = scope.launch {
                StreamResolver.onPlaybackRefused(stream)
                if (startStream(song)) {
                    if (resumeAt > 0) engine.seekTo(resumeAt)
                    prefetchNext()
                }
            }
            return
        }
        _state.update { it.copy(isPlaying = false, isLoading = false, error = message) }
    }

    override fun onRemoteNext() = next()

    override fun onRemotePrevious() = previous()

    private companion object {
        const val TAG = "BitChord"
    }
}

/** YouTube Music thumbnails carry their size in the URL (`=w120-h120`); ask for a big one. */
internal fun largeArtwork(url: String): String =
    url.replace(Regex("=w\\d+-h\\d+"), "=w720-h720")
