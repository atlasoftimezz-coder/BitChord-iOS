package com.music.bitchord.data.settings

// Automix setting types, as declared in the Android AppSettings.kt.

/** Which of the equaliser's two tabs is driving the sound. */
enum class EqualizerMode {
    /** The tone pad: tilt, contour, and how wide each is. */
    DYNAMIC,

    /** Seven sliders and a preset list. */
    MANUAL,
}

/** CPU budget for Automix's background analysis, not its audible mix algorithm. */
enum class AutomixPerformanceMode(val inferenceThreads: Int) {
    EFFICIENT(1),
    BALANCED(2),
    PERFORMANCE(4),
}

/** Where one track's Automix analysis stands, for the stats line. */
enum class TrackAnalysisState {
    /** Nothing in flight and no result — usually waiting on bytes to arrive. */
    WAITING,

    /** Decode and inference running now; a result is a few seconds away. */
    ANALYSING,

    /** Measured, with a tempo the planner can actually use. */
    ANALYSED,

    /** Measured off the track's opening, with the whole-track pass running now. */
    REFINING,

    /** Tried and came back with nothing usable. */
    FAILED,
}

/** Both sides of the next transition, for stats for nerds. */
data class SmartAnalysis(
    val current: TrackAnalysisState = TrackAnalysisState.WAITING,
    val next: TrackAnalysisState = TrackAnalysisState.WAITING,
)

/** A span of the playing track, in fractions of its duration, that the next transition occupies. */
data class TransitionWindow(val start: Float, val end: Float)
