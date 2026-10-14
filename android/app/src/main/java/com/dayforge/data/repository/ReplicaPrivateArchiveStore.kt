@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.dayforge.data.repository

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.dayforge.data.appearance.AccountIconNamespace
import com.dayforge.data.appearance.ReplicaPackIntent
import com.dayforge.data.appearance.strictAppearanceJson
import com.dayforge.data.appearance.ICON_OPEN_CLOEXEC
import com.dayforge.domain.model.isContractUuid
import java.io.*
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToStream

internal data class ReplicaArchiveEntry(val name: String, val byteLength: Long, val contentHash: String)

/** A private candidate with fully read-back bytes, NOT protocol ARCHIVE_VERIFIED or a switch ticket. */
internal class ReplicaArchiveReceipt internal constructor(val namespace: AccountIconNamespace, val archiveId: String,
    val inputHash: String, val byteLength: Long, val contentHash: String, entries: List<ReplicaArchiveEntry>) {
    val entries: List<ReplicaArchiveEntry> = Collections.unmodifiableList(entries.toList())
    val activationAuthorized: Boolean get() = false
}

internal open class ReplicaArchiveIo {
    open fun read(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int) = Os.read(fd, bytes, offset, length)
    open fun write(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int) = Os.write(fd, bytes, offset, length)
    open fun sync(fd: FileDescriptor) = Os.fsync(fd)
}

/**
 * App-private candidate storage only; never copies an active DB file, exports, extracts, deletes
 * archives or adopts old partial files. Caller binds authorization and runs create inside the
 * complete input callback. Its later source checks must succeed before retaining the receipt.
 * Failure may leave a private partial/unreferenced candidate, never an automatically usable one.
 * All instances share a process lease. Hostile same-UID writers are outside the trust boundary.
 */
