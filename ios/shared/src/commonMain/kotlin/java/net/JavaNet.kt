package java.net

import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.encodeURLParameter

/** java.net.URLEncoder/URLDecoder for ported code (form encoding: space <-> '+'). */
object URLEncoder {
    fun encode(s: String, enc: String = "UTF-8"): String = s.encodeURLParameter(spaceToPlus = true)
}

object URLDecoder {
    fun decode(s: String, enc: String = "UTF-8"): String = s.decodeURLQueryComponent(plusIsSpace = true)
}

class URL(private val spec: String) {
    override fun toString(): String = spec
    fun toExternalForm(): String = spec
    val host: String get() = spec.substringAfter("://").substringBefore('/').substringBefore(':')
    val protocol: String get() = spec.substringBefore("://")
    val path: String get() = "/" + spec.substringAfter("://").substringAfter('/', "").substringBefore('?')
    val query: String? get() = spec.substringAfter('?', "").ifEmpty { null }
}

class URI(private val spec: String) {
    override fun toString(): String = spec
    val host: String? get() = spec.substringAfter("://", "").substringBefore('/').substringBefore(':').ifEmpty { null }
    val scheme: String? get() = spec.substringBefore("://", "").ifEmpty { null }
    val path: String? get() = spec.substringAfter("://").substringAfter('/', "").substringBefore('?').let { "/$it" }
    fun toURL(): URL = URL(spec)
}
