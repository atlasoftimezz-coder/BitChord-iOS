package java.io

import com.music.bitchord.platform.FileSystem

/*
 * java.io for ported code: File over NSFileManager (see FileSystem.ios.kt) and
 * the in-memory streams. Paths are absolute iOS sandbox paths; Context.cacheDir
 * / filesDir hand out the Caches and Application Support directories.
 */

/** The same class kotlinx-io and the okhttp3 shim throw, so ported catch blocks see it. */
typealias IOException = kotlinx.io.IOException
class FileNotFoundException(message: String? = null) : kotlinx.io.IOException(message)
typealias EOFException = kotlinx.io.EOFException

class File(path: String) : Comparable<File> {
    constructor(parent: File?, child: String) : this(if (parent == null) child else join(parent.path, child))
    constructor(parent: String?, child: String) : this(if (parent == null) child else join(parent, child))

    val path: String = path.trimEnd('/').ifEmpty { "/" }
    val absolutePath: String get() = path
    val canonicalPath: String get() = path
    val absoluteFile: File get() = this
    val name: String get() = path.substringAfterLast('/')
    val nameWithoutExtension: String get() = name.substringBeforeLast('.')
    val extension: String get() = name.substringAfterLast('.', "")
    val parent: String? get() = path.substringBeforeLast('/', "").ifEmpty { null }
    val parentFile: File? get() = parent?.let(::File)

    fun exists(): Boolean = FileSystem.exists(path)
    val isFile: Boolean get() = FileSystem.isFile(path)
    val isDirectory: Boolean get() = FileSystem.isDirectory(path)
    fun length(): Long = FileSystem.size(path)
    fun lastModified(): Long = FileSystem.modifiedMillis(path)
    fun setLastModified(time: Long): Boolean = FileSystem.setModifiedMillis(path, time)
    fun canRead(): Boolean = exists()
    fun mkdirs(): Boolean = FileSystem.mkdirs(path)
    fun mkdir(): Boolean = FileSystem.mkdirs(path)
    fun delete(): Boolean = FileSystem.delete(path)
    fun deleteRecursively(): Boolean = FileSystem.delete(path)
    fun createNewFile(): Boolean = if (exists()) false else FileSystem.write(path, ByteArray(0))
    fun renameTo(dest: File): Boolean = FileSystem.move(path, dest.path)
    fun list(): Array<String>? = if (isDirectory) FileSystem.list(path).toTypedArray() else null
    fun listFiles(): Array<File>? = list()?.map { File(this, it) }?.toTypedArray()
    fun listFiles(filter: (File) -> Boolean): Array<File>? = listFiles()?.filter(filter)?.toTypedArray()
    fun walk(): Sequence<File> = sequence {
        yield(this@File)
        listFiles()?.forEach { child -> yieldAll(child.walk()) }
    }

    fun readBytes(): ByteArray = FileSystem.read(path) ?: throw FileNotFoundException(path)
    fun readText(): String = readBytes().decodeToString()
    fun writeBytes(bytes: ByteArray) {
        if (!FileSystem.write(path, bytes)) throw IOException("Could not write $path")
    }
    fun writeText(text: String) = writeBytes(text.encodeToByteArray())
    fun appendBytes(bytes: ByteArray) = writeBytes((FileSystem.read(path) ?: ByteArray(0)) + bytes)
    fun appendText(text: String) = appendBytes(text.encodeToByteArray())
    fun readLines(): List<String> = readText().lines()
    fun copyTo(target: File, overwrite: Boolean = false): File {
        if (target.exists() && !overwrite) throw IOException("${target.path} exists")
        target.writeBytes(readBytes())
        return target
    }
    fun inputStream(): InputStream = ByteArrayInputStream(readBytes())
    fun outputStream(): OutputStream = FileOutputStream(this)

    override fun compareTo(other: File): Int = path.compareTo(other.path)
    override fun equals(other: Any?): Boolean = other is File && other.path == path
    override fun hashCode(): Int = path.hashCode()
    override fun toString(): String = path

