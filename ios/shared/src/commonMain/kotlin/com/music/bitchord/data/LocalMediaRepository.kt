package com.music.bitchord.data

// ios-native: the iOS take on the Android LocalMediaRepository. Android scans
// MediaStore for music already on the phone; iOS gives apps no shared music
// folder, so the user imports files from the Files app (iCloud Drive, On My
// iPhone, USB drives, SMB shares mounted in Files…). The Swift side presents
// the picker, copies each file into the app and reads its tags with
// AVFoundation; this keeps the record and turns it into playable Songs.

import android.content.Context
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.model.Song
import com.music.bitchord.download.DownloadStore
import com.music.bitchord.platform.FileSystem
import com.music.bitchord.platform.KeyValueStore
import com.music.bitchord.platform.PlatformLock
import com.music.bitchord.platform.epochMillis
import com.music.bitchord.platform.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Presents the Files-app picker. Implemented in Swift (iosApp/LocalFilePicker.swift). */
interface LocalFilePicker {
    /**
     * Let the user pick audio files, copy each one into [directory] (an
     * absolute path) under a unique name, read its tags, and report the
     * results to [listener] on the main thread.
     */
    fun pickAudioFiles(directory: String, listener: LocalImportListener)
}

interface LocalImportListener {
    fun onImported(files: List<ImportedAudio>)
    fun onImportFailed(message: String)
}

/** One imported file, as the Swift picker read it. Paths are absolute. */
class ImportedAudio(
    val path: String,
    val originalName: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    /** The embedded cover, already written out as an image file; null when there was none. */
    val artworkPath: String?,
    /** Embedded lyrics text (any format), when the file had some. */
    val lyrics: String?,
)

object LocalMediaRepository {

    private const val TAG = "BitChord"
    private const val KEY = "imported_tracks"

    @Serializable
    private data class Track(
        val id: String,
        /** Relative to Application Support, like downloads, so it survives a reinstall's new container path. */
        val file: String,
        val title: String,
        val artist: String,
        val album: String? = null,
        val durationMs: Long = 0,
        val artwork: String? = null,
        val addedSeconds: Long = 0,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Track.serializer())
    private val lock = PlatformLock()

    private val _tracks = MutableStateFlow(
        runCatching { json.decodeFromString(serializer, KeyValueStore.getString(KEY) ?: "[]") }.getOrDefault(emptyList()),
    )

    /** Set by Swift at launch (see MainViewController). */
    var picker: LocalFilePicker? = null

    private val _songs = MutableStateFlow(_tracks.value.map { it.toSong() })

    /** Imported tracks, newest first. */
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    /** Always true: the Files picker needs no permission prompt. Kept for API parity with Android. */
    fun hasStoragePermission(context: Context): Boolean = true

    /** Imported tracks whose files still exist. */
    suspend fun getLocalMusic(context: Context): List<Song> {
        prune()
        return _songs.value
    }

    /** Open the Files picker; [onResult] gets a message to show (how many were added, or why not). */
    fun importFromFiles(onResult: (String) -> Unit) {
        val picker = picker ?: return onResult("Importing is not available")
        val directory = DownloadStore.absolute(DownloadStore.IMPORTED_DIR)
        FileSystem.mkdirs(directory)
        _importing.value = true
        picker.pickAudioFiles(directory, object : LocalImportListener {
            override fun onImported(files: List<ImportedAudio>) {
                _importing.value = false
                if (files.isEmpty()) return
                add(files)
                onResult(if (files.size == 1) "Added 1 song" else "Added ${files.size} songs")
            }

            override fun onImportFailed(message: String) {
                _importing.value = false
                Log.w(TAG, "import failed: $message")
                onResult(message)
            }
        })
    }

    private fun add(files: List<ImportedAudio>) {
        val root = DownloadStore.root() + "/"
        val now = epochMillis() / 1000
        val added = files.mapNotNull { file ->
            val relative = file.path.removePrefix(root).takeIf { it != file.path } ?: return@mapNotNull null
            // Lyrics that are actually timed go beside the file, where EmbeddedLyrics looks first.
            file.lyrics?.takeIf { TIMESTAMP.containsMatchIn(it) }?.let { FileSystem.write("${file.path}.lrc", it.encodeToByteArray()) }
            val stem = file.originalName.substringBeforeLast('.')
            Track(
                id = "local:" + relative.substringAfterLast('/').substringBeforeLast('.'),
                file = relative,
                title = file.title?.takeIf { it.isNotBlank() } ?: stem,
                artist = file.artist?.takeIf { it.isNotBlank() } ?: "",
                album = file.album?.takeIf { it.isNotBlank() },
                durationMs = file.durationMs,
                artwork = file.artworkPath?.removePrefix(root),
                addedSeconds = now,
            )
        }
        update { added + it }
    }

    /** Delete the imported copy (the original in Files is untouched). */
    fun delete(song: Song) {
        val track = _tracks.value.firstOrNull { it.id == song.videoId } ?: return
        DownloadStore.delete(track.file)
        DownloadStore.delete(track.file + ".lrc")
        track.artwork?.let(DownloadStore::delete)
        update { list -> list.filterNot { it.id == track.id } }
    }

    private fun prune() {
        val missing = _tracks.value.filterNot { DownloadStore.exists(it.file) }
        if (missing.isNotEmpty()) update { list -> list.filterNot { it in missing } }
    }

    private fun update(change: (List<Track>) -> List<Track>) {
        lock.withLock {
            val next = change(_tracks.value)
            _tracks.value = next
            _songs.value = next.map { it.toSong() }
            KeyValueStore.putString(KEY, json.encodeToString(serializer, next))
        }
    }

    private fun Track.toSong(): Song = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = artwork?.let(DownloadStore::absolute),
        durationText = durationMs.takeIf { it > 0 }?.let(::formatDuration),
        albumName = album,
        localUri = DownloadStore.uriFor(file),
        localPath = DownloadStore.absolute(file),
        localDateAddedSeconds = addedSeconds,
    )

    private fun formatDuration(ms: Long): String {
        val total = ms / 1000
        return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    }

    private val TIMESTAMP = Regex("""\[\d{1,2}:\d{2}""")
}
