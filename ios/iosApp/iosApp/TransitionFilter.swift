import AVFoundation
import Foundation
import MediaToolbox

/// The equaliser's target curve, shared by every item's tap. Set from the main
/// thread; each tap copies it with a try-lock so the audio thread never waits.
final class EqualizerTuning {
    static let slots = 10

    var enabled = false
    var kinds = [Int](repeating: 0, count: slots)
    var frequenciesHz = [Float](repeating: 1_000, count: slots)
    var gainsDb = [Float](repeating: 0, count: slots)
    var qs = [Float](repeating: 0.707, count: slots)
    var preampDb: Float = 0
    var balance: Float = 0
    /// Bumped on every change so a tap knows to copy.
    var version = 0

    static let shared = EqualizerTuning()
    static let lock = NSLock()

    /// Skip silence on; read by the engine, not the taps.
    static var skipSilence = false

    /// Whether items need a tap at all outside transitions.
    static var wanted: Bool { shared.enabled || skipSilence }
}

/// The audio processing on one player item, through an `MTAudioProcessingTap`:
///
/// 1. the in-app equaliser — a port of Android's `EqualizerProcessor`
///    (state-variable bell and shelf sections per `EqLayout` slot, gains and
///    Qs gliding every 64 frames, preamp and balance);
/// 2. a silence meter for skip silence;
/// 3. the low-pass and high-pass a transition rides — a port of Android's
///    `TransitionFilterProcessor` (two cascaded Butterworth sections per
///    filter, 24 dB/octave, cutoffs gliding geometrically).
///
/// Each stage passes audio through untouched while it has nothing to do.
final class TransitionFilter {
    static let openHz: Float = 20_000
    static let offHz: Float = 20

    /// Written on the main thread, read on the audio thread; a torn read only
    /// moves one sub-block's glide target, which the glide then corrects.
    var targetLowPassHz: Float = TransitionFilter.openHz
    var targetHighPassHz: Float = TransitionFilter.offHz

    /// Seconds of continuous near-silence just rendered; read by the engine.
    private(set) var silentSeconds: Double = 0

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
    /// -60 dBFS: quieter than this counts as silence.
    private static let silenceFloor: Float = 0.001

    private var lowA1 = [Float](repeating: 0, count: stages)
    private var lowA2 = [Float](repeating: 0, count: stages)
    private var lowA3 = [Float](repeating: 0, count: stages)
    private var highA1 = [Float](repeating: 0, count: stages)
    private var highA2 = [Float](repeating: 0, count: stages)
    private var highA3 = [Float](repeating: 0, count: stages)
    private var highK = [Float](repeating: 0, count: stages)
    private var lowState = [Float](repeating: 0, count: maxChannels * stages * 2)
    private var highState = [Float](repeating: 0, count: maxChannels * stages * 2)

