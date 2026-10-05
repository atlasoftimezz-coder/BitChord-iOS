package java.util

import kotlin.io.encoding.Base64 as KBase64

/** java.util.Base64 for ported code, over kotlin.io.encoding.Base64. */
object Base64 {
    fun getEncoder(): Encoder = Encoder(KBase64.Default, padding = true)
    fun getDecoder(): Decoder = Decoder(KBase64.Default)
    fun getUrlEncoder(): Encoder = Encoder(KBase64.UrlSafe, padding = true)
    fun getUrlDecoder(): Decoder = Decoder(KBase64.UrlSafe)
    fun getMimeDecoder(): Decoder = Decoder(KBase64.Mime)

    class Encoder internal constructor(private val codec: KBase64, private val padding: Boolean) {
        fun encodeToString(src: ByteArray): String {
            val text = codec.encode(src)
            return if (padding) text else text.trimEnd('=')
        }
        fun encode(src: ByteArray): ByteArray = encodeToString(src).encodeToByteArray()
        fun withoutPadding(): Encoder = Encoder(codec, padding = false)
    }

    class Decoder internal constructor(private val codec: KBase64) {
        fun decode(src: String): ByteArray {
            val cleaned = src.trim()
            val padded = when (cleaned.length % 4) {
                2 -> "$cleaned=="
                3 -> "$cleaned="
                else -> cleaned
            }
            return codec.withPadding(KBase64.PaddingOption.PRESENT_OPTIONAL).decode(padded)
        }
        fun decode(src: ByteArray): ByteArray = decode(src.decodeToString())
    }
}
