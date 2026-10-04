package com.music.bitchord.shared

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/** Entry point called from Swift: hosts [App] in a UIKit view controller. */
fun MainViewController(): UIViewController = ComposeUIViewController { App() }
