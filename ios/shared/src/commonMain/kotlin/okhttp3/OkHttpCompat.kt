package okhttp3

import com.music.bitchord.platform.RawHttpResponse
import com.music.bitchord.platform.httpExecuteBlocking
import com.music.bitchord.platform.httpExecuteAsync
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.formUrlEncode
import io.ktor.http.takeFrom
import java.util.concurrent.TimeUnit
import kotlinx.io.IOException

/*
 * The slice of OkHttp the ported Android code uses, re-implemented on top of
 * NSURLSession (see Http.ios.kt) so those files compile on iOS unchanged.
 * Same package and names as the real library on purpose: the Android sources
 * import `okhttp3.*`, and keeping that line valid is what keeps each port a
 * copy rather than a rewrite. Only what is actually called is here.
 */

class HttpUrl private constructor(internal val url: Url) {
    val host: String get() = url.host
    val scheme: String get() = url.protocol.name
    val encodedPath: String get() = url.encodedPath

    fun queryParameter(name: String): String? = url.parameters[name]
    fun newBuilder(): Builder = Builder(URLBuilder(url))

    /** Resolve [link] against this URL, like a browser would. */
    fun resolve(link: String): HttpUrl? = runCatching {
        HttpUrl(URLBuilder(url).takeFrom(link).build())
    }.getOrNull()

    override fun toString(): String = url.toString()
    override fun equals(other: Any?): Boolean = other is HttpUrl && other.url == url
    override fun hashCode(): Int = url.hashCode()

    class Builder internal constructor(private val builder: URLBuilder) {
        fun addQueryParameter(name: String, value: String?): Builder = apply {
            builder.parameters.append(name, value ?: "")
        }

        fun setQueryParameter(name: String, value: String?): Builder = apply {
            if (value == null) builder.parameters.remove(name) else builder.parameters[name] = value
        }

        fun removeAllQueryParameters(name: String): Builder = apply { builder.parameters.remove(name) }

        fun addPathSegment(segment: String): Builder = apply {
            builder.pathSegments = builder.pathSegments.filter { it.isNotEmpty() } + segment
        }

        fun addPathSegments(segments: String): Builder = apply {
            builder.pathSegments = builder.pathSegments.filter { it.isNotEmpty() } + segments.split('/')
        }

        fun build(): HttpUrl = HttpUrl(builder.build())
    }

    companion object {
        fun String.toHttpUrl(): HttpUrl =
            toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid URL: $this")

        fun String.toHttpUrlOrNull(): HttpUrl? = runCatching {
            val parsed = Url(this)
            if (parsed.host.isEmpty()) null else HttpUrl(parsed)
        }.getOrNull()

        fun get(url: String): HttpUrl = url.toHttpUrl()
    }
}

class MediaType private constructor(val value: String) {
    override fun toString() = value

    companion object {
        fun String.toMediaType(): MediaType = MediaType(this)
        fun String.toMediaTypeOrNull(): MediaType? = MediaType(this)
    }
}

abstract class RequestBody {
    abstract fun contentType(): MediaType?
    abstract fun bytes(): ByteArray

    companion object {
        fun String.toRequestBody(contentType: MediaType? = null): RequestBody = ByteBody(encodeToByteArray(), contentType)
        fun ByteArray.toRequestBody(contentType: MediaType? = null): RequestBody = ByteBody(this, contentType)
    }

    private class ByteBody(private val data: ByteArray, private val type: MediaType?) : RequestBody() {
        override fun contentType() = type
        override fun bytes() = data
    }
}

class FormBody private constructor(private val pairs: List<Pair<String, String>>) : RequestBody() {
    override fun contentType(): MediaType? = with(MediaType) { "application/x-www-form-urlencoded".toMediaType() }
    override fun bytes(): ByteArray = pairs.formUrlEncode().encodeToByteArray()

    class Builder {
        private val pairs = mutableListOf<Pair<String, String>>()
        fun add(name: String, value: String): Builder = apply { pairs += name to value }
        fun build(): FormBody = FormBody(pairs.toList())
    }
}

class Headers internal constructor(private val values: Map<String, String>) {
    operator fun get(name: String): String? =
        values.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    fun toMap(): Map<String, String> = values
}

