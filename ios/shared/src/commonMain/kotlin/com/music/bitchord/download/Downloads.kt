package com.music.bitchord.download

// Ported from the Android Downloads.kt with iOS changes:
//  - No DownloadService: a foreground service is Android-only, so the queue is
//    drained here by WORKERS coroutines, with an iOS background-task grant so a
//    batch can finish if the app is sent to the background.
//  - Files go to Application Support (see DownloadStore), recorded by relative
//    path, instead of MediaStore's Music folder.
//  - YouTube only (AAC in MP4, which AVPlayer plays). The configured-source
//    route, OfflineDash/OfflineHls and the Wi-Fi-only setting come with
//    pluggable sources and Settings in P7.
//  - No retagging: lyrics go to a `.lrc` sidecar (read back by EmbeddedLyrics)
//    and the cover to a `.jpg` beside the track.

import android.content.Context
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.model.Song
import com.music.bitchord.platform.FileSystem
import com.music.bitchord.platform.KeyValueStore
import com.music.bitchord.platform.PlatformLock
import com.music.bitchord.platform.beginBackgroundWork
import com.music.bitchord.platform.endBackgroundWork
import com.music.bitchord.platform.epochMillis
import com.music.bitchord.platform.withLock
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Where a track is between "not on this device" and "on it". */
sealed interface DownloadState {

    /** Accepted, waiting for the one in front of it. */
    data object Queued : DownloadState

    /** [fraction] is 0f until the length is known, which is the first thing asked for. */
    data class Running(val fraction: Float) : DownloadState

    data class Failed(val reason: String) : DownloadState
}

/**
 * The download queue, and the record of what came out of it.
 *
 *  - [active] is what is happening now — queued, running, just failed. It lives
 *    in memory and is empty on a cold start, because a download interrupted by
 *    the process dying did not happen.
 *  - [saved] is what exists on disk, keyed by videoId and remembered across
 *    launches: videoId to the file's path relative to Application Support.
 *    Every read that matters verifies the file before trusting the record.
 */
object Downloads {

    private const val TAG = "BitChord"
    private const val KEY_SAVED = "downloaded_tracks"
    private const val KEY_SAVED_METADATA = "downloaded_tracks_metadata"
    private const val KEY_SAVED_COLLECTIONS = "downloaded_collections"

    /** Tracks fetched at once. Fewer than Android's four: a phone's radio, and no lossless lookups to overlap. */
    private const val WORKERS = 3

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private val metadataSerializer = MapSerializer(String.serializer(), SavedSongMetadata.serializer())
    private val collectionSerializer = MapSerializer(String.serializer(), SavedCollection.serializer())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val artworkHttp by lazy { HttpClient { expectSuccess = false } }

    private val _active = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val active: StateFlow<Map<String, DownloadState>> = _active.asStateFlow()

    /** Ids asked for as part of a release's own download tap, by the browseId that was tapped. */
    private val _requested = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val requested: StateFlow<Map<String, Set<String>>> = _requested.asStateFlow()

    /** Record that [videoIds] were asked for as [browseId]'s own release. */
    fun markRequested(browseId: String, videoIds: Collection<String>) {
        if (videoIds.isEmpty()) return
        _requested.update { it + (browseId to (it[browseId].orEmpty() + videoIds)) }
    }

    private val _saved = MutableStateFlow(
        load(KEY_SAVED) { json.decodeFromString(serializer, it) },
    )

    /** videoId to the path of its file, relative to Application Support. */
    val saved: StateFlow<Map<String, String>> = _saved.asStateFlow()

    private val _savedMetadata = MutableStateFlow(
        load(KEY_SAVED_METADATA) { json.decodeFromString(metadataSerializer, it) },
    )

    private val _collections = MutableStateFlow(
        load(KEY_SAVED_COLLECTIONS) { json.decodeFromString(collectionSerializer, it) },
    )

    /** The releases that were downloaded *as* releases, by the id they were asked for under. */
    val collections: StateFlow<Map<String, SavedCollection>> = _collections.asStateFlow()

    private fun <T> load(key: String, decode: (String) -> Map<String, T>): Map<String, T> =
        runCatching { decode(KeyValueStore.getString(key) ?: "{}") }.getOrDefault(emptyMap())

    /** Waiting, in the order asked for. Guarded by [lock]. */
    private val pending = LinkedHashMap<String, Song>()

    /** Taken off the queue and not finished, to the job fetching each. Guarded by [lock]. */
    private val running = LinkedHashMap<String, Job?>()

