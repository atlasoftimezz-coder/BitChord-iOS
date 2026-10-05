package com.music.bitchord.ui

/** Stack of BackHandler registrations; [back] runs the newest enabled one. */
object BackDispatcher {
    class Entry(val enabled: () -> Boolean, val onBack: () -> Unit)

    private val entries = mutableListOf<Entry>()

    fun register(entry: Entry) {
        entries += entry
    }

    fun unregister(entry: Entry) {
        entries -= entry
    }

    /** @return whether a handler consumed it. */
    fun back(): Boolean {
        val handler = entries.lastOrNull { it.enabled() } ?: return false
        handler.onBack()
        return true
    }
}
