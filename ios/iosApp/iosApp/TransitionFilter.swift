import AVFoundation
import Foundation
import MediaToolbox

/// The low-pass and high-pass a transition rides on one player item — a port
/// of the Android `TransitionFilterProcessor`: two cascaded state-variable
/// Butterworth sections per filter (24 dB/octave), cutoffs gliding
/// geometrically towards their targets every 64 frames so a 30 ms control tick
/// never steps audibly.
///
/// Attached through an `MTAudioProcessingTap` on the item's audio mix. While
/// both filters are parked open the tap only passes the audio through.
final class TransitionFilter {
    static let openHz: Float = 20_000
    static let offHz: Float = 20

    /// Written on the main thread, read on the audio thread; a torn read only
    /// moves one sub-block's glide target, which the glide then corrects.
    var targetLowPassHz: Float = TransitionFilter.openHz
    var targetHighPassHz: Float = TransitionFilter.offHz

    private var currentLowPassHz: Float = TransitionFilter.openHz
    private var currentHighPassHz: Float = TransitionFilter.offHz
    private var sampleRate: Float = 44_100
    private var channelCount = 2
    private var isFloat = false
    private var isInterleaved = false

    private static let stages = 2
    private static let butterworthQ: [Float] = [0.54120, 1.30656]
    private static let glideFrames = 64
    private static let glideRate: Float = 0.05
    private static let settledHz: Float = 1
    private static let minHz: Float = 10
    private static let maxCutoffFraction: Float = 0.45
    private static let maxChannels = 8

    private var lowA1 = [Float](repeating: 0, count: stages)
    private var lowA2 = [Float](repeating: 0, count: stages)
    private var lowA3 = [Float](repeating: 0, count: stages)
    private var highA1 = [Float](repeating: 0, count: stages)
    private var highA2 = [Float](repeating: 0, count: stages)
    private var highA3 = [Float](repeating: 0, count: stages)
    private var highK = [Float](repeating: 0, count: stages)
    private var lowState = [Float](repeating: 0, count: maxChannels * stages * 2)
    private var highState = [Float](repeating: 0, count: maxChannels * stages * 2)

    func open() {
        targetLowPassHz = TransitionFilter.openHz
        targetHighPassHz = TransitionFilter.offHz
    }

    func prepare(format: AudioStreamBasicDescription) {
        sampleRate = Float(format.mSampleRate)
        channelCount = min(Int(format.mChannelsPerFrame), TransitionFilter.maxChannels)
        isFloat = (format.mFormatFlags & kAudioFormatFlagIsFloat) != 0 && format.mBitsPerChannel == 32
        isInterleaved = (format.mFormatFlags & kAudioFormatFlagIsNonInterleaved) == 0
        for index in lowState.indices { lowState[index] = 0 }
        for index in highState.indices { highState[index] = 0 }
    }

    func process(_ buffers: UnsafeMutableAudioBufferListPointer, frames: Int) {
        guard isFloat, frames > 0, sampleRate > 0 else { return }
        let targetLow = targetLowPassHz
        let targetHigh = targetHighPassHz
        // Parked open and already there: leave the audio untouched.
        if targetLow >= TransitionFilter.openHz - TransitionFilter.settledHz,
           targetHigh <= TransitionFilter.offHz + TransitionFilter.settledHz,
           currentLowPassHz >= TransitionFilter.openHz - TransitionFilter.settledHz,
           currentHighPassHz <= TransitionFilter.offHz + TransitionFilter.settledHz {
            return
        }

        var done = 0
        while done < frames {
            let block = min(frames - done, TransitionFilter.glideFrames)
            currentLowPassHz = glide(currentLowPassHz, targetLow)
            currentHighPassHz = glide(currentHighPassHz, targetHigh)
            let lowOn = currentLowPassHz < TransitionFilter.openHz - TransitionFilter.settledHz
            let highOn = currentHighPassHz > TransitionFilter.offHz + TransitionFilter.settledHz
            if lowOn { updateLowCoefficients() }
            if highOn { updateHighCoefficients() }

            if isInterleaved {
                guard let data = buffers.first?.mData?.assumingMemoryBound(to: Float.self) else { return }
                let channels = max(1, Int(buffers.first?.mNumberChannels ?? 1))
                for frame in done..<(done + block) {
                    for channel in 0..<min(channels, channelCount) {
                        let index = frame * channels + channel
                        var sample = data[index]
                        if lowOn { sample = lowPass(channel, sample) }
                        if highOn { sample = highPass(channel, sample) }
                        data[index] = sample
                    }
                }
            } else {
                for (channel, buffer) in buffers.enumerated() where channel < channelCount {
                    guard let data = buffer.mData?.assumingMemoryBound(to: Float.self) else { continue }
                    for frame in done..<(done + block) {
                        var sample = data[frame]
                        if lowOn { sample = lowPass(channel, sample) }
                        if highOn { sample = highPass(channel, sample) }
                        data[frame] = sample
                    }
                }
            }
            done += block
        }
    }

    // MARK: - Filter (Android TransitionFilterProcessor, unchanged maths)

