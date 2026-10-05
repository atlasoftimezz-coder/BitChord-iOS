package com.music.bitchord.data.sources

// ios-native: placeholders with the Android source-registry API until P7 ports
// add-on/module sources. With no extra sources configured, YouTube is the only
// one and nothing is ever substituted — exactly the Android behaviour then.

import android.content.Context

object SourceRegistry {
    fun init(context: Context) = Unit
    fun activeForPlayback(): List<MusicSource> = emptyList()
    fun reorderAddons(orderedIds: List<String>) = Unit

    /** "src:<config>:<track>" keys of non-YouTube sources; none exist yet. */
    fun parseTrackKey(key: String): Pair<String, String>? = null
}

object SourceResolver {
    fun requestForNow(): StreamRequest = StreamRequest.Best
    suspend fun substituteForYouTube(target: TrackMatcher.Target): SourceStream? = null
    suspend fun prefetchSubstitute(target: TrackMatcher.Target): SourceStream? = null
    fun canSubstituteForYouTube(): Boolean = false
}
