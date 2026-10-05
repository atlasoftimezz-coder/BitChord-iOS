import SwiftUI
import UIKit
import Shared

@main
struct iOSApp: App {
    /// One engine for the app's lifetime; Kotlin's PlayerController drives it.
    static let audioEngine = AVAudioEngineImpl()

    var body: some Scene {
        WindowGroup {
            ComposeView()
                .ignoresSafeArea()
        }
    }
}

/// Wraps the Kotlin `MainViewController(engine)` so SwiftUI can host the Compose UI.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: UIViewControllerRepresentableContext<ComposeView>) -> UIViewController {
        MainViewControllerKt.MainViewController(engine: iOSApp.audioEngine)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: UIViewControllerRepresentableContext<ComposeView>) {}
}
