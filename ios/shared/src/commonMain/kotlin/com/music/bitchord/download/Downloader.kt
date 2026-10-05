package com.music.bitchord.download

// ios-native: the googlevideo half of the Android Downloader, on ktor instead
// of OkHttp. The configured-source path (fetchDirect) arrives with pluggable
// sources in P7.

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.innertube.StreamResolver
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.readByteArray

/**
 * Pulls a resolved stream onto disk.
 *
 * As on Android, two things matter:
 *
 *  - **Bounded ranges, not one long GET.** googlevideo paces a continuous
 *    response down to roughly playback speed; the same bytes asked for as
 *    ranges arrive at line rate.
 *  - **The minting client's own headers.** googlevideo compares the identity
 *    baked into the URL against the request for the bytes, so every range
 *    carries [StreamResolver.Stream.headers].
 */
object Downloader {

    private const val TAG = "BitChord"

    /** Largest range asked for at once, further capped by what the client allows. */
    private const val CHUNK_BYTES = 2L * 1024 * 1024

    /** How much is read off the wire before it is written and progress is reported. */
    private const val SLICE_BYTES = 256L * 1024

    private val http = HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 30_000
        }
        expectSuccess = false
    }

    /**
     * Fetch all of [stream] into [sink].
     *
     * @param onProgress called as bytes land, with the running total and the
     *   full size. Never called with a total of zero.
     * @return how many bytes were written.
     */
    suspend fun fetch(
        videoId: String,
        stream: StreamResolver.Stream,
        sink: DownloadStore.Pending,
        onProgress: (written: Long, total: Long) -> Unit,
    ): Long {
        var current = stream
        val total = current.contentLength?.takeIf { it > 0 } ?: contentLength(current)
            ?: error("Track unavailable: no length to fetch")

        var position = 0L
        var reresolved = false

        while (position < total) {
            currentCoroutineContext().ensureActive()
            val length = minOf(CHUNK_BYTES, current.chunkBytes.takeIf { it > 0 } ?: CHUNK_BYTES, total - position)
            val from = position
            var refusal = 0

            http.prepareGet(current.url) {
                header("Range", "bytes=$from-${from + length - 1}")
                current.headers.forEach { (name, value) -> header(name, value) }
            }.execute { response ->
                val code = response.status.value
                if (code in REFUSAL_CODES) {
                    refusal = code
                    return@execute
                }
                if (code !in 200..299) error("Download failed (HTTP $code)")
                val channel = response.bodyAsChannel()
                var readForChunk = 0L
                while (readForChunk < length) {
                    currentCoroutineContext().ensureActive()
                    val bytes = channel.readRemaining(minOf(SLICE_BYTES, length - readForChunk)).readByteArray()
                    if (bytes.isEmpty()) break
                    sink.append(bytes)
                    readForChunk += bytes.size
                    position += bytes.size
                    onProgress(position, total)
                }
                // A short range is fine (the next pass asks for the rest); an empty one would loop forever.
                if (readForChunk == 0L) error("Download stalled at ${position}B")
            }

            // A URL that served its opening and then refuses means the identity
            // behind it was stood down mid-download: mint a new one, once.
            if (refusal != 0) {
                StreamResolver.onPlaybackRefused(current)
                if (reresolved) error("Download refused after ${position}B (HTTP $refusal)")
                reresolved = true
                Log.w(TAG, "re-resolving $videoId after HTTP $refusal at $position")
                current = StreamResolver.resolve(videoId)
                // A different client can answer with a different rendition, and
                // resuming one stream into the middle of another is unplayable.
                val newTotal = current.contentLength ?: contentLength(current)
                if (newTotal != total) error("The stream changed mid-download — try again")
            }
        }
        return position
    }

    /** The total size from a one-byte range's `Content-Range`, for URLs without `clen`. */
    private suspend fun contentLength(stream: StreamResolver.Stream): Long? = try {
        http.prepareGet(stream.url) {
            header("Range", "bytes=0-0")
            stream.headers.forEach { (name, value) -> header(name, value) }
        }.execute { response: HttpResponse ->
            response.headers["Content-Range"]?.substringAfter('/', "")?.toLongOrNull()?.takeIf { it > 0 }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "could not measure the track: ${e.message}")
        null
    }

    private val REFUSAL_CODES = setOf(403, 404, 410)
}
