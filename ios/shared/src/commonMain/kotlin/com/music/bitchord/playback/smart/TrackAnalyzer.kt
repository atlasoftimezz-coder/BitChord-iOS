package com.music.bitchord.playback.smart

// ios-native: the iOS counterpart of the Android TrackAnalyzer. The analysis
// itself — a whole-track DSP pass, then Beat This! and open-unmix over the
// head and tail windows, merged exactly as Android's analyze() merges them —
// is the same. What differs is where the audio comes from: Android reads
// ExoPlayer's cache, iOS reads the downloaded or imported file when there is
// one and otherwise fetches the track once into Caches for the analysis and
// deletes it afterwards.

import android.content.Context
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.model.Song
import com.music.bitchord.download.DownloadStore
import com.music.bitchord.download.Downloader
import com.music.bitchord.download.Downloads
import com.music.bitchord.platform.FileSystem
import com.music.bitchord.platform.PlatformLock
import com.music.bitchord.platform.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max

class TrackAnalyzer {

    private val store = AnalysisStore(Context.app)
    private val tracker = BeatTracker()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One analysis at a time: each is a whole-track decode and two model passes. */
    private val gate = Mutex()
    private val lock = PlatformLock()
    private val results = HashMap<String, TrackAnalysis>()
    private val running = HashSet<String>()
    private val restoreAttempted = HashSet<String>()

    /** Failed attempts per track; a track that keeps failing stops being retried this session. */
    private val strikes = HashMap<String, Int>()

    /** The stored analysis for [trackId], or an empty one (status blank) when there is none yet. */
    fun analysisFor(trackId: String): TrackAnalysis {
        lock.withLock { results[trackId] }?.let { return it }
        val restore = lock.withLock { restoreAttempted.add(trackId) }
        if (restore) {
            store.load(trackId)?.let { stored ->
                lock.withLock { results[trackId] = stored }
                return stored
            }
        }
        return TrackAnalysis(trackId = trackId)
    }

    fun isAnalysing(trackId: String): Boolean = lock.withLock { trackId in running }

    /**
     * Queue [song] for analysis. Cheap to call on every tick: a track already
     * analysed, in flight, or written off is a no-op.
     */
    fun request(song: Song, durationSeconds: Double) {
        val id = song.videoId
        if (song.isVideoOrigin || NativeAnalysisHolder.bridge == null) return
        if (analysisFor(id).status == TrackAnalysis.STATUS_READY) return
        val start = lock.withLock {
            if (id in running || (strikes[id] ?: 0) >= MAX_STRIKES) return
            running += id
            true
        }
        if (!start) return
        scope.launch {
            try {
                gate.withLock { analyzeSong(song, durationSeconds) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "analysis of $id failed: ${e.message}")
                lock.withLock { strikes[id] = (strikes[id] ?: 0) + 1 }
            } finally {
                lock.withLock { running -= id }
            }
        }
    }

    private suspend fun analyzeSong(song: Song, durationSeconds: Double) {
        val id = song.videoId
        val localPath = (song.localUri ?: Downloads.verifiedSavedUri(id))
            ?.let(DownloadStore::pathOf)?.takeIf(FileSystem::isFile)
        val path = localPath ?: fetch(id) ?: run {
            lock.withLock { strikes[id] = (strikes[id] ?: 0) + 1 }
            return
        }
        try {
            val analysis = analyze(id, path, durationSeconds)
            if (analysis == null) {
                lock.withLock { strikes[id] = (strikes[id] ?: 0) + 1 }
                return
            }
            lock.withLock { results[id] = analysis }
            store.save(id, analysis)
        } finally {
            if (localPath == null) FileSystem.delete(path)
        }
    }

