package com.music.bitchord.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.auth.AccountController
import com.music.bitchord.auth.WebSessionMode
import com.music.bitchord.auth.YtMusicLoginScreen

/**
 * The Android sign-in sheet: Google's real login page, then "Use this
 * profile" once YouTube Music has loaded — so an account with brand channels
 * can pick one in YouTube Music's own avatar menu before anything is saved.
 */
@Composable
fun SignInScreen(accounts: AccountController, onDone: (signedIn: Boolean) -> Unit) {
    var captureRequest by remember { mutableIntStateOf(0) }
    var captureFailed by remember { mutableStateOf(false) }
    var pageReady by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { onDone(false) }) {
                    Icon(Icons.Filled.Close, contentDescription = R.string.close)
                }
                Column(Modifier.weight(1f)) {
                    Text(R.string.sign_in_youtube_music, style = MaterialTheme.typography.titleMedium)
                    if (pageReady) {
                        Text(
                            if (captureFailed) R.string.profile_unavailable else R.string.switch_profile_hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                }
                if (pageReady) {
                    TextButton(
                        onClick = {
                            captureFailed = false
                            confirming = true
                            captureRequest++
                        },
                        enabled = !confirming,
                    ) {
                        Text(if (confirming) R.string.checking else R.string.use_this_profile)
                    }
                }
            }
            YtMusicLoginScreen(
                mode = WebSessionMode.SIGN_IN,
                captureRequest = captureRequest,
                onPageReady = { pageReady = it },
                onCaptureUnavailable = {
                    confirming = false
                    captureFailed = true
                },
                onCaptured = { session ->
                    accounts.onWebSession(session, WebSessionMode.SIGN_IN) { accepted ->
                        confirming = false
                        if (accepted) onDone(true) else captureFailed = true
                    }
                },
            )
        }
    }
}
