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

/** Secrets (cookies, tokens) in the Keychain, readable only on this device while unlocked. */
expect object SecureStore {
    fun read(key: String): String?
    fun write(key: String, value: String?)
}

/** The device region (ISO 3166), e.g. "IN"; "" when unknown. */
expect fun deviceRegion(): String

/** Synchronous file access for the java.io.File shim (NSFileManager on iOS). */
expect object FileSystem {
    fun exists(path: String): Boolean
    fun isFile(path: String): Boolean
    fun isDirectory(path: String): Boolean
    fun size(path: String): Long
    fun modifiedMillis(path: String): Long
    fun setModifiedMillis(path: String, millis: Long): Boolean
    fun mkdirs(path: String): Boolean
    fun delete(path: String): Boolean
    fun move(from: String, to: String): Boolean
    fun list(path: String): List<String>
    fun read(path: String): ByteArray?
    fun write(path: String, bytes: ByteArray): Boolean
    /** Adds [bytes] to the end of [path], creating it if missing; for streaming downloads to disk. */
    fun append(path: String, bytes: ByteArray): Boolean
    fun tempDirectory(): String
    fun cachesDirectory(): String
    fun filesDirectory(): String
    fun documentsDirectory(): String
}

expect fun md5(data: ByteArray): ByteArray

/** Unicode normalization; [form] is "NFD", "NFC", "NFKD" or "NFKC". */
expect fun unicodeNormalize(text: String, form: String): String

/** The device time zone's offset from UTC, in seconds, at [epochMs]. */
expect fun utcOffsetSeconds(epochMs: Long): Int

/**
 * Ask the OS for time to finish work after the app leaves the foreground (a
 * download batch). Returns a token for [endBackgroundWork]. iOS grants about
 * 30 seconds; when audio is playing the app keeps running regardless.
 */
expect fun beginBackgroundWork(name: String): Long

expect fun endBackgroundWork(token: Long)