    private val lock = PlatformLock()
    private var workers = 0
    private var backgroundToken = -1L

    /** Android loads the record here; on iOS it loads on first use. Kept for API parity. */
    fun init(context: Context) = Unit

    // ---- Asking -------------------------------------------------------------

    /**
     * Queue [song], and make sure something is draining the queue. A track
     * already saved, queued or running is left alone rather than doubled.
     *
     * @param from what release this track was asked for as part of.
     */
    fun enqueue(context: Context, song: Song, from: String? = null) {
        val id = song.videoId
        if (id.startsWith(LOCAL_PREFIX)) return
        if (id in _saved.value && verifiedSavedUri(id) != null) return
        val startWorkers = lock.withLock {
            if (id in pending || id in running) return
            pending[id] = song
            val missing = (WORKERS - workers).coerceAtLeast(0)
            workers += missing
            if (missing > 0 && backgroundToken < 0) backgroundToken = beginBackgroundWork("BitChord downloads")
            missing
        }
        _active.update { it + (id to DownloadState.Queued) }
        DownloadSession.queued(song, from)
        repeat(startWorkers) { scope.launch { work() } }
    }

    /** Queue every track of a release and remember it as one. */
    fun enqueueAll(context: Context, target: DownloadTarget, songs: List<Song>) {
        val wanted = songs.filterNot { it.videoId.startsWith(LOCAL_PREFIX) }
        if (wanted.isEmpty()) return
        rememberCollection(target, wanted)
        markRequested(target.id, wanted.map { it.videoId })
        wanted.forEach { enqueue(context, it, from = target.title) }
    }

    /** One worker: take tracks until the queue is empty. */
    private suspend fun work() {
        try {
            while (true) {
                val song = takeNext() ?: break
                val job = scope.launch { runDownload(song) }
                onRunning(song.videoId, job)
                job.join()
                onIdle(song.videoId)
            }
        } finally {
            val token = lock.withLock {
                workers--
                // The last worker out ends the background grant, unless more was queued meanwhile.
                if (workers == 0 && pending.isEmpty()) backgroundToken.also { backgroundToken = -1L } else -1L
            }
            if (token >= 0) endBackgroundWork(token)
        }
    }

    /** Drop [videoId] from the queue, or stop it if it is running. */
    fun cancel(videoId: String) {
        val job = lock.withLock {
            pending.remove(videoId)
            if (videoId !in running) return@withLock null
            running.remove(videoId)
        }
        job?.cancel()
        clear(videoId)
        DownloadSession.forget(videoId)
    }

    // ---- The record ---------------------------------------------------------

    /** The `file://` URI saved for [videoId], or null — pruning the record if the file is gone. */
    suspend fun savedUri(context: Context, videoId: String): String? = verifiedSavedUri(videoId)

    /** As [savedUri], synchronous: a single `stat`, cheap enough to call while building a queue. */
    fun verifiedSavedUri(videoId: String): String? {
        val recorded = _saved.value[videoId] ?: return null
        if (DownloadStore.exists(recorded)) return DownloadStore.uriFor(recorded)
        Log.d(TAG, "$videoId was downloaded but the file is gone; forgetting it")
        forget(videoId)
        return null
    }

    fun isDownloaded(videoId: String): Boolean = videoId in _saved.value

    /** Delete the file saved for [videoId] (with its cover and lyrics) and forget it. */
    suspend fun delete(context: Context, videoId: String): Boolean {
        val relative = _saved.value[videoId] ?: return false
        // A music video and its catalogue track can share one file; only delete it when nothing else names it.
        val others = _saved.value.filter { (id, path) -> id != videoId && path == relative }.keys
        forget(videoId)
        others.forEach(::forget)
        val deleted = DownloadStore.delete(relative)
        DownloadStore.delete("$relative.lrc")
        DownloadStore.delete(coverFor(relative))
        return deleted
    }

    private fun forget(videoId: String) {
        record(saved = { it - videoId }, meta = { it - videoId })
    }

    /** Drop the record for [videoId] because a read of its file has just failed. */
    fun forgetMissing(videoId: String) {
        if (videoId !in _saved.value) return
        Log.d(TAG, "$videoId could not be opened; forgetting the download")
        forget(videoId)
    }

    // ---- Releases -----------------------------------------------------------

