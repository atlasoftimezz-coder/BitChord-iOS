package com.music.bitchord.playback

/**
 * The audio output, implemented in Swift on AVFoundation (iosApp/AudioEngine.swift)
 * and handed to Kotlin through `MainViewController(engine)`.
 *
 * Kept deliberately small: Kotlin decides *what* plays (resolution, queue,
 * retries), Swift only plays a URL. That is the same split the Android app
 * has between `StreamResolver` and ExoPlayer.
 */
interface AudioEngine {
    /**
     * Start [url] from the beginning. Every HTTP request for its bytes carries
     * [headers] and asks for at most [chunkBytes] at a time, because
     * googlevideo refuses or throttles larger ranges for some clients.
     */
    fun play(url: String, headers: Map<String, String>, mimeType: String, contentLength: Long, chunkBytes: Long)
    fun pause()
    fun resume()
    fun seekTo(positionMs: Long)
    fun stop()
    fun setListener(listener: AudioEngineListener?)
}

interface AudioEngineListener {
    /** Called on the main thread, a few times a second while something is loaded. */
    fun onProgress(positionMs: Long, durationMs: Long, isPlaying: Boolean, isBuffering: Boolean)
    fun onEnded()
    /** [httpStatus] is the googlevideo status that caused it, or 0 when it was not an HTTP refusal. */
    fun onError(message: String, httpStatus: Int)
}
