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
import java.nio.channels.ClosedByInterruptException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeDocumentsTest {
    private val app = InstrumentationRegistry.getInstrumentation()
    private val directory = Files.createTempDirectory(app.targetContext.filesDir.toPath(), "theme-provider-").toFile()
    private val uri = Uri.parse("content://synthetic.theme.provider/document")
    private fun sample() = app.context.assets.open("next/theme.json").use { it.readBytes() }
    private fun docs(
        read: (Uri, CancellationSignal) -> AssetFileDescriptor? = { _, _ -> error("Unexpected read") },
        write: (Uri, CancellationSignal) -> ParcelFileDescriptor? = { _, _ -> error("Unexpected write") },
        timeout: Long = 150
    ) = ThemeDocuments(read, write, timeout)
    @After fun finish() { assertTrue(directory.deleteRecursively()) }

    @Test fun localAssetSectionUsesOffsetAndLengthAndOpensExactlyOnce() = runBlocking<Unit> {
        val bytes = sample()
        val file = File(directory, "section.json").also { it.writeBytes("prefix".toByteArray() + bytes + "suffix".toByteArray()) }
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val opens = AtomicInteger()
        val source = docs(read = { _, _ ->
            opens.incrementAndGet()
            AssetFileDescriptor(descriptor, 6, bytes.size.toLong())
        }, timeout = 5000)
        val preview = source.preview(uri)
        assertArrayEquals(bytes, preview.exportBytes())
        assertEquals(1, opens.get())
        assertFalse(descriptor.fileDescriptor.valid())
    }

    @Test fun stalledReadTimesOutCancelsProviderAndClosesOwnedDescriptor() = runBlocking<Unit> {
        val pipe = ParcelFileDescriptor.createPipe()
        var signal: CancellationSignal? = null
        try {
            val source = docs(read = { _, cancellation ->
                signal = cancellation
                AssetFileDescriptor(pipe[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH)
            })
            val failure = withTimeout(3000) { runCatching { source.preview(uri) }.exceptionOrNull() }
            assertEquals("THEME_DOCUMENT_TIMEOUT", failure?.message)
            assertTrue(signal!!.isCanceled)
            assertFalse(pipe[0].fileDescriptor.valid())
        } finally { pipe.forEach { it.close() } }
    }

    @Test fun stalledWriteTimesOutAndDoesNotLeaveABackgroundWriter() = runBlocking<Unit> {
        val pipe = ParcelFileDescriptor.createPipe()
        val theme = ValidatedTheme.parse(sample() + ByteArray(800_000) { 32 })
        var signal: CancellationSignal? = null
        try {
            val target = docs(write = { _, cancellation -> signal = cancellation; pipe[1] })
            val failure = withTimeout(3000) { runCatching { target.write(uri, theme) }.exceptionOrNull() }
            assertEquals("THEME_DOCUMENT_TIMEOUT", failure?.message)
            assertTrue(signal!!.isCanceled)
            assertFalse(pipe[1].fileDescriptor.valid())
        } finally { pipe.forEach { it.close() } }
    }

    @Test fun throwingCancellationListenerStillClosesPipeAndPreservesTimeout() = runBlocking<Unit> {
        val pipe = ParcelFileDescriptor.createPipe()
        val cleanupFailure = IllegalStateException("provider cancel failed")
        try {
            val source = docs(read = { _, signal ->
                signal.setOnCancelListener { throw cleanupFailure }
                AssetFileDescriptor(pipe[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH)
            })
            val failure = withTimeout(3000) { runCatching { source.preview(uri) }.exceptionOrNull() }
            assertEquals("THEME_DOCUMENT_TIMEOUT", failure?.message)
            assertTrue(failure!!.suppressed.any { it === cleanupFailure })
            assertFalse(pipe[0].fileDescriptor.valid())
        } finally { pipe.forEach { it.close() } }
    }

    @Test fun throwingCancellationListenerDoesNotReplaceSourceFailureOrCompletedWriteFailure() = runBlocking<Unit> {
        val primary = IOException("provider read failed")
        val cleanup = IllegalStateException("provider cancel failed")
        val source = docs(read = { _, signal ->
            signal.setOnCancelListener { throw cleanup }
            throw primary
        }, timeout = 5000)
        assertSame(primary, runCatching { source.preview(uri) }.exceptionOrNull())
        assertTrue(primary.suppressed.any { it === cleanup })

        val file = File(directory, "completed.json")
        val descriptor = ParcelFileDescriptor.open(file,
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_WRITE_ONLY)
        val target = docs(write = { _, signal ->
            signal.setOnCancelListener { throw cleanup }
            descriptor
        }, timeout = 5000)
        // Bytes may already have been written. Never report success when final cleanup failed.
        assertSame(cleanup, runCatching { target.write(uri, ValidatedTheme.parse(sample())) }.exceptionOrNull())
        assertArrayEquals(sample(), file.readBytes())
        assertFalse(descriptor.fileDescriptor.valid())
    }

    @Test fun callerCancellationRemainsCancellationAndJoinsReadCleanup() = runBlocking<Unit> {
        val pipe = ParcelFileDescriptor.createPipe()
        val opened = CompletableDeferred<CancellationSignal>()
        try {
            val source = docs(read = { _, signal ->
                opened.complete(signal)
                AssetFileDescriptor(pipe[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH)
            }, timeout = 10_000)
            val read = async(Dispatchers.IO) { source.preview(uri) }
            val signal = withTimeout(3000) { opened.await() }
            withTimeout(3000) { read.cancelAndJoin() }
            assertTrue(read.isCancelled)
            assertTrue(signal.isCanceled)
            assertFalse(pipe[0].fileDescriptor.valid())
        } finally { pipe.forEach { it.close() } }
    }

    @Test fun interruptedChannelFailureCannotReplaceCallerCancellation() = runBlocking<Unit> {
        val pipe = ParcelFileDescriptor.createPipe()
        val reading = CompletableDeferred<Unit>()
        val completion = CompletableDeferred<Throwable?>()
        var signal: CancellationSignal? = null
        try {
            val documents = AppearanceDocuments(
                { _, cancellation ->
                    signal = cancellation
                    AssetFileDescriptor(pipe[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH)
                },
                { _, _ -> error("Unexpected write") },
                "THEME_DOCUMENT", 10_000
            )
            val read = async(Dispatchers.IO) {
                documents.read(uri, { _, _ ->
                    reading.complete(Unit)
                    try {
                        Thread.sleep(3000)
                        error("Expected interruption")
                    } catch (_: InterruptedException) {
                        // Deterministically reproduce the exception emitted by FileChannel.
                        throw ClosedByInterruptException()
                    }
                }, { _, _ -> error("Cancelled bytes must not be validated") })
            }
            read.invokeOnCompletion { completion.complete(it) }
            withTimeout(3000) { reading.await() }
            withTimeout(3000) { read.cancelAndJoin() }
            assertTrue(completion.await() is CancellationException)
            assertTrue(signal!!.isCanceled)
            assertFalse(pipe[0].fileDescriptor.valid())
            assertFalse(read.children.any())
        } finally { pipe.forEach { it.close() } }
    }

    @Test fun cooperativeProviderOpenReceivesCancellationOnDeadline() = runBlocking<Unit> {
        val released = CountDownLatch(1)
        val source = docs(read = { _, signal ->
            signal.setOnCancelListener { released.countDown() }
            assertTrue(released.await(3, TimeUnit.SECONDS))
            signal.throwIfCanceled()
            error("Expected cancellation")
        })
        val failure = withTimeout(4000) { runCatching { source.preview(uri) }.exceptionOrNull() }
        assertEquals("THEME_DOCUMENT_TIMEOUT", failure?.message)
        assertEquals(0, released.count)
    }

    @Test fun declaredLengthCannotSilentlyAcceptTruncatedProviderContent() = runBlocking<Unit> {
        val bytes = sample()
        val file = File(directory, "short.json").also { it.writeBytes(bytes) }
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val source = docs(read = { _, _ -> AssetFileDescriptor(descriptor, 0, bytes.size + 1L) }, timeout = 5000)
        val failure = runCatching { source.preview(uri) }.exceptionOrNull()
        assertEquals("THEME_DOCUMENT_TRUNCATED", failure?.message)
        assertFalse(descriptor.fileDescriptor.valid())
    }

    @Test fun providerFailureAndMissingDescriptorAreNotMisreportedAsTimeoutOrSuccess() = runBlocking<Unit> {
        val original = IOException("synthetic provider failure")
        val source = docs(read = { _, _ -> throw original }, timeout = 5000)
        assertSame(original, runCatching { source.preview(uri) }.exceptionOrNull())
        val missing = docs(read = { _, _ -> null }, write = { _, _ -> null }, timeout = 5000)
        assertEquals("THEME_DOCUMENT_UNAVAILABLE", runCatching { missing.preview(uri) }.exceptionOrNull()?.message)
        assertEquals("THEME_DOCUMENT_UNAVAILABLE", runCatching { missing.write(uri, ValidatedTheme.parse(sample())) }.exceptionOrNull()?.message)
    }

    @Test fun completedPipeReadWaitsForEofAndRetainsExactBytes() = runBlocking<Unit> {
        val pipe = ParcelFileDescriptor.createPipe()
        val bytes = sample()
        try {
            val producer = async(Dispatchers.IO) {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(bytes) }
            }
            val source = docs(read = { _, _ -> AssetFileDescriptor(pipe[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH) }, timeout = 5000)
            val preview = withTimeout(6000) { source.preview(uri) }
            producer.await()
            assertArrayEquals(bytes, preview.exportBytes())
            assertFalse(pipe[0].fileDescriptor.valid())
        } finally { pipe.forEach { it.close() } }
    }
}
