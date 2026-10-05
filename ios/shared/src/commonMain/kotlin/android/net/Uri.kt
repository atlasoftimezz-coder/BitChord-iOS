package android.net

import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.decodeURLPart
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodedPath

/** android.net.Uri for ported code: parsing and the accessors the app reads. */
class Uri private constructor(private val raw: String) {
    private val parsed: Url? by lazy { runCatching { Url(raw) }.getOrNull()?.takeIf { raw.contains("://") || raw.startsWith("file:") } }

    val scheme: String? get() = raw.substringBefore(':', "").ifEmpty { null }
    val host: String? get() = parsed?.host?.ifEmpty { null }
    val authority: String? get() = parsed?.let { if (it.specifiedPort > 0) "${it.host}:${it.specifiedPort}" else it.host }
    val port: Int get() = parsed?.specifiedPort?.takeIf { it > 0 } ?: -1
    val path: String? get() = parsed?.encodedPath?.decodeURLPart() ?: raw.substringAfter(':', "").ifEmpty { null }
    val encodedPath: String? get() = parsed?.encodedPath
    val query: String? get() = parsed?.encodedQuery?.ifEmpty { null }
    val fragment: String? get() = parsed?.fragment?.ifEmpty { null }
    val pathSegments: List<String> get() = path?.split('/')?.filter { it.isNotEmpty() }.orEmpty()
    val lastPathSegment: String? get() = pathSegments.lastOrNull()
    val queryParameterNames: Set<String> get() = parsed?.parameters?.names().orEmpty()

    fun getQueryParameter(key: String): String? = parsed?.parameters?.get(key)
    fun getQueryParameters(key: String): List<String> = parsed?.parameters?.getAll(key).orEmpty()

    fun buildUpon(): Builder = Builder(raw)

    override fun toString(): String = raw
    override fun equals(other: Any?): Boolean = other is Uri && other.raw == raw
    override fun hashCode(): Int = raw.hashCode()

    class Builder internal constructor(start: String = "") {
        private var builder: URLBuilder = URLBuilder(start.ifEmpty { "https://localhost" })
        private var schemeOverride: String? = null
        fun scheme(value: String): Builder = apply { schemeOverride = value }
        fun authority(value: String): Builder = apply { builder.host = value }
        fun path(value: String): Builder = apply { builder.encodedPath = value }
        fun appendPath(segment: String): Builder = apply {
            builder.pathSegments = builder.pathSegments.filter { it.isNotEmpty() } + segment
        }
        fun appendQueryParameter(key: String, value: String?): Builder = apply {
            builder.parameters.append(key, value.orEmpty())
        }
        fun build(): Uri {
            val text = builder.buildString()
            return parse(schemeOverride?.let { it + text.substring(text.indexOf(':')) } ?: text)
        }
    }

    companion object {
        val EMPTY = Uri("")
        fun parse(uriString: String): Uri = Uri(uriString)
        fun fromFile(file: java.io.File): Uri = Uri("file://${file.path}")
        fun encode(s: String?): String? = s?.encodeURLParameter()
        fun decode(s: String?): String? = s?.decodeURLPart()
    }
}
