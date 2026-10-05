package com.music.bitchord.data

import com.music.bitchord.platform.platformLog

/**
 * `android.util.Log` stand-in for the iOS port. Import as
 * `import com.music.bitchord.data.DebugLog as Log` so ported call sites stay
 * untouched. Lines go to the device console (NSLog).
 */
object DebugLog {
    fun d(tag: String, message: String) = platformLog("D", tag, message)
    fun i(tag: String, message: String) = platformLog("I", tag, message)
    fun w(tag: String, message: String) = platformLog("W", tag, message)
    fun w(tag: String, message: String, error: Throwable) = platformLog("W", tag, "$message: $error")
    fun e(tag: String, message: String) = platformLog("E", tag, message)
    fun e(tag: String, message: String, error: Throwable) = platformLog("E", tag, "$message: $error")
}
