package com.music.bitchord.playback

// Ported from the Android CrossfadeController. The transition logic — when to
// arm, the equal-power gains, every filter ride and its constants, the bail
// ramp — is Android's. What changed is the machinery underneath: Android swaps
// two ExoPlayers that each own the queue; here [PlayerController] owns the one
// queue and the Swift AudioEngine owns two decks (see AudioEngine's "second
// deck"), so arming loads the incoming stream onto the other deck and the
// handoff is [AudioEngine.handoffToStandby] plus [Host.onHandoff].
// Not ported: the sleep-timer fade, Listen Together and version swaps (P7).

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.SmartAnalysis
import com.music.bitchord.data.settings.TrackAnalysisState
import com.music.bitchord.data.settings.TransitionWindow
import com.music.bitchord.platform.elapsedMillis
import com.music.bitchord.playback.smart.CrossfadeMode
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TrackAnalyzer
import com.music.bitchord.playback.smart.TransitionStyle
import com.music.bitchord.playback.smart.TransitionTrackInfo
import com.music.bitchord.playback.smart.planTransition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin

class CrossfadeController(
    private val scope: CoroutineScope,
    private val engine: AudioEngine,
    private val host: Host,
    private val analyzer: TrackAnalyzer,
) {
    /** What the controller needs from [PlayerController]. */
    interface Host {
        val current: Song?
        val next: Song?
        val isPlaying: Boolean
        val durationMs: Long

        /** The stream already resolved for [next], or null while it is still resolving. */
        fun nextStream(): StreamResolver.Stream?

        /** The incoming track just became the current deck: advance the queue onto it. */
        fun onHandoff(stream: StreamResolver.Stream)
    }

    private enum class Phase { IDLE, ARMING, FADING, BAILING }

    private var phase = Phase.IDLE
    private var handedOff = false
    private var tickerJob: Job? = null
    private var fadeMs = 0L
    private var fadeEndMs = 0L
    private var smartFadeActive = false
    private var incomingCueTimeMs = 0L
    private var incomingPlaybackRate = 1.0
    private var render = Render()
    private var armDeadline = 0L
    private var bailStartedAt = 0L
    private var bailFromGain = 0f
    private var outgoingGain = 1f
    private var armedFor: String? = null
    private var armedStream: StreamResolver.Stream? = null
    private var lastPlanVerdict = ""

    private data class Render(
        val style: TransitionStyle = TransitionStyle.EQUAL_POWER,
        val bassSwap: Boolean = false,
        val bassSwapFraction: Double = 0.7,
        val filterSweep: Double = 0.0,
        val vocalOverlap: Double = 0.0,
    )

    /** Filter corners for each side, pushed to the engine together. */
    private var inLow = OPEN_HZ
    private var inHigh = OFF_HZ
    private var outLow = OPEN_HZ
    private var outHigh = OFF_HZ

    private val filters = object {
        fun incoming(lowPassHz: Float, highPassHz: Float) {
            inLow = lowPassHz
            inHigh = highPassHz
            push()
        }

        fun outgoing(lowPassHz: Float, highPassHz: Float) {
            outLow = lowPassHz
            outHigh = highPassHz
            push()
        }

        fun open() {
            inLow = OPEN_HZ; inHigh = OFF_HZ; outLow = OPEN_HZ; outHigh = OFF_HZ
            push()
        }

        // Filters only ride once both tracks are audible, i.e. after the handoff,
        // when the incoming track is the current deck and the outgoing the other.
        private fun push() = engine.setDeckFilters(inLow, inHigh, outLow, outHigh)
    }

    fun isTransitioning(): Boolean = phase != Phase.IDLE

    fun start() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                tick()
                delay(
                    when (phase) {
                        Phase.IDLE -> IDLE_STEP_MS
                        Phase.ARMING -> ARM_STEP_MS
                        Phase.FADING -> FADE_STEP_MS
                        Phase.BAILING -> BAIL_STEP_MS
                    },
                )
            }
        }
    }

    /** A skip, seek or new queue: drop any blend in flight and get out of the way. */
    fun onSkipRequested() {
        if (phase != Phase.IDLE) bail()
    }

    /** The current deck ended or advanced on its own while a transition was armed: it is moot. */
    fun onTrackChangedUnderneath() {
        if (phase == Phase.ARMING) finish()
    }

    // ---- Ticker -------------------------------------------------------------

    private fun tick() {
        publishAnalysisState()
        when (phase) {
            Phase.IDLE -> considerAutoTransition()
            Phase.ARMING -> driveArming()
            Phase.FADING -> driveFade()
            Phase.BAILING -> driveBail()
        }
    }

    private fun considerAutoTransition() {
        if (!host.isPlaying) return
        val current = host.current ?: return
        val next = host.next
        if (next == null) {
            AppSettings.smartTransitionWindow.value = null
            return
        }
        val duration = host.durationMs
        if (duration <= 0L) return

        if (AppSettings.smartFadeEnabled.value) {
            considerSmartTransition(current, next, duration)
            return
        }

        if (configuredFadeMs() <= 0L) return
        val fade = fadeFor(duration)
        if (fade <= 0L) return
        val remaining = duration - engine.currentPositionMs()
        if (remaining > fade + ARM_LEAD_MS) return
        begin(next, fade, endMs = duration, smart = false)
    }

    private fun considerSmartTransition(current: Song, next: Song, duration: Long) {
        // AutoMix's analysis and cueing are never applied to a video row.
        if (current.isVideoOrigin || next.isVideoOrigin) {
            AppSettings.smartTransitionWindow.value = null
            AppSettings.smartMixInProgress.value = false
            return
        }
        val nextDuration = next.durationMillis()
        analyzer.request(current, duration / 1000.0)
        analyzer.request(next, nextDuration / 1000.0)

        val fallbackSeconds = configuredFadeMs().takeIf { it > 0L }?.div(1000.0) ?: DEFAULT_SMART_FALLBACK_SECONDS
        val currentAnalysis = analyzer.analysisFor(current.videoId)
        val nextAnalysis = analyzer.analysisFor(next.videoId)
        val analysisState = AppSettings.smartAnalysis.value

        val plan = planTransition(
            analysis = currentAnalysis,
            nextAnalysis = nextAnalysis,
            currentTrack = current.toTransitionInfo(duration),
            nextTrack = next.toTransitionInfo(nextDuration),
            currentTime = engine.currentPositionMs() / 1000.0,
            duration = duration / 1000.0,
            fadeSeconds = fallbackSeconds,
            mode = CrossfadeMode.SMART,
        )
        val verdict = "${plan.reason}|${plan.transitionStyle}|fade=${plan.fadeMs}" +
            "|cue=${plan.incomingCueTime}|rate=${plan.incomingPlaybackRate}" +
            "|vocalOverlap=${(plan.vocalOverlap * 100).toInt()}%" +
            "|blocked=${plan.blocked}|policy=${plan.policyReasons.joinToString(",")}"
        if (verdict != lastPlanVerdict) {
            lastPlanVerdict = verdict
            Log.d(
                TAG,
                "plan ${current.videoId}->${next.videoId}: $verdict " +
                    "bpm=${currentAnalysis.bpm}/${nextAnalysis.bpm} " +
                    "conf=${currentAnalysis.beatConfidence}/${nextAnalysis.beatConfidence}",
            )
        }

        val markable = !plan.blocked &&
            plan.markerVisible &&
            analysisState.current == TrackAnalysisState.ANALYSED &&
            analysisState.next in MEASURED_ENOUGH_TO_ENTER_ON
        AppSettings.smartTransitionWindow.value = if (markable) {
            TransitionWindow(
                start = (plan.transitionStart * 1000.0 / duration).toFloat().coerceIn(0f, 1f),
                end = (plan.transitionEnd * 1000.0 / duration).toFloat().coerceIn(0f, 1f),
            )
        } else {
            null
        }

        if (plan.blocked) return
        val fade = plan.fadeMs
        if (fade <= 0L) return
        val transitionStartMs = (plan.transitionStart * 1000).roundToLong()
        if (transitionStartMs - engine.currentPositionMs() > ARM_LEAD_MS) return

        begin(
            next,
            fade,
            endMs = (plan.transitionEnd * 1000).roundToLong(),
            smart = true,
            cueTimeMs = (plan.incomingCueTime * 1000).roundToLong(),
            playbackRate = plan.incomingPlaybackRate,
            renderStyle = Render(
                style = plan.transitionStyle,
                bassSwap = plan.bassSwap,
                bassSwapFraction = plan.bassSwapFraction,
                filterSweep = plan.filterSweep,
                vocalOverlap = plan.vocalOverlap,
            ),
        )
    }

    private fun publishAnalysisState() {
        if (!AppSettings.smartFadeEnabled.value) return
        val current = host.current
        val next = host.next
        AppSettings.smartAnalysis.value = SmartAnalysis(
            current = current?.let { stateOf(it.videoId) } ?: TrackAnalysisState.WAITING,
            next = next?.let { stateOf(it.videoId) } ?: TrackAnalysisState.WAITING,
        )
    }

    private fun stateOf(id: String): TrackAnalysisState {
        val analysis = analyzer.analysisFor(id)
        return when {
            analysis.isUsable -> TrackAnalysisState.ANALYSED
            analyzer.isAnalysing(id) -> TrackAnalysisState.ANALYSING
            analysis.status == TrackAnalysis.STATUS_READY -> TrackAnalysisState.FAILED
            else -> TrackAnalysisState.WAITING
        }
    }

    private fun Song.toTransitionInfo(durationMs: Long) = TransitionTrackInfo(
        id = videoId,
        durationMs = durationMs,
        title = title,
        artist = artist,
        album = albumName.orEmpty(),
        albumId = albumId.orEmpty(),
    )

    // ---- A transition -------------------------------------------------------

    /** Note the plan; the incoming stream is loaded onto the other deck as soon as it is resolved. */
    private fun begin(
        next: Song,
        fade: Long,
        endMs: Long,
        smart: Boolean,
        cueTimeMs: Long = 0L,
        playbackRate: Double = 1.0,
        renderStyle: Render = Render(),
    ) {
        fadeMs = fade
        fadeEndMs = endMs
        smartFadeActive = smart
        incomingCueTimeMs = cueTimeMs.coerceAtLeast(0L)
        incomingPlaybackRate = playbackRate
        render = renderStyle
        armDeadline = elapsedMillis() + ARM_TIMEOUT_MS
        handedOff = false
        outgoingGain = 1f
        armedFor = next.videoId
        armedStream = null
        Log.d(
            TAG,
            "arm ${if (smart) "smart" else "standard"} fade=${fade}ms end=${endMs}ms " +
                "cue=${incomingCueTimeMs}ms rate=$incomingPlaybackRate at=${engine.currentPositionMs()}ms " +
                "style=${render.style} bassSwap=${render.bassSwap}@${render.bassSwapFraction} sweep=${render.filterSweep}",
        )
        phase = Phase.ARMING
        loadStandbyIfResolved()
    }

    private fun loadStandbyIfResolved() {
        if (armedStream != null) return
        val stream = host.nextStream()?.takeIf { it.videoId == armedFor } ?: return
        armedStream = stream
        engine.armStandby(
            url = stream.url,
            headers = stream.headers,
            mimeType = stream.mimeType,
            contentLength = stream.contentLength ?: -1L,
            chunkBytes = stream.chunkBytes,
            startMs = incomingCueTimeMs,
            rate = incomingPlaybackRate.toFloat(),
        )
        engine.setDeckVolumes(1f, 0f)
    }

    private fun driveArming() {
        if (!stillWorthFading()) return bail()
        if (!host.isPlaying) return bail()
        loadStandbyIfResolved()
        val expired = elapsedMillis() > armDeadline
        val ready = armedStream != null && engine.isStandbyReady()
        if (expired && !ready) return bail()
        val atFadePoint = fadeEndMs <= 0L || fadeEndMs - engine.currentPositionMs() <= fadeMs
        if (!atFadePoint) return
        if (ready) startFade()
    }

    private fun startFade() {
        val stream = armedStream ?: return bail()
        engine.setDeckVolumes(1f, 0f)
        Log.d(TAG, "handoff at cue=${incomingCueTimeMs}ms out=${engine.currentPositionMs()}ms")
        engine.handoffToStandby()
        // From here the incoming track is the current deck and the outgoing one the other.
        engine.setDeckVolumes(0f, 1f)
        handedOff = true
        host.onHandoff(stream)
        AppSettings.smartMixInProgress.value = isRealMix()
        AppSettings.smartTransitionWindow.value = null
        phase = Phase.FADING
    }

    /** The crossfade proper, driven off the incoming track's position so a pause parks it. */
    private fun driveFade() {
        val incomingDuration = host.durationMs
        val remainingIncoming = incomingDuration.takeIf { it > 0L }?.minus(incomingCueTimeMs)?.coerceAtLeast(0L)
        val incomingCap = remainingIncoming?.div(3) ?: Long.MAX_VALUE
        val span = minOf(fadeMs, incomingCap).coerceAtLeast(1L)
        val elapsed = (engine.currentPositionMs() - incomingCueTimeMs).coerceAtLeast(0L)
        val progress = (elapsed.toFloat() / span).coerceIn(0f, 1f)

        outgoingGain = fallGain(progress)
        engine.setDeckVolumes(riseGain(progress), outgoingGain)
        rideFilters(progress)

        val settingSwitchedOff = if (smartFadeActive) !AppSettings.smartFadeEnabled.value else configuredFadeMs() <= 0L
        val done = progress >= 1f || !engine.isTailPlaying() || settingSwitchedOff
        if (done) finish()
    }

    private fun driveBail() {
        val progress = (elapsedMillis() - bailStartedAt).toFloat() / BAIL_MS
        if (progress < 1f) {
            engine.setDeckVolumes(1f, bailFromGain * fallGain(progress))
            return
        }
        finish()
    }

    private fun bail() {
        if (phase == Phase.IDLE || phase == Phase.BAILING) return
        Log.d(TAG, "bail from $phase")
        AppSettings.smartMixInProgress.value = false
        if (!handedOff) {
            finish()
            return
        }
        filters.open()
        bailFromGain = outgoingGain
        bailStartedAt = elapsedMillis()
        phase = Phase.BAILING
    }

    private fun finish() {
        if (phase != Phase.IDLE) Log.d(TAG, "finish from $phase")
        AppSettings.smartMixInProgress.value = false
        filters.open()
        render = Render()
        engine.releaseOtherDeck()
        engine.setDeckVolumes(1f, 0f)
        if (handedOff && incomingPlaybackRate != 1.0) engine.setCurrentRate(1f)
        handedOff = false
        armedFor = null
        armedStream = null
        incomingCueTimeMs = 0L
        incomingPlaybackRate = 1.0
        phase = Phase.IDLE
    }

    private fun stillWorthFading(): Boolean {
        val stillOn = if (smartFadeActive) AppSettings.smartFadeEnabled.value else configuredFadeMs() > 0L
        return stillOn && host.next?.videoId == armedFor
    }

    // ---- Numbers ------------------------------------------------------------

    private fun configuredFadeMs(): Long = AppSettings.crossfadeSeconds.value * 1000L

    private fun fadeFor(duration: Long): Long {
        val configured = configuredFadeMs()
        if (duration <= 0L) return configured
        return minOf(configured, duration / 3).coerceAtLeast(0L)
    }

    private fun rideFilters(progress: Float) {
        when (render.style) {
            TransitionStyle.DJ_FILTER -> rideFilterSweep(progress)
            TransitionStyle.DJ_BLEND -> if (render.bassSwap) rideBassSwap(progress) else rideVocalSeparation(progress)
            TransitionStyle.GAPLESS -> filters.open()
            TransitionStyle.EQUAL_POWER -> rideVocalSeparation(progress)
        }
    }

    private fun rideVocalSeparation(progress: Float) {
        val amount = render.vocalOverlap.coerceIn(0.0, 1.0)
        if (amount <= 0.0) {
            filters.open()
            return
        }
        val open = OPEN_HZ.toDouble()
        val floor = glide(open, VOCAL_SEPARATION_FLOOR_HZ, amount)
        filters.outgoing(glide(open, floor, progress.toDouble().pow(FILTER_SWEEP_SHAPE)).toFloat(), OFF_HZ)
        filters.incoming(OPEN_HZ, entryHighPass(progress, amount, VOCAL_SEPARATION_HIGH_PASS_HZ, ENTRY_OPEN_BY))
    }

    private fun rideFilterSweep(progress: Float) {
        val sweep = render.filterSweep.coerceIn(0.0, 1.0)
        if (sweep <= 0.0) {
            filters.open()
            return
        }
        val open = OPEN_HZ.toDouble()
        val entry = glide(open, FILTER_ENTRY_HZ, sweep)
        val floor = glide(open, FILTER_FLOOR_HZ, sweep)
        val cutoff = glide(entry, floor, progress.toDouble().pow(FILTER_SWEEP_SHAPE))
        filters.outgoing(cutoff.toFloat(), OFF_HZ)
        filters.incoming(OPEN_HZ, entryHighPass(progress, sweep, ENTRY_HIGH_PASS_HZ, ENTRY_OPEN_BY))
    }

    private fun entryHighPass(progress: Float, amount: Double, topHz: Double, openBy: Double): Float {
        val remaining = (1.0 - progress / openBy).coerceIn(0.0, 1.0)
        return glide(OFF_HZ.toDouble(), topHz, amount * remaining.pow(ENTRY_SHAPE)).toFloat()
    }

    private fun glide(from: Double, to: Double, amount: Double): Double =
        from * (to / from).pow(amount.coerceIn(0.0, 1.0))

    private fun rideBassSwap(progress: Float) {
        val swapAt = render.bassSwapFraction.coerceIn(0.05, 0.95)
        val handover = ((progress - swapAt) / BASS_SWAP_WIDTH * 0.5 + 0.5).coerceIn(0.0, 1.0)
        val clash = render.vocalOverlap.coerceIn(0.0, 1.0)
        val entry = maxOf(
            bassCutoff(1.0 - handover),
            entryHighPass(
                progress,
                1.0,
                glide(BLEND_ENTRY_HIGH_PASS_HZ, BLEND_ENTRY_CLASH_HIGH_PASS_HZ, clash),
                BLEND_ENTRY_OPEN_BY + (BLEND_ENTRY_CLASH_OPEN_BY - BLEND_ENTRY_OPEN_BY) * clash,
            ),
        )
        filters.incoming(OPEN_HZ, entry)
        filters.outgoing(blendExitLowPass(progress, clash), bassCutoff(handover))
    }

    private fun blendExitLowPass(progress: Float, clash: Double): Float {
        val from = BLEND_EXIT_FROM + (BLEND_EXIT_CLASH_FROM - BLEND_EXIT_FROM) * clash
        val amount = ((progress - from) / (1.0 - from)).coerceIn(0.0, 1.0)
        val floor = glide(BLEND_EXIT_LOW_PASS_HZ, BLEND_EXIT_CLASH_LOW_PASS_HZ, clash)
        return glide(OPEN_HZ.toDouble(), floor, amount).toFloat()
    }

    private fun bassCutoff(amount: Double): Float = glide(OFF_HZ.toDouble(), BASS_SWAP_HZ, amount).toFloat()

    private fun isRealMix(): Boolean = smartFadeActive && (
        render.style == TransitionStyle.DJ_BLEND ||
            render.style == TransitionStyle.DJ_FILTER ||
            incomingCueTimeMs > 0L ||
            incomingPlaybackRate != 1.0
        )

    /** Equal-power pair: riseGain² + fallGain² = 1, so the blend never dips. */
    private fun riseGain(progress: Float): Float = sin(progress.coerceIn(0f, 1f) * PI.toFloat() / 2f)

    private fun fallGain(progress: Float): Float = cos(progress.coerceIn(0f, 1f) * PI.toFloat() / 2f)

    private companion object {
        const val TAG = "BitChordCrossfade"
        const val OPEN_HZ = 20_000f
        const val OFF_HZ = 20f
        const val DEFAULT_SMART_FALLBACK_SECONDS = 6.0
        const val BAIL_MS = 120L

        /** More than Android's 4 s: the incoming stream is opened over the network, never from a disk cache. */
        const val ARM_LEAD_MS = 6_000L
        const val ARM_TIMEOUT_MS = 14_000L
        val MEASURED_ENOUGH_TO_ENTER_ON = setOf(TrackAnalysisState.ANALYSED, TrackAnalysisState.REFINING)

        const val FILTER_ENTRY_HZ = 7_000.0
        const val FILTER_FLOOR_HZ = 300.0
        const val BASS_SWAP_HZ = 200.0
        const val BASS_SWAP_WIDTH = 0.10
        const val FILTER_SWEEP_SHAPE = 0.75
        const val ENTRY_HIGH_PASS_HZ = 1_200.0
        const val ENTRY_OPEN_BY = 0.6
        const val ENTRY_SHAPE = 0.35
        const val VOCAL_SEPARATION_FLOOR_HZ = 1_600.0
        const val VOCAL_SEPARATION_HIGH_PASS_HZ = 700.0
        const val BLEND_ENTRY_HIGH_PASS_HZ = 520.0
        const val BLEND_ENTRY_OPEN_BY = 0.45
        const val BLEND_ENTRY_CLASH_HIGH_PASS_HZ = 950.0
        const val BLEND_ENTRY_CLASH_OPEN_BY = 0.7
        const val BLEND_EXIT_CLASH_FROM = 0.12
        const val BLEND_EXIT_CLASH_LOW_PASS_HZ = 1_100.0
        const val BLEND_EXIT_FROM = 0.3
        const val BLEND_EXIT_LOW_PASS_HZ = 2_200.0

        const val IDLE_STEP_MS = 250L
        const val ARM_STEP_MS = 40L
        const val FADE_STEP_MS = 30L
        const val BAIL_STEP_MS = 15L
    }
}
