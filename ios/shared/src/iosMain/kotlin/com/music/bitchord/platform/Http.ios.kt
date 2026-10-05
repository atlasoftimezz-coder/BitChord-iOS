package com.music.bitchord.platform

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.create
import platform.posix.memcpy
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait

private val session: NSURLSession by lazy {
    val config = NSURLSessionConfiguration.defaultSessionConfiguration
    config.HTTPMaximumConnectionsPerHost = 6L
    NSURLSession.sessionWithConfiguration(config)
}

actual fun httpExecuteBlocking(
    method: String,
    url: String,
    headers: Map<String, String>,
    body: ByteArray?,
    contentType: String?,
    timeoutMs: Long,
): RawHttpResponse {
    var result: RawHttpResponse? = null
    val done = dispatch_semaphore_create(0)
    httpExecuteAsync(method, url, headers, body, contentType, timeoutMs) {
        result = it
        dispatch_semaphore_signal(done)
    }
    dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER)
    return result ?: RawHttpResponse(0, emptyMap(), ByteArray(0), "No response")
}

actual fun httpExecuteAsync(
    method: String,
    url: String,
    headers: Map<String, String>,
    body: ByteArray?,
    contentType: String?,
    timeoutMs: Long,
    onDone: (RawHttpResponse) -> Unit,
) {
    val nsUrl = NSURL.URLWithString(url)
    if (nsUrl == null) {
        onDone(RawHttpResponse(0, emptyMap(), ByteArray(0), "Invalid URL"))
        return
    }
    val request = NSMutableURLRequest(uRL = nsUrl)
    request.HTTPMethod = method
    request.timeoutInterval = timeoutMs / 1000.0
    headers.forEach { (name, value) -> request.setValue(value, forHTTPHeaderField = name) }
    if (body != null) {
        contentType?.let { request.setValue(it, forHTTPHeaderField = "Content-Type") }
        request.HTTPBody = body.toNSData()
    }
    session.dataTaskWithRequest(request) { data, response, error ->
        if (error != null) {
            onDone(RawHttpResponse(0, emptyMap(), ByteArray(0), error.localizedDescription))
            return@dataTaskWithRequest
        }
        val http = response as? NSHTTPURLResponse
        val responseHeaders = http?.allHeaderFields?.entries
            ?.associate { (k, v) -> k.toString() to v.toString() }
            .orEmpty()
        onDone(RawHttpResponse(http?.statusCode?.toInt() ?: 0, responseHeaders, data?.toByteArray() ?: ByteArray(0)))
    }.resume()
}

internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

internal fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val out = ByteArray(size)
    out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}
