package com.music.bitchord.platform

import com.music.bitchord.ui.haptics.Haptic
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UISelectionFeedbackGenerator

/*
 * The Android patterns are composed from vibrator primitives; iOS has a fixed
 * set of Taptic styles instead, so each Haptic maps to the closest one —
 * light for repeats and taps, rigid/heavy for the "landing" beats.
 */
private val selection by lazy { UISelectionFeedbackGenerator() }
private val light by lazy { UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleLight) }
private val medium by lazy { UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium) }
private val rigid by lazy { UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleRigid) }
private val soft by lazy { UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleSoft) }

actual fun playHaptic(haptic: Haptic) {
    when (haptic) {
        Haptic.Tick -> selection.selectionChanged()
        Haptic.Tap -> light.impactOccurred()
        Haptic.Select -> selection.selectionChanged()
        Haptic.ToggleOn -> rigid.impactOccurred()
        Haptic.ToggleOff -> soft.impactOccurred()
        Haptic.SkipNext, Haptic.SkipPrevious -> medium.impactOccurred()
        Haptic.Resume -> medium.impactOccurred()
        Haptic.Pause -> soft.impactOccurred()
        Haptic.Expand -> light.impactOccurred()
    }
}
