package com.dayforge.data.appearance

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Only the trusted app-private filesDir may be supplied, never a provider/import path.
 * All instances share a process lease, including suspension and cancellation cleanup.
 * No separate process or hostile same-UID writer is supported. No GC/purge is provided.
 */
internal class AccountIconFiles(filesDirectory: File, private val io: IconFileIo = IconFileIo()) {
    private val parent = filesDirectory.also {
        require(it.isAbsolute && it.parentFile != null && OsConstants.S_ISDIR(Os.lstat(it.path).st_mode))
    }.canonicalFile

    internal class Directory(val path: File, val files: LocalIconFiles, private val verify: () -> Unit) {
        fun inventory(): Set<String> {
            verify()
            var bytes = 0L
            val names = linkedSetOf<String>()
            Files.newDirectoryStream(path.toPath()).use { entries ->
                for (entry in entries) {
                    check(names.size < ENTRY_LIMIT) { "ICON_FILE_ENTRY_LIMIT" }
                    val stat = Os.lstat(entry.toString())
                    require(OsConstants.S_ISREG(stat.st_mode)) { "ICON_FILE_NOT_REGULAR" }
                    check(stat.st_size in 0..(BYTE_LIMIT - bytes)) { "ICON_FILE_BYTE_LIMIT" }
                    bytes += stat.st_size
                    names.add(entry.fileName.toString())
                }
            }
            verify()
            return names
        }

        fun capacity(incoming: Int, hash: String) {
            val names = inventory()
            val used = names.sumOf { Os.lstat(File(path, it).path).st_size }
            check(hash in names || (names.size < ENTRY_LIMIT && incoming <= BYTE_LIMIT - used)) { "ICON_FILE_CAPACITY" }
        }
    }

    suspend fun <T> exclusive(namespace: AccountIconNamespace, create: Boolean,
        beforeAccess: suspend () -> Unit, block: suspend (Directory?) -> T): T =
        lease.withLock {
            // Blocking syscalls finish before withContext propagates cancellation and releases the lease.
            withContext(Dispatchers.IO) {
                beforeAccess()
                val held = mutableListOf<Pair<File, FileDescriptor>>()
                var failure: Throwable? = null
                try {
                    fun verify() {
                        for ((path, fd) in held) {
                            val current = Os.lstat(path.path)
                            val original = Os.fstat(fd)
                            check(OsConstants.S_ISDIR(current.st_mode) && current.st_dev == original.st_dev &&
                                current.st_ino == original.st_ino) { "ICON_DIRECTORY_CHANGED" }
                        }
                    }
                    var path = parent
                    val segments = listOf(null, "account-icons-v1", namespace.accountId, namespace.serverInstanceId, namespace.syncEpoch)
                    for (segment in segments) {
                        currentCoroutineContext().ensureActive()
                        if (segment != null) path = File(path, segment)
                        verify()
                        if (!exists(path)) {
                            if (!create) return@withContext block(null)
                            Os.mkdir(path.path, 448)
                        }
                        require(OsConstants.S_ISDIR(Os.lstat(path.path).st_mode)) { "ICON_DIRECTORY_INVALID" }
                        val fd = Os.open(path.path, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or
                            OsConstants.O_NOFOLLOW or ICON_OPEN_CLOEXEC, 0)
                        held.add(path to fd)
                        verify()
                        // Sync every parent on retries too: a previous mkdir/fsync could have been interrupted.
                        if (create && held.size > 1) io.sync(held[held.lastIndex - 1].second)
                    }
                    LocalIconFiles(path, io).use { files ->
                        verify()
                        val result = block(Directory(path, files, ::verify))
                        verify()
                        currentCoroutineContext().ensureActive()
                        result
                    }
                } catch (error: Throwable) {
                    failure = error
                    throw error
                } finally {
                    var closeFailure: Throwable? = null
                    for ((_, fd) in held.asReversed()) try { Os.close(fd) } catch (closing: Throwable) {
                        val primary = failure
                        if (primary != null) primary.addSuppressed(closing)
                        else {
                            val previous = closeFailure
                            if (previous == null) closeFailure = closing else previous.addSuppressed(closing)
                        }
                    }
                    closeFailure?.let { throw it }
                }
            }
        }

    companion object {
        private val lease = Mutex()
        private const val ENTRY_LIMIT = 4096
        private const val BYTE_LIMIT = 536_870_912L
        private fun exists(path: File): Boolean = try { Os.lstat(path.path); true } catch (error: ErrnoException) {
            if (error.errno == OsConstants.ENOENT) false else throw error
        }
    }
}
