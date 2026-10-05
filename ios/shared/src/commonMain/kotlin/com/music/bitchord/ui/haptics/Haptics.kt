package com.music.bitchord.ui.haptics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.music.bitchord.platform.playHaptic

/**
 * The Android app's haptic vocabulary, played on iOS through the Taptic Engine
 * (UIImpact/UISelection feedback generators — see Haptics.ios.kt). Same enum,
 * same call sites: `rememberHaptics().play(Haptic.Select)`.
 */
enum class Haptic {
    /** The lightest single beat, for something that repeats while a finger is down. */
    Tick,

    /** A plain button press with no state behind it: More, Download, Menu. */
    Tap,

    /** A discrete choice landing: a tab, a filter pill, the end of a scrub. */
    Select,

    /** Switching something on. */
    ToggleOn,

    /** Switching it back off. */
    ToggleOff,

    /** Forward through the queue. */
    SkipNext,

    /** Backward through the queue. */
    SkipPrevious,

    /** Playback starting. */
    Resume,

    /** Playback stopping. */
    Pause,

    /** Something growing to fill the screen, e.g. the mini player opening. */
    Expand,
}

class Haptics internal constructor() {
    fun play(haptic: Haptic) = playHaptic(haptic)
}

/** `val haptics = rememberHaptics()`, then `haptics.play(Haptic.Select)`. */
@Composable
fun rememberHaptics(): Haptics = remember { Haptics() }