    private func glide(_ current: Float, _ target: Float) -> Float {
        let from = log(max(current, TransitionFilter.minHz))
        let to = log(max(target, TransitionFilter.minHz))
        return exp(from + (to - from) * TransitionFilter.glideRate)
    }

    private func usableCutoff(_ hz: Float) -> Float {
        min(max(hz, TransitionFilter.minHz), sampleRate * TransitionFilter.maxCutoffFraction)
    }

    private func updateLowCoefficients() {
        let g = tan(Float.pi * usableCutoff(currentLowPassHz) / sampleRate)
        for stage in 0..<TransitionFilter.stages {
            let k = 1 / TransitionFilter.butterworthQ[stage]
            let a1 = 1 / (1 + g * (g + k))
            lowA1[stage] = a1
            lowA2[stage] = g * a1
            lowA3[stage] = g * (g * a1)
        }
    }

    private func updateHighCoefficients() {
        let g = tan(Float.pi * usableCutoff(currentHighPassHz) / sampleRate)
        for stage in 0..<TransitionFilter.stages {
            let k = 1 / TransitionFilter.butterworthQ[stage]
            let a1 = 1 / (1 + g * (g + k))
            highA1[stage] = a1
            highA2[stage] = g * a1
            highA3[stage] = g * (g * a1)
            highK[stage] = k
        }
    }

    private func lowPass(_ channel: Int, _ input: Float) -> Float {
        var value = input
        for stage in 0..<TransitionFilter.stages {
            let i = (channel * TransitionFilter.stages + stage) * 2
            let ic1 = lowState[i]
            let ic2 = lowState[i + 1]
            let v3 = value - ic2
            let v1 = lowA1[stage] * ic1 + lowA2[stage] * v3
            let v2 = ic2 + lowA2[stage] * ic1 + lowA3[stage] * v3
            lowState[i] = 2 * v1 - ic1
            lowState[i + 1] = 2 * v2 - ic2
            value = v2
        }
        return value
    }

    private func highPass(_ channel: Int, _ input: Float) -> Float {
        var value = input
        for stage in 0..<TransitionFilter.stages {
            let i = (channel * TransitionFilter.stages + stage) * 2
            let ic1 = highState[i]
            let ic2 = highState[i + 1]
            let v3 = value - ic2
            let v1 = highA1[stage] * ic1 + highA2[stage] * v3
            let v2 = ic2 + highA2[stage] * ic1 + highA3[stage] * v3
            highState[i] = 2 * v1 - ic1
            highState[i + 1] = 2 * v2 - ic2
            value -= highK[stage] * v1 + v2
        }
        return value
    }

    // MARK: - Attaching

    /// Puts this filter on [item]'s first audio track. Best effort: when the
    /// tap cannot be made, transitions still run, with gains only.
    func attach(to item: AVPlayerItem) {
        guard let track = item.asset.tracks(withMediaType: .audio).first else { return }
        var callbacks = MTAudioProcessingTapCallbacks(
            version: kMTAudioProcessingTapCallbacksVersion_0,
            clientInfo: UnsafeMutableRawPointer(Unmanaged.passRetained(self).toOpaque()),
            init: tapInit,
            finalize: tapFinalize,
            prepare: tapPrepare,
            unprepare: nil,
            process: tapProcess
        )
        var tap: Unmanaged<MTAudioProcessingTap>?
        let status = MTAudioProcessingTapCreate(
            kCFAllocatorDefault, &callbacks, kMTAudioProcessingTapCreationFlag_PostEffects, &tap
        )
        guard status == noErr, let tap else {
            Unmanaged.passUnretained(self).release()
            return
        }
        let parameters = AVMutableAudioMixInputParameters(track: track)
        parameters.audioTapProcessor = tap.takeRetainedValue()
        let mix = AVMutableAudioMix()
        mix.inputParameters = [parameters]
        item.audioMix = mix
    }
}

private let tapInit: MTAudioProcessingTapInitCallback = { _, clientInfo, storageOut in
    storageOut.pointee = clientInfo
}

private let tapFinalize: MTAudioProcessingTapFinalizeCallback = { tap in
    Unmanaged<TransitionFilter>.fromOpaque(MTAudioProcessingTapGetStorage(tap)).release()
}

private let tapPrepare: MTAudioProcessingTapPrepareCallback = { tap, _, format in
    Unmanaged<TransitionFilter>.fromOpaque(MTAudioProcessingTapGetStorage(tap))
        .takeUnretainedValue()
        .prepare(format: format.pointee)
}

private let tapProcess: MTAudioProcessingTapProcessCallback = { tap, frames, _, bufferList, framesOut, flagsOut in
    let status = MTAudioProcessingTapGetSourceAudio(tap, frames, bufferList, flagsOut, nil, framesOut)
    guard status == noErr else { return }
    Unmanaged<TransitionFilter>.fromOpaque(MTAudioProcessingTapGetStorage(tap))
        .takeUnretainedValue()
        .process(UnsafeMutableAudioBufferListPointer(bufferList), frames: Int(framesOut.pointee))
}
