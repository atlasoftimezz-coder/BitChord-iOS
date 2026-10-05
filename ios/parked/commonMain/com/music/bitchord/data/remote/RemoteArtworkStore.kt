package com.music.bitchord.data.remote

// ios-native: placeholder until SMB/WebDAV libraries are ported (P7); no remote art to resolve.

import android.content.Context
import com.music.bitchord.data.model.Song

object RemoteArtworkStore {
    suspend fun resolveArt(context: Context, song: Song): String? = song.thumbnailUrl
}
