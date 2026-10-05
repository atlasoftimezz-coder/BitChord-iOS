package com.music.bitchord.platform

/** The device's preferred language as an ISO 639 code, e.g. "en". */
expect fun deviceLanguage(): String

/** Wall-clock time in milliseconds since the Unix epoch. */
expect fun epochMillis(): Long

/** Monotonic milliseconds, the counterpart of Android's `SystemClock.elapsedRealtime()`. */
expect fun elapsedMillis(): Long

expect fun sha1(data: ByteArray): ByteArray

expect fun platformLog(level: String, tag: String, message: String)

/** Save escaped Kotlin exceptions (with recent log lines) for the next launch to show. */
expect fun installCrashCatcher()

/** The report saved by the last crash, if any; reading it deletes it. */
expect fun takeLastCrash(): String?

/** The most recent log lines, for a "copy debug log" action. */
expect fun recentLog(): String

expect fun sha256(data: ByteArray): ByteArray

expect fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray

/** [code]'s language name written in [inLanguage], e.g. ("de", "en") -> "German"; null if unknown. */
expect fun languageDisplayName(code: String, inLanguage: String): String?

/** A plain mutual-exclusion lock for code that cannot suspend. */
expect class PlatformLock() {
    fun lock()
    fun unlock()
}

inline fun <T> PlatformLock.withLock(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}

/** Files under the app's caches directory (purgeable by iOS), by relative path. */
expect object AppCache {
    fun read(path: String): ByteArray?
    fun write(path: String, data: ByteArray): Boolean
    fun delete(path: String)
    /** File names (not paths) in [directory], newest first. */
    fun list(directory: String): List<String>
}

/** Plays one of the app's haptic patterns on the device. */
expect fun playHaptic(haptic: com.music.bitchord.ui.haptics.Haptic)

/** Small persistent preferences (NSUserDefaults on iOS); getters return null when unset. */
expect object KeyValueStore {
    fun getBoolean(key: String): Boolean?
    fun putBoolean(key: String, value: Boolean)
    fun getInt(key: String): Int?
    fun putInt(key: String, value: Int)
    fun getLong(key: String): Long?
    fun putLong(key: String, value: Long)
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}
