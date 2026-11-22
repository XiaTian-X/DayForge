package com.dayforge.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.local.DatabaseEpochPublication
import com.dayforge.data.local.DatabaseEpochValue
import com.dayforge.data.local.DatabaseObjectSelection
import com.dayforge.data.local.DatabaseRuntimeHost
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Android execution of the UI MEMORY adapters only. Host has only a mocked synchronous
 * listener registration; there is no mocked database, recovered root or suspend business spy.
 * These cases deliberately do not prove physical graph replacement or commit correctness.
 */
@RunWith(AndroidJUnit4::class)
class EpochPublishedStateTest {
    private class Memory {
        val host = mockk<DatabaseRuntimeHost>()
        private val monitor = Any()
        private var epoch = 1
        private val listeners = arrayListOf<DatabaseRuntimeHost.MemoryListener>()
        init { every { host.observeMemory(any()) } answers {
            val listener = firstArg<DatabaseRuntimeHost.MemoryListener>()
            listeners += listener; listener.transition(false); Any()
        } }
        fun source(): DatabaseEpochPublication {
            val issued = synchronized(monitor) { epoch }
            return DatabaseEpochPublication(host, { synchronized(monitor) { issued == epoch } }, { assignment ->
                synchronized(monitor) { if (issued == epoch) { assignment(); true } else false }
            }, { assignment -> synchronized(monitor) { if (issued == epoch) assignment() else false } })
        }
        fun replace() {
            synchronized(monitor) { epoch++ }
            listeners.toList().forEach { it.transition(true) }
            listeners.toList().forEach { it.transition(false) }
        }
        fun noIssuanceOrDatabaseAccess() {
            verify(exactly = 0) { host.currentPublication() }
            verify(exactly = 0) { host.boundGraph() }
        }
    }

    @Test fun bufferedOldValuesAndOldClearNeverReplaceTheNewPublishedValue() = runBlocking<Unit> {
        val memory = Memory(); val old = memory.source()
        val source = MutableSharedFlow<DatabaseEpochValue<String?>>(replay = 1)
        val job = SupervisorJob(); val scope = CoroutineScope(job + Dispatchers.Main.immediate)
        val accepted = AtomicInteger()
        val assigned = kotlinx.coroutines.channels.Channel<String?>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        try {
            lateinit var state: kotlinx.coroutines.flow.StateFlow<String?>
            state = epochPublishedState(scope, memory.host, SharingStarted.Eagerly, null, source,
                initialized = { if (it) { accepted.incrementAndGet(); assigned.trySend(state.value) } })
            source.emit(DatabaseEpochValue("old", old))
            withTimeout(5_000) { state.first { it == "old" } }
            memory.replace(); assertNull(state.value)
            val current = memory.source()
            source.emit(DatabaseEpochValue("new", current))
            withTimeout(5_000) { state.first { it == "new" } }
            source.emit(DatabaseEpochValue("late old", old)); source.emit(DatabaseEpochValue(null, old))
            source.emit(DatabaseEpochValue("barrier", current))
            withTimeout(5_000) { while (assigned.receive() != "barrier") Unit }
            assertEquals("barrier", state.value)
            assertEquals(3, accepted.get())
            memory.noIssuanceOrDatabaseAccess()
        } finally { job.cancelAndJoin(); assigned.close() }
    }

    @Test fun finalProducerGuardKeepsSameValueRequestIdentityAndRejectsLateClear() = runBlocking<Unit> {
        val memory = Memory(); val origin = memory.source()
        val first = DatabaseEpochValue<String?>("same", origin)
        val second = DatabaseEpochValue<String?>("same", origin)
        val oldClear = DatabaseEpochValue<String?>(null, origin)
        val currentClear = DatabaseEpochValue<String?>(null, origin)
        val source = MutableSharedFlow<DatabaseEpochValue<String?>?>(replay = 1)
        val monitor = Any(); var current: DatabaseEpochValue<String?>? = first
        val delivered = arrayListOf<DatabaseEpochValue<String?>?>()
        val job = SupervisorJob(); val scope = CoroutineScope(job + Dispatchers.Main.immediate)
        try {
            val state = producerEpochFrameState(scope, memory.host, SharingStarted.Eagerly, source) { frame, assignment ->
                fun assign() = synchronized(monitor) {
                    if (frame !== current) false else if (frame == null) { assignment(); delivered += frame; true }
                    else frame.publication.publish { assignment(); delivered += frame }
                }
                if (frame == null) assign() else frame.publication.withSourceFence(::assign)
            }
            source.emit(first); withTimeout(5_000) { state.first { it === first } }
            synchronized(monitor) { current = second }
            source.emit(second); withTimeout(5_000) { state.first { it === second } }
            source.emit(first); source.emit(oldClear)
            synchronized(monitor) { current = currentClear }
            source.emit(currentClear); withTimeout(5_000) { state.first { it === currentClear } }
            assertEquals(listOf(first, second, currentClear), synchronized(monitor) { delivered.toList() })
            assertNull(state.value?.value)
            memory.noIssuanceOrDatabaseAccess()
        } finally { job.cancelAndJoin() }
    }

    private fun habit(uuid: String) = HabitEntity(id = 1, uuid = uuid, name = "rendered",
        habitType = HabitType.COUNTING, schedule = HabitSchedule.Daily, iconResId = 0, colorHex = "#FFFFFF")

    @Test fun obsoleteNumericIdCannotUseLegacyNavigationOrBeRetaggedFromANewRow() = runBlocking<Unit> {
        val memory = Memory(); val oldOrigin = memory.source()
        val original = habit("c4010000-0000-4000-8000-000000000001")
        val oldSelection = DatabaseObjectSelection.habit(original, oldOrigin)
        memory.replace()
        val replacement = habit("c4010000-0000-4000-8000-000000000002")
        val currentSelection = DatabaseObjectSelection.habit(replacement, memory.source())
        var legacyCalls = 0; val selected = arrayListOf<DatabaseObjectSelection>()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.dayforge.testbed")
        withContext(Dispatchers.Main.immediate) {
            navigateRenderedDatabaseInput(context, false, 1, oldSelection, { legacyCalls++ }, { selected += it })
            navigateRenderedDatabaseInput(context, false, 1, null, { legacyCalls++ }, { selected += it })
            navigateRenderedDatabaseInput(context, false, 1, currentSelection, { legacyCalls++ }, { selected += it })
        }
        assertEquals(0, legacyCalls); assertEquals(listOf(currentSelection), selected)
        assertEquals(original.uuid, oldSelection.uuid); assertEquals(replacement.uuid, selected.single().uuid)
        memory.noIssuanceOrDatabaseAccess()
    }

    @Test fun permissionAndConfirmationInputRetainsItsOriginalTargetAndEpoch() = runBlocking<Unit> {
        val memory = Memory()
        val original = habit("c4020000-0000-4000-8000-000000000001")
        val pending = TimerLaunchInput(1, 25, DatabaseObjectSelection.habit(original, memory.source()), null)
        assertTrue(pending.isCurrentFor(false))
        memory.replace()
        val replacement = habit("c4020000-0000-4000-8000-000000000002")
        val next = TimerLaunchInput(1, 40, DatabaseObjectSelection.habit(replacement, memory.source()), null)
        assertFalse(pending.isCurrentFor(false)); assertTrue(next.isCurrentFor(false))
        assertEquals(25, pending.targetMinutes); assertEquals(original.uuid, pending.selection?.uuid)
        assertEquals(40, next.targetMinutes); assertEquals(replacement.uuid, next.selection?.uuid)
        memory.noIssuanceOrDatabaseAccess()
    }
}
