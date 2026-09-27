package com.dayforge.data.appearance

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.dayforge.domain.model.IconBlob
import com.dayforge.domain.model.isContractUuid
import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream

/** Fault-injectable syscall boundary; production always uses the actual Android filesystem. */
internal open class IconFileIo {
    open fun write(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int): Int =
        Os.write(fd, bytes, offset, length)
    open fun sync(fd: FileDescriptor) = Os.fsync(fd)
    open fun rename(source: String, target: String) = Os.rename(source, target)
    open fun unlink(path: String) = Os.remove(path)
}

/**
 * Blocking private-file primitive, NOT account authorization or a database install transaction.
 * The caller owns this pre-created app-private directory exclusively for this object's lifetime,
 * serializes all methods, and persists its operation journal before publish/cleanup. Never use an
 * external/shared directory. Same-UID malicious filesystem mutation is outside this trust boundary.
 * Run on IO; a cancelled caller must wait for actual IO to stop before releasing exclusivity.
 */
internal class LocalIconFiles(root: File, private val io: IconFileIo = IconFileIo()) : Closeable {
    private val root: File
    private val directory: FileDescriptor
    private var closed = false

    init {
        require(root.isAbsolute && root.parentFile != null)
        require(OsConstants.S_ISDIR(Os.lstat(root.path).st_mode))
        this.root = root.canonicalFile
        require(this.root.parentFile != null)
        val fd = Os.open(this.root.path, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or
            OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
        try {
            require(OsConstants.S_ISDIR(Os.fstat(fd).st_mode))
            directory = fd
        } catch (error: Throwable) {
            try { Os.close(fd) } catch (closing: Throwable) { error.addSuppressed(closing) }
            throw error
        }
    }

    /** All image validation precedes writes; successful return means file durability, not DB ready. */
    fun publish(operationId: String, content: ByteArray, expected: IconBlob): String {
        require(isContractUuid(operationId))
        require(content.size == expected.byteLength)
        val frozen = content.copyOf()
        validate(frozen, expected)
        active()
        val target = path(expected.sha256)
        // Never silently replace an existing corrupted file, even with a correct incoming image.
        if (exists(target)) {
            read(expected)
            io.sync(directory)
            return profile(expected)
        }
        val temporary = path(".install-$operationId.part")
        descriptor(temporary, OsConstants.O_RDWR or OsConstants.O_CREAT or OsConstants.O_EXCL).use { handle ->
            var written = 0
            while (written < frozen.size) {
                val count = io.write(handle.fd, frozen, written, minOf(65_536, frozen.size - written))
                if (count <= 0 || count > minOf(65_536, frozen.size - written)) throw IOException("ICON_FILE_WRITE")
                written += count
            }
            Os.lseek(handle.fd, 0, OsConstants.SEEK_SET)
            readIconBytes(stream(handle.fd), expected)
            io.sync(handle.fd)
        }
        active()
        // Recheck under caller's exclusive lease; do not overwrite a file created outside that lease.
        if (exists(target)) throw IOException("ICON_FILE_TARGET_CHANGED")
        io.rename(temporary, target)
        io.sync(directory)
        return profile(expected)
    }

    /** Reads are bounded through EOF from one ordinary file; existence alone never means ready. */
    fun read(expected: IconBlob, validationProfile: String = profile(expected)): ByteArray {
        require(validationProfile == profile(expected))
        active()
        val bytes = descriptor(path(expected.sha256), OsConstants.O_RDONLY).use { handle ->
            readIconBytes(stream(handle.fd), expected)
        }
        validate(bytes, expected)
        return bytes
    }

    /** Only for an operation proven by the caller's durable journal; final hashes are never removed. */
    fun cleanupTemporary(operationId: String) {
        require(isContractUuid(operationId))
        active()
        val temporary = path(".install-$operationId.part")
        if (exists(temporary)) {
            require(OsConstants.S_ISREG(Os.lstat(temporary).st_mode))
            io.unlink(temporary)
        }
        io.sync(directory)
    }

    override fun close() {
        if (!closed) {
            closed = true
            Os.close(directory)
        }
    }

    private fun active() {
        check(!closed) { "ICON_FILES_CLOSED" }
        val held = Os.fstat(directory)
        val current = Os.lstat(root.path)
        check(OsConstants.S_ISDIR(current.st_mode) && held.st_dev == current.st_dev && held.st_ino == current.st_ino) {
            "ICON_FILE_ROOT_CHANGED"
        }
    }

    private fun path(name: String) = File(root, name).path
    private fun exists(path: String): Boolean = try { Os.lstat(path); true } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT) false else throw error
    }

    private class Descriptor(val fd: FileDescriptor) : Closeable {
        override fun close() = Os.close(fd)
    }

    private fun descriptor(path: String, flags: Int): Descriptor {
        // NONBLOCK ensures a pre-existing FIFO cannot hang before fstat rejects it.
        val fd = Os.open(path, flags or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC or OsConstants.O_NONBLOCK, 384)
        try {
            require(OsConstants.S_ISREG(Os.fstat(fd).st_mode))
            return Descriptor(fd)
        } catch (error: Throwable) {
            try { Os.close(fd) } catch (closing: Throwable) { error.addSuppressed(closing) }
            throw error
        }
    }

    private fun stream(fd: FileDescriptor) = object : InputStream() {
        override fun read(): Int {
            val byte = ByteArray(1)
            return if (read(byte, 0, 1) == -1) -1 else byte[0].toInt() and 255
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val count = Os.read(fd, bytes, offset, length)
            return if (count == 0) -1 else count
        }
        // The Descriptor, not this borrowed stream, owns fd.
    }

    private fun validate(bytes: ByteArray, expected: IconBlob) {
        val bitmap = if (expected.mediaType == "image/png") decodePng(bytes, expected) else renderSvg(bytes, expected)
        bitmap.recycle()
    }

    private fun profile(expected: IconBlob) = if (expected.mediaType == "image/png") "png-v1" else "svg-v1"
}
