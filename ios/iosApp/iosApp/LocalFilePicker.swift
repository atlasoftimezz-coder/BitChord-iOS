import AVFoundation
import Foundation
import Shared
import UIKit
import UniformTypeIdentifiers

/// Swift implementation of the Kotlin `LocalFilePicker`: the Files-app import
/// behind "On this device".
///
/// - Presents `UIDocumentPickerViewController` for audio, several at once.
/// - Copies each pick into the app (the picker hands over a temporary copy),
///   so the song keeps playing after the original is moved or offline.
/// - Reads title / artist / album / duration / cover / lyrics with AVFoundation,
///   which understands every format the player can play (MP3, AAC/ALAC M4A,
///   FLAC, WAV, AIFF…).
final class LocalFilePickerImpl: NSObject, LocalFilePicker, UIDocumentPickerDelegate {
    private var directory = ""
    private var listener: LocalImportListener?

    func pickAudioFiles(directory: String, listener: LocalImportListener) {
        DispatchQueue.main.async {
            self.directory = directory
            self.listener = listener
            guard let top = Self.topViewController() else {
                listener.onImportFailed(message: "Could not open Files")
                self.listener = nil
                return
            }
            let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.audio], asCopy: true)
            picker.allowsMultipleSelection = true
            picker.delegate = self
            top.present(picker, animated: true)
        }
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        let directory = self.directory
        let listener = self.listener
        self.listener = nil
        DispatchQueue.global(qos: .userInitiated).async {
            let imported = urls.compactMap { Self.importFile($0, into: directory) }
            DispatchQueue.main.async {
                if imported.isEmpty && !urls.isEmpty {
                    listener?.onImportFailed(message: "Those files could not be played on iPhone")
                } else {
                    listener?.onImported(files: imported)
                }
            }
        }
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        listener?.onImported(files: [])
        listener = nil
    }

    // MARK: - Import

    private static func importFile(_ url: URL, into directory: String) -> ImportedAudio? {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }

        let fileManager = FileManager.default
        let folder = URL(fileURLWithPath: directory, isDirectory: true)
        try? fileManager.createDirectory(at: folder, withIntermediateDirectories: true)
        let ext = url.pathExtension.isEmpty ? "audio" : url.pathExtension.lowercased()
        let stem = UUID().uuidString
        let destination = folder.appendingPathComponent("\(stem).\(ext)")
        do {
            try fileManager.copyItem(at: url, to: destination)
        } catch {
            NSLog("BitChord import: copy failed")
            return nil
        }

        let asset = AVURLAsset(url: destination)
        guard asset.isPlayable else {
            try? fileManager.removeItem(at: destination)
            return nil
        }
        let common = asset.commonMetadata
        func text(_ identifier: AVMetadataIdentifier) -> String? {
            AVMetadataItem.metadataItems(from: common, filteredByIdentifier: identifier).first?.stringValue
        }

        var artworkPath: String?
        if let data = AVMetadataItem.metadataItems(from: common, filteredByIdentifier: .commonIdentifierArtwork)
            .first?.dataValue, UIImage(data: data) != nil {
            let cover = folder.appendingPathComponent("\(stem).jpg")
            if (try? data.write(to: cover)) != nil { artworkPath = cover.path }
        }

        let seconds = CMTimeGetSeconds(asset.duration)
        return ImportedAudio(
            path: destination.path,
            originalName: url.lastPathComponent,
            title: text(.commonIdentifierTitle),
            artist: text(.commonIdentifierArtist),
            album: text(.commonIdentifierAlbumName),
            durationMs: seconds.isFinite ? Int64(seconds * 1000) : 0,
            artworkPath: artworkPath,
            lyrics: asset.lyrics
        )
    }

    private static func topViewController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        let window = scene?.windows.first(where: { $0.isKeyWindow }) ?? scene?.windows.first
        var top = window?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}
