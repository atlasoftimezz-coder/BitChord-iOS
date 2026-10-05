package com.music.bitchord.platform


import kotlinx.coroutines.IO
/** A finished HTTP exchange; [error] is set (and the rest empty) when there was no response at all. */
class RawHttpResponse(
    val code: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
    val error: String? = null,
)

/**
 * Blocking request for code ported from OkHttp's `execute()`. Must not be
 * called on the main thread; callers run it on Dispatchers.IO as on Android.
 */
expect fun httpExecuteBlocking(
    method: String,
    url: String,
    headers: Map<String, String>,
    body: ByteArray?,
    contentType: String?,
    timeoutMs: Long,
): RawHttpResponse

/** Asynchronous variant for OkHttp's `enqueue()`; [onDone] runs on a background queue. */
expect fun httpExecuteAsync(
    method: String,
    url: String,
    headers: Map<String, String>,
    body: ByteArray?,
    contentType: String?,
    timeoutMs: Long,
    onDone: (RawHttpResponse) -> Unit,
)
