package com.music.bitchord.shared

import androidx.compose.ui.window.ComposeUIViewController
import com.music.bitchord.playback.AudioEngine
import platform.UIKit.UIViewController

/** Entry point called from Swift: hosts [App] with the Swift-side AVFoundation [engine]. */
fun MainViewController(engine: AudioEngine): UIViewController = ComposeUIViewController { App(engine) }
