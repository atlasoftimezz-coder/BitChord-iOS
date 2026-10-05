package com.music.bitchord.data

// ios-native: placeholders with the Android APIs for features that do not
// exist on iOS (app updates: sideloaded builds update by reinstalling).
// LocalMediaRepository is real since P5 (shared/.../data/LocalMediaRepository.kt).

import android.content.Context
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
