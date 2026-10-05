package com.music.bitchord.data.innertube

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.InnerTubeLogLevel
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.generateClientPlaybackNonce
import com.metrolist.innertubex.models.YouTubeLocale
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.platform.elapsedMillis
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Url
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json

/**
 * iOS counterpart of the Android app's `StreamResolver` + `InnerTubeXResolver`.
 *
 * Android tries InnerTubeX first and falls back to NewPipe; NewPipe is
 * JVM-only, so here InnerTubeX is the whole story. The probe-before-trust
 * logic is ported as-is: a minted URL is read before it is handed to the
 * player, and a client whose URL is refused is skipped for the next attempt.
 *
 * AVPlayer cannot decode WebM/Opus, so the request always asks for AAC in MP4
 * ([AudioQuality.MP4]) — the format the Android app uses for downloads.
 */
object StreamResolver {

    private const val TAG = "BitChord"

    /** A stream ready for [com.music.bitchord.playback.AudioEngine]. */
    class Stream(
        val videoId: String,
        val url: String,
        val kbps: Int,
        val mimeType: String,
        val contentLength: Long?,
        /** Headers every media request must carry to match the client that minted the URL. */
        val headers: Map<String, String>,
        /** Largest range googlevideo will serve this client in one request. */
        val chunkBytes: Long,
        val loudnessDb: Double?,
        val profileId: String,
    )

