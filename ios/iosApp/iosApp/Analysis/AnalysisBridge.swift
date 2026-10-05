import AVFoundation
import Foundation
import OnnxRuntimeBindings
import Shared

/// Swift implementation of the Kotlin `NativeAnalysis`: decodes audio files
/// with AVAudioFile, runs the shared C++ analyzer (Analysis/BitChordAnalysis.h)
/// and the two Automix models with ONNX Runtime — the work Android does with
/// MediaCodec, JNI and onnxruntime-android. Large buffers (decoded PCM,
/// spectrograms, model tensors) never leave this file; only the JSON, two
/// logit rows or one curve cross back into Kotlin.
///
/// Every method blocks; Kotlin calls them from a background dispatcher.
final class AnalysisBridgeImpl: NSObject, NativeAnalysis {
    private let lock = NSLock()
    private var env: ORTEnv?
    private var beatSession: (session: ORTSession, threads: Int32)?
    private var vocalSession: (session: ORTSession, threads: Int32)?

    private static let featuresRate = 11_025.0
    private static let beatRate = 22_050.0
    private static let vocalRate = 44_100.0
    private static let chunkFrames = 1_500
    private static let borderFrames = 6
    private static let vocalFixedFrames = 960
    private static let vocalFft = 4_096.0
    private static let vocalLowHz = 200.0
    private static let vocalHighHz = 4_000.0

    // MARK: - NativeAnalysis

    func analyzeFeatures(path: String, durationSeconds: Double) -> String? {
        guard let decoded = Self.decode(path: path, start: 0, end: nil, stereo: false) else { return nil }
        let decodedSeconds = Double(decoded.left.count) / decoded.rate
        let duration = durationSeconds > 0 ? durationSeconds : decodedSeconds
        // Android refuses a decode that stopped well short: it would read as trailing silence.
        if decodedSeconds < duration * 0.95 {
            NSLog("BitChord analysis: decoded too little of the track")
            return nil
        }
        guard let mono = Self.resample(decoded.left, from: decoded.rate, to: Self.featuresRate) else { return nil }
        let json = mono.withUnsafeBufferPointer { buffer in
            bc_analyze_features(buffer.baseAddress, buffer.count, Self.featuresRate, duration)
        }
        guard let json else { return nil }
        defer { bc_free(json) }
        return String(cString: json)
    }

    func beatLogits(path: String, startSeconds: Double, endSeconds: Double, threads: Int32) -> BeatLogits? {
        guard let decoded = Self.decode(path: path, start: startSeconds, end: endSeconds, stereo: false),
              let mono = Self.resample(decoded.left, from: decoded.rate, to: Self.beatRate) else { return nil }
        var frames = 0
        let mels = bc_beat_mels()
        let raw = mono.withUnsafeBufferPointer { buffer in
            bc_beat_spectrogram(buffer.baseAddress, buffer.count, Self.beatRate, &frames)
        }
        guard let raw, frames > 0 else {
            bc_free(raw)
            return nil
        }
        let spectrogram = Array(UnsafeBufferPointer(start: raw, count: frames * mels))
        bc_free(raw)
        guard let session = session(beat: true, threads: threads) else { return nil }

        var beat = [Float](repeating: 0, count: frames)
        var downbeat = [Float](repeating: 0, count: frames)
        do {
            let inputName = try session.inputNames().first ?? "input"
            let outputNames = try session.outputNames()
            guard outputNames.count >= 2 else { return nil }
            let stride = Self.chunkFrames - 2 * Self.borderFrames
            var start = 0
            while start < frames {
                let length = min(Self.chunkFrames, frames - start)
                // A chunk shorter than the border padding carries no usable centre.
                if length <= 2 * Self.borderFrames && start > 0 { break }
                let chunk = Array(spectrogram[(start * mels)..<((start + length) * mels)])
                let data = chunk.withUnsafeBufferPointer { NSMutableData(bytes: $0.baseAddress, length: $0.count * 4) }
                let tensor = try ORTValue(tensorData: data, elementType: .float,
                                          shape: [1, NSNumber(value: length), NSNumber(value: mels)])
                let outputs = try session.run(withInputs: [inputName: tensor],
                                              outputNames: Set(outputNames.prefix(2)), runOptions: nil)
                guard let beatOut = try outputs[outputNames[0]]?.tensorData(),
                      let downbeatOut = try outputs[outputNames[1]]?.tensorData() else { return nil }
                let beatValues = Self.floats(beatOut)
                let downbeatValues = Self.floats(downbeatOut)
                let keepFrom = start == 0 ? 0 : Self.borderFrames
                let keepTo = start + length >= frames ? length : length - Self.borderFrames
                for index in keepFrom..<keepTo {
                    let target = start + index
                    if target >= frames || index >= beatValues.count || index >= downbeatValues.count { break }
                    beat[target] = beatValues[index]
                    downbeat[target] = downbeatValues[index]
                }
                if start + length >= frames { break }
                start += stride
            }
        } catch {
            NSLog("BitChord analysis: beat inference failed")
            return nil
        }
        return BeatLogits(beat: Self.kotlin(beat), downbeat: Self.kotlin(downbeat), startSeconds: decoded.start)
    }

