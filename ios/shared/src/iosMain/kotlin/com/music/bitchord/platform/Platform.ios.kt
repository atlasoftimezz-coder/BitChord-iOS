package com.music.bitchord.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.CC_MD5
import platform.CoreCrypto.CC_MD5_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA1
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.kCCHmacAlgSHA256
import platform.CoreCrypto.CC_SHA1_DIGEST_LENGTH
import platform.Foundation.NSDate
import platform.Foundation.NSLocale
import platform.Foundation.NSString
import platform.Foundation.decomposedStringWithCanonicalMapping
import platform.Foundation.precomposedStringWithCanonicalMapping
import platform.Foundation.decomposedStringWithCompatibilityMapping
import platform.Foundation.precomposedStringWithCompatibilityMapping
import platform.Foundation.NSLock
import platform.Foundation.NSRecursiveLock
import platform.Foundation.localizedStringForLanguageCode
import platform.Foundation.NSLog
import platform.Foundation.NSProcessInfo
import platform.Foundation.preferredLanguages
import platform.Foundation.timeIntervalSince1970

actual fun deviceLanguage(): String {
    // "en-IN", "zh-Hans-CN" → "en", "zh"
    val first = NSLocale.preferredLanguages.firstOrNull() as? String ?: return "en"
    return first.substringBefore('-')
}

actual fun epochMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

actual fun elapsedMillis(): Long = (NSProcessInfo.processInfo.systemUptime * 1000).toLong()

@OptIn(ExperimentalForeignApi::class)
actual fun sha1(data: ByteArray): ByteArray {
    val out = ByteArray(CC_SHA1_DIGEST_LENGTH)
    data.usePinned { input ->
        out.usePinned { digest ->
            CC_SHA1(
                if (data.isEmpty()) null else input.addressOf(0),
                data.size.toUInt(),
                digest.addressOf(0).reinterpret<UByteVar>(),
            )
        }
    }
    return out
}

actual fun platformLog(level: String, tag: String, message: String) {
    val line = "$level/$tag: $message"
    CrashCatcher.record(line)
    // NSLog's first argument is a format string. Passing the text as a vararg
    // ("%@", text) crashes — Kotlin does not box a String into an NSString for
    // C varargs — so escape the percent signs and pass it as the format itself.
    NSLog(line.replace("%", "%%"))
}

@OptIn(ExperimentalForeignApi::class)
actual fun sha256(data: ByteArray): ByteArray {
    val out = ByteArray(CC_SHA256_DIGEST_LENGTH)
    data.usePinned { input ->
        out.usePinned { digest ->
            CC_SHA256(
                if (data.isEmpty()) null else input.addressOf(0),
                data.size.toUInt(),
                digest.addressOf(0).reinterpret<UByteVar>(),
            )
        }
    }
    return out
}

@OptIn(ExperimentalForeignApi::class)
actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val out = ByteArray(CC_SHA256_DIGEST_LENGTH)
    key.usePinned { k ->
        data.usePinned { d ->
            out.usePinned { o ->
                CCHmac(
                    kCCHmacAlgSHA256.toUInt(),
                    if (key.isEmpty()) null else k.addressOf(0),
                    key.size.toULong(),
                    if (data.isEmpty()) null else d.addressOf(0),
                    data.size.toULong(),
                    o.addressOf(0),
                )
            }
        }
    }
    return out
}

actual fun languageDisplayName(code: String, inLanguage: String): String? =
    NSLocale(localeIdentifier = inLanguage).localizedStringForLanguageCode(code)

actual class PlatformLock actual constructor() {
    // Recursive: ported `synchronized` blocks may nest on the same lock, as the JVM allows.
    private val lock = NSRecursiveLock()
    actual fun lock() = lock.lock()
    actual fun unlock() = lock.unlock()
}

@OptIn(ExperimentalForeignApi::class)
@Suppress("DEPRECATION")
actual fun md5(data: ByteArray): ByteArray {
    val out = ByteArray(CC_MD5_DIGEST_LENGTH)
    data.usePinned { input ->
        out.usePinned { digest ->
            CC_MD5(
                if (data.isEmpty()) null else input.addressOf(0),
                data.size.toUInt(),
                digest.addressOf(0).reinterpret<UByteVar>(),
            )
        }
    }
    return out
}

@Suppress("CAST_NEVER_SUCCEEDS")
actual fun unicodeNormalize(text: String, form: String): String {
    val ns = text as NSString
    return when (form) {
        "NFD" -> ns.decomposedStringWithCanonicalMapping
        "NFKD" -> ns.decomposedStringWithCompatibilityMapping
        "NFKC" -> ns.precomposedStringWithCompatibilityMapping
        else -> ns.precomposedStringWithCanonicalMapping
    }
}
