import AVFoundation
import Foundation
import MediaPlayer
import Shared
import UIKit

/// AVFoundation implementation of the Kotlin `AudioEngine`.
///
/// - Playback runs on an `AVQueuePlayer` holding the current item and, once
///   Kotlin has resolved it, the next one — so the next track starts by itself,
///   gaplessly, even with the screen locked.
/// - Media bytes go through `ChunkedResourceLoader` (bounded ranges, minting
///   client's headers), one loader per item.
/// - Owns the iOS side of a music app: audio session, interruptions (calls,
///   Siri, alarms), headphone unplug, and the lock screen / Control Center
///   Now Playing card with its remote commands.
final class AVAudioEngineImpl: NSObject, AudioEngine {
    private let player = AVQueuePlayer()
    private weak var listener: AudioEngineListener?

    /// Loader per queued item; an item's loader must live as long as the item.
    private var loaders: [ObjectIdentifier: ChunkedResourceLoader] = [:]
    private var currentItem: AVPlayerItem?
    private var nextItem: AVPlayerItem?

    private var timeObserver: Any?
    private var statusObservation: NSKeyValueObservation?
    private var currentItemObservation: NSKeyValueObservation?
    private var rateObservation: NSKeyValueObservation?
    private var notificationTokens: [NSObjectProtocol] = []
    private var pendingSeekMs: Int64?

    /// Paused by an interruption (call, Siri) rather than by the user, so it may resume after.
    private var pausedByInterruption = false
    /// Last reported playing state; by the time an interruption is announced iOS has already paused.
    private var wasPlaying = false

    private var nowPlaying: [String: Any] = [:]
    private var artworkTask: URLSessionDataTask?

    override init() {
        super.init()
        configureSession()
        player.automaticallyWaitsToMinimizeStalling = true
        player.actionAtItemEnd = .advance

        timeObserver = player.addPeriodicTimeObserver(
            forInterval: CMTime(value: 1, timescale: 4),
            queue: .main
        ) { [weak self] _ in self?.reportProgress() }

        // The queue player moved on to the item queued with setNext.
        currentItemObservation = player.observe(\.currentItem, options: [.old, .new]) { [weak self] _, change in
            DispatchQueue.main.async { self?.currentItemChanged(from: change.oldValue ?? nil) }
        }
        rateObservation = player.observe(\.timeControlStatus, options: [.new]) { [weak self] _, _ in
            DispatchQueue.main.async {
                self?.reportProgress()
                self?.updateNowPlayingTiming()
            }
        }

        observeSystemEvents()
        configureRemoteCommands()
    }

    // MARK: - AudioEngine

    func setListener(listener: AudioEngineListener?) {
        self.listener = listener
    }

    func play(url: String, headers: [String: String], mimeType: String, contentLength: Int64, chunkBytes: Int64) {
        stop()
        guard let item = makeItem(url: url, headers: headers, mimeType: mimeType,
                                  contentLength: contentLength, chunkBytes: chunkBytes) else {
            listener?.onError(message: "Bad stream URL", httpStatus: 0)
            return
        }
        try? AVAudioSession.sharedInstance().setActive(true)
        becomeCurrent(item)
        player.insert(item, after: nil)
        player.play()
    }

    func setNext(url: String, headers: [String: String], mimeType: String, contentLength: Int64, chunkBytes: Int64) {
        clearNext()
        guard currentItem != nil,
              let item = makeItem(url: url, headers: headers, mimeType: mimeType,
                                  contentLength: contentLength, chunkBytes: chunkBytes) else { return }
        nextItem = item
        player.insert(item, after: currentItem)
    }

    func clearNext() {
        guard let item = nextItem else { return }
        nextItem = nil
        player.remove(item)
        loaders.removeValue(forKey: ObjectIdentifier(item))?.cancelAll()
    }

    func pause() {
        pausedByInterruption = false
        player.pause()
        reportProgress()
    }

    func resume() {
        try? AVAudioSession.sharedInstance().setActive(true)
        player.play()
        reportProgress()
    }

    func seekTo(positionMs: Int64) {
        guard let item = player.currentItem, item.status == .readyToPlay else {
            pendingSeekMs = positionMs
            return
        }
        let time = CMTime(value: positionMs, timescale: 1000)
        player.seek(to: time, toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
            self?.reportProgress()
            self?.updateNowPlayingTiming()
        }
    }

