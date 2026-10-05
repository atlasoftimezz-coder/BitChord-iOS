package com.music.bitchord.data

import okhttp3.OkHttpClient

/** The app's shared client, as on Android; on iOS it is the OkHttp shim over NSURLSession. */
object Http {
    val client: OkHttpClient = OkHttpClient()
}
