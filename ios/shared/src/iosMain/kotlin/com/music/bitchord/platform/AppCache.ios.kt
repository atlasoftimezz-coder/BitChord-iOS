package com.music.bitchord.platform

import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile

actual object AppCache {
    private val root: String by lazy {
        NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String
    }

    private fun full(path: String) = "$root/${path.trimStart('/')}"

    actual fun read(path: String): ByteArray? =
        NSData.dataWithContentsOfFile(full(path))?.toByteArray()

    actual fun write(path: String, data: ByteArray): Boolean {
        val target = full(path)
        val directory = target.substringBeforeLast('/')
        NSFileManager.defaultManager.createDirectoryAtPath(
            directory,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        return data.toNSData().writeToFile(target, atomically = true)
    }

    actual fun delete(path: String) {
        NSFileManager.defaultManager.removeItemAtPath(full(path), error = null)
    }

    actual fun list(directory: String): List<String> {
        val dir = full(directory)
        val manager = NSFileManager.defaultManager
        val names = manager.contentsOfDirectoryAtPath(dir, error = null)?.map { it as String }.orEmpty()
        return names.sortedByDescending { name ->
            val attrs = manager.attributesOfItemAtPath("$dir/$name", error = null)
            (attrs?.get(NSFileModificationDate) as? NSDate)?.timeIntervalSince1970 ?: 0.0
        }
    }
}