    private val http = HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 20_000
        }
        expectSuccess = false
    }

    private val logger = InnerTubeLogger { event ->
        val details = if (event.details.isEmpty()) "" else
            event.details.entries.joinToString(prefix = " [", postfix = "]") { "${it.key}=${it.value}" }
        val line = "ITX ${event.tag}: ${event.message}$details"
        if (event.level == InnerTubeLogLevel.INFO || event.level == InnerTubeLogLevel.DEBUG) Log.d(TAG, line)
        else Log.w(TAG, line)
    }

    /** Player-config cache kept in memory for now; persisted in a later phase. */
    private val repository = object : PlayerConfigRepository {
        override val enabled: Boolean = true
        override val sourceUrl: String = PLAYER_CONFIG_URL
        override val defaultSourceUrl: String = PLAYER_CONFIG_URL
        override var cachedJson: String = ""
        override var cachedAtMs: Long = 0L
        override var cachedSourceUrl: String = ""
        override var cachedEtag: String = ""
    }

    private val innerTube = InnerTube(http, logger = logger)
    private val remoteStore = RemotePlayerConfigStore(http, repository, logger)
    private val cipherService = YouTubeCipherService(http, remoteStore, logger)
    private val extractor = InnerTubeExtractor(
        configParser = YtConfigParserImpl(http, innerTube, remoteStore, logger, cipherService = cipherService),
        cipherService = cipherService,
        innerTube = innerTube,
        logger = logger,
    )

    /** Recently resolved streams; googlevideo URLs stay valid for hours. */
    private val recent = mutableMapOf<String, Pair<Stream, Long>>()
    private val recentLock = Mutex()

    /** Clients refused a track mid-playback, by videoId, and until when. */
    private val excluded = mutableMapOf<String, MutableMap<String, Long>>()

    suspend fun resolve(videoId: String): Stream {
        recentLock.withLock {
            recent[videoId]?.let { (stream, at) ->
                if (elapsedMillis() - at < URL_TTL_MS) return stream
            }
        }
        val start = elapsedMillis()
        val stream = innerTubeXStream(videoId)
            ?: throw IllegalStateException("No playable stream found for this track")
        Log.d(TAG, "TIMING $videoId total resolve: ${elapsedMillis() - start}ms")
        recentLock.withLock { recent[videoId] = stream to elapsedMillis() }
        return stream
    }

    /** Called when the player gets 403/404/410 mid-track: forget the URL, skip its client. */
    suspend fun onPlaybackRefused(stream: Stream) {
        recentLock.withLock {
            recent.remove(stream.videoId)
            excluded.getOrPut(stream.videoId) { mutableMapOf() }[stream.profileId] = elapsedMillis() + EXCLUDE_MS
        }
        runCatching { cipherService.refreshAfterStreamRejection() }
    }

    private suspend fun innerTubeXStream(videoId: String): Stream? {
        val skip = mutableSetOf<String>()
        repeat(INNERTUBEX_ATTEMPTS) {
            val found = try {
                extract(videoId, skip)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "InnerTubeX failed for $videoId: ${e::class.simpleName}: ${e.message}")
                return null
            } ?: return null
            val verdict = probe(found)
            if (verdict == Probe.OK) {
                Log.d(TAG, "resolved $videoId via InnerTubeX ${found.profileId} @ ${found.kbps}kbps")
                return found
            }
            Log.w(TAG, "InnerTubeX ${found.profileId} minted an unusable URL for $videoId: $verdict")
            skip += found.profileId
        }
        return null
    }

    private suspend fun extract(videoId: String, skipClients: Set<String>): Stream? {
        innerTube.cookie = Innertube.cookie
        innerTube.visitorData = Innertube.ensureVisitorData()
        innerTube.locale = YouTubeLocale(gl = "US", hl = Innertube.currentLanguage)
        val stream = extractor.extract(
            videoId = videoId,
            hints = ContentHints().withStreamCapabilities(allowHls = false, allowSabr = false, allowBoundedRange = true),
            excludedClients = excludedFor(videoId) + skipClients,
            audioQuality = AudioQuality.MP4,
            clientPlaybackNonce = generateClientPlaybackNonce(),
        ) ?: return null
        check(stream.sabrBootstrap == null) { "SABR is not supported by this playback engine" }
        val mime = stream.mimeType.orEmpty()
        return Stream(
            videoId = videoId,
            url = stream.audioUrl,
            kbps = (stream.bitrate ?: 0) / 1000,
            mimeType = if (stream.codecs.isNullOrBlank()) mime else "$mime; codecs=\"${stream.codecs}\"",
            contentLength = stream.contentLengthBytes ?: Url(stream.audioUrl).parameters["clen"]?.toLongOrNull(),
            headers = stream.headers,
            chunkBytes = stream.rangeChunkSizeBytes.takeIf { it > 0 } ?: DEFAULT_CHUNK_BYTES,
            loudnessDb = stream.loudnessDb,
            profileId = stream.profileId,
        )
    }

    private suspend fun excludedFor(videoId: String): Set<String> = recentLock.withLock {
        val entries = excluded[videoId] ?: return@withLock emptySet()
        val now = elapsedMillis()
        entries.entries.removeAll { it.value <= now }
        entries.keys.toSet()
    }

    private enum class Probe { OK, REFUSED, UNREACHABLE }

    /**
     * Read a real-sized range of the URL before trusting it — ported from the
     * Android resolver, which explains the reasoning at length: some clients'
     * URLs serve small or early ranges and refuse the rest, so the probe asks
     * for a full chunk past the first megabyte and insists the bytes arrive.
     */
    private suspend fun probe(stream: Stream): Probe {
        val length = stream.contentLength
        val start = if (length != null && length > AUTH_BOUNDARY_BYTES + PROBE_READ_BYTES) AUTH_BOUNDARY_BYTES else 0L
        val end = minOf(start + stream.chunkBytes, length ?: Long.MAX_VALUE) - 1
        return try {
            val response: HttpResponse = http.get(stream.url) {
                header("Range", "bytes=$start-$end")
                stream.headers.forEach { (name, value) -> header(name, value) }
            }
            val code = response.status.value
            when {
                code in REFUSAL_CODES -> Probe.REFUSED
                code !in 200..299 && code != 416 -> Probe.UNREACHABLE
                response.headers["Content-Type"]?.startsWith("audio/") != true -> Probe.REFUSED
                response.bodyAsChannel().readRemaining(PROBE_READ_BYTES).readByteArray().size < PROBE_READ_BYTES &&
                    end - start + 1 >= PROBE_READ_BYTES -> Probe.UNREACHABLE
                else -> Probe.OK
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "probe failed: ${e.message}")
            Probe.UNREACHABLE
        }
    }

    private val REFUSAL_CODES = setOf(403, 404, 410)
    private const val INNERTUBEX_ATTEMPTS = 3
    private const val AUTH_BOUNDARY_BYTES = 1024L * 1024
    private const val PROBE_READ_BYTES = 16L * 1024
    private const val DEFAULT_CHUNK_BYTES = 1024L * 1024
    private const val URL_TTL_MS = 60L * 60 * 1000
    private const val EXCLUDE_MS = 10L * 60 * 1000
    private const val PLAYER_CONFIG_URL =
        "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json"
}
