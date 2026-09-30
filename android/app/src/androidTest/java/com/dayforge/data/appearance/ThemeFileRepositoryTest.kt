package com.dayforge.data.appearance

import android.os.Looper
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeFileRepositoryTest {
    private val app = InstrumentationRegistry.getInstrumentation()
    private val parent = Files.createTempDirectory(app.targetContext.filesDir.toPath(), "theme-version-").toFile()
    private val root = File(parent, "theme-definitions-v1")
    private val id = "50000000-0000-0000-0000-000000000001"
    private fun raw(): ByteArray = app.context.assets.open("next/theme.json").use { it.readBytes() }
    private fun input(text: String = raw().toString(Charsets.UTF_8)) = ValidatedTheme.parse(text.toByteArray())
    private fun target(revision: Int = 1) = File(root, "$id-$revision.json")
    private fun code(expected: String, action: () -> Unit) =
        assertEquals(expected, assertThrows(ThemeInputException::class.java) { action() }.code)
    @After fun cleanup() { assertTrue(parent.deleteRecursively()) }

    @Test fun inputOpensOnceOffMainClosesAndFreezesRawBytesAndAllPaletteMaps() = runBlocking {
        val bytes = raw(); val original = bytes.copyOf()
        var opened = 0; var closed = false
        val theme = ValidatedTheme.read {
            opened++
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
            object : ByteArrayInputStream(bytes) {
                override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(len, 3))
                override fun close() { closed = true; super.close() }
            }
        }
        bytes.fill(0)
        val returned = theme.exportBytes(); returned.fill(0)
        assertEquals(1, opened); assertTrue(closed); assertArrayEquals(original, theme.exportBytes())
        for (palette in listOf(theme.definition.light, theme.definition.dark)) {
            for (map in listOf(palette.material, palette.status, palette.chart)) {
                assertThrows(UnsupportedOperationException::class.java) { (map as MutableMap).clear() }
            }
        }
        assertEquals("#245EAC", theme.definition.light.material.getValue("primary"))
        val bom = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + original
        assertArrayEquals(bom, ValidatedTheme.parse(bom).exportBytes())
        assertEquals(theme.definition, ValidatedTheme.parse(bom).definition)
    }

    @Test fun boundedParsedCacheStillReadsChangedMissingAndUnsafeFiles() = runBlocking<Unit> {
        val repo = ThemeFileRepository(parent)
        val original = raw()
        repo.install(input())
        val first = repo.read(id, 1)
        assertSame(first, repo.read(id, 1))
        first.exportBytes().fill(0)
        assertArrayEquals(original, repo.read(id, 1).exportBytes())
        target().writeText(original.toString(Charsets.UTF_8).replace("#245EAC", "#123456"))
        val changed = repo.read(id, 1)
        assertNotSame(first, changed)
        assertEquals("#123456", changed.definition.light.material.getValue("primary"))
        assertEquals("#245EAC", first.definition.light.material.getValue("primary"))
        target().writeText("not JSON")
        assertThrows(ThemeInputException::class.java) { runBlocking { repo.read(id, 1) } }
        target().writeBytes(original)
        assertSame(first, repo.read(id, 1))
        assertTrue(target().delete())
        assertThrows(Exception::class.java) { runBlocking { repo.read(id, 1) } }
        val outside = File(parent, "outside.json").also { it.writeBytes(original) }
        Os.symlink(outside.path, target().path)
        assertThrows(Exception::class.java) { runBlocking { repo.read(id, 1) } }
        assertArrayEquals(original, outside.readBytes())
        assertTrue(target().delete())
        target().writeBytes(original)
        for (revision in 2..3) {
            repo.install(input(original.toString(Charsets.UTF_8).replace("\"revision\": 1", "\"revision\": $revision")))
            assertEquals(revision, repo.read(id, revision).definition.revision)
        }
        // Two newer versions evict the first parsed object; the bytes and meaning stay unchanged.
        val evicted = repo.read(id, 1)
        assertNotSame(first, evicted)
        assertArrayEquals(original, evicted.exportBytes())
    }

    @Test fun strictJsonRejectsDuplicateEscapedKeysUnicodeDepthVersionsAndWrongTypes() {
        val source = raw().toString(Charsets.UTF_8)
        code("THEME_DUPLICATE") { input(source.replaceFirst("{", "{\"format\":\"dayforge.theme\",")) }
        code("THEME_DUPLICATE") { input(source.replaceFirst("{", "{\"for\\u006dat\":\"dayforge.theme\",")) }
        code("THEME_UTF8") { ValidatedTheme.parse(byteArrayOf(0xc3.toByte(), 0x28)) }
        code("THEME_UNICODE") { input(source.replaceFirst("{", "{\"\\uD800\":null,")) }
        code("THEME_DEPTH") { input("[".repeat(17) + "0" + "]".repeat(17)) }
        code("THEME_JSON") { input(source + " {}") }
        code("THEME_VERSION") { input(source.replace("dayforge.theme", "legacy.theme")) }
        code("THEME_VERSION") { input("{\"id\":\"legacy\",\"seedColor\":\"#000000\"}") }
        code("THEME_JSON") { input(source.replaceFirst("{", "{\"unknown\":0,")) }
        assertThrows(ThemeInputException::class.java) { input(source.replace("\"revision\": 1", "\"revision\": \"1\"")) }
        assertThrows(ThemeInputException::class.java) { input(source.replace("#245EAC", "#1234567")) }
    }

    @Test fun exactLimitEofZeroReadsAndInvalidReadContractsAreEnforced() = runBlocking {
        val bytes = raw()
        val boundary = bytes + ByteArray(THEME_FILE_LIMIT - bytes.size) { 32 }
        assertEquals(THEME_FILE_LIMIT, ValidatedTheme.read { ByteArrayInputStream(boundary) }.exportBytes().size)
        val tooLarge = boundary + 32
        var consumed = 0; var closed = false
        val source = object : ByteArrayInputStream(tooLarge) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) consumed += it }
            override fun close() { closed = true }
        }
        val failure = runCatching { ValidatedTheme.read { source } }.exceptionOrNull()
        assertEquals("THEME_LIMIT", (failure as ThemeInputException).code)
        assertEquals(THEME_FILE_LIMIT + 1, consumed); assertTrue(closed)
        val short = object : ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int) = 0
        }
        assertArrayEquals(bytes, ValidatedTheme.read { short }.exportBytes())
        for (count in listOf(-2, 9000)) {
            val bad = object : InputStream() {
                override fun read() = 0
                override fun read(b: ByteArray, off: Int, len: Int) = count
            }
            val rejected = runCatching { ValidatedTheme.read { bad } }.exceptionOrNull()
            assertEquals("THEME_READ_INVALID", (rejected as ThemeInputException).code)
        }
    }

    @Test fun sourceIoAndCancellationPropagateAndCloseWithoutWriting() = runBlocking {
        val failure = IOException("synthetic input")
        var closed = false
        val source = object : InputStream() {
            override fun read(): Int = throw failure
            override fun close() { closed = true }
        }
        assertSame(failure, runCatching { ValidatedTheme.read { source } }.exceptionOrNull())
        assertTrue(closed); assertFalse(root.exists())
        val entered = CompletableDeferred<Unit>(); val release = java.util.concurrent.CountDownLatch(1)
        var cancelledClosed = false
        val job = launch {
            ValidatedTheme.read {
                object : ByteArrayInputStream(raw()) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        entered.complete(Unit); check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                        return super.read(b, off, minOf(3, len))
                    }
                    override fun close() { cancelledClosed = true }
                }
            }
        }
        try { entered.await(); job.cancel() } finally { release.countDown() }
        job.join(); assertTrue(job.isCancelled); assertTrue(cancelledClosed); assertFalse(root.exists())
    }

    @Test fun installReopenAndReplayPreserveOriginalBytesWithoutTouchingOldThemeFiles() = runBlocking {
        val old = File(parent, "themes").apply { mkdir() }
        File(old, "old.json").writeText("old untouched")
        val preview = input()
        ThemeFileRepository(parent).install(preview)
        val inode = Os.lstat(target().path).st_ino
        val mode = Os.lstat(target().path).st_mode and 511
        assertEquals(384, mode)
        val reopened = ThemeFileRepository(parent).read(id, 1)
        assertArrayEquals(raw(), reopened.exportBytes()); assertEquals(preview.definition, reopened.definition)
        val differentlyFormatted = input(" \n" + raw().toString(Charsets.UTF_8))
        val replay = ThemeFileRepository(parent).install(differentlyFormatted)
        assertEquals(inode, Os.lstat(target().path).st_ino); assertArrayEquals(raw(), replay.exportBytes())
        assertEquals("old untouched", File(old, "old.json").readText())
        assertEquals(1, root.list()!!.size)
    }

    @Test fun sameVersionConflictAndMalformedExistingFileCannotOverwriteButNewRevisionCanInstall() = runBlocking {
        val repo = ThemeFileRepository(parent); val preview = input()
        repo.install(preview)
        val different = input(raw().toString(Charsets.UTF_8).replace("#245EAC", "#000000"))
        assertEquals("THEME_VERSION_REUSED", (runCatching { repo.install(different) }.exceptionOrNull() as ThemeInputException).code)
        assertArrayEquals(raw(), target().readBytes())
        val next = input(raw().toString(Charsets.UTF_8).replace("\"revision\": 1", "\"revision\": 2"))
        assertEquals(2, next.definition.revision)
        repo.install(next); assertEquals(next.definition, repo.read(id, 2).definition)
        target().writeText("corrupted")
        assertTrue(runCatching { repo.install(preview) }.isFailure)
        assertEquals("corrupted", target().readText()); assertTrue(target(2).isFile)
    }

    @Test fun separateRepositoryInstancesSerializeConcurrentExactAndConflictingImports() = runBlocking {
        val preview = input()
        val imports = (1..8).map { async { ThemeFileRepository(parent).install(preview) } }.awaitAll()
        assertTrue(imports.all { it.definition == preview.definition }); assertEquals(1, root.list()!!.size)
        val changed = input(raw().toString(Charsets.UTF_8).replace("#245EAC", "#000000"))
        val conflicts = (1..8).map { async { runCatching { ThemeFileRepository(parent).install(changed) }.exceptionOrNull() } }.awaitAll()
        assertTrue(conflicts.all { it is ThemeInputException && it.code == "THEME_VERSION_REUSED" })
        assertArrayEquals(raw(), target().readBytes())
    }

    @Test fun shortWritesAreCompletedAndCorruptZeroPartialWritesNeverPublish() = runBlocking {
        val preview = input()
        ThemeFileRepository(parent, object : ThemeFileIo() {
            override fun write(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int) = super.write(fd, bytes, offset, minOf(3, length))
        }).install(preview)
        assertArrayEquals(raw(), target().readBytes())
        for (mode in 0..4) {
            val separate = File(parent, "failure$mode").apply { mkdir() }
            val io = object : ThemeFileIo() {
                override fun write(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int): Int = when (mode) {
                    0 -> 0
                    1 -> { super.write(fd, bytes, offset, minOf(3, length)); throw IOException("partial") }
                    3 -> -1
                    4 -> length + 1
                    else -> super.write(fd, bytes.copyOf().apply { fill(0) }, offset, length)
                }
            }
            assertTrue(runCatching { ThemeFileRepository(separate, io).install(preview) }.isFailure)
            assertEquals(0, File(separate, "theme-definitions-v1").list()!!.size)
            ThemeFileRepository(separate).install(preview)
        }
    }

    @Test fun fileSyncRenameAndPostPublishSyncFailuresHaveTruthfulRetrySemantics() = runBlocking {
        val preview = input()
        for (stage in listOf("file-sync", "rename", "post-rename", "directory-sync")) {
            val separate = File(parent, stage).apply { mkdir() }
            val directory = File(separate, "theme-definitions-v1")
            var published = false
            val failure = IOException("synthetic $stage")
            val io = object : ThemeFileIo() {
                override fun sync(fd: FileDescriptor) {
                    if (stage == "file-sync" && OsConstants.S_ISREG(Os.fstat(fd).st_mode) || stage == "directory-sync" && published) throw failure
                    super.sync(fd)
                }
                override fun rename(source: String, target: String) {
                    if (stage == "rename") throw failure
                    super.rename(source, target); published = true
                    if (stage == "post-rename") throw failure
                }
            }
            assertSame(failure, runCatching { ThemeFileRepository(separate, io).install(preview) }.exceptionOrNull())
            val final = File(directory, "$id-1.json")
            assertEquals(published, final.exists())
            if (published) assertArrayEquals(raw(), final.readBytes())
            assertEquals(if (published) 1 else 0, directory.list()!!.size)
            ThemeFileRepository(separate).install(preview)
            assertArrayEquals(raw(), final.readBytes())
        }
    }

    @Test fun linksSpecialFilesIdentityMismatchAndAbsentReadsNeverCreateOrReplaceData() = runBlocking {
        val repo = ThemeFileRepository(parent)
        assertTrue(runCatching { repo.read(id, 1) }.isFailure); assertFalse(root.exists())
        for (bad in listOf("../x", "", "ABCDEF00-0000-0000-0000-000000000001")) {
            assertTrue(runCatching { repo.read(bad, 1) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertTrue(runCatching { repo.read(id, 0) }.exceptionOrNull() is IllegalArgumentException); assertFalse(root.exists())
        val outside = File(parent, "outside").apply { mkdir() }
        Os.symlink(outside.path, root.path)
        assertTrue(runCatching { repo.install(input()) }.isFailure); assertEquals(0, outside.list()!!.size)
        Os.remove(root.path); root.mkdir()
        val original = File(outside, "keep").apply { writeBytes(raw()) }
        Os.symlink(original.path, target().path)
        assertTrue(runCatching { repo.read(id, 1) }.isFailure); assertTrue(runCatching { repo.install(input()) }.isFailure)
        assertArrayEquals(raw(), original.readBytes()); Os.remove(target().path)
        Os.mkfifo(target().path, 384)
        assertTrue(runCatching { repo.read(id, 1) }.isFailure); Os.remove(target().path)
        target().mkdir(); assertTrue(runCatching { repo.read(id, 1) }.isFailure); target().delete()
        target().writeText(raw().toString(Charsets.UTF_8).replace("\"revision\": 1", "\"revision\": 2"))
        assertEquals("THEME_FILE_IDENTITY", (runCatching { repo.read(id, 1) }.exceptionOrNull() as ThemeInputException).code)
    }

    @Test fun invalidIdentitiesCannotReadInstallFindDeleteOrDiscardOutsideFiles() = runBlocking {
        val repo = ThemeFileRepository(parent)
        repo.install(input())
        val sentinel = File(parent, "sentinel.json").apply { writeBytes(raw()) }
        val invalid = listOf("../sentinel", "nested/../../sentinel", "/sentinel", "a\\b",
            ".", "..", "", " ", "bad\u0000id", "bad\nid", "界".repeat(81),
            "ABCDEF00-0000-0000-0000-000000000001")
        for (bad in invalid) {
            assertTrue(bad, runCatching { repo.read(bad, 1) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(bad, runCatching { repo.find(bad, 1) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(bad, runCatching { repo.removeVersion(bad, 1) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(bad, runCatching { repo.discardOperation(bad) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(bad, runCatching { repo.install(input(), bad) }.exceptionOrNull() is IllegalArgumentException)
            val malformed = raw().toString(Charsets.UTF_8)
                .replace("\"$id\"", kotlinx.serialization.json.JsonPrimitive(bad).toString())
            assertTrue(bad, runCatching { repo.install(input(malformed)) }.isFailure)
            assertArrayEquals(raw(), sentinel.readBytes())
            assertArrayEquals(raw(), target().readBytes())
            assertEquals(listOf(target().name), root.list()!!.toList())
        }
        for (revision in listOf(0, -1, Int.MIN_VALUE)) {
            assertTrue(runCatching { repo.read(id, revision) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { repo.find(id, revision) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { repo.removeVersion(id, revision) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertArrayEquals(raw(), target().readBytes())
    }

    @Test fun danglingVersionLinkIsNotFollowedOrRemovedByAnyStorageEntry() = runBlocking {
        val repo = ThemeFileRepository(parent)
        root.mkdir()
        val absent = File(parent, "never-create.json")
        Os.symlink(absent.path, target().path)
        assertTrue(runCatching { repo.read(id, 1) }.isFailure)
        assertTrue(runCatching { repo.find(id, 1) }.isFailure)
        assertTrue(runCatching { repo.install(input()) }.isFailure)
        assertTrue(runCatching { repo.removeVersion(id, 1) }.isFailure)
        assertTrue(Files.isSymbolicLink(target().toPath()))
        assertFalse(absent.exists())
        Os.remove(target().path)
    }

    @Test fun cleanupPreservesUnknownFilesAndPrimaryFailureWithSuppressedCleanupError() = runBlocking {
        root.mkdir()
        val unknown = File(root, ".theme-unknown.part").apply { writeText("keep") }
        val primary = IOException("write failed"); val cleanup = IOException("cleanup failed")
        val repo = ThemeFileRepository(parent, object : ThemeFileIo() {
            override fun write(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int): Int = throw primary
            override fun remove(path: String) = throw cleanup
        })
        assertSame(primary, runCatching { repo.install(input()) }.exceptionOrNull())
        assertTrue(primary.suppressed.contains(cleanup)); assertEquals("keep", unknown.readText())
        assertEquals(2, root.list()!!.size); assertFalse(target().exists())
        ThemeFileRepository(parent).install(input())
        assertEquals(3, root.list()!!.size); assertEquals("keep", unknown.readText())
    }

    @Test fun repeatedSuccessAndRejectionCloseAllOwnedFileDescriptors() = runBlocking {
        fun opened(): Int = File("/proc/self/fd").listFiles()!!.count {
            try { Os.readlink(it.path).startsWith(parent.path + "/") } catch (_: Exception) { false }
        }
        val before = opened()
        repeat(20) {
            val repo = ThemeFileRepository(parent); repo.install(input()); repo.read(id, 1)
            assertTrue(runCatching { repo.read(id, 2) }.isFailure)
        }
        assertEquals(before, opened())
    }

    @Test fun cancellationBeforeAndAfterPublicationKeepsOldVersionAndDoesNotLieAboutRollback() = runBlocking {
        val previous = input()
        val next = input(raw().toString(Charsets.UTF_8).replace("\"revision\": 1", "\"revision\": 2"))
        for (afterPublish in listOf(false, true)) {
            val separate = File(parent, "cancel$afterPublish").apply { mkdir() }
            ThemeFileRepository(separate).install(previous)
            val job = launch {
                val running = currentCoroutineContext()[Job]!!
                ThemeFileRepository(separate, object : ThemeFileIo() {
                    override fun write(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int): Int {
                        val count = super.write(fd, bytes, offset, minOf(3, length))
                        if (!afterPublish) running.cancel(CancellationException("synthetic before publish"))
                        return count
                    }
                    override fun rename(source: String, target: String) {
                        super.rename(source, target)
                        if (afterPublish) running.cancel(CancellationException("synthetic after publish"))
                    }
                }).install(next)
            }
            job.join(); assertTrue(job.isCancelled)
            val directory = File(separate, "theme-definitions-v1")
            assertEquals(afterPublish, File(directory, "$id-2.json").exists())
            assertEquals(if (afterPublish) 2 else 1, directory.list()!!.size)
            assertArrayEquals(raw(), ThemeFileRepository(separate).read(id, 1).exportBytes())
            ThemeFileRepository(separate).install(next)
            assertEquals(next.definition, ThemeFileRepository(separate).read(id, 2).definition)
        }
    }

    @Test fun replacedDirectoryStopsPublicationAndPreservesUnrelatedReplacement() = runBlocking {
        var replaced = false
        val moved = File(parent, "moved")
        val io = object : ThemeFileIo() {
            override fun write(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int): Int {
                if (!replaced) {
                    replaced = true
                    assertTrue(root.renameTo(moved)); assertTrue(root.mkdir())
                    File(root, "unrelated").writeText("keep")
                }
                return super.write(fd, bytes, offset, length)
            }
        }
        val failure = runCatching { ThemeFileRepository(parent, io).install(input()) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException); assertEquals("THEME_DIRECTORY_CHANGED", failure!!.message)
        assertFalse(target().exists()); assertEquals("keep", File(root, "unrelated").readText())
        assertEquals(1, moved.list()!!.size); assertTrue(moved.list()!!.single().endsWith(".part"))
    }
}
