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

/** JDK `sortedSetOf()` (a TreeSet) for ported code: a set that iterates in natural order. */
fun <T : Comparable<T>> sortedSetOf(vararg elements: T): MutableSet<T> = SortedMutableSet<T>().apply { addAll(elements) }

class SortedMutableSet<T : Comparable<T>> : AbstractMutableSet<T>() {
    private val items = ArrayList<T>()
    override val size: Int get() = items.size
    override fun add(element: T): Boolean {
        val index = items.binarySearch(element)
        if (index >= 0) return false
        items.add(-index - 1, element)
        return true
    }
    override fun contains(element: T): Boolean = items.binarySearch(element) >= 0
    override fun iterator(): MutableIterator<T> = items.iterator()
    override fun remove(element: T): Boolean {
        val index = items.binarySearch(element)
        if (index < 0) return false
        items.removeAt(index)
        return true
    }
    fun first(): T = items.first()
    fun last(): T = items.last()
}

/**
 * JDK `Map.merge` for ported code: puts [value] when [key] is absent,
 * otherwise stores remapping(old, value); a null result removes the key.
 */
fun <K, V : Any> MutableMap<K, V>.merge(key: K, value: V, remapping: (V, V) -> V?): V? {
    val old = this[key]
    val next = if (old == null) value else remapping(old, value)
    if (next == null) remove(key) else this[key] = next
    return next
}

/** JDK `Map.putIfAbsent` for ported code. */
fun <K, V> MutableMap<K, V>.putIfAbsent(key: K, value: V): V? {
    val existing = this[key]
    if (existing == null) this[key] = value
    return existing
}
