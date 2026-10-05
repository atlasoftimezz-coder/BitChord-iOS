package android.os

import com.music.bitchord.platform.elapsedMillis

/** android.os.SystemClock for ported code. */
object SystemClock {
    fun elapsedRealtime(): Long = elapsedMillis()
    fun uptimeMillis(): Long = elapsedMillis()
}

/**
 * android.os.Build for ported code. Feature checks in the Android sources are
 * of the form `SDK_INT >= VERSION_CODES.S` ("is this a modern platform?"); on
 * iOS every such feature (blur, haptics, ...) is available, so SDK_INT
 * reports a level above all of them.
 */
object Build {
    object VERSION {
        const val SDK_INT: Int = 99
        const val RELEASE: String = "iOS"
    }

    object VERSION_CODES {
        const val O = 26
        const val P = 28
        const val Q = 29
        const val R = 30
        const val S = 31
        const val S_V2 = 32
        const val TIRAMISU = 33
        const val UPSIDE_DOWN_CAKE = 34
        const val VANILLA_ICE_CREAM = 35
        const val BAKLAVA = 36
    }

    const val MANUFACTURER: String = "Apple"
    const val MODEL: String = "iPhone"
}
