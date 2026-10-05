package com.music.bitchord.data

// ios-native: placeholders with the Android APIs for features ported in later
// phases (local files: P5, app updates: none on iOS — sideloaded builds update
// by reinstalling).

import android.content.Context
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Device music library. iOS has no shared file store to scan; the Files-app import arrives in P5. */
object LocalMediaRepository {
    fun hasStoragePermission(context: Context): Boolean = false
    suspend fun getLocalMusic(context: Context): List<Song> = emptyList()
}

/** Self-update from GitHub releases is Android-only (APK install); on iOS it never reports an update. */
object AppUpdateChecker {
    data class UpdateInfo(
        val version: String,
        val releaseUrl: String,
        val apkUrl: String?,
        val notes: String = "",
    )

    val available: StateFlow<UpdateInfo?> = MutableStateFlow<UpdateInfo?>(null).asStateFlow()

    suspend fun check() = Unit
    suspend fun clearCache(context: Context) = Unit
}
