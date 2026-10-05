import AVFoundation
import Foundation
import Shared

/// AVFoundation implementation of the Kotlin `AudioEngine`.
///
/// googlevideo only serves some clients' URLs in bounded ranges (512 KB – 1 MB)
/// and expects every request to carry the minting client's headers. AVPlayer
/// would otherwise ask for the whole file in one go with its own User-Agent,
/// so media is routed through a custom URL scheme and fetched here by
/// `ChunkedResourceLoader`, one bounded range at a time.
final class AVAudioEngineImpl: NSObject, AudioEngine {
    private let player = AVPlayer()
    private var loader: ChunkedResourceLoader?
    private weak var listener: AudioEngineListener?
    private var timeObserver: Any?
    private var statusObservation: NSKeyValueObservation?
    private var endObserver: NSObjectProtocol?
    private var failObserver: NSObjectProtocol?
    private var pendingSeekMs: Int64?

    override init() {
        super.init()
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
        player.automaticallyWaitsToMinimizeStalling = true
        timeObserver = player.addPeriodicTimeObserver(
            forInterval: CMTime(value: 1, timescale: 4),
            queue: .main
        ) { [weak self] _ in self?.reportProgress() }
    }

    func setListener(listener: AudioEngineListener?) {
        self.listener = listener
    }

    func play(url: String, headers: [String: String], mimeType: String, contentLength: Int64, chunkBytes: Int64) {
        stop()
        guard let source = URL(string: url) else {
            listener?.onError(message: "Bad stream URL", httpStatus: 0)
            return
        }
        try? AVAudioSession.sharedInstance().setActive(true)

        let loader = ChunkedResourceLoader(
            source: source,
            headers: headers,
            mimeType: mimeType,
            knownLength: contentLength > 0 ? contentLength : nil,
            chunkBytes: max(64 * 1024, chunkBytes)
        )
        loader.onHTTPError = { [weak self] status in
            DispatchQueue.main.async {
                self?.listener?.onError(message: "Stream refused (HTTP \(status))", httpStatus: Int32(status))
            }
        }
        self.loader = loader

        let asset = AVURLAsset(url: loader.proxyURL)
        asset.resourceLoader.setDelegate(loader, queue: loader.queue)
        let item = AVPlayerItem(asset: asset)
        item.preferredForwardBufferDuration = 30

        statusObservation = item.observe(\.status, options: [.new]) { [weak self] item, _ in
            DispatchQueue.main.async {
                guard let self else { return }
                switch item.status {
                case .readyToPlay:
                    if let ms = self.pendingSeekMs {
                        self.pendingSeekMs = nil
                        self.seekTo(positionMs: ms)
                    }
                case .failed:
                    // An HTTP refusal is reported separately with its status code.
                    if !(self.loader?.reportedHTTPError ?? false) {
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
        endObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main
        ) { [weak self] _ in self?.listener?.onEnded() }
        failObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemFailedToPlayToEndTime, object: item, queue: .main
        ) { [weak self] note in
            guard let self, !(self.loader?.reportedHTTPError ?? false) else { return }
            let error = note.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error
            self.listener?.onError(message: error?.localizedDescription ?? "Playback stopped", httpStatus: 0)
        }

        player.replaceCurrentItem(with: item)
        player.play()
    }

    func pause() {
        player.pause()
        reportProgress()
    }

    func resume() {
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
        }
    }

    func stop() {
        player.pause()
        player.replaceCurrentItem(with: nil)
        statusObservation = nil
        if let o = endObserver { NotificationCenter.default.removeObserver(o) }
        if let o = failObserver { NotificationCenter.default.removeObserver(o) }
        endObserver = nil
        failObserver = nil
        loader?.cancelAll()
        loader = nil
        pendingSeekMs = nil
    }

    private func reportProgress() {
        guard let item = player.currentItem else { return }
        let position = player.currentTime().seconds
        let duration = item.duration.seconds
        let isPlaying = player.timeControlStatus == .playing
        let isBuffering = player.timeControlStatus == .waitingToPlayAtSpecifiedRate
            || (item.status == .unknown && player.rate > 0)
        listener?.onProgress(
            positionMs: position.isFinite ? Int64(position * 1000) : 0,
            durationMs: duration.isFinite ? Int64(duration * 1000) : 0,
            isPlaying: isPlaying,
            isBuffering: isBuffering
        )
    }
}

/// Serves AVPlayer's byte-range requests from googlevideo in bounded chunks,
/// sending the minting client's headers on each.
final class ChunkedResourceLoader: NSObject, AVAssetResourceLoaderDelegate {
    static let scheme = "bitchord-stream"

    let queue = DispatchQueue(label: "bitchord.resource-loader")
    let proxyURL: URL
    var onHTTPError: ((Int) -> Void)?
    private(set) var reportedHTTPError = false

    private let source: URL
    private let headers: [String: String]
    private let mimeType: String
    private var knownLength: Int64?
    private let chunkBytes: Int64
    private let session: URLSession
    private var tasks: [ObjectIdentifier: URLSessionDataTask] = [:]
    private var cancelled: Set<ObjectIdentifier> = []
    /// Set once the session is invalidated; creating a task after that throws.
    private var invalidated = false

