package com.dayforge.data.repository

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Random
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Executed on the physical Android device; exact old encoding remains the independent oracle. */
@RunWith(AndroidJUnit4::class)
class ReplicaStorageEncodingTest {
    @Test fun fullByteAlphabetAndLargeBlobsMatchOriginalLowercaseEncoding() {
        assertEquals("", replicaStorageBlobHex(byteArrayOf()))
        assertEquals("00ff807f", replicaStorageBlobHex(byteArrayOf(0, -1, -128, 127)))
        for (size in listOf(256, 16384, 262144)) {
            val bytes = ByteArray(size)
            if (size == 256) bytes.indices.forEach { bytes[it] = it.toByte() } else Random(41L).nextBytes(bytes)
            val untouched = bytes.copyOf()
            val oldStart = SystemClock.elapsedRealtimeNanos()
            val expected = bytes.joinToString("") { "%02x".format(it) }
            val oldElapsed = SystemClock.elapsedRealtimeNanos() - oldStart
            val newStart = SystemClock.elapsedRealtimeNanos()
            val actual = replicaStorageBlobHex(bytes)
            val newElapsed = SystemClock.elapsedRealtimeNanos() - newStart
            assertEquals(expected, actual); assertArrayEquals(untouched, bytes); assertEquals(size * 2, actual.length)
            Log.i("ReplicaStorageEncoding", "bytes=$size old_ns=$oldElapsed new_ns=$newElapsed exact=true")
        }
    }
    @Test fun cancellationNeverReturnsAPrefixAndFollowingEncodeStartsFresh() {
        val bytes = ByteArray(16384) { it.toByte() }; var polls = 0
        val cancellation = CancellationException("STORAGE_HEX_CANCELLED")
        val caught = try {
            replicaStorageBlobHex(bytes) { if (++polls == 3) throw cancellation }
            throw AssertionError("Cancellation must propagate")
        } catch (error: CancellationException) { error }
        assertSame(cancellation, caught); assertEquals(3, polls)
        assertEquals(bytes.joinToString("") { "%02x".format(it) }, replicaStorageBlobHex(bytes))
    }
}
