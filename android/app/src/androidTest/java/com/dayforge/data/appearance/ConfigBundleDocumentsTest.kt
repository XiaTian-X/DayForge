package com.dayforge.data.appearance

import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConfigBundleDocumentsTest {
    private val app = InstrumentationRegistry.getInstrumentation()
    private val directory = Files.createTempDirectory(app.targetContext.filesDir.toPath(), "config-provider-").toFile()
    private val uri = Uri.parse("content://synthetic.config.provider/document")
    private fun docs(read: (Uri, CancellationSignal) -> AssetFileDescriptor? = { _, _ -> error("Unexpected read") },
        write: (Uri, CancellationSignal) -> ParcelFileDescriptor? = { _, _ -> error("Unexpected write") },
        timeout: Long = 5000) = ConfigBundleDocuments(read, write, timeout)
    @After fun finish() { assertTrue(directory.deleteRecursively()) }

    @Test fun actualDescriptorSectionPreviewIsFrozenAndExportWritesExactlyThatArchive() = runBlocking {
        val bundle = ConfigBundleOutput.create(ConfigFileFixture.manifest(), ConfigFileFixture::content)
        val original = bundle.exportBytes()
        val file = File(directory, "source.zip").also { it.writeBytes("prefix".toByteArray() + original + "suffix".toByteArray()) }
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val reads = AtomicInteger()
        val preview = docs(read = { _, _ ->
            assertNotEquals(android.os.Looper.getMainLooper().thread, Thread.currentThread())
            reads.incrementAndGet(); AssetFileDescriptor(descriptor, 6, original.size.toLong())
        }).preview(uri)
        assertFalse(descriptor.fileDescriptor.valid())
        file.writeBytes(byteArrayOf(0))
        assertEquals(bundle.manifest, preview.manifest)
        assertArrayEquals(original, preview.exportBytes())
        val target = File(directory, "export.zip")
        val output = ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_WRITE_ONLY)
        var writes = 0
        docs(write = { _, _ -> writes++; output }).write(uri, preview)
        assertFalse(output.fileDescriptor.valid())
        assertEquals(1, reads.get()); assertEquals(1, writes)
        assertArrayEquals(original, target.readBytes())
        assertEquals(bundle.manifest, ValidatedConfigBundle.parse(target.readBytes()).manifest)
    }

    @Test fun truncatedOrInvalidProviderContentNeverProducesPreviewAndClosesDescriptors() = runBlocking {
        val bytes = ConfigFileFixture.archive(Json.encodeToString(ConfigFileFixture.empty()).toByteArray())
        val file = File(directory, "input.zip").also { it.writeBytes(bytes) }
        val truncated = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val failure = runCatching { docs(read = { _, _ -> AssetFileDescriptor(truncated, 0, bytes.size + 1L) }).preview(uri) }.exceptionOrNull()
        assertEquals("CONFIG_DOCUMENT_TRUNCATED", failure?.message)
        assertFalse(truncated.fileDescriptor.valid())
        file.writeBytes(byteArrayOf(0))
        val invalid = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        assertTrue(runCatching { docs(read = { _, _ -> AssetFileDescriptor(invalid, 0, 1) }).preview(uri) }.exceptionOrNull() is ConfigBundleInputException)
        assertFalse(invalid.fileDescriptor.valid())
    }

    @Test fun readTimeoutAndCallerCancellationJoinActualPipeAndProviderCleanup() = runBlocking {
        for (cancel in listOf(false, true)) {
            val pipe = ParcelFileDescriptor.createPipe()
            val opened = CompletableDeferred<CancellationSignal>()
            try {
                val source = docs(read = { _, signal -> opened.complete(signal)
                    AssetFileDescriptor(pipe[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH)
                }, timeout = if (cancel) 10_000 else 150)
                if (cancel) {
                    val task = async(Dispatchers.IO) { source.preview(uri) }
                    val signal = withTimeout(3000) { opened.await() }
                    withTimeout(3000) { task.cancelAndJoin() }
                    assertTrue(task.isCancelled); assertFalse(task.children.any()); assertTrue(signal.isCanceled)
                } else {
                    val failure = withTimeout(3000) { runCatching { source.preview(uri) }.exceptionOrNull() }
                    assertEquals("CONFIG_DOCUMENT_TIMEOUT", failure?.message)
                    assertTrue(opened.await().isCanceled)
                }
                assertFalse(pipe[0].fileDescriptor.valid())
            } finally { pipe.forEach { it.close() } }
        }
    }

    @Test fun stalledLargeArchiveWriteTimesOutAndLeavesNoDetachedWriter() = runBlocking {
        val raw = Json.encodeToString(ConfigFileFixture.empty()).padEnd(800_000, ' ').toByteArray()
        val bundle = ValidatedConfigBundle.parse(ConfigFileFixture.archive(raw))
        assertTrue(bundle.exportBytes().size > 800_000)
        val pipe = ParcelFileDescriptor.createPipe()
        var signal: CancellationSignal? = null
        try {
            val target = docs(write = { _, cancellation -> signal = cancellation; pipe[1] }, timeout = 150)
            val failure = withTimeout(3000) { runCatching { target.write(uri, bundle) }.exceptionOrNull() }
            assertEquals("CONFIG_DOCUMENT_TIMEOUT", failure?.message)
            assertTrue(signal!!.isCanceled); assertFalse(pipe[1].fileDescriptor.valid())
        } finally { pipe.forEach { it.close() } }
    }

    @Test fun missingDescriptorOriginalIoAndCloseFailureAreNeverReportedAsSuccessfulExport() = runBlocking {
        val bundle = ConfigBundleOutput.create(ConfigFileFixture.empty()) { error("No source") }
        val failure = IOException("original provider failure")
        assertSame(failure, runCatching { docs(read = { _, _ -> throw failure }).preview(uri) }.exceptionOrNull())
        assertSame(failure, runCatching { docs(write = { _, _ -> throw failure }).write(uri, bundle) }.exceptionOrNull())
        val missing = docs(read = { _, _ -> null }, write = { _, _ -> null })
        assertEquals("CONFIG_DOCUMENT_UNAVAILABLE", runCatching { missing.preview(uri) }.exceptionOrNull()?.message)
        assertEquals("CONFIG_DOCUMENT_UNAVAILABLE", runCatching { missing.write(uri, bundle) }.exceptionOrNull()?.message)
        val file = File(directory, "completed.zip")
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_WRITE_ONLY)
        val cleanup = IllegalStateException("provider cleanup failed")
        val target = docs(write = { _, signal -> signal.setOnCancelListener { throw cleanup }; descriptor })
        assertSame(cleanup, runCatching { target.write(uri, bundle) }.exceptionOrNull())
        assertArrayEquals(bundle.exportBytes(), file.readBytes())
        assertFalse(descriptor.fileDescriptor.valid())
    }
}