    companion object {
        const val separator: String = "/"
        const val separatorChar: Char = '/'
        private fun join(parent: String, child: String) = parent.trimEnd('/') + "/" + child.trimStart('/')
        fun createTempFile(prefix: String, suffix: String?, directory: File? = null): File {
            val dir = directory ?: File(FileSystem.tempDirectory())
            dir.mkdirs()
            return File(dir, "$prefix${kotlin.random.Random.nextLong().toULong()}${suffix ?: ".tmp"}").also { it.writeBytes(ByteArray(0)) }
        }
    }
}

abstract class InputStream : AutoCloseable {
    abstract fun read(): Int
    open fun read(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size): Int {
        var n = 0
        while (n < length) {
            val b = read()
            if (b < 0) return if (n == 0) -1 else n
            buffer[offset + n] = b.toByte()
            n++
        }
        return n
    }
    fun read(buffer: ByteArray): Int = read(buffer, 0, buffer.size)
    open fun readBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = read(buf, 0, buf.size)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
    open fun available(): Int = 0
    override fun close() = Unit
    fun bufferedReader(): BufferedReader = BufferedReader(readBytes().decodeToString())
    fun buffered(): InputStream = this
}

class ByteArrayInputStream(private val data: ByteArray) : InputStream() {
    private var pos = 0
    override fun read(): Int = if (pos < data.size) data[pos++].toInt() and 0xff else -1
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (pos >= data.size) return -1
        val n = minOf(length, data.size - pos)
        data.copyInto(buffer, offset, pos, pos + n)
        pos += n
        return n
    }
    override fun available(): Int = data.size - pos
}

class FileInputStream(file: File) : InputStream() {
    private val inner = ByteArrayInputStream(file.readBytes())
    constructor(path: String) : this(File(path))
    override fun read(): Int = inner.read()
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = inner.read(buffer, offset, length)
    override fun available(): Int = inner.available()
}

abstract class OutputStream : AutoCloseable {
    abstract fun write(b: Int)
    open fun write(buffer: ByteArray, offset: Int, length: Int) {
        for (i in offset until offset + length) write(buffer[i].toInt())
    }
    fun write(buffer: ByteArray) = write(buffer, 0, buffer.size)
    open fun flush() = Unit
    override fun close() = Unit
    fun bufferedWriter(): BufferedWriter = BufferedWriter(this)
    fun buffered(): OutputStream = this
}

open class ByteArrayOutputStream(initialSize: Int = 32) : OutputStream() {
    private var buf = ByteArray(maxOf(initialSize, 1))
    private var count = 0
    private fun ensure(extra: Int) {
        if (count + extra > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, count + extra))
    }
    override fun write(b: Int) {
        ensure(1)
        buf[count++] = b.toByte()
    }
    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        ensure(length)
        buffer.copyInto(buf, count, offset, offset + length)
        count += length
    }
    fun toByteArray(): ByteArray = buf.copyOf(count)
    fun size(): Int = count
    fun reset() {
        count = 0
    }
    override fun toString(): String = toByteArray().decodeToString()
}

/** Collects in memory and writes the file on close (or flush). */
class FileOutputStream(private val file: File, private val append: Boolean = false) : ByteArrayOutputStream() {
    constructor(path: String, append: Boolean = false) : this(File(path), append)
    private var closed = false
    override fun flush() {
        val bytes = toByteArray()
        if (append) file.appendBytes(bytes) else file.writeBytes(bytes)
    }
    override fun close() {
        if (closed) return
        closed = true
        flush()
    }
}

class BufferedReader(private val text: String) : AutoCloseable {
    private val lines = text.lines().iterator()
    fun readText(): String = text
    fun readLine(): String? = if (lines.hasNext()) lines.next() else null
    fun readLines(): List<String> = text.lines()
    fun lineSequence(): Sequence<String> = text.lineSequence()
    override fun close() = Unit
}

class BufferedWriter(private val out: OutputStream) : AutoCloseable {
    fun write(text: String) = out.write(text.encodeToByteArray())
    fun newLine() = write("\n")
    fun flush() = out.flush()
    override fun close() = out.close()
}

class StringReader(val text: String)