    func stop() {
        player.pause()
        player.removeAllItems()
        statusObservation = nil
        currentItem = nil
        nextItem = nil
        loaders.values.forEach { $0.cancelAll() }
        loaders.removeAll()
        pendingSeekMs = nil
        pausedByInterruption = false
    }

    func setNowPlaying(title: String, artist: String, album: String?, artworkUrl: String?) {
        artworkTask?.cancel()
        nowPlaying = [
            MPMediaItemPropertyTitle: title,
            MPMediaItemPropertyArtist: artist,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: 0.0,
            MPNowPlayingInfoPropertyPlaybackRate: 0.0,
        ]
        if let album { nowPlaying[MPMediaItemPropertyAlbumTitle] = album }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nowPlaying

        guard let artworkUrl, let url = URL(string: artworkUrl) else { return }
        let expectedTitle = title
        artworkTask = URLSession.shared.dataTask(with: url) { [weak self] data, _, _ in
            guard let data, let image = UIImage(data: data) else { return }
            DispatchQueue.main.async {
                guard let self, self.nowPlaying[MPMediaItemPropertyTitle] as? String == expectedTitle else { return }
                self.nowPlaying[MPMediaItemPropertyArtwork] =
                    MPMediaItemArtwork(boundsSize: image.size) { _ in image }
                MPNowPlayingInfoCenter.default().nowPlayingInfo = self.nowPlaying
            }
        }
        artworkTask?.resume()
    }

    func setQueueCapabilities(hasNext: Bool, hasPrevious: Bool) {
        let center = MPRemoteCommandCenter.shared()
        center.nextTrackCommand.isEnabled = hasNext
        center.previousTrackCommand.isEnabled = hasPrevious
    }

    // MARK: - Items

    private func makeItem(url: String, headers: [String: String], mimeType: String,
                          contentLength: Int64, chunkBytes: Int64) -> AVPlayerItem? {
        guard let source = URL(string: url) else { return nil }
        let loader = ChunkedResourceLoader(
            source: source,
            headers: headers,
            mimeType: mimeType,
            knownLength: contentLength > 0 ? contentLength : nil,
            chunkBytes: max(64 * 1024, chunkBytes)
        )
        let asset = AVURLAsset(url: loader.proxyURL)
        asset.resourceLoader.setDelegate(loader, queue: loader.queue)
        let item = AVPlayerItem(asset: asset)
        item.preferredForwardBufferDuration = 30
        loader.onHTTPError = { [weak self, weak item] status in
            DispatchQueue.main.async {
                // Only the playing item's refusal is actionable; a queued one is
                // re-resolved by Kotlin when it becomes current and fails.
                guard let self, let item, item === self.currentItem else { return }
                self.listener?.onError(message: "Stream refused (HTTP \(status))", httpStatus: Int32(status))
            }
        }
        loaders[ObjectIdentifier(item)] = loader
        return item
    }

    /// Point the status observation and the end/fail notifications at [item].
    private func becomeCurrent(_ item: AVPlayerItem) {
        currentItem = item
        statusObservation = item.observe(\.status, options: [.new]) { [weak self] item, _ in
            DispatchQueue.main.async {
                guard let self, item === self.currentItem else { return }
                switch item.status {
                case .readyToPlay:
                    if let ms = self.pendingSeekMs {
                        self.pendingSeekMs = nil
                        self.seekTo(positionMs: ms)
                    }
                    self.updateNowPlayingTiming()
                case .failed:
                    let refused = self.loaders[ObjectIdentifier(item)]?.reportedHTTPError ?? false
                    if !refused {
                        self.listener?.onError(
                            message: item.error?.localizedDescription ?? "Playback failed",
                            httpStatus: 0
                        )
                    }
                default:
                    break
                }
            }
        }
    }

    private func currentItemChanged(from old: AVPlayerItem?) {
        let now = player.currentItem
        if let old, old !== now {
            loaders.removeValue(forKey: ObjectIdentifier(old))?.cancelAll()
        }
        guard let now else {
            // Queue ran dry: the last item ended (or everything was removed by stop()).
            if old != nil && currentItem != nil {
                currentItem = nil
                listener?.onEnded()
            }
            return
        }
        if now === nextItem {
            nextItem = nil
            becomeCurrent(now)
            listener?.onAdvancedToNext()
        }
    }

