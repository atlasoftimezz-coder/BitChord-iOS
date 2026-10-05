package java.util.concurrent.atomic

import kotlin.concurrent.atomics.AtomicReference as KAtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Stand-in for the JDK class over Kotlin's common atomics, for ported code. */
@OptIn(ExperimentalAtomicApi::class)
class AtomicReference<T>(initial: T) {
    private val ref = KAtomicReference(initial)

    fun get(): T = ref.load()
    fun set(value: T) = ref.store(value)
    fun getAndSet(value: T): T = ref.exchange(value)
    fun compareAndSet(expect: T, update: T): Boolean = ref.compareAndSet(expect, update)
}
