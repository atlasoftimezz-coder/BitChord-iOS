import SwiftUI
import UIKit
import Shared

@main
struct iOSApp: App {
    /// One engine for the app's lifetime; Kotlin's PlayerController drives it.
    static let audioEngine = AVAudioEngineImpl()
    /// The Files-app import behind "On this device".
    static let filePicker = LocalFilePickerImpl()

    init() {
        Self.excludeOfflineFilesFromBackup()
    }

    /// Downloads and imported songs can run to gigabytes; keep them out of iCloud backups.
    private static func excludeOfflineFilesFromBackup() {
        guard let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else { return }
        for name in ["Downloads", "Imported"] {
            var url = base.appendingPathComponent(name, isDirectory: true)
            try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try? url.setResourceValues(values)
        }
    }

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
        MainViewControllerKt.MainViewController(engine: iOSApp.audioEngine, filePicker: iOSApp.filePicker)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: UIViewControllerRepresentableContext<ComposeView>) {}
}
