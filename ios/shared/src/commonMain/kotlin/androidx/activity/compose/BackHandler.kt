package androidx.activity.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import com.music.bitchord.ui.BackDispatcher

/**
 * androidx.activity BackHandler for ported code. iOS has no back button; the
 * app's own close/back gestures call BackDispatcher.back(), which runs the most
 * recently registered enabled handler — the same LIFO order Android uses.
 */
@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val current = rememberUpdatedState(onBack)
    val enabledState = rememberUpdatedState(enabled)
    DisposableEffect(Unit) {
        val entry = BackDispatcher.Entry({ enabledState.value }, { current.value() })
        BackDispatcher.register(entry)
        onDispose { BackDispatcher.unregister(entry) }
    }
}