class Request private constructor(
    val url: HttpUrl,
    val method: String,
    val headers: Headers,
    val body: RequestBody?,
) {
    fun header(name: String): String? = headers[name]
    fun newBuilder(): Builder = Builder().also {
        it.url(url)
        headers.toMap().forEach { (k, v) -> it.header(k, v) }
        if (method == "POST" && body != null) it.post(body)
    }

    class Builder {
        private var url: HttpUrl? = null
        private var method = "GET"
        private var body: RequestBody? = null
        private val headers = linkedMapOf<String, String>()

        fun url(url: String): Builder = apply { this.url = with(HttpUrl) { url.toHttpUrl() } }
        fun url(url: HttpUrl): Builder = apply { this.url = url }
        fun header(name: String, value: String): Builder = apply {
            headers.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let(headers::remove)
            headers[name] = value
        }
        fun addHeader(name: String, value: String): Builder = header(name, value)
        fun removeHeader(name: String): Builder = apply {
            headers.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let(headers::remove)
        }
        fun get(): Builder = apply { method = "GET"; body = null }
        fun head(): Builder = apply { method = "HEAD"; body = null }
        fun post(body: RequestBody): Builder = apply { method = "POST"; this.body = body }

        fun build(): Request = Request(
            url = url ?: throw IllegalStateException("url == null"),
            method = method,
            headers = Headers(headers.toMap()),
            body = body,
        )
    }
}

class ResponseBody internal constructor(private val data: ByteArray, private val type: String?) {
    fun string(): String = data.decodeToString()
    fun bytes(): ByteArray = data
    fun contentLength(): Long = data.size.toLong()
    fun contentType(): MediaType? = type?.let { with(MediaType) { it.toMediaType() } }
    fun close() = Unit
}

class Response internal constructor(
    val request: Request,
    val code: Int,
    val headers: Headers,
    val body: ResponseBody?,
) : AutoCloseable {
    val isSuccessful: Boolean get() = code in 200..299
    val message: String get() = ""
    fun header(name: String, defaultValue: String? = null): String? = headers[name] ?: defaultValue
    override fun close() = Unit
}

interface Callback {
    fun onFailure(call: Call, e: IOException)
    fun onResponse(call: Call, response: Response)
}

interface Call {
    fun request(): Request
    fun execute(): Response
    fun enqueue(responseCallback: Callback)
    fun cancel()
    fun isCanceled(): Boolean
}

class OkHttpClient private constructor(
    internal val callTimeoutMs: Long,
    internal val connectTimeoutMs: Long,
) {
    constructor() : this(callTimeoutMs = 30_000, connectTimeoutMs = 15_000)

    fun newBuilder(): Builder = Builder(callTimeoutMs, connectTimeoutMs)

    fun newCall(request: Request): Call = RealCall(this, request)

    class Builder internal constructor(private var callTimeoutMs: Long, private var connectTimeoutMs: Long) {
        constructor() : this(30_000, 15_000)

        fun callTimeout(timeout: Long, unit: TimeUnit): Builder = apply { callTimeoutMs = unit.toMillis(timeout) }
        fun connectTimeout(timeout: Long, unit: TimeUnit): Builder = apply { connectTimeoutMs = unit.toMillis(timeout) }
        fun readTimeout(timeout: Long, unit: TimeUnit): Builder = apply {
            // NSURLSession has one per-request idle timeout; the connect one stands in for it.
            connectTimeoutMs = maxOf(connectTimeoutMs, unit.toMillis(timeout))
        }
        fun writeTimeout(timeout: Long, unit: TimeUnit): Builder = this
        fun followRedirects(follow: Boolean): Builder = this
        fun build(): OkHttpClient = OkHttpClient(callTimeoutMs, connectTimeoutMs)
    }
}

private class RealCall(private val client: OkHttpClient, private val request: Request) : Call {
    private var canceled = false

    override fun request() = request

    override fun execute(): Response {
        if (canceled) throw IOException("Canceled")
        val raw = httpExecuteBlocking(
            method = request.method,
            url = request.url.toString(),
            headers = request.headers.toMap(),
            body = request.body?.bytes(),
            contentType = request.body?.contentType()?.value,
            timeoutMs = client.callTimeoutMs.takeIf { it > 0 } ?: client.connectTimeoutMs,
        )
        return raw.toResponse()
    }

    override fun enqueue(responseCallback: Callback) {
        httpExecuteAsync(
            method = request.method,
            url = request.url.toString(),
            headers = request.headers.toMap(),
            body = request.body?.bytes(),
            contentType = request.body?.contentType()?.value,
            timeoutMs = client.callTimeoutMs.takeIf { it > 0 } ?: client.connectTimeoutMs,
        ) { raw ->
            if (canceled) return@httpExecuteAsync
            val error = raw.error
            if (error != null) responseCallback.onFailure(this, IOException(error))
            else responseCallback.onResponse(this, raw.toResponse())
        }
    }

    override fun cancel() {
        canceled = true
    }

    override fun isCanceled() = canceled

    private fun RawHttpResponse.toResponse(): Response {
        error?.let { throw IOException(it) }
        return Response(
            request = request,
            code = code,
            headers = Headers(headers),
            body = ResponseBody(body, headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value),
        )
    }
}