    private func reportProgress() {
        guard let item = player.currentItem else { return }
        let position = player.currentTime().seconds
        let duration = item.duration.seconds
        let isPlaying = player.timeControlStatus == .playing
        let isBuffering = player.timeControlStatus == .waitingToPlayAtSpecifiedRate
        wasPlaying = isPlaying || isBuffering
        listener?.onProgress(
            positionMs: position.isFinite ? Int64(position * 1000) : 0,
            durationMs: duration.isFinite ? Int64(duration * 1000) : 0,
            isPlaying: isPlaying,
            isBuffering: isBuffering
        )
    }

    // MARK: - Now Playing timing

    /// The lock screen extrapolates the position from elapsed time + rate, so
    /// it only needs telling on play/pause/seek/track change, not every tick.
    private func updateNowPlayingTiming() {
        guard !nowPlaying.isEmpty else { return }
        let position = player.currentTime().seconds
        if let duration = player.currentItem?.duration.seconds, duration.isFinite {
            nowPlaying[MPMediaItemPropertyPlaybackDuration] = duration
        }
        nowPlaying[MPNowPlayingInfoPropertyElapsedPlaybackTime] = position.isFinite ? position : 0
        nowPlaying[MPNowPlayingInfoPropertyPlaybackRate] = player.timeControlStatus == .playing ? 1.0 : 0.0
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nowPlaying
    }

    // MARK: - Audio session & system events

    private func configureSession() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .default)
    }

    private func observeSystemEvents() {
        let center = NotificationCenter.default
        notificationTokens.append(center.addObserver(
            forName: AVAudioSession.interruptionNotification, object: nil, queue: .main
        ) { [weak self] note in self?.handleInterruption(note) })

        notificationTokens.append(center.addObserver(
            forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main
        ) { [weak self] note in self?.handleRouteChange(note) })

        // Media services reset (rare, but kills every player): rebuild the session.
        notificationTokens.append(center.addObserver(
            forName: AVAudioSession.mediaServicesWereResetNotification, object: nil, queue: .main
        ) { [weak self] _ in self?.configureSession() })

        notificationTokens.append(center.addObserver(
            forName: .AVPlayerItemFailedToPlayToEndTime, object: nil, queue: .main
        ) { [weak self] note in
            guard let self, let item = note.object as? AVPlayerItem, item === self.currentItem else { return }
            if self.loaders[ObjectIdentifier(item)]?.reportedHTTPError ?? false { return }
            let error = note.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error
            self.listener?.onError(message: error?.localizedDescription ?? "Playback stopped", httpStatus: 0)
        })
    }

    private func handleInterruption(_ note: Notification) {
        guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
        switch type {
        case .began:
            // iOS has already paused the player; remember it wasn't the user.
            pausedByInterruption = wasPlaying
            reportProgress()
        case .ended:
            let optionsRaw = note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
            let options = AVAudioSession.InterruptionOptions(rawValue: optionsRaw)
            if pausedByInterruption && options.contains(.shouldResume) {
                resume()
            }
            pausedByInterruption = false
        @unknown default:
            break
        }
    }

    private func handleRouteChange(_ note: Notification) {
        guard let raw = note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
              let reason = AVAudioSession.RouteChangeReason(rawValue: raw) else { return }
        // Headphones unplugged / Bluetooth disconnected: pause rather than blast the speaker.
        if reason == .oldDeviceUnavailable {
            pause()
        }
    }

    // MARK: - Remote commands (lock screen, Control Center, headphones)

    private func configureRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()

        center.playCommand.addTarget { [weak self] _ in
            guard let self, self.player.currentItem != nil else { return .noActionableNowPlayingItem }
            self.resume()
            return .success
        }
        center.pauseCommand.addTarget { [weak self] _ in
            self?.pause()
            return .success
        }
        center.togglePlayPauseCommand.addTarget { [weak self] _ in
            guard let self, self.player.currentItem != nil else { return .noActionableNowPlayingItem }
            if self.player.timeControlStatus == .paused { self.resume() } else { self.pause() }
            return .success
        }
        center.nextTrackCommand.addTarget { [weak self] _ in
            self?.listener?.onRemoteNext()
            return .success
        }
        center.previousTrackCommand.addTarget { [weak self] _ in
            self?.listener?.onRemotePrevious()
            return .success
        }
        center.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let self, let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            self.seekTo(positionMs: Int64(event.positionTime * 1000))
            return .success
        }
        center.skipForwardCommand.isEnabled = false
        center.skipBackwardCommand.isEnabled = false
        UIApplication.shared.beginReceivingRemoteControlEvents()
    }
}
