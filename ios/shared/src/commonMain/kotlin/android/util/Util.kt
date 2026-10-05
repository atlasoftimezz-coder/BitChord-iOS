package android.util

import com.music.bitchord.compat.LruMap
import com.music.bitchord.platform.platformLog

/** android.util.Log for ported code; lines go to the device console and the in-app debug log. */
object Log {
    fun v(tag: String, msg: String): Int { platformLog("V", tag, msg); return 0 }
    fun d(tag: String, msg: String): Int { platformLog("D", tag, msg); return 0 }
    fun i(tag: String, msg: String): Int { platformLog("I", tag, msg); return 0 }
    fun w(tag: String, msg: String): Int { platformLog("W", tag, msg); return 0 }
    fun w(tag: String, msg: String?, tr: Throwable?): Int { platformLog("W", tag, "$msg: $tr"); return 0 }
    fun e(tag: String, msg: String): Int { platformLog("E", tag, msg); return 0 }
    fun e(tag: String, msg: String?, tr: Throwable?): Int { platformLog("E", tag, "$msg: $tr"); return 0 }
    fun isLoggable(tag: String, level: Int): Boolean = true
    const val VERBOSE = 2
    const val DEBUG = 3
    const val INFO = 4
    const val WARN = 5
    const val ERROR = 6
}

/** android.util.LruCache for ported code (entry-count sizing only, which is all the app uses). */
open class LruCache<K : Any, V : Any>(private val maxSize: Int) {
    private val map = LruMap<K, V>(maxSize)
    fun get(key: K): V? = map[key]
    fun put(key: K, value: V): V? = map.put(key, value)
    fun remove(key: K): V? = map.remove(key)
    fun evictAll() = map.evictAll()
    fun size(): Int = map.size
    fun maxSize(): Int = maxSize
}