    /** Remember that [songs] were asked for as one release rather than one at a time. */
    fun rememberCollection(target: DownloadTarget, songs: List<Song>) {
        if (songs.isEmpty()) return
        val ids = songs.map { it.videoId }.distinct()
        recordCollections { current ->
            val existing = current[target.id]
            val record = SavedCollection(
                id = target.id,
                title = target.title,
                subtitle = target.subtitle,
                thumbnailUrl = target.thumbnailUrl ?: existing?.thumbnailUrl,
                playlist = target.playlist,
                videoIds = (ids + (existing?.videoIds ?: emptyList())).distinct(),
            )
            current + (target.id to record)
        }
    }

    /** Drop a release from the record without touching the files under it. */
    fun forgetCollection(id: String) {
        if (id !in _collections.value) return
        recordCollections { it - id }
    }

    /** Delete every file downloaded for release [id] and drop the record of it. */
    suspend fun deleteCollection(context: Context, id: String): Boolean {
        val record = _collections.value[id] ?: return false
        var any = false
        record.videoIds.forEach { videoId -> if (delete(context, videoId)) any = true }
        forgetCollection(id)
        return any
    }

    const val PLAYLIST_PREFIX = "local:playlist:"

    /** Ids of tracks that are not YouTube's (imported files) start with this. */
    const val LOCAL_PREFIX = "local:"

    fun pageIdFor(id: String): String = PLAYLIST_PREFIX + id

    fun recordIdOf(browseId: String): String? =
        browseId.removePrefix(PLAYLIST_PREFIX).takeIf { it != browseId && it.isNotEmpty() }

    /** Releases with at least one track on disk, in name order. */
    fun savedPlaylists(onDisk: Map<String, String> = _saved.value): List<SavedCollection> {
        if (_collections.value.isEmpty()) return emptyList()
        return _collections.value.values
            .filter { record -> record.videoIds.any { it in onDisk } }
            .sortedBy { it.title.lowercase() }
    }

    /** The releases at least one of [songs] belongs to, each with its own tracks picked out of that list. */
    fun collectionsAmong(songs: List<Song>): List<DownloadedCollection> {
        if (songs.isEmpty() || _collections.value.isEmpty()) return emptyList()
        val byId = songs.associateBy { it.videoId }
        val byUri = songs.mapNotNull { song -> song.localUri?.let { it to song } }.toMap()
        val uris = _saved.value
        return _collections.value.values
            .mapNotNull { record ->
                val tracks = record.videoIds
                    .mapNotNull { id -> byId[id] ?: uris[id]?.let { byUri[DownloadStore.uriFor(it)] } }
                    .distinctBy { it.localUri ?: it.videoId }
                if (tracks.isEmpty()) {
                    null
                } else {
                    DownloadedCollection(
                        id = record.id,
                        title = record.title,
                        subtitle = record.subtitle,
                        thumbnailUrl = record.thumbnailUrl ?: tracks.firstNotNullOfOrNull { it.thumbnailUrl },
                        playlist = record.playlist,
                        songs = tracks,
                    )
                }
            }
            .sortedBy { it.title.lowercase() }
    }

    private val collectionLock = PlatformLock()

    private fun recordCollections(update: (Map<String, SavedCollection>) -> Map<String, SavedCollection>) {
        collectionLock.withLock {
            val map = update(_collections.value)
            _collections.value = map
            KeyValueStore.putString(KEY_SAVED_COLLECTIONS, json.encodeToString(collectionSerializer, map))
        }
    }

    /**
     * Record one file under every id it could be asked about ([asked] is the
     * row tapped, [fetched] what was downloaded; on iOS these are the same
     * track until catalogue matching arrives, but the record keeps both shapes).
     */
    private fun remember(asked: Song, fetched: Song, relative: String, downloadFormat: String?) {
        val ids = setOf(asked.videoId, fetched.videoId)
        val existingAdded = ids.firstNotNullOfOrNull { _savedMetadata.value[it]?.dateAddedSeconds }
        val dateAddedSeconds = existingAdded ?: (epochMillis() / 1_000)
        val album = fetched.albumName?.takeIf { it.isNotBlank() } ?: asked.albumName?.takeIf { it.isNotBlank() }
        fun meta(song: Song) = SavedSongMetadata(
            videoId = song.videoId,
            title = song.title,
            artist = song.artist,
            thumbnailUrl = song.thumbnailUrl,
            durationText = song.durationText,
            albumName = album,
            uri = relative,
            downloadFormat = downloadFormat,
            dateAddedSeconds = dateAddedSeconds,
        )
        record(
            saved = { it + ids.associateWith { relative } },
            meta = { it + mapOf(asked.videoId to meta(asked), fetched.videoId to meta(fetched)) },
        )
    }

