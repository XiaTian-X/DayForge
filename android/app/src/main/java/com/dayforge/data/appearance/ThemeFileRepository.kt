package com.dayforge.data.appearance

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.dayforge.domain.model.isContractUuid
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal open class ThemeFileIo {
    open fun write(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int): Int = Os.write(fd, bytes, offset, length)
    open fun sync(fd: FileDescriptor) = Os.fsync(fd)
    open fun rename(source: String, target: String) = Os.rename(source, target)
    open fun remove(path: String) = Os.remove(path)
}

/**
 * Device-local immutable theme versions, separate from old themes and active preferences.
 * filesDirectory must be the app's trusted private filesDir, never an imported/external path.
 * All instances serialize in one process; hostile same-UID directory mutation is not supported.
 * Errors propagate, including post-publication errors: re-read/retry, never assume rollback.
 */
internal class ThemeFileRepository(filesDirectory: File, private val io: ThemeFileIo = ThemeFileIo()) {
    private val parent = filesDirectory.canonicalFile.also {
        require(filesDirectory.isAbsolute && it.parentFile != null && it.isDirectory)
    }
    private val root = File(parent, "theme-definitions-v1")
    // At most two 1 MiB frozen inputs plus their immutable parsed palettes (the light/dark pair).
    // Guarded by access(), like all file operations. Every read still opens/reads/checks the file.
    private val parsed = ArrayDeque<ValidatedTheme>(2)

    suspend fun read(themeId: String, revision: Int): ValidatedTheme = access {
        val name = name(themeId, revision)
        directory(create = false) { readFile(File(root, name), themeId, revision) }
    }

    suspend fun find(themeId: String, revision: Int): ValidatedTheme? = access {
        val target = File(root, name(themeId, revision))
        if (!exists(root)) return@access null
        directory(create = false) { if (exists(target)) readFile(target, themeId, revision) else null }
    }

    /** Only a persisted catalog journal may supply an operation ID or authorize cleanup/deletion. */
    suspend fun install(theme: ValidatedTheme, operationId: String? = null): ValidatedTheme = access {
        require(operationId == null || isContractUuid(operationId))
        val id = theme.definition.themeId
        val revision = theme.definition.revision
        val target = File(root, name(id, revision))
        val context = currentCoroutineContext()
        directory(create = true) { directory ->
            if (exists(target)) {
                val stored = readFile(target, id, revision)
                if (stored.definition != theme.definition) throw ThemeInputException("THEME_VERSION_REUSED")
                io.sync(directory)
                return@directory stored
            }
            val temporary = File(root, ".theme-${operationId ?: UUID.randomUUID()}.part")
            val bytes = theme.exportBytes()
            checkCapacity(directory, bytes.size)
            var created = false
            var failure: Throwable? = null
            try {
                descriptor(temporary, OsConstants.O_RDWR or OsConstants.O_CREAT or OsConstants.O_EXCL) { fd ->
                    created = true
                    var offset = 0
                    while (offset < bytes.size) {
                        context.ensureActive()
                        val requested = minOf(8192, bytes.size - offset)
                        val count = io.write(fd, bytes, offset, requested)
                        if (count <= 0 || count > requested) throw IOException("THEME_FILE_WRITE")
                        offset += count
                    }
                    Os.lseek(fd, 0, OsConstants.SEEK_SET)
                    val actual = readThemeBytes(stream(fd)) { context.ensureActive() }
                    if (!actual.contentEquals(bytes)) throw IOException("THEME_FILE_READBACK")
                    io.sync(fd)
                }
                context.ensureActive()
                checkRoot(directory)
                if (exists(target)) throw IOException("THEME_FILE_TARGET_CHANGED")
                io.rename(temporary.path, target.path)
                io.sync(directory)
                theme
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                // Only this invocation's successfully created file, not stale/unknown temporary files.
                if (created) try {
                    checkRoot(directory)
                    if (exists(temporary)) {
                        require(OsConstants.S_ISREG(Os.lstat(temporary.path).st_mode))
                        io.remove(temporary.path)
                        io.sync(directory)
                    }
                } catch (cleanup: Throwable) {
                    val primary = failure
                    if (primary == null) throw cleanup else primary.addSuppressed(cleanup)
                }
            }
        }
    }

    /** Reclaim only the exact operation recorded before its writer started; unknown parts stay. */
    suspend fun discardOperation(operationId: String) = access {
        require(isContractUuid(operationId))
        removeRegular(File(root, ".theme-$operationId.part"))
    }

