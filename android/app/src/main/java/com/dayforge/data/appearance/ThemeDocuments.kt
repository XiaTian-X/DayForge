package com.dayforge.data.appearance

import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One provider open per operation, bounded pipe I/O and joined descriptor cleanup on cancellation.
 * CancellationSignal covers provider opening; an arbitrary provider that ignores Binder cancellation
 * cannot be forcibly stopped by this process. No detached reader/writer is left running on timeout.
 */
internal class ThemeDocuments internal constructor(
    private val openRead: (Uri, CancellationSignal) -> AssetFileDescriptor?,
    private val openWrite: (Uri, CancellationSignal) -> ParcelFileDescriptor?,
    private val timeoutMillis: Long = 15_000
) {
    constructor(resolver: ContentResolver) : this(
        { uri, signal -> resolver.openAssetFileDescriptor(uri, "r", signal) },
        { uri, signal -> resolver.openFileDescriptor(uri, "wt", signal) }
    )

    init { require(timeoutMillis > 0) }

    suspend fun preview(uri: Uri): ValidatedTheme = bounded { signal ->
        val context = currentCoroutineContext()
        val checkpoint = { context.ensureActive() }
        (openRead(uri, signal) ?: throw IOException("THEME_DOCUMENT_UNAVAILABLE")).use { asset ->
            checkpoint()
            ParcelFileDescriptor.AutoCloseInputStream(asset.parcelFileDescriptor).use { source ->
                val channel = source.channel
                var remaining = asset.declaredLength
                val stream = object : InputStream() {
                    override fun read(): Int {
                        val single = ByteArray(1)
                        while (true) {
                            val count = read(single, 0, 1)
                            if (count < 0) return -1
                            if (count > 0) return single[0].toInt() and 255
                            // A provider may hand out an already nonblocking descriptor.
                            Thread.sleep(10)
                        }
                    }
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
                        checkpoint()
                        if (length == 0) return 0
                        if (remaining == 0L) return -1
                        val count = if (remaining < 0) length else minOf(length.toLong(), remaining).toInt()
                        val read = channel.read(ByteBuffer.wrap(bytes, offset, count))
                        if (read < 0) {
                            if (remaining > 0) throw IOException("THEME_DOCUMENT_TRUNCATED")
                            asset.parcelFileDescriptor.checkError()
                        } else if (remaining >= 0) remaining -= read
                        return read
                    }
                }
                // FileChannel is an InterruptibleChannel on every supported API. Unlike a plain
                // InputStream, cancellation closes the channel and unblocks pipe I/O without a
                // hidden fcntl API, raised minSdk, detached thread or polling-only race.
                val bytes = runInterruptible {
                    if (asset.startOffset > 0) channel.position(asset.startOffset)
                    readThemeBytes(stream, checkpoint).also { asset.parcelFileDescriptor.checkError() }
                }
                ValidatedTheme.parse(bytes, checkpoint)
            }
        }
    }

    suspend fun write(uri: Uri, theme: ValidatedTheme) = bounded { signal ->
        val context = currentCoroutineContext()
        val checkpoint = { context.ensureActive() }
        (openWrite(uri, signal) ?: throw IOException("THEME_DOCUMENT_UNAVAILABLE")).use { target ->
            checkpoint()
            val bytes = theme.exportBytes()
            ParcelFileDescriptor.AutoCloseOutputStream(target).use { output ->
                runInterruptible {
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) {
                        checkpoint()
                        if (output.channel.write(buffer) == 0) Thread.sleep(10)
                    }
                    checkpoint()
                    target.checkError()
                }
            }
        }
    }

    private suspend fun <T> bounded(action: suspend (CancellationSignal) -> T): T = supervisorScope {
        val signal = CancellationSignal()
        val task = async(Dispatchers.IO) { action(signal) }
        var primaryFailure: Throwable? = null
        try {
            withTimeoutOrNull(timeoutMillis) { Completed(task.await()) }?.value
                ?: throw IOException("THEME_DOCUMENT_TIMEOUT")
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                signal.cancel()
            } catch (cleanupFailure: Throwable) {
                val primary = primaryFailure
                if (primary == null) throw cleanupFailure
                if (cleanupFailure !== primary) primary.addSuppressed(cleanupFailure)
            } finally {
                // A provider cancellation callback can throw. The structured child must still
                // be cancelled so interruptible pipe I/O closes before this scope returns.
                task.cancel()
            }
        }
    }

    private data class Completed<T>(val value: T)
}
