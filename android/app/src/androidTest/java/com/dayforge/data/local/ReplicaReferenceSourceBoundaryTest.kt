package com.dayforge.data.local

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.repository.NextReplicaStorageProof
import com.dayforge.widget.IsolatedWidgetRefreshRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Hilt source plus a fresh, final fixed reader per case. No cache reset, caller loader,
 * factory injection, fake authorization, prewarming or source-guard bypass. Native lifecycle
 * failure injection remains in the original complete ReplicaReferenceSchemaTest six methods.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ReplicaReferenceSourceBoundaryTest {
    @get:Rule(order = 0) val widgets = IsolatedWidgetRefreshRule()
    @get:Rule(order = 1) val epochs = PhysicalDatabaseEpochFixture()
    @get:Rule(order = 2) val hilt = HiltAndroidRule(this)
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun connect() = runBlocking<Unit> { hilt.inject(); epochs.connect(); epochs.login() }
    private suspend fun source() = epochs.host.snapshot { Unit }.publication
    private suspend fun image(database: HabitDatabase) = database.withTransaction {
        NextReplicaStorageProof(database).readInTransaction().let { PhysicalDatabaseMainProof(it.contents, it.hash) }
    }
    private suspend fun rejected(block: suspend () -> Unit) {
        val failure = try { block(); null } catch (error: IllegalStateException) { error }
        assertEquals("DATABASE_EPOCH_INPUT_EXPIRED", failure?.message)
    }
    private fun immutable(block: () -> Unit) {
        val failure = try { block(); null } catch (error: UnsupportedOperationException) { error }
        assertNotNull("Generated metadata must own immutable collections", failure)
    }

    @Test(timeout = 150_000) fun coldFixedReferenceUnderRealSourceRestoresFrameCallerAndDeepFrozenMetadata() = runBlocking<Unit> {
        val publication = source(); val before = epochs.exactMain()
        val reader = DatabaseOperationSource.GeneratedReferenceSchemaReader()
        assertNull(DatabaseOperationSource.current())
        epochs.host.withSourceGraph(publication) { graph ->
            val frame = requireNotNull(DatabaseOperationSource.current())
            val caller = currentCoroutineContext()[Job]
            val cold = reader.read(app) // This reader's private cache is necessarily empty.
            assertSame(frame, DatabaseOperationSource.current()); assertSame(caller, currentCoroutineContext()[Job])
            frame.requireLive()
            assertEquals(20, cold.version); assertEquals(currentHabitIdentity(), cold.roomIdentity)
            assertTrue(cold.objects.containsKey("table.habits"))
            assertTrue(cold.objects.containsKey("table.count_trust_control"))
            val definition = cold.objects.getValue("table.habits").definition
            assertTrue(definition.isNotEmpty()); assertTrue(definition.first().isNotEmpty())
            immutable { (cold.objects as MutableMap<String, ReplicaSqlSchema.SchemaObject>).clear() }
            immutable { (definition as MutableList<List<String>>).clear() }
            immutable { (definition.first() as MutableList<String>)[0] = "changed" }
            assertSame(cold, reader.read(app))
            assertSame(frame, DatabaseOperationSource.current()); assertSame(caller, currentCoroutineContext()[Job])
            graph.database.withTransaction {
                graph.database.openHelper.writableDatabase.query("SELECT id FROM main.sync_control").use {
                    assertTrue(it.moveToFirst()); assertEquals(1L, it.getLong(0)); assertFalse(it.moveToNext())
                }
            }
        }
        assertNull(DatabaseOperationSource.current()); assertEquals(before, epochs.exactMain())
    }

    @Test(timeout = 150_000) fun fixedReferenceReadDoesNotAllowForeignNativeTransactionUnderOriginalSource() = runBlocking<Unit> {
        val factory = CountTrustOpenHelperFactory(app)
        val foreign = Room.inMemoryDatabaseBuilder(app, HabitDatabase::class.java)
            .openHelperFactory(factory).addCallback(SyncSchemaCallback).build()
        CountTrustRuntime.attach(foreign, factory)
        try {
            val foreignBefore = image(foreign) // Real different controlled database, fully open before the source.
            val publication = source(); val before = epochs.exactMain()
            val reader = DatabaseOperationSource.GeneratedReferenceSchemaReader()
            var bodyEntered = false
            epochs.host.withSourceGraph(publication) {
                val frame = requireNotNull(DatabaseOperationSource.current()); val caller = currentCoroutineContext()[Job]
                reader.read(app)
                assertSame(frame, DatabaseOperationSource.current()); assertSame(caller, currentCoroutineContext()[Job])
                rejected { foreign.withTransaction {
                    bodyEntered = true
                    foreign.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
                } }
                assertFalse(bodyEntered); assertSame(frame, DatabaseOperationSource.current()); frame.requireLive()
            }
            assertFalse(foreign.inTransaction()); assertEquals(foreignBefore, image(foreign))
            assertEquals(before, epochs.exactMain())
        } finally { foreign.close() }
    }

    @Test(timeout = 150_000) fun fixedMetadataNeverRequalifiesAnOriginalPublicationRevokedDuringTheOperation() = runBlocking<Unit> {
        val publication = source(); val before = epochs.exactMain(); var bodyEntered = false
        epochs.host.withSourceGraph(publication) { graph ->
            val frame = requireNotNull(DatabaseOperationSource.current()); val caller = currentCoroutineContext()[Job]
            val initial = DatabaseOperationSource.GeneratedReferenceSchemaReader().read(app)
            val old = requireNotNull(epochs.tokens.localCoreWriteAccess())
            epochs.sessions.exclusive {
                epochs.tokens.saveLoginSession("reference-new-session", "reference-new-refresh", "member",
                    old.session.authentication.userId, false)
            }
            assertFalse(publication.isCurrentInMemory())
            assertNotEquals(old.session.authentication.generation,
                requireNotNull(epochs.tokens.localCoreWriteAccess()).session.authentication.generation)
            // Pure generated metadata can be read; it is not a grant to use the old source.
            val independent = DatabaseOperationSource.GeneratedReferenceSchemaReader().read(app)
            assertEquals(initial, independent)
            assertSame(frame, DatabaseOperationSource.current()); assertSame(caller, currentCoroutineContext()[Job])
            rejected { graph.database.withTransaction {
                bodyEntered = true
                graph.database.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            } }
            assertFalse(bodyEntered); assertSame(frame, DatabaseOperationSource.current())
        }
        assertNull(DatabaseOperationSource.current()); assertEquals(before, epochs.exactMain())
    }

    @Test(timeout = 150_000) fun cancelledCallersCannotReceiveColdOrWarmMetadataOrLoseTheirOriginalFrame() = runBlocking<Unit> {
        val publication = source(); val before = epochs.exactMain()
        for (warm in listOf(false, true)) {
            val reader = DatabaseOperationSource.GeneratedReferenceSchemaReader()
            val prior = if (warm) epochs.host.withSourceGraph(publication) { reader.read(app) } else null
            var entered = false; var returned = false; var restored = false
            supervisorScope {
                val work = async {
                    epochs.host.withSourceGraph(publication) {
                        val frame = requireNotNull(DatabaseOperationSource.current()); val caller = currentCoroutineContext()[Job]
                        entered = true
                        currentCoroutineContext().cancel(CancellationException("REFERENCE_SOURCE_CANCELLED_CALLER"))
                        try { reader.read(app); returned = true }
                        finally {
                            assertSame(frame, DatabaseOperationSource.current()); assertSame(caller, currentCoroutineContext()[Job])
                            restored = true
                        }
                    }
                }
                val failure = runCatching { work.await() }.exceptionOrNull()
                assertTrue(failure is CancellationException)
            }
            assertTrue(entered); assertFalse(returned); assertTrue(restored)
            val ready = epochs.host.withSourceGraph(publication) { reader.read(app) }
            if (prior != null) assertSame(prior, ready)
            assertSame(ready, epochs.host.withSourceGraph(publication) { reader.read(app) })
            assertNull(DatabaseOperationSource.current())
        }
        assertEquals(before, epochs.exactMain())
    }
}