    private val recordLock = PlatformLock()

    /** Apply [saved] and [meta] atomically and persist the result; several downloads finish at once. */
    private fun record(
        saved: (Map<String, String>) -> Map<String, String>,
        meta: (Map<String, SavedSongMetadata>) -> Map<String, SavedSongMetadata>,
    ) {
        recordLock.withLock {
            val savedMap = _saved.updateAndGet(saved)
            val metaMap = _savedMetadata.updateAndGet(meta)
            KeyValueStore.putString(KEY_SAVED, json.encodeToString(serializer, savedMap))
            KeyValueStore.putString(KEY_SAVED_METADATA, json.encodeToString(metadataSerializer, metaMap))
        }
    }

    /** All downloaded songs whose files still exist, newest first. */
    suspend fun getDownloadedSongs(context: Context): List<Song> {
        val result = mutableListOf<Song>()
        val seen = mutableSetOf<String>()
        for ((_, meta) in _savedMetadata.value) {
            // A music video and its catalogue track are two ids for one file; list it once.
            if (!seen.add(meta.uri)) continue
            if (DownloadStore.exists(meta.uri)) {
                result.add(meta.toSong())
            } else {
                _savedMetadata.value.filterValues { it.uri == meta.uri }.keys.forEach(::forget)
            }
        }
        return result.sortedByDescending { it.localDateAddedSeconds ?: 0L }
    }

    /** The available files for one downloaded collection, in its saved running order. */
    suspend fun getCollectionSongs(context: Context, collectionId: String): List<Song> {
        val record = _collections.value[collectionId] ?: return emptyList()
        val metadata = _savedMetadata.value
        val seen = HashSet<String>()
        return record.videoIds.mapNotNull { videoId ->
            val meta = metadata[videoId] ?: return@mapNotNull null
            if (!seen.add(meta.uri)) return@mapNotNull null
            if (!DownloadStore.exists(meta.uri)) {
                metadata.filterValues { it.uri == meta.uri }.keys.forEach(::forget)
                return@mapNotNull null
            }
            meta.toSong()
        }
    }

    private fun SavedSongMetadata.toSong(): Song {
        val cover = coverFor(uri)
        return Song(
            videoId = videoId,
            title = title,
            artist = artist,
            // Plain absolute path: Coil reads it as a file, and Swift's lock-screen artwork does too.
            thumbnailUrl = if (DownloadStore.exists(cover)) DownloadStore.absolute(cover) else thumbnailUrl,
            durationText = durationText,
            albumName = albumName,
            localUri = DownloadStore.uriFor(uri),
            localPath = DownloadStore.absolute(uri),
            downloadFormat = downloadFormat,
            localDateAddedSeconds = dateAddedSeconds,
        )
    }

    /** The downloaded version of [song] if there is one, so a played row uses its file and offline cover. */
    fun offlineVersionOf(song: Song): Song? {
        val meta = _savedMetadata.value[song.videoId] ?: return null
        if (!DownloadStore.exists(meta.uri)) {
            forget(song.videoId)
            return null
        }
        val local = meta.toSong()
        return song.copy(
            localUri = local.localUri,
            localPath = local.localPath,
            downloadFormat = local.downloadFormat,
            thumbnailUrl = if (local.thumbnailUrl?.startsWith("/") == true) local.thumbnailUrl else song.thumbnailUrl,
        )
    }

    private fun coverFor(relative: String): String = relative.substringBeforeLast('.') + ".jpg"

    // ---- Driving the queue --------------------------------------------------

    internal fun takeNext(): Song? = lock.withLock {
        val entry = pending.entries.firstOrNull() ?: return@withLock null
        pending.remove(entry.key)
        running[entry.key] = null
        entry.value
    }

    internal fun onRunning(videoId: String, job: Job) {
        val cancelled = lock.withLock {
            if (videoId !in running) return@withLock true
            running[videoId] = job
            false
        }
        if (cancelled) job.cancel()
    }

    internal fun onIdle(videoId: String) {
        lock.withLock { running.remove(videoId) }
    }

    internal fun busy(): Boolean = lock.withLock { pending.isNotEmpty() || running.isNotEmpty() }

