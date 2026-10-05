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
