package java.util.concurrent.atomic

import kotlin.concurrent.atomics.AtomicBoolean as KAtomicBoolean
import kotlin.concurrent.atomics.AtomicInt as KAtomicInt
import kotlin.concurrent.atomics.AtomicLong as KAtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** JDK atomics for ported code, over Kotlin's common atomics. */
@OptIn(ExperimentalAtomicApi::class)
class AtomicLong(initial: Long = 0L) {
    private val v = KAtomicLong(initial)
    fun get(): Long = v.load()
    fun set(value: Long) = v.store(value)
    fun getAndSet(value: Long): Long = v.exchange(value)
    fun incrementAndGet(): Long = v.addAndFetch(1L)
    fun decrementAndGet(): Long = v.addAndFetch(-1L)
    fun getAndIncrement(): Long = v.fetchAndAdd(1L)
    fun addAndGet(delta: Long): Long = v.addAndFetch(delta)
    fun getAndAdd(delta: Long): Long = v.fetchAndAdd(delta)
    fun compareAndSet(expect: Long, update: Long): Boolean = v.compareAndSet(expect, update)
    override fun toString(): String = get().toString()
}

@OptIn(ExperimentalAtomicApi::class)
class AtomicInteger(initial: Int = 0) {
    private val v = KAtomicInt(initial)
    fun get(): Int = v.load()
    fun set(value: Int) = v.store(value)
    fun getAndSet(value: Int): Int = v.exchange(value)
    fun incrementAndGet(): Int = v.addAndFetch(1)
    fun decrementAndGet(): Int = v.addAndFetch(-1)
    fun getAndIncrement(): Int = v.fetchAndAdd(1)
    fun addAndGet(delta: Int): Int = v.addAndFetch(delta)
    fun compareAndSet(expect: Int, update: Int): Boolean = v.compareAndSet(expect, update)
    override fun toString(): String = get().toString()
}

@OptIn(ExperimentalAtomicApi::class)
class AtomicBoolean(initial: Boolean = false) {
    private val v = KAtomicBoolean(initial)
    fun get(): Boolean = v.load()
    fun set(value: Boolean) = v.store(value)
    fun getAndSet(value: Boolean): Boolean = v.exchange(value)
    fun compareAndSet(expect: Boolean, update: Boolean): Boolean = v.compareAndSet(expect, update)
    override fun toString(): String = get().toString()
}