    /** Fetch one track, start to finish. */
    private suspend fun runDownload(song: Song) {
        val id = song.videoId
        _active.update { it + (id to DownloadState.Running(0f)) }
        DownloadSession.running(id, 0f)
        try {
            transfer(song)
        } catch (e: CancellationException) {
            clear(id)
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "download failed for $id: ${e.message}")
            fail(id, e.friendly())
        }
    }

    private suspend fun transfer(song: Song) {
        val id = song.videoId
        val relative = DownloadStore.relativeFor(id, "m4a")
        // Already there from a run whose record was lost — adopt it rather than fetching again.
        if (DownloadStore.exists(relative)) {
            remember(song, song, relative, null)
            DownloadSession.done(id)
            clear(id)
            return
        }

        val stream = StreamResolver.resolve(id)
        Log.d(TAG, "downloading $id (${stream.kbps}kbps ${stream.mimeType} via ${stream.profileId})")
        val sink = DownloadStore.Pending(relative)
        try {
            coroutineScope {
                // Raced against the transfer so neither waits on the other.
                val lyrics = async { LyricsTag.forTrack(song) }
                val cover = async { saveCover(song, coverFor(relative)) }
                Downloader.fetch(id, stream, sink) { written, total ->
                    val fraction = written.toFloat() / total
                    _active.update { it + (id to DownloadState.Running(fraction)) }
                    DownloadSession.running(id, fraction)
                }
                val words = withTimeoutOrNull(LYRICS_WAIT_MS) { lyrics.await() }
                lyrics.cancel()
                words?.let { FileSystem.write(DownloadStore.absolute("$relative.lrc"), (it.enhanced ?: it.plain).encodeToByteArray()) }
                withTimeoutOrNull(COVER_WAIT_MS) { cover.await() }
                cover.cancel()
            }
            sink.commit()
        } catch (e: Throwable) {
            sink.abort()
            throw e
        }
        remember(song, song, relative, youtubeDownloadBadge("m4a", stream.kbps))
        DownloadSession.done(id)
        clear(id)
        Log.d(TAG, "saved $id")
    }

    /** The full-size cover beside the track, so Downloads shows artwork with no connection. */
    private suspend fun saveCover(song: Song, relative: String) {
        val url = song.thumbnailUrl?.replace(Regex("=w\\d+-h\\d+"), "=w720-h720") ?: return
        try {
            val bytes = artworkHttp.get(url).readRawBytes()
            if (bytes.size > 512) FileSystem.write(DownloadStore.absolute(relative), bytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "no cover saved for ${song.videoId}: ${e.message}")
        }
    }

    private fun clear(videoId: String) {
        _active.update { it - videoId }
    }

    private fun fail(videoId: String, reason: String) {
        _active.update { it + (videoId to DownloadState.Failed(reason)) }
        DownloadSession.failed(videoId, reason)
    }

    private fun Exception.friendly(): String = when {
        (this is IllegalStateException || this is IllegalArgumentException) && !message.isNullOrBlank() -> message!!
        else -> "Download failed — check your connection"
    }

    /** Dropped when the sheet is reopened; a failure is worth showing once. */
    fun dismissFailure(videoId: String) {
        if (_active.value[videoId] is DownloadState.Failed) clear(videoId)
    }

    private const val LYRICS_WAIT_MS = 30_000L
    private const val COVER_WAIT_MS = 15_000L
}

@kotlinx.serialization.Serializable
internal data class SavedSongMetadata(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String? = null,
    val durationText: String? = null,
    val albumName: String? = null,
    /** iOS: path relative to Application Support (see [DownloadStore]), not a URI. */
    val uri: String,
    val downloadFormat: String? = null,
    val dateAddedSeconds: Long? = null,
)

/** Codec and bitrate shown in the Downloads list, e.g. "AAC · 128 kbps". */
internal fun youtubeDownloadBadge(extension: String, kbps: Int): String? {
    if (kbps <= 0) return null
    val codec = if (extension.equals("m4a", ignoreCase = true)) "AAC" else "OPUS"
    return "$codec · $kbps kbps"
}

/** What a batch download was asked for as a whole. */
data class DownloadTarget(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val thumbnailUrl: String? = null,
    val playlist: Boolean = false,
)

/** A release the record says was downloaded whole. */
@kotlinx.serialization.Serializable
data class SavedCollection(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val thumbnailUrl: String? = null,
    val playlist: Boolean = false,
    val videoIds: List<String> = emptyList(),
)

/** A [SavedCollection] with its surviving tracks attached, ready to draw. */
data class DownloadedCollection(
    val id: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val playlist: Boolean,
    val songs: List<Song>,
)
