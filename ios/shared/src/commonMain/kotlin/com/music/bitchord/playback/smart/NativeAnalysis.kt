package com.music.bitchord.playback.smart

// ios-native: the seam to the Swift side of Automix analysis
// (iosApp/Analysis/AnalysisBridge.swift). Android decodes with MediaCodec and
// calls the C++ analyzer and ONNX Runtime from Kotlin over JNI; on iOS the
// decode (AVAudioFile), the same C++ analyzer (native/analyzer, compiled into
// the app) and ONNX Runtime (onnxruntime-objc) all live in Swift, and only
// small results cross back: a JSON string, two logit rows, one curve.

/** Implemented in Swift; set at launch through `MainViewController`. Every call blocks — run off the main thread. */
interface NativeAnalysis {
    /**
     * Whole-track DSP features: decode [path] to mono, resample to 11,025 Hz,
     * run `AnalyzeAudio`, and return the analyzer's JSON (the same document
     * Android's `analysis_jni.cpp` writes). Null when the file will not decode.
     */
    fun analyzeFeatures(path: String, durationSeconds: Double): String?

    /**
     * Beat This! over the region [startSeconds, endSeconds): mono at 22,050 Hz,
     * the log-mel front end, the model in 1,500-frame chunks. Null when the
     * region will not decode or the model is unavailable.
     */
    fun beatLogits(path: String, startSeconds: Double, endSeconds: Double, threads: Int): BeatLogits?

    /**
     * open-unmix vocal presence over the region, one value in 0..1 per STFT
     * frame (44,100 Hz, hop 1,024), band-averaged over 200–4,000 Hz. Null when
     * unavailable.
     */
    fun vocalCurve(path: String, startSeconds: Double, endSeconds: Double, threads: Int): VocalCurve?
}

/** Beat and downbeat logits per 20 ms frame, for a region that really began at [startSeconds]. */
class BeatLogits(val beat: FloatArray, val downbeat: FloatArray, val startSeconds: Double)

/** Per-frame vocal presence for a region that really began at [startSeconds]. */
class VocalCurve(val values: FloatArray, val startSeconds: Double)

object NativeAnalysisHolder {
    /** Set by Swift at launch; null means Automix analysis is unavailable and transitions fall back to plain fades. */
    var bridge: NativeAnalysis? = null
}
