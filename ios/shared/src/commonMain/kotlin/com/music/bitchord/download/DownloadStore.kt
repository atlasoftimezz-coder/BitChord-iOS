package com.music.bitchord.download

// ios-native: the iOS counterpart of the Android DownloadStore. Android saves
// into the shared Music folder through MediaStore; an iOS app has no shared
// file store, so downloads live in the app's own Application Support directory
// (not purged by iOS, unlike Caches) and are played and listed from there.

import com.music.bitchord.platform.FileSystem
import io.ktor.http.decodeURLPart

/**
 * Where downloaded files live and how they are named.
 *
 * Records store paths *relative* to Application Support, never absolute ones:
 * the app's container path changes when it is reinstalled or updated
 * (every 7-day sideload renewal), and an absolute path saved before that would
 * point into a container that no longer exists.
 */
object DownloadStore {

    /** Relative directory for downloaded tracks, their covers and lyric sidecars. */
    const val DOWNLOADS_DIR = "Downloads"

    /** Relative directory for audio imported from the Files app. */
    const val IMPORTED_DIR = "Imported"

    /** Application Support, the root every relative path here is resolved against. */
    fun root(): String = FileSystem.filesDirectory()

    fun absolute(relative: String): String = root() + "/" + relative.trimStart('/')

    fun exists(relative: String): Boolean = FileSystem.isFile(absolute(relative))

    fun delete(relative: String): Boolean = FileSystem.delete(absolute(relative))

    /** The relative path the track [videoId] is saved under. Ids are URL-safe, so no escaping. */
    fun relativeFor(videoId: String, extension: String): String =
        "$DOWNLOADS_DIR/${sanitise(videoId)}.$extension"

    /**
     * A `file://` URI for [relative], with the space in "Application Support"
     * escaped so both the `android.net.Uri` shim and Swift's `URL(string:)`
     * read it back as the same path.
     */
    fun uriFor(relative: String): String = fileUri(absolute(relative))

    fun fileUri(absolutePath: String): String =
        "file://" + absolutePath.replace("%", "%25").replace(" ", "%20")

    /** The absolute path behind a `file://` URI, or null for anything else. */
    fun pathOf(uri: String): String? =
        uri.takeIf { it.startsWith("file://") }?.removePrefix("file://")?.decodeURLPart()

    private fun sanitise(raw: String): String = raw.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    /**
     * A file being written: bytes go to a `.part` beside the destination and
     * only [commit] makes it the real file, so a cancelled or failed download
     * never leaves something that looks complete.
     */
    class Pending(val relative: String) {
        val partPath: String = absolute(relative) + ".part"

        init {
            FileSystem.delete(partPath)
        }

        fun append(bytes: ByteArray) {
            if (!FileSystem.append(partPath, bytes)) error("Could not write to storage — is the phone full?")
        }

        fun commit(): String {
            if (!FileSystem.move(partPath, absolute(relative))) error("Could not save the download")
            return relative
        }

        fun abort() {
            FileSystem.delete(partPath)
        }
    }
}
