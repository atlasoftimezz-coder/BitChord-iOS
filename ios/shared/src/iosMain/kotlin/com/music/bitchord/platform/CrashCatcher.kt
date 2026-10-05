package com.music.bitchord.platform

import kotlin.experimental.ExperimentalNativeApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSLock
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * Keeps the last few hundred log lines in memory and, if a Kotlin exception
 * escapes, writes it plus those lines to Documents/last-crash.txt so the next
 * launch can show it. The user can't attach a debugger, so this is the
 * cheapest way to get a stack trace off the phone.
 */
internal object CrashCatcher {
    private const val MAX_LINES = 400
    private val lock = NSLock()
    private val lines = ArrayDeque<String>()

    fun record(line: String) {
        lock.lock()
        try {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        } finally {
            lock.unlock()
        }
    }

    fun recent(): String {
        lock.lock()
        try {
            return lines.joinToString("\n")
        } finally {
            lock.unlock()
        }
    }

    private val crashPath: String by lazy {
        val dir = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String
        "$dir/last-crash.txt"
    }

    @OptIn(ExperimentalNativeApi::class)
    fun install() {
        val previous = setUnhandledExceptionHook { error ->
            val report = buildString {
                appendLine("BitChord crash")
                appendLine(error.stackTraceToString())
                appendLine()
                appendLine("--- recent log ---")
                append(recent())
            }
            @Suppress("CAST_NEVER_SUCCEEDS")
            (report as NSString).writeToFile(crashPath, atomically = true, encoding = NSUTF8StringEncoding, error = null)
        }
        // Keep any hook installed before ours (there is none today) from being lost silently.
        if (previous != null) record("W/BitChord: replaced an existing unhandled-exception hook")
    }

    fun takeLastCrash(): String? {
        val manager = NSFileManager.defaultManager
        if (!manager.fileExistsAtPath(crashPath)) return null
        val text = NSString.stringWithContentsOfFile(crashPath, NSUTF8StringEncoding, null)
        manager.removeItemAtPath(crashPath, null)
        return text
    }
}

actual fun installCrashCatcher() = CrashCatcher.install()

actual fun takeLastCrash(): String? = CrashCatcher.takeLastCrash()

actual fun recentLog(): String = CrashCatcher.recent()
