package com.dayforge.data.history

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.repository.G0PhaseTrace
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Observations must never replace strict codec work or outlive the trace context. */
@RunWith(AndroidJUnit4::class)
class HistoryBlockDiagnosticsTest {
    private val directory = AuthenticatedHistoryDirectory()
    private val day = LocalDate.of(2026, 10, 5)
    private val key = HistoryBlockPlanner.OrderKey(day, "fact")
    private val unit = HistoryBlockPlanner.UnitKey(HistoryBlockPlanner.Partition(
        HistoryBlockPlanner.Scope("binding"), "domain", "object", null), YearMonth.from(day))
    private val record = HistoryBlockCodec.Record(key, "request", HistoryBlockCodec.Bytes.copyOf(
        "{\"名称\":\"家庭\"}".toByteArray(Charsets.UTF_8)))
    private fun built() = directory.build(unit, listOf(record))

    @Test fun observingDecodeCannotTurnDamagedBytesIntoASuccessfulNode() = runBlocking<Unit> {
        val built = built(); val stages = mutableListOf<Pair<HistoryBlockDiagnostics.Stage, Boolean>>()
        val observer = HistoryBlockDiagnostics.Observer { stage ->
            HistoryBlockDiagnostics.Observation { success -> stages += stage to success }
        }
        val bytes = built.blocks.getValue(built.root.digest).toByteArray()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        val failure = runCatching { withContext(HistoryBlockDiagnostics.current.asContextElement(observer)) {
            directory.readPoint(built.root, key, AuthenticatedHistoryDirectory.Loader { _, _ -> bytes })
        } }.exceptionOrNull()
        assertTrue(failure is HistoryBlockCodec.Rejected)
        assertEquals(HistoryBlockCodec.Code.HASH_MISMATCH, (failure as HistoryBlockCodec.Rejected).code)
        assertEquals(listOf(HistoryBlockDiagnostics.Stage.NODE_DECODE to false), stages)
    }

    @Test fun damagedHashRemainsPrimaryWhenObservationAlsoFails() {
        val built = built(); val expectedEnd = IllegalStateException("end failure")
        val previous = HistoryBlockDiagnostics.current.get(); var ended = false
        HistoryBlockDiagnostics.current.set(HistoryBlockDiagnostics.Observer {
            HistoryBlockDiagnostics.Observation { success ->
                assertFalse(success); ended = true; throw expectedEnd
            }
        })
        val bytes = built.blocks.getValue(built.root.digest).toByteArray()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        val failure = try {
            runCatching { directory.readPoint(built.root, key,
                AuthenticatedHistoryDirectory.Loader { _, _ -> bytes }) }.exceptionOrNull()
        } finally { HistoryBlockDiagnostics.current.set(previous) }
        assertTrue(ended); assertTrue(failure is HistoryBlockCodec.Rejected)
        val primary = failure as HistoryBlockCodec.Rejected
        assertEquals(HistoryBlockCodec.Code.HASH_MISMATCH, primary.code)
        assertEquals(listOf(expectedEnd), primary.suppressed.toList())
        assertSame(previous, HistoryBlockDiagnostics.current.get())
    }

    @Test fun codecCancellationRemainsPrimaryWithFailingOrSelfSuppressedObservation() {
        val built = built(); val previous = HistoryBlockDiagnostics.current.get()
        for (sameFailure in listOf(false, true)) {
            val expected = CancellationException("codec cancelled")
            val expectedEnd = if (sameFailure) expected else IllegalStateException("end failure")
            var decoding = false; var ended = false
            HistoryBlockDiagnostics.current.set(HistoryBlockDiagnostics.Observer { stage ->
                assertEquals(HistoryBlockDiagnostics.Stage.NODE_DECODE, stage)
                decoding = true
                HistoryBlockDiagnostics.Observation { success ->
                    assertFalse(success); ended = true; throw expectedEnd
                }
            })
            val failure = try {
                runCatching { directory.readPoint(built.root, key,
                    AuthenticatedHistoryDirectory.Loader { digest, _ -> built.blocks[digest]?.toByteArray() },
                    checkCancelled = { if (decoding) throw expected }) }.exceptionOrNull()
            } finally { HistoryBlockDiagnostics.current.set(previous) }
            assertTrue(decoding); assertTrue(ended); assertSame(expected, failure)
            assertEquals(if (sameFailure) emptyList<Throwable>() else listOf(expectedEnd), expected.suppressed.toList())
            assertSame(previous, HistoryBlockDiagnostics.current.get())
        }
    }

    @Test fun failingObservationPoisonsTheSessionInsteadOfReturningOrCachingAPartialSuccess() = runBlocking<Unit> {
        val built = built(); val identity = Any(); val expected = IllegalStateException("diagnostic failure")
        var observations = 0
        val observer = HistoryBlockDiagnostics.Observer {
            observations++
            HistoryBlockDiagnostics.Observation { throw expected }
        }
        val failure = runCatching { withContext(HistoryBlockDiagnostics.current.asContextElement(observer)) {
            HistoryNodeSession.use(identity, built.root, HistoryNodeSession.Lookup { digest, _ -> built.blocks[digest] }) { session ->
                val tree = session.tree(identity, built.root)
                assertSame(expected, runCatching { directory.readPoint(built.root, key, tree) }.exceptionOrNull())
                assertSame(expected, runCatching { directory.readPoint(built.root, key, tree) }.exceptionOrNull())
            }
        } }.exceptionOrNull()
        assertSame(expected, failure); assertEquals(1, observations)
    }

    @Test fun g0TraceBridgeKeepsCountsAcrossDispatchersAndRestoresThePreviousObserver() = runBlocking<Unit> {
        val built = built(); val previous = HistoryBlockDiagnostics.current.get()
        var outerCalls = 0
        val outer = HistoryBlockDiagnostics.Observer {
            outerCalls++; HistoryBlockDiagnostics.Observation { }
        }
        withContext(HistoryBlockDiagnostics.current.asContextElement(outer)) {
            val trace = G0PhaseTrace(false)
            trace.run {
                repeat(2) {
                    val result = withContext(Dispatchers.Default) {
                        directory.readPoint(built.root, key, AuthenticatedHistoryDirectory.Loader { digest, _ ->
                            built.blocks[digest]?.toByteArray()
                        })
                    }
                    assertEquals(listOf(record), result.records)
                    assertArrayEquals(record.payload.toByteArray(), result.records.single().payload.toByteArray())
                }
            }
            assertSame(outer, HistoryBlockDiagnostics.current.get()); assertEquals(0, outerCalls)
            assertEquals(2, trace.count("count_block_node_decode"))
            assertTrue(trace.spans.filter { it.phase == "count_block_node_decode" }.all {
                it.ok && (it.cpuNanos == null || it.cpuNanos >= 0L)
            })
            val expected = IllegalStateException("leave trace")
            assertSame(expected, runCatching { G0PhaseTrace(false).run { throw expected } }.exceptionOrNull())
            assertSame(outer, HistoryBlockDiagnostics.current.get())
        }
        assertSame(previous, HistoryBlockDiagnostics.current.get())
    }
}
