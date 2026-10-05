package java.util.concurrent

import com.music.bitchord.platform.PlatformLock
import com.music.bitchord.platform.withLock

/**
 * java.util.concurrent.ConcurrentHashMap for ported code: a lock around a
 * LinkedHashMap. The Android code uses it for small caches and in-flight
 * maps, never for anything contended enough to need lock striping.
 */
class ConcurrentHashMap<K, V> : MutableMap<K, V> {
    private val lock = PlatformLock()
    private val map = LinkedHashMap<K, V>()

    override val size: Int get() = lock.withLock { map.size }
    override fun isEmpty(): Boolean = lock.withLock { map.isEmpty() }
    override fun containsKey(key: K): Boolean = lock.withLock { map.containsKey(key) }
    override fun containsValue(value: V): Boolean = lock.withLock { map.containsValue(value) }
    override fun get(key: K): V? = lock.withLock { map[key] }
    override fun put(key: K, value: V): V? = lock.withLock { map.put(key, value) }
    override fun remove(key: K): V? = lock.withLock { map.remove(key) }
    override fun putAll(from: Map<out K, V>) = lock.withLock { map.putAll(from) }
    override fun clear() = lock.withLock { map.clear() }

    // Snapshots: iterating a live view without the lock is what this class exists to avoid.
    override val keys: MutableSet<K> get() = lock.withLock { LinkedHashSet(map.keys) }
    override val values: MutableCollection<V> get() = lock.withLock { ArrayList(map.values) }
    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() = lock.withLock { LinkedHashMap(map).entries }

    fun putIfAbsent(key: K, value: V): V? = lock.withLock {
        val existing = map[key]
        if (existing == null) map[key] = value
        existing
    }

    fun computeIfAbsent(key: K, mapping: (K) -> V): V = lock.withLock {
        map[key] ?: mapping(key).also { map[key] = it }
    }

    fun compute(key: K, remapping: (K, V?) -> V?): V? = lock.withLock {
        val next = remapping(key, map[key])
        if (next == null) map.remove(key) else map[key] = next
        next
    }

    fun merge(key: K, value: V, remapping: (V, V) -> V?): V? = lock.withLock {
        val old = map[key]
        val next = if (old == null) value else remapping(old, value)
        if (next == null) map.remove(key) else map[key] = next
        next
    }

    fun remove(key: K, value: V): Boolean = lock.withLock {
        if (map[key] == value) {
            map.remove(key)
            true
        } else {
            false
        }
    }

    fun replace(key: K, value: V): V? = lock.withLock { if (map.containsKey(key)) map.put(key, value) else null }

    fun getOrDefault(key: K, defaultValue: V): V = lock.withLock { map[key] ?: defaultValue }

    override fun toString(): String = lock.withLock { map.toString() }

    companion object {
        fun <K> newKeySet(): MutableSet<K> = ConcurrentSet()
    }
}

/** What `ConcurrentHashMap.newKeySet()` returns. */
class ConcurrentSet<E> : MutableSet<E> {
    private val lock = PlatformLock()
    private val set = LinkedHashSet<E>()
    override val size: Int get() = lock.withLock { set.size }
    override fun isEmpty(): Boolean = lock.withLock { set.isEmpty() }
    override fun contains(element: E): Boolean = lock.withLock { set.contains(element) }
    override fun containsAll(elements: Collection<E>): Boolean = lock.withLock { set.containsAll(elements) }
    override fun add(element: E): Boolean = lock.withLock { set.add(element) }
    override fun addAll(elements: Collection<E>): Boolean = lock.withLock { set.addAll(elements) }
    override fun remove(element: E): Boolean = lock.withLock { set.remove(element) }
    override fun removeAll(elements: Collection<E>): Boolean = lock.withLock { set.removeAll(elements.toSet()) }
    override fun retainAll(elements: Collection<E>): Boolean = lock.withLock { set.retainAll(elements.toSet()) }
    override fun clear() = lock.withLock { set.clear() }
    override fun iterator(): MutableIterator<E> = lock.withLock { LinkedHashSet(set).iterator() }
}

class CopyOnWriteArrayList<E> : MutableList<E> by ArrayList()
