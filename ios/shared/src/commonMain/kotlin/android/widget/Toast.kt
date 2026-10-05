package android.widget

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * android.widget.Toast for ported code. `show()` posts the text to [messages],
 * which the app root renders as a short-lived bubble (see ToastHost).
 */
class Toast private constructor(private val text: String, private val duration: Int) {
    fun show() {
        _messages.tryEmit(Message(text, if (duration == LENGTH_LONG) 3_500L else 2_000L))
    }

    fun cancel() = Unit

    class Message(val text: String, val durationMs: Long)

    companion object {
        const val LENGTH_SHORT = 0
        const val LENGTH_LONG = 1

        private val _messages = MutableSharedFlow<Message>(extraBufferCapacity = 8)
        val messages: SharedFlow<Message> = _messages.asSharedFlow()

        fun makeText(context: Context?, text: CharSequence, duration: Int): Toast = Toast(text.toString(), duration)
    }
}