    func vocalCurve(path: String, startSeconds: Double, endSeconds: Double, threads: Int32) -> VocalCurve? {
        guard let decoded = Self.decode(path: path, start: startSeconds, end: endSeconds, stereo: true),
              let left = Self.resample(decoded.left, from: decoded.rate, to: Self.vocalRate),
              let right = Self.resample(decoded.right, from: decoded.rate, to: Self.vocalRate) else { return nil }
        let count = min(left.count, right.count)
        guard count > 0 else { return nil }
        var frames = 0
        let bins = bc_vocal_bins()
        let raw = left.withUnsafeBufferPointer { l in
            right.withUnsafeBufferPointer { r in
                bc_vocal_spectrogram(l.baseAddress, r.baseAddress, count, Self.vocalRate, &frames)
            }
        }
        guard let raw, frames > 0, frames <= Self.vocalFixedFrames else {
            bc_free(raw)
            return nil
        }
        // Re-stride [2][bins][frames] into the model's fixed [1, 2, bins, 960], zero-padded.
        let fixed = Self.vocalFixedFrames
        let total = 2 * bins * fixed
        let mix = NSMutableData(length: total * 4)!
        let mixPointer = mix.mutableBytes.assumingMemoryBound(to: Float.self)
        for row in 0..<(2 * bins) {
            (mixPointer + row * fixed).update(from: raw + row * frames, count: frames)
        }
        bc_free(raw)
        guard let session = session(beat: false, threads: threads) else { return nil }
        do {
            let inputName = try session.inputNames().first ?? "input"
            guard let outputName = try session.outputNames().first else { return nil }
            let tensor = try ORTValue(tensorData: mix, elementType: .float,
                                      shape: [1, 2, NSNumber(value: bins), NSNumber(value: fixed)])
            let outputs = try session.run(withInputs: [inputName: tensor], outputNames: [outputName], runOptions: nil)
            guard let target = try outputs[outputName]?.tensorData(), target.length >= total * 4 else { return nil }
            let targetPointer = target.mutableBytes.assumingMemoryBound(to: Float.self)
            let curve = Self.bandCurve(mix: mixPointer, target: targetPointer, bins: bins, frames: frames)
            return VocalCurve(values: Self.kotlin(curve), startSeconds: decoded.start)
        } catch {
            NSLog("BitChord analysis: vocal inference failed")
            return nil
        }
    }

    // MARK: - Models

    private func session(beat: Bool, threads: Int32) -> ORTSession? {
        lock.lock()
        defer { lock.unlock() }
        if beat, let cached = beatSession, cached.threads == threads { return cached.session }
        if !beat, let cached = vocalSession, cached.threads == threads { return cached.session }
        let name = beat ? "beat_this_int8" : "vocals_umxhq_int8"
        guard let path = Bundle.main.path(forResource: name, ofType: "onnx") else {
            NSLog("BitChord analysis: model missing from the bundle")
            return nil
        }
        do {
            let environment = try env ?? ORTEnv(loggingLevel: .warning)
            env = environment
            let options = try ORTSessionOptions()
            try options.setIntraOpNumThreads(threads)
            try options.setGraphOptimizationLevel(.all)
            let session = try ORTSession(env: environment, modelPath: path, sessionOptions: options)
            if beat { beatSession = (session, threads) } else { vocalSession = (session, threads) }
            return session
        } catch {
            NSLog("BitChord analysis: model failed to load")
            return nil
        }
    }