    /** Caller must first durably mark this unselected catalog version as deleting. */
    suspend fun removeVersion(themeId: String, revision: Int) = access {
        removeRegular(File(root, name(themeId, revision)))
    }

    private fun removeRegular(target: File) {
        if (!exists(root)) return
        directory(create = false) { directory ->
            if (exists(target)) descriptor(target, OsConstants.O_RDONLY) { held ->
                checkRoot(directory)
                val actual = Os.lstat(target.path)
                val opened = Os.fstat(held)
                check(actual.st_dev == opened.st_dev && actual.st_ino == opened.st_ino && OsConstants.S_ISREG(actual.st_mode))
                io.remove(target.path)
            }
            // Also sync after an already-absent retry: the previous unlink may not have been durable.
            checkRoot(directory)
            io.sync(directory)
        }
    }

    private fun checkCapacity(directory: FileDescriptor, incoming: Int) {
        var used = 0L
        var count = 0
        Files.newDirectoryStream(root.toPath()).use { entries ->
            for (entry in entries) {
                if (++count >= 512) throw ThemeInputException("THEME_STORAGE_LIMIT")
                val stat = Os.lstat(entry.toString())
                if (!OsConstants.S_ISREG(stat.st_mode)) throw ThemeInputException("THEME_STORAGE_ENTRY")
                if (stat.st_size < 0 || stat.st_size > STORAGE_LIMIT - used) throw ThemeInputException("THEME_STORAGE_LIMIT")
                used += stat.st_size
            }
        }
        checkRoot(directory)
        if (incoming > STORAGE_LIMIT - used) throw ThemeInputException("THEME_STORAGE_LIMIT")
    }

    private fun name(id: String, revision: Int): String {
        require(isContractUuid(id) && revision > 0)
        return "$id-$revision.json"
    }

    private suspend fun readFile(file: File, id: String, revision: Int): ValidatedTheme {
        val context = currentCoroutineContext()
        return descriptor(file, OsConstants.O_RDONLY) { fd ->
            val bytes = readThemeBytes(stream(fd)) { context.ensureActive() }
            context.ensureActive()
            val theme = parsed.lastOrNull { it.matchesSource(bytes) }
                ?: ValidatedTheme.parse(bytes) { context.ensureActive() }
            if (theme.definition.themeId != id || theme.definition.revision != revision) throw ThemeInputException("THEME_FILE_IDENTITY")
            parsed.remove(theme)
            if (parsed.size == 2) parsed.removeFirst()
            parsed.addLast(theme)
            theme
        }
    }

    private inline fun <T> directory(create: Boolean, block: (FileDescriptor) -> T): T {
        if (!exists(root)) {
            if (!create) throw IOException("THEME_DIRECTORY_MISSING")
            Os.mkdir(root.path, 448)
        }
        require(OsConstants.S_ISDIR(Os.lstat(root.path).st_mode))
        if (create) descriptor(parent, OsConstants.O_RDONLY, directory = true) { io.sync(it) }
        return descriptor(root, OsConstants.O_RDONLY, directory = true) { fd ->
            checkRoot(fd)
            block(fd)
        }
    }

    private fun checkRoot(fd: FileDescriptor) {
        val held = Os.fstat(fd)
        val current = Os.lstat(root.path)
        check(OsConstants.S_ISDIR(current.st_mode) && held.st_dev == current.st_dev && held.st_ino == current.st_ino) {
            "THEME_DIRECTORY_CHANGED"
        }
    }

    private inline fun <T> descriptor(file: File, flags: Int, directory: Boolean = false, block: (FileDescriptor) -> T): T {
        val fd = Os.open(file.path, flags or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK or ICON_OPEN_CLOEXEC, 384)
        var failure: Throwable? = null
        try {
            val mode = Os.fstat(fd).st_mode
            require(if (directory) OsConstants.S_ISDIR(mode) else OsConstants.S_ISREG(mode))
            return block(fd)
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            try { Os.close(fd) } catch (closing: Throwable) {
                val primary = failure
                if (primary == null) throw closing else primary.addSuppressed(closing)
            }
        }
    }

    private fun stream(fd: FileDescriptor) = object : InputStream() {
        override fun read(): Int {
            val byte = ByteArray(1)
            return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 255
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val count = Os.read(fd, bytes, offset, length)
            return if (count == 0) -1 else count
        }
    }

    private fun exists(file: File): Boolean = try { Os.lstat(file.path); true } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT) false else throw error
    }

    private suspend fun <T> access(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { currentCoroutineContext().ensureActive(); block() }
    }

    private companion object {
        val mutex = Mutex()
        const val STORAGE_LIMIT = 64L * 1024 * 1024
    }
}
