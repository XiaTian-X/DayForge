package com.dayforge.data.appearance

import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.domain.model.IconBlob
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalIconFilesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val parent = Files.createTempDirectory(context.filesDir.toPath(), "icon-files-").toFile()
    private val root = File(parent, "blobs").apply { mkdir() }
    private val op = "92000000-0000-4000-8000-000000000001"
    private val other = "92000000-0000-4000-8000-000000000002"
    private val svg = """<svg width="1" height="1"><rect width="1" height="1" fill="#ff0000"/></svg>""".toByteArray()
    private val png by lazy {
        val source = InstrumentationRegistry.getInstrumentation().context.assets.open("next/png.json").bufferedReader().use { it.readText() }
        Base64.decode(Json.parseToJsonElement(source).jsonArray.first().jsonObject.getValue("png").jsonPrimitive.content, Base64.DEFAULT)
    }
    private fun blob(bytes: ByteArray, media: String = "image/svg+xml") = IconBlob(
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, bytes.size, media, 1, 1)
    private fun temporary(id: String = op) = File(root, ".install-$id.part")
    private fun errno(expected: Int, action: () -> Unit) {
        assertEquals(expected, assertThrows(ErrnoException::class.java) { action() }.errno)
    }
    @After fun cleanup() { assertTrue(parent.deleteRecursively()) }

    @Test fun realPngAndSvgPersistAcrossReopenWithoutRewritingExistingFiles() {
        for ((bytes, media) in listOf(svg to "image/svg+xml", png to "image/png")) {
            val expected = blob(bytes, media)
            val profile = if (media == "image/png") "png-v1" else "svg-v1"
            LocalIconFiles(root).use { files ->
                assertEquals(profile, files.publish(op, bytes, expected))
                assertArrayEquals(bytes, files.read(expected, profile))
            }
            val path = File(root, expected.sha256)
            val inode = Os.lstat(path.path).st_ino
            LocalIconFiles(root).use { files ->
                assertEquals(profile, files.publish(other, bytes, expected))
                assertArrayEquals(bytes, files.read(expected))
                assertEquals(inode, Os.lstat(path.path).st_ino)
                assertFalse(temporary().exists()); assertFalse(temporary(other).exists())
                assertEquals(384, Os.lstat(path.path).st_mode and 511)
                val result = files.read(expected); result.fill(0)
                assertArrayEquals(bytes, files.read(expected))
            }
        }
    }

    @Test fun invalidInputAndIdentityDoNotCreateFiles() {
        LocalIconFiles(root).use { files ->
            assertThrows(IllegalArgumentException::class.java) { files.publish("../bad", svg, blob(svg)) }
            assertThrows(IllegalArgumentException::class.java) { files.publish(op, svg + 0, blob(svg)) }
            val altered = svg.copyOf().apply { this[10] = 0 }
            assertThrows(IllegalArgumentException::class.java) { files.publish(op, altered, blob(svg)) }
            val unsafe = """<svg width="1" height="1"><script/></svg>""".toByteArray()
            assertThrows(IllegalArgumentException::class.java) { files.publish(op, unsafe, blob(unsafe)) }
            assertThrows(IllegalArgumentException::class.java) { files.cleanupTemporary("../bad") }
            assertEquals(0, root.list()!!.size)
        }
    }

    @Test fun shortWritesAreCompletedButZeroOrInvalidProgressFails() {
        val expected = blob(svg)
        LocalIconFiles(root, object : IconFileIo() {
            override fun write(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int) =
                super.write(fd, bytes, offset, minOf(length, 3))
        }).use { it.publish(op, svg, expected); assertArrayEquals(svg, it.read(expected)) }
        for (count in listOf(0, -1, svg.size + 1)) {
            val sub = File(parent, "fail$count").apply { mkdir() }
            LocalIconFiles(sub, object : IconFileIo() {
                override fun write(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int) = count
            }).use { assertThrows(IOException::class.java) { it.publish(op, svg, expected) } }
            assertFalse(File(sub, expected.sha256).exists())
            assertTrue(File(sub, ".install-$op.part").exists())
        }
    }

    @Test fun sameHandleReadbackRejectsCorruptedWritesBeforePublish() {
        val expected = blob(svg)
        LocalIconFiles(root, object : IconFileIo() {
            override fun write(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int): Int {
                val bad = bytes.copyOf().apply { this[0] = 0 }
                return super.write(fd, bad, offset, length)
            }
        }).use { assertThrows(ImageInputException::class.java) { it.publish(op, svg, expected) } }
        assertFalse(File(root, expected.sha256).exists())
        assertTrue(temporary().exists())
        LocalIconFiles(root).use { it.cleanupTemporary(op); it.publish(op, svg, expected) }
    }

    @Test fun fileSyncAndRenameFailureLeaveRecoverableTemporaryOnly() {
        val expected = blob(svg)
        val failure = IOException("synthetic stage")
        for (rename in listOf(false, true)) {
            val io = object : IconFileIo() {
                override fun sync(fd: FileDescriptor) { if (!rename) throw failure else super.sync(fd) }
                override fun rename(source: String, target: String) { throw failure }
            }
            LocalIconFiles(root, io).use {
                assertSame(failure, assertThrows(IOException::class.java) { it.publish(op, svg, expected) })
            }
            assertTrue(temporary().exists()); assertFalse(File(root, expected.sha256).exists())
            LocalIconFiles(root).use { it.cleanupTemporary(op) }
        }
        LocalIconFiles(root).use { it.publish(op, svg, expected); assertArrayEquals(svg, it.read(expected)) }
    }

    @Test fun directorySyncFailureMayAlreadyPublishAndReplayReestablishesDurability() {
        val expected = blob(svg)
        val failure = IOException("synthetic directory sync")
        LocalIconFiles(root, object : IconFileIo() {
            override fun sync(fd: FileDescriptor) {
                if (OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) throw failure
                super.sync(fd)
            }
        }).use { assertSame(failure, assertThrows(IOException::class.java) { it.publish(op, svg, expected) }) }
        assertFalse(temporary().exists())
        assertArrayEquals(svg, File(root, expected.sha256).readBytes())
        LocalIconFiles(root).use { assertEquals("svg-v1", it.publish(op, svg, expected)) }
    }

    @Test fun existingCorruptionMissingBytesAndUnknownProfileCannotBecomeReady() {
        val expected = blob(svg)
        val target = File(root, expected.sha256)
        LocalIconFiles(root).use { files ->
            errno(OsConstants.ENOENT) { files.read(expected) }
            target.writeBytes(byteArrayOf(1))
            assertThrows(ImageInputException::class.java) { files.publish(op, svg, expected) }
            assertArrayEquals(byteArrayOf(1), target.readBytes())
            target.writeBytes(svg.copyOf().apply { this[0] = 0 })
            assertEquals("IMAGE_HASH", assertThrows(ImageInputException::class.java) { files.read(expected) }.code)
            target.writeBytes(svg)
            assertThrows(IllegalArgumentException::class.java) { files.read(expected, "svg-v99") }
            assertEquals("svg-v1", files.publish(op, svg, expected))
        }
    }

    @Test fun linksDirectoriesAndFifosAreRejectedWithoutFollowingOrBlocking() {
        val expected = blob(svg)
        val outside = File(parent, "outside").apply { writeBytes(svg) }
        val target = File(root, expected.sha256)
        val symlinkRoot = File(parent, "alias")
        Os.symlink(root.path, symlinkRoot.path)
        assertThrows(IllegalArgumentException::class.java) { LocalIconFiles(symlinkRoot) }
        LocalIconFiles(root).use { files ->
            Os.symlink(outside.path, target.path)
            errno(OsConstants.ELOOP) { files.read(expected) }
            errno(OsConstants.ELOOP) { files.publish(op, svg, expected) }
            Os.remove(target.path)
            target.mkdir()
            assertThrows(IllegalArgumentException::class.java) { files.read(expected) }
            assertTrue(target.delete())
            Os.mkfifo(target.path, 384)
            assertThrows(IllegalArgumentException::class.java) { files.read(expected) }
            Os.remove(target.path)
            Os.symlink(outside.path, temporary().path)
            errno(OsConstants.EEXIST) { files.publish(op, svg, expected) }
            assertThrows(IllegalArgumentException::class.java) { files.cleanupTemporary(op) }
            assertArrayEquals(svg, outside.readBytes())
            Os.remove(temporary().path)
        }
        Os.remove(symlinkRoot.path)
    }

    @Test fun preciseCleanupPreservesOtherOperationsAndFinalBytesAndCanRetryFailures() {
        val expected = blob(svg)
        LocalIconFiles(root).use { it.publish(op, svg, expected) }
        temporary().writeBytes(byteArrayOf(1)); temporary(other).writeBytes(byteArrayOf(2))
        File(root, "unknown").writeBytes(byteArrayOf(3))
        LocalIconFiles(root, object : IconFileIo() {
            override fun unlink(path: String) { throw IOException("synthetic unlink") }
        }).use { assertThrows(IOException::class.java) { it.cleanupTemporary(op) } }
        assertTrue(temporary().exists())
        LocalIconFiles(root).use {
            it.cleanupTemporary(op); it.cleanupTemporary(op)
            assertFalse(temporary().exists()); assertTrue(temporary(other).exists())
            assertArrayEquals(byteArrayOf(3), File(root, "unknown").readBytes())
            assertArrayEquals(svg, it.read(expected))
        }
    }

    @Test fun temporaryCollisionIsPreservedAndReplacedRootOrClosedHandleCannotWrite() {
        val expected = blob(svg)
        temporary().writeBytes(byteArrayOf(9))
        val files = LocalIconFiles(root)
        try {
            errno(OsConstants.EEXIST) { files.publish(op, svg, expected) }
            assertArrayEquals(byteArrayOf(9), temporary().readBytes())
            val moved = File(parent, "moved")
            assertTrue(root.renameTo(moved)); assertTrue(root.mkdir())
            assertThrows(IllegalStateException::class.java) { files.publish(other, svg, expected) }
            assertEquals(0, root.list()!!.size)
        } finally { files.close() }
        files.close()
        assertThrows(IllegalStateException::class.java) { files.read(expected) }
        assertThrows(IllegalStateException::class.java) { files.cleanupTemporary(other) }
    }

    @Test fun partialWriteExceptionAndCleanupSyncFailureNeverClaimRollback() {
        val failure = IOException("synthetic partial IO")
        var first = true
        LocalIconFiles(root, object : IconFileIo() {
            override fun write(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int): Int {
                if (!first) throw failure
                first = false
                return super.write(fd, bytes, offset, 3)
            }
        }).use { assertSame(failure, assertThrows(IOException::class.java) { it.publish(op, svg, blob(svg)) }) }
        assertArrayEquals(svg.copyOf(3), temporary().readBytes())
        assertFalse(File(root, blob(svg).sha256).exists())
        LocalIconFiles(root, object : IconFileIo() {
            override fun sync(fd: FileDescriptor) { throw failure }
        }).use { assertSame(failure, assertThrows(IOException::class.java) { it.cleanupTemporary(op) }) }
        assertFalse(temporary().exists()) // Unlink happened; failed fsync does not restore the file.
        LocalIconFiles(root).use { it.cleanupTemporary(op); it.publish(op, svg, blob(svg)) }
    }

    @Test fun inputIsFrozenAndAReportedRenameFailureCanAlreadyHavePublished() {
        val original = svg.copyOf()
        val expected = blob(original)
        val failure = IOException("synthetic post-rename")
        LocalIconFiles(root, object : IconFileIo() {
            override fun write(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int): Int {
                original.fill(0)
                return super.write(fd, bytes, offset, length)
            }
            override fun rename(source: String, target: String) {
                super.rename(source, target)
                throw failure
            }
        }).use { assertSame(failure, assertThrows(IOException::class.java) { it.publish(op, original, expected) }) }
        assertFalse(temporary().exists())
        LocalIconFiles(root).use {
            assertArrayEquals(svg, it.read(expected))
            assertEquals("svg-v1", it.publish(op, svg, expected))
        }
    }

    @Test fun missingRootIsNotCreatedAndRootAndFileDescriptorsAreReleased() {
        val missing = File(parent, "missing")
        errno(OsConstants.ENOENT) { LocalIconFiles(missing) }
        assertFalse(missing.exists())
        assertThrows(IllegalArgumentException::class.java) { LocalIconFiles(File("/")) }
        val expected = blob(svg)
        // Count links to this exact test directory, not unrelated descriptors from the instrumentation.
        fun opened(): Int = File("/proc/self/fd").listFiles()!!.count { link ->
            try { Os.readlink(link.path).startsWith(parent.path + "/") } catch (_: Exception) { false }
        }
        val before = opened()
        repeat(20) {
            LocalIconFiles(root).use { files ->
                files.publish(op, svg, expected); files.read(expected)
                assertThrows(IllegalArgumentException::class.java) { files.read(expected, "png-v1") }
            }
        }
        assertEquals(before, opened())
    }
}
