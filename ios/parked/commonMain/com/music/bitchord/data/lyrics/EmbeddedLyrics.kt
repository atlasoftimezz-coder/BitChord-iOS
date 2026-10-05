package com.music.bitchord.data.lyrics

// ios-native: placeholder until P5 ports tag reading for downloaded/local files.

import android.content.Context

object EmbeddedLyrics {
    suspend fun forUri(context: Context, uriString: String): List<LyricLine>? = null
}
