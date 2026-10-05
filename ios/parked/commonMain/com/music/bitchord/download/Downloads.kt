package com.music.bitchord.download

// ios-native: placeholder with the Android Downloads API until P5 ports the real one.

import android.content.Context
import android.net.Uri
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface DownloadState {
    /** Accepted, waiting for the one in front of it. */
    data object Queued : DownloadState

    /** [fraction] is 0f until the length is known, which is the first thing asked for. */
    data class Running(val fraction: Float) : DownloadState

    data class Failed(val reason: String) : DownloadState
}

data class SavedCollection(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val thumbnailUrl: String? = null,
    val playlist: Boolean = false,
    /** In the order the page listed them, which is the order to play them in. */
    val videoIds: List<String> = emptyList(),
)

/** Offline downloads. Empty until P5: nothing is saved, nothing is queued. */
object Downloads {
    val active: StateFlow<Map<String, DownloadState>> = MutableStateFlow<Map<String, DownloadState>>(emptyMap()).asStateFlow()
    val requested: StateFlow<Map<String, Set<String>>> = MutableStateFlow<Map<String, Set<String>>>(emptyMap()).asStateFlow()
    val saved: StateFlow<Map<String, String>> = MutableStateFlow<Map<String, String>>(emptyMap()).asStateFlow()
    val collections: StateFlow<Map<String, SavedCollection>> =
        MutableStateFlow<Map<String, SavedCollection>>(emptyMap()).asStateFlow()

    const val PLAYLIST_PREFIX = "local:playlist:"

    fun pageIdFor(id: String): String = PLAYLIST_PREFIX + id

    fun recordIdOf(browseId: String): String? =
        browseId.removePrefix(PLAYLIST_PREFIX).takeIf { it != browseId && it.isNotEmpty() }

    fun enqueue(context: Context, song: Song, from: String? = null) = Unit
    fun cancel(videoId: String) = Unit
    fun dismissFailure(videoId: String) = Unit
    suspend fun savedUri(context: Context, videoId: String): Uri? = null
    fun isMissingLocalFile(uriString: String): Boolean = false
    suspend fun delete(context: Context, videoId: String): Boolean = false
    suspend fun getDownloadedSongs(context: Context): List<Song> = emptyList()
    suspend fun getCollectionSongs(context: Context, collectionId: String): List<Song> = emptyList()
}
