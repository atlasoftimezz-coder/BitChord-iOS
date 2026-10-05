package com.music.bitchord.compat

import com.music.bitchord.platform.PlatformLock
import com.music.bitchord.platform.withLock

/**
 * Thread-safe, size-bounded, least-recently-used map — what the Android code
 * builds from an access-ordered LinkedHashMap (not available in common Kotlin)
 * or android.util.LruCache.
 */
class LruMap<K, V>(private val maxSize: Int) {
    private val lock = PlatformLock()
    private val map = LinkedHashMap<K, V>()

    operator fun get(key: K): V? = lock.withLock {
        val value = map.remove(key) ?: return@withLock null
        map[key] = value
        value
    }

    operator fun set(key: K, value: V) = put(key, value)

    fun put(key: K, value: V): V? = lock.withLock {
        val previous = map.remove(key)
        map[key] = value
        while (map.size > maxSize) map.remove(map.keys.first())
        previous
    }

    fun remove(key: K): V? = lock.withLock { map.remove(key) }

    fun evictAll() = lock.withLock { map.clear() }

    val size: Int get() = lock.withLock { map.size }
}