internal class ReplicaPrivateArchiveStore(filesDirectory: File, private val io: ReplicaArchiveIo = ReplicaArchiveIo(),
    private val identity: () -> String = { UUID.randomUUID().toString() }) {
    private val parent = filesDirectory.also {
        require(it.isAbsolute && it.parentFile != null && OsConstants.S_ISDIR(Os.lstat(it.path).st_mode))
    }.canonicalFile

    // Runs on the input reader's IO coroutine: changing jobs would invalidate its borrowed readers.
    suspend fun create(input: ReplicaArchiveInput, access: ReplicaArchiveFileAccess,
        authorize: suspend () -> Unit): ReplicaArchiveReceipt = lease.withLock {
        authorize()
        val coroutine = currentCoroutineContext()
        val checkpoint = { coroutine.ensureActive() }
        val id = identity().also { require(isContractUuid(it)) }
        val ns = input.icons.original.session.let {
            AccountIconNamespace(it.authentication.userId, requireNotNull(it.serverInstanceId), requireNotNull(it.syncEpoch))
        }
        val jsonEntries = listOf("input.json" to input.manifest, "business.json" to input.storage.contents,
            "icons.json" to input.icons.contents).map { (name, value) ->
            val measured = measure(value, checkpoint)
            val expected = when (name) { "input.json" -> input.hash; "business.json" -> input.storage.hash; else -> input.icons.hash }
            require(measured.second == expected) { "SYNC_ARCHIVE_ENCODING_CHANGED" }
            ReplicaArchiveEntry(name, measured.first, expected) to value
        }
        val entries = jsonEntries.map { it.first } + input.iconFiles.files.map {
            ReplicaArchiveEntry("icons/${it.name}", it.byteLength.toLong(), it.contentHash)
        } + input.themes.files.map { ReplicaArchiveEntry("themes/${it.name}", it.byteLength.toLong(), it.contentHash) }
        val length = length(entries)
        directory(ns, create = true) { root, verify, directories ->
            authorize(); checkpoint(); verify()
            val path = File(root, "$id.candidate")
            descriptor(path, OsConstants.O_RDWR or OsConstants.O_CREAT or OsConstants.O_EXCL) { fd ->
                val digest = MessageDigest.getInstance("SHA-256")
                var count = 0L
                val raw = object : OutputStream() {
                    override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                    override fun write(bytes: ByteArray, offset: Int, size: Int) {
                        require(offset >= 0 && size >= 0 && offset <= bytes.size - size)
                        var position = offset
                        while (position < offset + size) {
                            checkpoint(); verify()
                            val amount = minOf(65_536, offset + size - position)
                            val written = io.write(fd, bytes, position, amount)
                            if (written <= 0 || written > amount) throw IOException("SYNC_ARCHIVE_WRITE")
                            count += written; require(count <= length) { "SYNC_ARCHIVE_LENGTH_INVALID" }
                            digest.update(bytes, position, written); position += written
                        }
                    }
                }
                val out = DataOutputStream(BufferedOutputStream(raw, 65_536))
                out.write(MAGIC)
                for ((index, entry) in entries.withIndex()) {
                    checkpoint(); authorize()
                    val name = entry.name.toByteArray(Charsets.US_ASCII)
                    out.writeInt(name.size); out.write(name); out.writeLong(entry.byteLength)
                    val contentDigest = MessageDigest.getInstance("SHA-256")
                    var contentCount = 0L
                    val bounded = object : OutputStream() {
                        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                        override fun write(bytes: ByteArray, offset: Int, size: Int) {
                            checkpoint(); contentCount += size
                            require(contentCount <= entry.byteLength) { "SYNC_ARCHIVE_ENTRY_INVALID" }
                            contentDigest.update(bytes, offset, size); out.write(bytes, offset, size)
                        }
                    }
                    if (index < jsonEntries.size) Json.encodeToStream(JsonObject.serializer(), jsonEntries[index].second, bounded)
                    else bounded.write(if (entry.name.startsWith("icons/")) access.icon(entry.name.removePrefix("icons/"))
                        else access.theme(entry.name.removePrefix("themes/")))
                    require(contentCount == entry.byteLength && hash(contentDigest.digest()) == entry.contentHash) { "SYNC_ARCHIVE_ENTRY_INVALID" }
                }
                out.writeInt(0); out.flush(); checkpoint(); verify()
                require(count == length && Os.fstat(fd).st_size == length) { "SYNC_ARCHIVE_LENGTH_INVALID" }
                io.sync(fd)
                val receipt = ReplicaArchiveReceipt(ns, id, input.hash, length, hash(digest.digest()), entries)
                // Read every actual framed byte through EOF, not a cached buffer or just fstat.
                Os.lseek(fd, 0, OsConstants.SEEK_SET)
                readback(fd, receipt, verify, checkpoint)
                for (directory in directories.asReversed()) { checkpoint(); verify(); io.sync(directory) }
                authorize(); checkpoint(); verify()
                receipt
            }
        }
    }

    /** Cold validation against the source-bound persistent receipt; never opens another account. */
    suspend fun verify(receipt: ReplicaArchiveReceipt, authorize: suspend () -> Unit) = lease.withLock {
        authorize()
        require(isContractUuid(receipt.archiveId)) { "SYNC_ARCHIVE_RECEIPT_INVALID" }
        withContext(Dispatchers.IO) {
            val caller = currentCoroutineContext()
            directory(receipt.namespace, create = false) { root, verify, _ ->
                descriptor(File(root, "${receipt.archiveId}.candidate"), OsConstants.O_RDONLY) { fd ->
                    readback(fd, receipt, verify, { caller.ensureActive() })
                }
            }
        }
        authorize()
    }

    /** Bounded preference read from the SAME descriptor as full frame/hash/EOF verification.
     * No file extraction, old pixel adoption or path reuse. Damaged later frames reject the read.
     */
    suspend fun iconIntent(receipt: ReplicaArchiveReceipt, authorize: suspend () -> Unit): ReplicaPackIntent = lease.withLock {
        authorize()
        require(isContractUuid(receipt.archiveId)) { "SYNC_ARCHIVE_RECEIPT_INVALID" }
        val intent = withContext(Dispatchers.IO) {
            val caller = currentCoroutineContext()
            directory(receipt.namespace, create = false) { root, verify, _ ->
                descriptor(File(root, "${receipt.archiveId}.candidate"), OsConstants.O_RDONLY) { fd ->
                    val bytes = requireNotNull(readback(fd, receipt, verify, { caller.ensureActive() }, captureIcons = true))
                    val text = strictAppearanceJson(bytes, JSON_LIMIT.toInt(), { caller.ensureActive() }) {
                        error("SYNC_ARCHIVE_ICON_$it")
                    }
                    ReplicaPackIntent.fromArchive(receipt.namespace, Json.parseToJsonElement(text) as JsonObject) { caller.ensureActive() }
                }
            }
        }
        authorize(); intent
    }

    private fun measure(value: JsonObject, checkpoint: () -> Unit): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256"); var count = 0L
        Json.encodeToStream(JsonObject.serializer(), value, object : OutputStream() {
            override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
            override fun write(bytes: ByteArray, offset: Int, size: Int) {
                checkpoint(); count += size; require(count <= JSON_LIMIT) { "SYNC_ARCHIVE_ENTRY_TOO_LARGE" }
                digest.update(bytes, offset, size)
            }
        })
        return count to hash(digest.digest())
    }

    private fun length(entries: List<ReplicaArchiveEntry>): Long {
        require(entries.size in 3..4610 && entries.map { it.name }.distinct().size == entries.size)
        var total = MAGIC.size.toLong() + 4
        for ((index, entry) in entries.withIndex()) {
            val valid = if (index < 3) entry.name == listOf("input.json", "business.json", "icons.json")[index]
                else entry.name.matches(Regex("icons/([0-9a-f]{64}|\\.install-[0-9a-f-]{36}\\.part)")) ||
                    entry.name.matches(Regex("themes/([0-9a-f-]{36}-[1-9][0-9]{0,9}\\.json|\\.theme-[0-9a-f-]{36}\\.part)"))
            require(valid && entry.name.length <= 160 && entry.contentHash.matches(Regex("[0-9a-f]{64}"))) { "SYNC_ARCHIVE_ENTRY_INVALID" }
            val limit = if (index < 3) JSON_LIMIT else if (entry.name.startsWith("icons/")) 2_097_152L else 1_048_576L
            require(entry.byteLength in 0..limit)
            total += 12 + entry.name.length + entry.byteLength
            require(total <= ARCHIVE_LIMIT) { "SYNC_ARCHIVE_TOO_LARGE" }
        }
        return total
    }

    private fun readback(fd: FileDescriptor, receipt: ReplicaArchiveReceipt, verify: () -> Unit,
        checkpoint: () -> Unit, captureIcons: Boolean = false): ByteArray? {
        require(isContractUuid(receipt.archiveId) && receipt.inputHash == receipt.entries.firstOrNull()?.contentHash &&
            receipt.contentHash.matches(Regex("[0-9a-f]{64}")) && receipt.byteLength == length(receipt.entries)) { "SYNC_ARCHIVE_RECEIPT_INVALID" }
        val before = Os.fstat(fd)
        require(OsConstants.S_ISREG(before.st_mode) && before.st_size == receipt.byteLength && before.st_nlink == 1L) { "SYNC_ARCHIVE_FILE_INVALID" }
        val digest = MessageDigest.getInstance("SHA-256")
        val stream = object : InputStream() {
            override fun read(): Int { val one = ByteArray(1); return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 255 }
            override fun read(bytes: ByteArray, offset: Int, size: Int): Int {
                checkpoint(); verify()
                if (size == 0) return 0
                val count = io.read(fd, bytes, offset, minOf(size, 65_536))
                if (count < 0 || count > minOf(size, 65_536)) throw IOException("SYNC_ARCHIVE_READ")
                return if (count == 0) -1 else count
            }
        }
        val input = DataInputStream(BufferedInputStream(DigestInputStream(stream, digest), 65_536))
        val magic = ByteArray(MAGIC.size); input.readFully(magic); require(magic.contentEquals(MAGIC)) { "SYNC_ARCHIVE_FORMAT_INVALID" }
        val buffer = ByteArray(65_536)
        var icons: ByteArray? = null
        for (entry in receipt.entries) {
            checkpoint()
            require(input.readInt() == entry.name.length) { "SYNC_ARCHIVE_ENTRY_INVALID" }
            val name = ByteArray(entry.name.length); input.readFully(name)
            require(name.contentEquals(entry.name.toByteArray(Charsets.US_ASCII)) && input.readLong() == entry.byteLength) { "SYNC_ARCHIVE_ENTRY_INVALID" }
            var remaining = entry.byteLength; val content = MessageDigest.getInstance("SHA-256")
            val captured = if (captureIcons && entry.name == "icons.json") ByteArray(entry.byteLength.toInt()) else null
            while (remaining > 0) {
                checkpoint(); val amount = minOf(remaining, buffer.size.toLong()).toInt()
                val offset = (entry.byteLength - remaining).toInt()
                input.readFully(buffer, 0, amount); content.update(buffer, 0, amount); remaining -= amount
                captured?.let { buffer.copyInto(it, offset, 0, amount) }
            }
            require(hash(content.digest()) == entry.contentHash) { "SYNC_ARCHIVE_CONTENT_INVALID" }
            if (captured != null) icons = captured
        }
        require(input.readInt() == 0 && input.read() == -1 && hash(digest.digest()) == receipt.contentHash) { "SYNC_ARCHIVE_CONTENT_INVALID" }
        val after = Os.fstat(fd); verify(); checkpoint()
        require(after.st_dev == before.st_dev && after.st_ino == before.st_ino && after.st_size == before.st_size) { "SYNC_ARCHIVE_FILE_CHANGED" }
        return icons
    }

    private suspend fun <T> directory(ns: AccountIconNamespace, create: Boolean,
        block: suspend (File, () -> Unit, List<FileDescriptor>) -> T): T {
        val held = mutableListOf<Pair<File, FileDescriptor>>(); var failure: Throwable? = null
        try {
            fun verify() { for ((path, fd) in held) {
                val now = Os.lstat(path.path); val old = Os.fstat(fd)
                check(OsConstants.S_ISDIR(now.st_mode) && now.st_dev == old.st_dev && now.st_ino == old.st_ino) { "SYNC_ARCHIVE_DIRECTORY_CHANGED" }
            } }
            var path = parent
            for (segment in listOf(null, "replica-archives-v1", ns.accountId, ns.serverInstanceId, ns.syncEpoch)) {
                currentCoroutineContext().ensureActive(); verify()
                if (segment != null) path = File(path, segment)
                if (!exists(path)) { check(create) { "SYNC_ARCHIVE_NOT_FOUND" }; Os.mkdir(path.path, 448) }
                require(OsConstants.S_ISDIR(Os.lstat(path.path).st_mode)) { "SYNC_ARCHIVE_DIRECTORY_INVALID" }
                val fd = Os.open(path.path, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW or ICON_OPEN_CLOEXEC, 0)
                held += path to fd; verify()
            }
            return block(path, ::verify, held.map { it.second })
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            var closing: Throwable? = null
            for ((_, fd) in held.asReversed()) try { Os.close(fd) } catch (close: Throwable) {
                if (failure != null) failure.addSuppressed(close)
                else if (closing == null) closing = close else closing.addSuppressed(close)
            }
            if (failure == null && closing != null) throw closing
        }
    }

    private inline fun <T> descriptor(path: File, flags: Int, block: (FileDescriptor) -> T): T {
        val fd = Os.open(path.path, flags or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW or ICON_OPEN_CLOEXEC, 384)
        var failure: Throwable? = null
        try {
            val before = Os.fstat(fd)
            require(OsConstants.S_ISREG(before.st_mode) && before.st_nlink == 1L) { "SYNC_ARCHIVE_FILE_INVALID" }
            val value = block(fd); val current = Os.lstat(path.path)
            require(OsConstants.S_ISREG(current.st_mode) && current.st_dev == before.st_dev && current.st_ino == before.st_ino) { "SYNC_ARCHIVE_FILE_CHANGED" }
            return value
        } catch (error: Throwable) { failure = error; throw error }
        finally { try { Os.close(fd) } catch (close: Throwable) { if (failure == null) throw close else failure.addSuppressed(close) } }
    }
    private fun exists(path: File): Boolean = try { Os.lstat(path.path); true } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT) false else throw error
    }
    private fun hash(bytes: ByteArray) = bytes.joinToString("") { HEX[(it.toInt() ushr 4) and 15].toString() + HEX[it.toInt() and 15] }
    companion object {
        private val lease = Mutex()
        private val MAGIC = "DayForge private replica candidate\n1\n".toByteArray(Charsets.US_ASCII)
        private const val JSON_LIMIT = 32 * 1024 * 1024L
        private const val ARCHIVE_LIMIT = 672 * 1024 * 1024L
        private const val HEX = "0123456789abcdef"
    }
}
