package com.music.bitchord.playback

import android.content.Context
import com.music.bitchord.data.lyrics.EmbeddedLyrics
import com.music.bitchord.download.Downloads
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsRepository
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Per-provider progress, as the Android player's provider drawer shows it. */
enum class LyricsProviderState { IDLE, FETCHING, FOUND, NOT_FOUND }

/**
 * Lyrics for whatever [PlayerController] is playing — the lyrics half of the
 * Android app's MainViewModel: one lookup per track once its duration is known
 * (every source matches on length), the result per provider remembered so the
 * provider picker can switch between them without asking again.
 */
class LyricsController(private val player: PlayerController) {

    data class State(
        val videoId: String? = null,
        val lines: List<LyricLine>? = null,
        val source: LyricsSource? = null,
        /** The lookup finished; [lines] null then means nothing anywhere had it. */
        val checked: Boolean = false,
        val providers: Map<LyricsSource, LyricsProviderState> = emptyMap(),
    ) {
        val unavailable: Boolean get() = checked && lines.isNullOrEmpty()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null
    private var generation = 0L
    private val results = mutableMapOf<LyricsSource, LyricsRepository.Result>()
    private var selected: LyricsSource? = null
    private var request: Request? = null
    private val manualJobs = mutableMapOf<LyricsSource, Job>()

    private data class Request(
        val videoId: String,
        val title: String,
        val artist: String,
        val durationMs: Long,
        val album: String?,
    )

    init {
        scope.launch {
            player.state.collect { s ->
                val song = s.current ?: return@collect
                // Duration arrives a beat after the track does; every source matches on it.
                if (s.durationMs <= 0L) return@collect
                if (request?.videoId == song.videoId) return@collect
                load(Request(song.videoId, song.title, song.artist, s.durationMs, song.albumName))
            }
        }
    }

    private fun load(next: Request) {
        job?.cancel()
        manualJobs.values.forEach { it.cancel() }
        manualJobs.clear()
        results.clear()
        selected = null
        request = next
        val gen = ++generation
        _state.value = State(videoId = next.videoId)
        val localUri = player.state.value.current?.takeIf { it.videoId == next.videoId }?.localUri
            ?: Downloads.verifiedSavedUri(next.videoId)
        job = scope.launch {
            // A downloaded or imported track carries its own lyrics (saved
            // beside it, or in its tags): no network, and they work offline.
            if (localUri != null) {
                val embedded = EmbeddedLyrics.forUri(Context.app, localUri)
                if (gen != generation) return@launch
                if (embedded != null) {
                    _state.update { it.copy(lines = embedded, source = null, checked = true) }
                    return@launch
                }
            }
            val found = LyricsRepository.lyrics(
                videoId = next.videoId,
                title = next.title,
                artist = next.artist,
                durationMs = next.durationMs,
                album = next.album,
                sources = AppSettings.lyricsSources.value,
                order = AppSettings.lyricsSourceOrder.value,
                prioritizeSyllableSync = AppSettings.prioritizeSyllableSync.value,
                onSourceStarted = { source -> providerStarted(gen, source) },
                onSourceResult = { source, result -> providerFinished(gen, source, result) },
                onSourceCancelled = { source -> providerCancelled(gen, source) },
            )
            if (gen != generation) return@launch
            val shown = selected?.let(results::get) ?: found
            _state.update { it.copy(lines = shown?.lines, source = shown?.source, checked = true) }
        }
    }

    /** Pick a provider in the picker: found ones apply at once, untried ones are asked now. */
    fun selectProvider(source: LyricsSource) {
        val req = request ?: return
        selected = source
        when (_state.value.providers[source]) {
            LyricsProviderState.FOUND -> results[source]?.let(::show)
            LyricsProviderState.FETCHING -> Unit // applied when it lands
            LyricsProviderState.NOT_FOUND -> Unit
            else -> {
                if (manualJobs[source]?.isActive == true) return
                val gen = generation
                manualJobs[source] = scope.launch {
                    LyricsRepository.lyrics(
                        videoId = req.videoId,
                        title = req.title,
                        artist = req.artist,
                        durationMs = req.durationMs,
                        album = req.album,
                        sources = setOf(source),
                        order = listOf(source),
                        prioritizeSyllableSync = false,
                        onSourceStarted = { providerStarted(gen, it) },
                        onSourceResult = { s, r -> providerFinished(gen, s, r) },
                        onSourceCancelled = { providerCancelled(gen, it) },
                    )
                }
            }
        }
    }

    private fun show(result: LyricsRepository.Result) {
        _state.update { it.copy(lines = result.lines, source = result.source, checked = true) }
    }

    private fun providerStarted(gen: Long, source: LyricsSource) {
        if (gen != generation) return
        _state.update { it.copy(providers = it.providers + (source to LyricsProviderState.FETCHING)) }
    }

    private fun providerFinished(gen: Long, source: LyricsSource, result: LyricsRepository.Result?) {
        if (gen != generation) return
        if (result == null) {
            _state.update { it.copy(providers = it.providers + (source to LyricsProviderState.NOT_FOUND)) }
            return
        }
        results[source] = result
        _state.update { it.copy(providers = it.providers + (source to LyricsProviderState.FOUND)) }
        if (selected == source) show(result)
    }

    private fun providerCancelled(gen: Long, source: LyricsSource) {
        if (gen != generation) return
        _state.update { it.copy(providers = it.providers - source) }
    }
}
