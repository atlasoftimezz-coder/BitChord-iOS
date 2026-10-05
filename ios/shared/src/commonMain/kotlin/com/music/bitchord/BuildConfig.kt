package com.music.bitchord

/** The Android BuildConfig fields ported code reads. */
object BuildConfig {
    const val DEBUG: Boolean = false
    const val APPLICATION_ID: String = "com.music.bitchord"
    const val BUILD_TYPE: String = "release"
    const val FLAVOR: String = "ios"
    const val VERSION_CODE: Int = 1
    const val VERSION_NAME: String = "ios-0.4"
    // Secrets injected from local.properties on Android; not shipped in the iOS build.
    const val LASTFM_API_KEY: String = ""
    const val LASTFM_SECRET: String = ""
    const val LISTEN_TOGETHER_SERVER: String = ""
}
