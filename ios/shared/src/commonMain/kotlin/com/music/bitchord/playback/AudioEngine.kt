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
