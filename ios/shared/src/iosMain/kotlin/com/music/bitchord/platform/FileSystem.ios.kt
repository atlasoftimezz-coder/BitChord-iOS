package com.music.bitchord.platform

import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask
import platform.Foundation.localTimeZone
import platform.Foundation.preferredLanguages
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile

actual fun deviceRegion(): String {
    val first = platform.Foundation.NSLocale.preferredLanguages.firstOrNull() as? String ?: return ""
    return first.split('-').drop(1).firstOrNull { it.length == 2 }?.uppercase().orEmpty()
}

actual object FileSystem {
    private val fm get() = NSFileManager.defaultManager

    actual fun exists(path: String): Boolean = fm.fileExistsAtPath(path)

    private fun dirFlag(path: String): Boolean? = memScoped {
        val isDir = alloc<BooleanVar>()
        if (!fm.fileExistsAtPath(path, isDirectory = isDir.ptr)) null else isDir.value
    }

    actual fun isFile(path: String): Boolean = dirFlag(path) == false
    actual fun isDirectory(path: String): Boolean = dirFlag(path) == true

    actual fun size(path: String): Long =
        (fm.attributesOfItemAtPath(path, error = null)?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L

    actual fun modifiedMillis(path: String): Long =
        ((fm.attributesOfItemAtPath(path, error = null)?.get(NSFileModificationDate) as? NSDate)
            ?.timeIntervalSince1970?.times(1000))?.toLong() ?: 0L

    actual fun setModifiedMillis(path: String, millis: Long): Boolean = fm.setAttributes(
        mapOf<Any?, Any?>(NSFileModificationDate to NSDate.dateWithTimeIntervalSince1970(millis / 1000.0)),
        ofItemAtPath = path,
        error = null,
    )

    actual fun mkdirs(path: String): Boolean =
        isDirectory(path) || fm.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)

    actual fun delete(path: String): Boolean = exists(path) && fm.removeItemAtPath(path, error = null)

    actual fun move(from: String, to: String): Boolean {
        if (exists(to)) fm.removeItemAtPath(to, error = null)
        return fm.moveItemAtPath(from, toPath = to, error = null)
    }

    actual fun list(path: String): List<String> =
        fm.contentsOfDirectoryAtPath(path, error = null)?.map { it as String }.orEmpty()

    actual fun read(path: String): ByteArray? = NSData.dataWithContentsOfFile(path)?.toByteArray()

    actual fun write(path: String, bytes: ByteArray): Boolean {
        path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let(::mkdirs)
        return bytes.toNSData().writeToFile(path, atomically = true)
    }

    actual fun append(path: String, bytes: ByteArray): Boolean {
        path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let(::mkdirs)
        val file = fopen(path, "ab") ?: return false
        return try {
            if (bytes.isEmpty()) true
            else bytes.usePinned { pinned -> fwrite(pinned.addressOf(0), 1uL, bytes.size.toULong(), file) == bytes.size.toULong() }
        } finally {
            fclose(file)
        }
    }

    actual fun tempDirectory(): String = NSTemporaryDirectory().trimEnd('/')

    private fun searchPath(kind: ULong): String =
        (NSSearchPathForDirectoriesInDomains(kind, NSUserDomainMask, true).first() as String)

    actual fun cachesDirectory(): String = searchPath(NSCachesDirectory)
    actual fun filesDirectory(): String = searchPath(NSApplicationSupportDirectory).also { mkdirs(it) }
    actual fun documentsDirectory(): String = searchPath(NSDocumentDirectory)
}

actual fun utcOffsetSeconds(epochMs: Long): Int =
    platform.Foundation.NSTimeZone.localTimeZone.secondsFromGMTForDate(
        platform.Foundation.NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0),
    ).toInt()
