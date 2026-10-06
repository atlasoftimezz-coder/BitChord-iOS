package com.music.bitchord.playback

/**
 * The audio output, implemented in Swift on AVFoundation (iosApp/AudioEngine.swift)
 * and handed to Kotlin through `MainViewController(engine)`.
 *
 * Kotlin decides *what* plays (resolution, queue, retries), Swift plays URLs
 * and owns everything iOS-specific about it: the audio session, interruptions,
 * the lock screen / Control Center ("Now Playing") and remote commands. That
 * is the split the Android app has between `StreamResolver` and its
 * ExoPlayer + MediaSession service.
 */
interface AudioEngine {
    /**
     * Start [url] from the beginning, dropping anything queued. Every HTTP
     * request for its bytes carries [headers] and asks for at most
     * [chunkBytes], because googlevideo refuses or throttles larger ranges
     * for some clients.
     */
    fun play(url: String, headers: Map<String, String>, mimeType: String, contentLength: Long, chunkBytes: Long)

    /**
     * Queue the track after the current one, so it starts the moment the
     * current one ends — gaplessly, and without the app having to be awake to
     * resolve it, which matters with the screen locked.
     */
    fun setNext(url: String, headers: Map<String, String>, mimeType: String, contentLength: Long, chunkBytes: Long)
    fun clearNext()

    fun pause()
    fun resume()
    fun seekTo(positionMs: Long)
    fun stop()

    /** What the lock screen and Control Center show for the current track. */
    fun setNowPlaying(title: String, artist: String, album: String?, artworkUrl: String?)

    /** Enables or greys out the lock-screen skip buttons. */
    fun setQueueCapabilities(hasNext: Boolean, hasPrevious: Boolean)

    fun setListener(listener: AudioEngineListener?)

    // ---- The second deck, for crossfades and Automix (P6) -----------------
    //
    // Two players, as on Android: the *current* deck is what the app shows,
    // reports progress for and the lock screen controls; the *other* deck is
    // either a standby armed with the incoming track (silent, before the
    // handoff) or the tail of the outgoing track (fading, after it).

    /**
     * Load [url] on the other deck: silent, paused at [startMs], to play at
     * [rate] (time-stretched, pitch kept) once started.
     */
    fun armStandby(
        url: String,
        headers: Map<String, String>,
        mimeType: String,
        contentLength: Long,
        chunkBytes: Long,
        startMs: Long,
        rate: Float,
    )

    /** True once the armed standby is buffered at its start position. */
    fun isStandbyReady(): Boolean

    /**
     * Start the armed standby and make it the current deck. The old current
     * deck keeps playing as the tail (anything queued after it is dropped);
     * progress, Now Playing and remote commands follow the new deck.
     */
    fun handoffToStandby()

    /** Volume, 0..1, of the current deck and of the other one. */
    fun setDeckVolumes(current: Float, other: Float)

    /**
     * Low-pass and high-pass corners in Hz for each deck. A low-pass at or
     * above 20 kHz and a high-pass at or below 20 Hz mean "open".
     */
    fun setDeckFilters(currentLowPassHz: Float, currentHighPassHz: Float, otherLowPassHz: Float, otherHighPassHz: Float)

    /** The current deck's position, read now rather than from the last progress tick. */
    fun currentPositionMs(): Long

    /** True while the other deck is playing a tail that has not run out. */
    fun isTailPlaying(): Boolean

    /** Stop and empty the other deck — an armed standby or a tail — and reset its volume and filters. */
    fun releaseOtherDeck()

    /**
     * The listener's speed (0.5–2, pitch kept). Applies to both decks and
     * multiplies any beatmatch rate, as on Android.
     */
    fun setPlaybackSpeed(speed: Float)

    /**
     * The in-app equaliser, applied to every track on both decks. [kinds],
     * [frequenciesHz], [gainsDb] and [qs] describe one filter section per
     * slot of [EqLayout] (kind 0 = bell, 1 = low shelf, 2 = high shelf);
     * [preampDb] and [balance] (-1 left .. 1 right) follow them.
     */
    fun setEqualizer(
        enabled: Boolean,
        kinds: IntArray,
        frequenciesHz: FloatArray,
        gainsDb: FloatArray,
        qs: FloatArray,
        preampDb: Float,
        balance: Float,
    )

    /** Play through silent stretches quickly instead of waiting them out. */
    fun setSkipSilence(enabled: Boolean)

    /** Playback rate of the current deck; back to 1 once a beatmatched blend is over. */
    fun setCurrentRate(rate: Float)
}

interface AudioEngineListener {
    /** Called on the main thread, a few times a second while something is loaded. */
    fun onProgress(positionMs: Long, durationMs: Long, isPlaying: Boolean, isBuffering: Boolean)

    /** The current track ended with nothing queued after it. */
    fun onEnded()

    /** The current track ended and the one given to [AudioEngine.setNext] took over. */
    fun onAdvancedToNext()

    /** [httpStatus] is the googlevideo status that caused it, or 0 when it was not an HTTP refusal. */
    fun onError(message: String, httpStatus: Int)

    /** Skip buttons pressed on the lock screen, Control Center, headphones or CarPlay-less car audio. */
    fun onRemoteNext()
    fun onRemotePrevious()
}