    /** The track's audio in Caches, fetched the same way a download is. */
    private suspend fun fetch(id: String): String? {
        val path = FileSystem.cachesDirectory() + "/analysis/" + id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".m4a"
        FileSystem.delete(path)
        return try {
            val stream = StreamResolver.resolve(id)
            Downloader.fetch(id, stream, { bytes -> if (!FileSystem.append(path, bytes)) error("Could not write analysis audio") }) { _, _ -> }
            path
        } catch (e: CancellationException) {
            FileSystem.delete(path)
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "could not fetch $id for analysis: ${e.message}")
            FileSystem.delete(path)
            null
        }
    }

    /** Android's analyze(): Pass 1 DSP over the whole track, Pass 2 models over head and tail. */
    private fun analyze(trackId: String, path: String, durationSeconds: Double): TrackAnalysis? {
        val features = TrackFeatures.analyze(path, durationSeconds)
            ?: return empty(trackId, durationSeconds)
        val effectiveDuration = durationSeconds.takeIf { it.isFinite() && it > 0 } ?: features.duration
        if (effectiveDuration <= 0) return empty(trackId, 0.0)

        val window = BeatTracker.WINDOW_SECONDS
        val tailStart = max(0.0, effectiveDuration - window)
        val headGrid = tracker.track(path, 0.0, minOf(window, effectiveDuration))
        val headMask = vocalMask(path, 0.0, features)
        val hasTail = tailStart > window / 2
        val tailGrid = if (hasTail) tracker.track(path, tailStart, effectiveDuration) else null
        val tailMask = if (hasTail) vocalMask(path, tailStart, features) else null

        // The tail governs where the outgoing track is mixed out, so it takes precedence; the
        // head is what a track uses when it is the *incoming* side of a different transition.
        val leading = tailGrid ?: headGrid

        Log.d(
            TAG,
            "Analysed $trackId: bpm=${leading?.bpm ?: features.bpm} " +
                "conf=${leading?.beatConfidence ?: features.beatConfidence} " +
                "key=${features.key} contentEnd=${features.contentEndTime} " +
                "mixOutCandidates=${features.mixOutCandidates.size} " +
                "vocalMask=${if (headMask != null || tailMask != null) "model" else "dsp"}",
        )

        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = trackId,
            duration = effectiveDuration,
            contentEndTime = features.contentEndTime.takeIf { it > 0 } ?: effectiveDuration,
            bpm = leading?.bpm ?: features.bpm,
            beatInterval = leading?.beatInterval ?: features.beatInterval,
            beatConfidence = leading?.beatConfidence ?: features.beatConfidence,
            downbeats = (headGrid?.downbeats.orEmpty() + tailGrid?.downbeats.orEmpty())
                .ifEmpty { features.downbeats }
                .sorted(),
            firstBeat = headGrid?.firstBeat ?: features.firstBeat,
            phraseBoundaries = features.phraseBoundaries,
            key = features.key,
            keyConfidence = features.keyConfidence,
            audibleStartTime = features.audibleStartTime,
            pickupTime = features.pickupTime,
            introEndTime = features.introEndTime,
            outroStartTime = features.outroStartTime,
            mixInTime = features.mixInTime,
            mixOutTime = features.mixOutTime,
            mixInCandidates = features.mixInCandidates,
            mixOutCandidates = features.mixOutCandidates,
            energyCurve = features.energyCurve,
            lowEnergyCurve = features.lowEnergyCurve,
            vocalActivityMask = mergeMasks(features.energyCurve.size, headMask, tailMask)
                ?: features.vocalActivityMask,
            vocalProbability = features.vocalProbability,
        )
    }

    /**
     * A vocal-presence value for every point on the energy curve, filled only
     * where the model ran (one fixed ~22 s window from [startSeconds]); the
     * rest stays at [NEUTRAL_VOCAL], below the policy's activity threshold.
     */
    private fun vocalMask(path: String, startSeconds: Double, features: TrackFeatures.Features): DoubleArray? {
        val curve = features.energyCurve
        val bridge = NativeAnalysisHolder.bridge ?: return null
        if (curve.isEmpty()) return null
        val maxSeconds = (VOCAL_FIXED_FRAMES - 2) * VOCAL_HOP / VOCAL_SAMPLE_RATE
        val result = runCatching {
            bridge.vocalCurve(path, startSeconds, startSeconds + maxSeconds,
                com.music.bitchord.data.settings.AppSettings.automixPerformanceMode.value.inferenceThreads)
        }.onFailure { Log.w(TAG, "Vocal inference failed: ${it.message}") }.getOrNull() ?: return null
        val values = result.values
        val frameRate = VOCAL_SAMPLE_RATE / VOCAL_HOP
        val mask = DoubleArray(curve.size) { NEUTRAL_VOCAL }
        for (index in curve.indices) {
            val frame = ((curve[index].time - result.startSeconds) * frameRate).toInt()
            if (frame in values.indices) mask[index] = values[frame].toDouble()
        }
        return mask
    }

    private fun mergeMasks(size: Int, head: DoubleArray?, tail: DoubleArray?): List<Double>? {
        if (size <= 0 || (head == null && tail == null)) return null
        val merged = DoubleArray(size) { NEUTRAL_VOCAL }
        for (source in listOfNotNull(head, tail)) {
            for (index in merged.indices) {
                if (index < source.size && source[index] != NEUTRAL_VOCAL) merged[index] = source[index]
            }
        }
        return merged.toList()
    }

    /** Recorded ready-but-empty so a track that cannot be decoded is not retried every tick. */
    private fun empty(trackId: String, durationSeconds: Double) = TrackAnalysis(
        status = TrackAnalysis.STATUS_READY,
        trackId = trackId,
        duration = durationSeconds,
    )

    private companion object {
        const val TAG = "BitChordAnalyzer"
        const val MAX_STRIKES = 3

        /** Below the policy's VOCAL_ACTIVE_THRESHOLD, so unmeasured material never trips vocal logic. */
        const val NEUTRAL_VOCAL = 0.5

        /** open-unmix's fixed input width and STFT, as in Android's VocalTracker / VocalSpectrogram. */
        const val VOCAL_FIXED_FRAMES = 960
        const val VOCAL_HOP = 1024.0
        const val VOCAL_SAMPLE_RATE = 44_100.0
    }
}