    // Equaliser (Android EqualizerProcessor)
    private static let eqSlots = EqualizerTuning.slots
    private static let eqGlideRate: Float = 0.08
    private static let eqSettledDb: Float = 0.01
    private static let eqSettledBalance: Float = 0.0005
    private static let eqMinQ: Float = 0.05
    private static let denormalFloor: Float = 1e-12
    private var eqVersion = -1
    private var eqEnabled = false
    private var eqKinds = [Int](repeating: 0, count: eqSlots)
    private var eqFrequencies = [Float](repeating: 1_000, count: eqSlots)
    private var eqTargetGain = [Float](repeating: 0, count: eqSlots)
    private var eqTargetQ = [Float](repeating: 0.707, count: eqSlots)
    private var eqTargetPreamp: Float = 0
    private var eqTargetBalance: Float = 0
    private var eqGain = [Float](repeating: 0, count: eqSlots)
    private var eqQ = [Float](repeating: 0.707, count: eqSlots)
    private var eqPreamp: Float = 0
    private var eqBalance: Float = 0
    private var eqA1 = [Float](repeating: 0, count: eqSlots)
    private var eqA2 = [Float](repeating: 0, count: eqSlots)
    private var eqA3 = [Float](repeating: 0, count: eqSlots)
    private var eqMixInput = [Float](repeating: 1, count: eqSlots)
    private var eqMixBand = [Float](repeating: 0, count: eqSlots)
    private var eqMixLow = [Float](repeating: 0, count: eqSlots)
    private var eqActive = [Int](repeating: 0, count: eqSlots)
    private var eqRunning = [Bool](repeating: false, count: eqSlots)
    private var eqState = [Float](repeating: 0, count: maxChannels * eqSlots * 2)
    private var channelGain = [Float](repeating: 1, count: maxChannels)

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
        for index in eqState.indices { eqState[index] = 0 }
        for index in eqRunning.indices { eqRunning[index] = false }
    }

    /// Calls [body] once per sample with (channel, frame, pointer), whatever the layout.
    @inline(__always)
    private func forEachSample(_ buffers: UnsafeMutableAudioBufferListPointer, from: Int, count: Int,
                               _ body: (Int, UnsafeMutablePointer<Float>) -> Void) {
        if isInterleaved {
            guard let data = buffers.first?.mData?.assumingMemoryBound(to: Float.self) else { return }
            let channels = max(1, Int(buffers.first?.mNumberChannels ?? 1))
            for frame in from..<(from + count) {
                for channel in 0..<min(channels, channelCount) {
                    body(channel, data + frame * channels + channel)
                }
            }
        } else {
            for frame in from..<(from + count) {
                for (channel, buffer) in buffers.enumerated() where channel < channelCount {
                    guard let data = buffer.mData?.assumingMemoryBound(to: Float.self) else { continue }
                    body(channel, data + frame)
                }
            }
        }
    }

    func process(_ buffers: UnsafeMutableAudioBufferListPointer, frames: Int) {
        guard isFloat, frames > 0, sampleRate > 0 else { return }
        applyEqualizer(buffers, frames: frames)
        measureSilence(buffers, frames: frames)
        applyTransitionFilters(buffers, frames: frames)
    }

    // MARK: - Silence

    private func measureSilence(_ buffers: UnsafeMutableAudioBufferListPointer, frames: Int) {
        var peak: Float = 0
        forEachSample(buffers, from: 0, count: frames) { _, sample in
            peak = max(peak, abs(sample.pointee))
        }
        if peak < TransitionFilter.silenceFloor {
            silentSeconds += Double(frames) / Double(sampleRate)
        } else {
            silentSeconds = 0
        }
    }

    // MARK: - Equaliser (Android EqualizerProcessor, unchanged maths)

    private func syncEqualizer() {
        guard EqualizerTuning.lock.try() else { return }
        let shared = EqualizerTuning.shared
        if shared.version != eqVersion {
            eqVersion = shared.version
            eqEnabled = shared.enabled
            eqKinds = shared.kinds
            eqFrequencies = shared.frequenciesHz
            if shared.enabled {
                eqTargetGain = shared.gainsDb
                eqTargetQ = shared.qs
                eqTargetPreamp = shared.preampDb
                eqTargetBalance = shared.balance
            } else {
                for slot in 0..<TransitionFilter.eqSlots {
                    eqTargetGain[slot] = 0
                    eqTargetQ[slot] = 0.707
                }
                eqTargetPreamp = 0
                eqTargetBalance = 0
            }
        }
        EqualizerTuning.lock.unlock()
    }

    private var equalizerIsFlatAndSettled: Bool {
        if abs(eqTargetBalance) >= TransitionFilter.eqSettledBalance || abs(eqTargetPreamp) >= TransitionFilter.eqSettledDb { return false }
        if abs(eqBalance - eqTargetBalance) >= TransitionFilter.eqSettledBalance || abs(eqPreamp - eqTargetPreamp) >= TransitionFilter.eqSettledDb { return false }
        for slot in 0..<TransitionFilter.eqSlots {
            if abs(eqTargetGain[slot]) >= TransitionFilter.eqSettledDb || abs(eqGain[slot] - eqTargetGain[slot]) >= TransitionFilter.eqSettledDb {
                return false
            }
        }
        return true
    }

    private func applyEqualizer(_ buffers: UnsafeMutableAudioBufferListPointer, frames: Int) {
        syncEqualizer()
        if equalizerIsFlatAndSettled { return }
        var done = 0
        while done < frames {
            let block = min(frames - done, TransitionFilter.glideFrames)
            for slot in 0..<TransitionFilter.eqSlots {
                eqGain[slot] += (eqTargetGain[slot] - eqGain[slot]) * TransitionFilter.eqGlideRate
                let from = log(max(eqQ[slot], TransitionFilter.eqMinQ))
                let to = log(max(eqTargetQ[slot], TransitionFilter.eqMinQ))
                eqQ[slot] = exp(from + (to - from) * TransitionFilter.eqGlideRate)
            }
            eqPreamp += (eqTargetPreamp - eqPreamp) * TransitionFilter.eqGlideRate
            eqBalance += (eqTargetBalance - eqBalance) * TransitionFilter.eqGlideRate

            var active = 0
            for slot in 0..<TransitionFilter.eqSlots {
                if abs(eqGain[slot]) >= TransitionFilter.eqSettledDb {
                    updateEqCoefficients(slot)
                    eqActive[active] = slot
                    active += 1
                    eqRunning[slot] = true
                } else if eqRunning[slot] {
                    for channel in 0..<TransitionFilter.maxChannels {
                        let i = (channel * TransitionFilter.eqSlots + slot) * 2
                        eqState[i] = 0
                        eqState[i + 1] = 0
                    }
                    eqRunning[slot] = false
                }
            }
            let preamp = pow(10, eqPreamp / 20)
            if channelCount == 2 {
                channelGain[0] = preamp * min(1, 1 - eqBalance)
                channelGain[1] = preamp * min(1, 1 + eqBalance)
            } else {
                for channel in 0..<channelCount { channelGain[channel] = preamp }
            }

            forEachSample(buffers, from: done, count: block) { channel, sample in
                var value = sample.pointee
                for index in 0..<active {
                    value = eqSection(eqActive[index], channel, value)
                }
                sample.pointee = value * channelGain[channel]
            }
            for index in 0..<active {
                let slot = eqActive[index]
                for channel in 0..<channelCount {
                    let i = (channel * TransitionFilter.eqSlots + slot) * 2
                    if abs(eqState[i]) < TransitionFilter.denormalFloor { eqState[i] = 0 }
                    if abs(eqState[i + 1]) < TransitionFilter.denormalFloor { eqState[i + 1] = 0 }
                }
            }
            done += block
        }
    }

    private func updateEqCoefficients(_ slot: Int) {
        let a = pow(10, eqGain[slot] / 40)
        let q = max(eqQ[slot], TransitionFilter.eqMinQ)
        let frequency = min(max(eqFrequencies[slot], TransitionFilter.minHz), sampleRate * TransitionFilter.maxCutoffFraction)
        let base = tan(Float.pi * frequency / sampleRate)
        let g: Float
        let k: Float
        switch eqKinds[slot] {
        case 1: // low shelf
            g = base / sqrt(a)
            k = 1 / q
            eqMixInput[slot] = 1
            eqMixBand[slot] = k * (a - 1)
            eqMixLow[slot] = a * a - 1
        case 2: // high shelf
            g = base * sqrt(a)
            k = 1 / q
            eqMixInput[slot] = a * a
            eqMixBand[slot] = k * (1 - a) * a
            eqMixLow[slot] = 1 - a * a
        default: // bell
            g = base
            k = 1 / (q * a)
            eqMixInput[slot] = 1
            eqMixBand[slot] = k * (a * a - 1)
            eqMixLow[slot] = 0
        }
        let d = 1 / (1 + g * (g + k))
        eqA1[slot] = d
        eqA2[slot] = g * d
        eqA3[slot] = g * (g * d)
    }

    @inline(__always)
    private func eqSection(_ slot: Int, _ channel: Int, _ input: Float) -> Float {
        let i = (channel * TransitionFilter.eqSlots + slot) * 2
        let ic1 = eqState[i]
        let ic2 = eqState[i + 1]
        let v3 = input - ic2
        let v1 = eqA1[slot] * ic1 + eqA2[slot] * v3
        let v2 = ic2 + eqA2[slot] * ic1 + eqA3[slot] * v3
        eqState[i] = 2 * v1 - ic1
        eqState[i + 1] = 2 * v2 - ic2
        return eqMixInput[slot] * input + eqMixBand[slot] * v1 + eqMixLow[slot] * v2
    }

    // MARK: - Transition filters (Android TransitionFilterProcessor, unchanged maths)

    private func applyTransitionFilters(_ buffers: UnsafeMutableAudioBufferListPointer, frames: Int) {
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
            forEachSample(buffers, from: done, count: block) { channel, sample in
                var value = sample.pointee
                if lowOn { value = lowPass(channel, value) }
                if highOn { value = highPass(channel, value) }
                sample.pointee = value
            }
            done += block
        }
    }

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

    /// Puts this processing on [item]'s first audio track, once its tracks are
    /// known (never blocking the main thread on a network asset). Best effort:
    /// without a tap, playback and transitions still run, unfiltered.
    func attach(to item: AVPlayerItem) {
        let asset = item.asset
        if asset.statusOfValue(forKey: "tracks", error: nil) == .loaded {
            install(on: item)
            return
        }
        asset.loadValuesAsynchronously(forKeys: ["tracks"]) { [weak self, weak item] in
            DispatchQueue.main.async {
                guard let self, let item else { return }
                self.install(on: item)
            }
        }
    }

    private func install(on item: AVPlayerItem) {
        guard item.audioMix == nil, let track = item.asset.tracks(withMediaType: .audio).first else { return }
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
