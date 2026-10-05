package com.music.bitchord.compat

import com.music.bitchord.platform.PlatformLock

/*
 * Small JVM-isms the ported code leans on. `synchronized` is imported by the
 * port fixer (ios/tools/fix_ported.py) into files that call it.
 */

private val globalLocks = mutableMapOf<Any, PlatformLock>()
private val registry = PlatformLock()

private fun lockFor(target: Any): PlatformLock {
    registry.lock()
    try {
        return globalLocks.getOrPut(target) { PlatformLock() }
    } finally {
        registry.unlock()
    }
}

/** JVM `synchronized(lock) { }`: a re-entrancy-free mutex keyed on the lock object. */
inline fun <T> synchronized(lock: Any, block: () -> T): T {
    val l = lockObject(lock)
    l.lock()
    try {
        return block()
    } finally {
        l.unlock()
    }
}

@PublishedApi
internal fun lockObject(lock: Any): PlatformLock = lock as? PlatformLock ?: lockFor(lock)
