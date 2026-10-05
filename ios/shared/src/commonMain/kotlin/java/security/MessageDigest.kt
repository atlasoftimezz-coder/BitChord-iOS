package java.security

import com.music.bitchord.platform.md5
import com.music.bitchord.platform.sha1
import com.music.bitchord.platform.sha256

/** java.security.MessageDigest for ported code: MD5, SHA-1 and SHA-256 over CommonCrypto. */
class MessageDigest private constructor(private val algorithm: String) {
    private val buffer = java.io.ByteArrayOutputStream()

    fun update(input: ByteArray) = buffer.write(input, 0, input.size)
    fun update(input: ByteArray, offset: Int, len: Int) = buffer.write(input, offset, len)
    fun update(input: Byte) = buffer.write(input.toInt())

    fun digest(): ByteArray {
        val data = buffer.toByteArray()
        buffer.reset()
        return when (algorithm) {
            "MD5" -> md5(data)
            "SHA-1", "SHA1" -> sha1(data)
            else -> sha256(data)
        }
    }

    fun digest(input: ByteArray): ByteArray {
        update(input)
        return digest()
    }

    fun reset() = buffer.reset()

    companion object {
        fun getInstance(algorithm: String): MessageDigest {
            val normalized = algorithm.uppercase()
            require(normalized in setOf("MD5", "SHA-1", "SHA1", "SHA-256", "SHA256")) {
                "Unsupported digest: $algorithm"
            }
            return MessageDigest(normalized)
        }
    }
}

class NoSuchAlgorithmException(message: String? = null) : Exception(message)
