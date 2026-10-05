package com.music.bitchord.auth

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * In-app Google sign-in for YouTube Music — the Android YtMusicLoginScreen's
 * contract on WKWebView: the real Google login page, nothing typed into app
 * code, and the session taken from the page's own `ytcfg` only when
 * [captureRequest] is raised.
 */
@Composable
expect fun YtMusicLoginScreen(
    mode: WebSessionMode,
    initialCookie: String? = null,
    onCaptured: (CapturedSession) -> Unit,
    modifier: Modifier = Modifier,
    captureRequest: Int = 0,
    onCaptureUnavailable: () -> Unit = {},
    onPageReady: (Boolean) -> Unit = {},
)