    init(source: URL, headers: [String: String], mimeType: String, knownLength: Int64?, chunkBytes: Int64) {
        self.source = source
        self.headers = headers
        self.mimeType = mimeType
        self.knownLength = knownLength
        self.chunkBytes = chunkBytes
        var components = URLComponents(url: source, resolvingAgainstBaseURL: false)!
        components.scheme = ChunkedResourceLoader.scheme
        self.proxyURL = components.url!
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 20
        config.httpMaximumConnectionsPerHost = 2
        self.session = URLSession(configuration: config)
        super.init()
    }

    func cancelAll() {
        queue.async {
            self.invalidated = true
            self.tasks.values.forEach { $0.cancel() }
            self.tasks.removeAll()
            self.session.invalidateAndCancel()
        }
    }

    func resourceLoader(
        _ resourceLoader: AVAssetResourceLoader,
        shouldWaitForLoadingOfRequestedResource loadingRequest: AVAssetResourceLoadingRequest
    ) -> Bool {
        let key = ObjectIdentifier(loadingRequest)
        cancelled.remove(key)

        if let info = loadingRequest.contentInformationRequest {
            info.contentType = uti(for: mimeType)
            info.isByteRangeAccessSupported = true
            if let length = knownLength {
                info.contentLength = length
            }
        }

        guard let dataRequest = loadingRequest.dataRequest else {
            if knownLength != nil { loadingRequest.finishLoading() } else { fetch(loadingRequest, from: 0, end: 1) }
            return true
        }

        let start = dataRequest.requestedOffset
        var end: Int64
        if dataRequest.requestsAllDataToEndOfResource {
            end = (knownLength ?? Int64.max) - 1
        } else {
            end = start + Int64(dataRequest.requestedLength) - 1
        }
        if let length = knownLength { end = min(end, length - 1) }
        fetch(loadingRequest, from: start, end: end)
        return true
    }

    func resourceLoader(
        _ resourceLoader: AVAssetResourceLoader,
        didCancel loadingRequest: AVAssetResourceLoadingRequest
    ) {
        let key = ObjectIdentifier(loadingRequest)
        cancelled.insert(key)
        tasks.removeValue(forKey: key)?.cancel()
    }

    /// Fetches [from, end] one chunk at a time, handing each to AVPlayer as it lands.
    private func fetch(_ loadingRequest: AVAssetResourceLoadingRequest, from start: Int64, end: Int64) {
        let key = ObjectIdentifier(loadingRequest)
        if invalidated || cancelled.contains(key) || loadingRequest.isCancelled { return }
        if start > end {
            loadingRequest.finishLoading()
            tasks.removeValue(forKey: key)
            return
        }
        let chunkEnd = min(end, start + chunkBytes - 1)

        var request = URLRequest(url: source)
        request.setValue("bytes=\(start)-\(chunkEnd)", forHTTPHeaderField: "Range")
        for (name, value) in headers { request.setValue(value, forHTTPHeaderField: name) }

        let task = session.dataTask(with: request) { [weak self] data, response, error in
            guard let self else { return }
            self.queue.async {
                if self.cancelled.contains(key) || loadingRequest.isCancelled { return }
                if let error {
                    if (error as NSError).code == NSURLErrorCancelled { return }
                    loadingRequest.finishLoading(with: error)
                    self.tasks.removeValue(forKey: key)
                    return
                }
                let http = response as? HTTPURLResponse
                let status = http?.statusCode ?? 0
                guard (200...299).contains(status), let data else {
                    if [403, 404, 410].contains(status), !self.reportedHTTPError {
                        self.reportedHTTPError = true
                        self.onHTTPError?(status)
                    }
                    loadingRequest.finishLoading(with: NSError(
                        domain: "BitChord", code: status,
                        userInfo: [NSLocalizedDescriptionKey: "HTTP \(status)"]
                    ))
                    self.tasks.removeValue(forKey: key)
                    return
                }
                if self.knownLength == nil, let total = Self.totalLength(http) {
                    self.knownLength = total
                    loadingRequest.contentInformationRequest?.contentLength = total
                }
                if loadingRequest.contentInformationRequest != nil && loadingRequest.dataRequest == nil {
                    loadingRequest.finishLoading()
                    self.tasks.removeValue(forKey: key)
                    return
                }
                loadingRequest.dataRequest?.respond(with: data)
                let next = start + Int64(data.count)
                let finalEnd = min(end, (self.knownLength ?? Int64.max) - 1)
                if data.isEmpty || next > finalEnd {
                    loadingRequest.finishLoading()
                    self.tasks.removeValue(forKey: key)
                } else {
                    self.fetch(loadingRequest, from: next, end: finalEnd)
                }
            }
        }
        tasks[key] = task
        task.resume()
    }

    /// "bytes 0-1023/4567890" → 4567890
    private static func totalLength(_ response: HTTPURLResponse?) -> Int64? {
        guard let range = response?.value(forHTTPHeaderField: "Content-Range"),
              let slash = range.lastIndex(of: "/") else { return nil }
        return Int64(range[range.index(after: slash)...])
    }

    private func uti(for mime: String) -> String {
        let base = mime.split(separator: ";").first.map(String.init)?.trimmingCharacters(in: .whitespaces) ?? ""
        switch base {
        case "audio/mp4", "audio/m4a", "audio/x-m4a": return AVFileType.m4a.rawValue
        case "audio/mpeg": return AVFileType.mp3.rawValue
        case "audio/aac": return "public.aac-audio"
        default: return AVFileType.m4a.rawValue
        }
    }
}