    /// Android VocalTracker.reduceToBandCurve: mask = target / mix, averaged over 200–4000 Hz and both channels.
    private static func bandCurve(mix: UnsafeMutablePointer<Float>, target: UnsafeMutablePointer<Float>,
                                  bins: Int, frames: Int) -> [Float] {
        let lowBin = max(0, Int(floor(vocalLowHz * vocalFft / vocalRate)))
        let highBin = min(bins - 1, Int(ceil(vocalHighHz * vocalFft / vocalRate)))
        guard highBin > lowBin, frames > 0 else { return [] }
        var curve = [Float](repeating: 0, count: frames)
        for frame in 0..<frames {
            var sum = 0.0
            var count = 0
            for channel in 0..<2 {
                for bin in lowBin...highBin {
                    let index = (channel * bins + bin) * vocalFixedFrames + frame
                    let mixValue = mix[index]
                    if mixValue <= 1e-6 { continue }
                    sum += Double(min(max(target[index] / mixValue, 0), 1))
                    count += 1
                }
            }
            curve[frame] = count > 0 ? Float(sum / Double(count)) : 0
        }
        return curve
    }

    // MARK: - Audio

    private struct Decoded {
        var left: [Float]
        var right: [Float]
        var rate: Double
        var start: Double
    }

    /// Decodes [start, end) of the file; mono is the channel average, stereo keeps both
    /// (a mono file is duplicated). AVAudioFile seeks sample-accurately, so `start` is exact.
    private static func decode(path: String, start: Double, end: Double?, stereo: Bool) -> Decoded? {
        guard let file = try? AVAudioFile(forReading: Foundation.URL(fileURLWithPath: path)) else { return nil }
        let format = file.processingFormat
        let rate = format.sampleRate
        guard rate > 0 else { return nil }
        let total = file.length
        let startFrame = AVAudioFramePosition(max(0, start) * rate)
        guard startFrame < total else { return nil }
        let endFrame = end.map { min(total, AVAudioFramePosition($0 * rate)) } ?? total
        let wanted = Int(endFrame - startFrame)
        guard wanted > 0 else { return nil }
        file.framePosition = startFrame

        let channels = Int(format.channelCount)
        let capacity: AVAudioFrameCount = 65_536
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: capacity) else { return nil }
        var left = [Float]()
        var right = [Float]()
        left.reserveCapacity(wanted)
        if stereo { right.reserveCapacity(wanted) }
        var remaining = wanted
        while remaining > 0 {
            let ask = AVAudioFrameCount(min(Int(capacity), remaining))
            do {
                try file.read(into: buffer, frameCount: ask)
            } catch {
                break
            }
            let count = Int(buffer.frameLength)
            if count == 0 { break }
            guard let data = buffer.floatChannelData else { return nil }
            let l = data[0]
            let r = channels > 1 ? data[1] : data[0]
            if stereo {
                left.append(contentsOf: UnsafeBufferPointer(start: l, count: count))
                right.append(contentsOf: UnsafeBufferPointer(start: r, count: count))
            } else {
                for index in 0..<count { left.append((l[index] + r[index]) * 0.5) }
            }
            remaining -= count
        }
        // Under a second of audio carries no usable grid.
        guard Double(left.count) >= rate else { return nil }
        return Decoded(left: left, right: right, rate: rate, start: Double(startFrame) / rate)
    }

    private static func resample(_ input: [Float], from: Double, to: Double) -> [Float]? {
        if input.isEmpty { return nil }
        if abs(from - to) <= 1 { return input }
        var count = 0
        let output = input.withUnsafeBufferPointer { buffer in
            bc_resample(buffer.baseAddress, buffer.count, from, to, &count)
        }
        guard let output, count > 0 else {
            bc_free(output)
            return nil
        }
        defer { bc_free(output) }
        return Array(UnsafeBufferPointer(start: output, count: count))
    }

    private static func floats(_ data: NSMutableData) -> [Float] {
        let count = data.length / 4
        let pointer = data.mutableBytes.assumingMemoryBound(to: Float.self)
        return Array(UnsafeBufferPointer(start: pointer, count: count))
    }

    private static func kotlin(_ values: [Float]) -> KotlinFloatArray {
        let array = KotlinFloatArray(size: Int32(values.count))
        for (index, value) in values.enumerated() {
            array.set(index: Int32(index), value: value)
        }
        return array
    }
}
