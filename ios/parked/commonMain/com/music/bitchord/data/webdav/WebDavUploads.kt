package com.music.bitchord.data.webdav

// ios-native: placeholder until WebDAV upload is ported (P7).

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object WebDavUploads {
    sealed interface TrackState {
        data object Queued : TrackState
        data class Running(val fraction: Float) : TrackState
        data object Done : TrackState
        data class Failed(val reason: String) : TrackState
        data object Skipped : TrackState
    }

    val active: StateFlow<Map<String, TrackState>> = MutableStateFlow<Map<String, TrackState>>(emptyMap()).asStateFlow()

    fun isUploadable(song: Song): Boolean = false
}
