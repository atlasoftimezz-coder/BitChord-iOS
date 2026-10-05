package com.music.bitchord.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Renders android.widget.Toast messages from ported code as a short-lived bubble. */
@Composable
fun ToastHost(modifier: Modifier = Modifier) {
    var current by remember { mutableStateOf<Toast.Message?>(null) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        Toast.messages.collect { message ->
            current = message
            visible = true
            delay(message.durationMs)
            visible = false
        }
    }
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Text(
            current?.text.orEmpty(),
            color = Color.White,
            modifier = Modifier
                .background(Color(0xE6303036), RoundedCornerShape(20.dp))
                .padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}
