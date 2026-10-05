package com.music.bitchord.shared

import androidx.compose.ui.window.ComposeUIViewController
import com.music.bitchord.data.LocalFilePicker
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.platform.installCrashCatcher
import com.music.bitchord.playback.AudioEngine
import com.music.bitchord.playback.smart.NativeAnalysis
import com.music.bitchord.playback.smart.NativeAnalysisHolder
import platform.UIKit.UIViewController

/** Entry point called from Swift: hosts [App] with the Swift-side AVFoundation [engine] and Files [filePicker]. */
fun MainViewController(engine: AudioEngine, filePicker: LocalFilePicker, analysis: NativeAnalysis): UIViewController {
    installCrashCatcher()
    LocalMediaRepository.picker = filePicker
    NativeAnalysisHolder.bridge = analysis
    return ComposeUIViewController { App(engine) }
}
