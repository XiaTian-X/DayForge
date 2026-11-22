package com.dayforge.data.local

import android.content.Context
import android.database.Cursor
import android.net.ConnectivityManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.compose.ui.test.*
import androidx.room.withTransaction
import com.dayforge.data.local.entity.SyncOutboxEntity
import kotlinx.serialization.json.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.R
import com.dayforge.data.repository.*
import com.dayforge.di.DatabaseModule
import com.dayforge.domain.service.DeviceCalendar
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Joint A+B on a real old file. Legacy local business deliberately stays in its original
 * lane; a retained unknown result never authorizes conversion to the formal protocol. */
@HiltAndroidTest @UninstallModules(DatabaseModule::class) @RunWith(AndroidJUnit4::class)
class RetainedSessionCountJourneyTest : RetainedSessionJourneyFixture() {
    @Test(timeout = 150_000) fun missingGenerationAndOldRawCountReachSharedDisplayRequiredPromptAndChildDelivery() = auth.ownedTest {
        historical(); readyAndEnroll()
        val originalGeneration = generation()
        val objects = legacyObjects()
        settings.setNeverAskAgain(requireNotNull(epochs.tokens.localCoreWriteAccess()).session, objects.habit.uuid, false)
        val baselineOutbox = captureOutbox()
        val identity = current { requireNotNull(it.countActionReader.legacy(objects.habit)) }
        val beforeDecryptions = auth.cipher.decryptions.get()
        val saved = current { graph -> requireNotNull(graph.habitRepository.logCompletionWithReceipt(app, objects.habit.id,
            countIdentity = identity, expectedHabitUuid = objects.habit.uuid).command) }
        assertTrue(saved.recorded); assertTrue(saved.requiresMetricFollowUp)
        assertTrue(auth.cipher.decryptions.get() > beforeDecryptions)
        val parent = row(saved.reference)
        assertEquals(CountCommandRecords.Execution.APPLIED, parent.status.execution)
        assertEquals(CountCommandRecords.Delivery.FOLLOW_UP_PENDING, parent.status.delivery)
        assertDashboard(objects.habit, 1)
        current { metrics.showCountPrompt(identity, objects.habit.name, command = saved) }
        val frame = requireNotNull(metrics.postCheckInFrames.value)
        assertTrue(frame.publication.belongsTo(epochs.host)); assertTrue(frame.publication.isCurrentInMemory())
        val state = requireNotNull(frame.value)
        assertEquals(objects.metrics.map { it.id }.toSet(), state.linkedMetrics.map { it.metricId }.toSet())
        assertEquals(saved.reference, requireNotNull(state.habitPrompt?.command).reference)
        val submitted = showLinkedPrompt(frame)
        state.linkedMetrics.forEachIndexed { index, metric ->
            compose.onNode(hasText("${metric.metricName} (${metric.unit})") and hasAnyAncestor(isDialog()))
                .performTextReplacement((3.5 + index).toString())
        }
        compose.onNode(hasText(app.getString(R.string.post_checkin_record)) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        assertTrue(withTimeout(45_000) { submitted.await() })
        val child = requireNotNull(state.habitPrompt?.submittedCommand)
        assertEquals(CountCommandRecords.Delivery.DELIVERED, row(saved.reference).status.delivery)
        assertEquals(CountCommandRecords.Delivery.DELIVERED, row(child.reference).status.delivery)
        assertEquals(parent.intent.text(), row(saved.reference).intent.text())
        assertEquals(requireNotNull(parent.outcome).text(), requireNotNull(row(saved.reference).outcome).text())
        current { graph ->
            assertEquals(1, graph.database.completionDao().countAll())
            assertEquals(2, graph.database.metricLogDao().countAll())
        }
        val added = assertOutboxDelta(baselineOutbox, 3, listOf(saved.reference, child.reference))
        assertEquals(setOf("completion", "metric_log"), added.map { it.recordType }.toSet())
        assertDashboard(objects.habit, 1); assertRetained()
        assertEquals(originalGeneration, generation())
        assertEquals(1, epochs.realRoomCreates)
        assertTrue(native.ends.any { it.kind == RetainedJourneyNative.Kind.T0 })
        assertEquals(1, native.ends.count { it.kind == RetainedJourneyNative.Kind.COUNT_T1 })
        assertEquals(1, native.ends.count { it.kind == RetainedJourneyNative.Kind.METRIC_T1 })
        assertTrue(native.ends.any { it.kind == RetainedJourneyNative.Kind.T2 })
        assertTrue(native.observationFailures.isEmpty())
    }

    @Test(timeout = 150_000) fun failedMetadataAdmitsNoBusinessThenExplicitRetrySavesAndRecoversOneIndependentMetric() = auth.ownedTest {
        historical(); readyAndEnroll(failFirstMetadata = true)
        val objects = legacyObjects(); val selected = objects.metrics.first()
        val baselineOutbox = captureOutbox()
        val attempt = current { it.metricRepository.beginIndependentValue(selected.id, 7.25, "joint independent",
            expectedMetricUuid = selected.uuid) }
        val saved = current { it.metricRepository.executeIndependentValue(attempt) }
        val reference = requireNotNull(saved.reference)
        val original = row(reference)
        assertEquals(CountCommandRecords.Execution.APPLIED, original.status.execution)
        assertTrue(original.status.pending)
        val panel = showPanel()
        val first = requireNotNull(panel.frames.value)
        assertEquals(1L, first.value.page.value.retainedCount)
        assertEquals(listOf(reference), first.value.page.value.entries.map { it.reference })
        compose.onNodeWithText(app.getString(R.string.count_recovery_continue)).assertIsEnabled().performClick()
        compose.waitUntil(45_000) {
            panel.frames.value?.value?.result is CountCommandPendingRepository.Result.StandaloneMetricSaved && !panel.busy.value
        }
        assertFalse(panel.failed.value)
        val delivered = requireNotNull(panel.frames.value)
        withTimeout(45_000) { assertTrue(delivered.value.accepted.await()) }
        val terminal = row(reference)
        assertEquals(CountCommandRecords.Delivery.DELIVERED, terminal.status.delivery)
        assertEquals(original.intent.text(), terminal.intent.text()); assertEquals(requireNotNull(original.outcome).text(), requireNotNull(terminal.outcome).text())
        current { graph ->
            val selection = DatabaseObjectSelection.metric(selected, epochs.host.currentPublication())
            val display = graph.metricDetailSourceReader.observe(selection).first()
            assertEquals(listOf(saved.factUuid), display.logs.map { it.uuid })
            assertEquals(7.25, requireNotNull(display.latest).value, 0.0)
        }
        val added = assertOutboxDelta(baselineOutbox, 1, listOf(reference))
        assertEquals(listOf("metric_log"), added.map { it.recordType })
        val stable = image()
        current { graph ->
            val resolved = graph.metricRepository.resolveIndependentValue(reference) as IndependentMetricResolution.Saved
            graph.metricRepository.acknowledgeIndependentValue(resolved.value) { true }
        }
        assertEquals(stable, image()); assertRetained()
        assertEquals(1, native.ends.count { it.kind == RetainedJourneyNative.Kind.METRIC_T1 })
        assertTrue(native.ends.any { it.kind == RetainedJourneyNative.Kind.T0 })
        assertTrue(native.ends.any { it.kind == RetainedJourneyNative.Kind.T2 })
        assertTrue(native.observationFailures.isEmpty())
    }
    /** Preserve the whole setup queue, including native types and rowids. A definition
     * operation is neither discarded nor silently counted as this command's new fact. */
    private suspend fun captureOutbox(): JsonArray = current { graph -> graph.database.withTransaction {
        CountCommandMigrationFixture.snapshot(graph.database.openHelper.writableDatabase).contents
            .getValue("tables").jsonObject.getValue("sync_outbox").jsonArray
    } }
    private fun nativeLong(row: JsonObject, column: String): Long {
        val cell = row.getValue(column).jsonObject
        assertEquals(Cursor.FIELD_TYPE_INTEGER, cell.getValue("sqlite_type").jsonPrimitive.int)
        return cell.getValue("value").jsonPrimitive.long
    }
    private fun nativeText(row: JsonObject, column: String): String {
        val cell = row.getValue(column).jsonObject
        assertEquals(Cursor.FIELD_TYPE_STRING, cell.getValue("sqlite_type").jsonPrimitive.int)
        return cell.getValue("value").jsonPrimitive.content
    }
    private fun assertNativeEvidence(record: CountCommandEvidence.Record, actual: JsonObject) {
        val columns = record.body().getValue("cells").jsonObject.keys
        assertEquals(columns, actual.keys - "__recovery_rowid")
        for (column in columns) {
            val expected = record.cell(column)
            val cell = actual.getValue(column).jsonObject
            assertEquals("Native type in ${record.table}.$column", expected.type, cell.getValue("sqlite_type").jsonPrimitive.int)
            val value = cell.getValue("value")
            when (expected.type) {
                Cursor.FIELD_TYPE_NULL -> assertEquals(JsonNull, value)
                Cursor.FIELD_TYPE_INTEGER -> assertEquals(expected.value, value.jsonPrimitive.long)
                Cursor.FIELD_TYPE_FLOAT -> assertEquals(expected.value, value.jsonPrimitive.content.toLong(16))
                Cursor.FIELD_TYPE_STRING -> assertEquals(expected.value, value.jsonPrimitive.content)
                else -> error("Unexpected BLOB in original immutable command evidence")
            }
        }
        assertEquals(nativeLong(actual, "id"), nativeLong(actual, "__recovery_rowid"))
    }
    private suspend fun assertOutboxDelta(before: JsonArray, expectedCount: Int,
        references: List<CountCommandReference>): List<SyncOutboxEntity> {
        // Original Store authenticates receipts BEFORE this independent oracle transaction.
        val outcomes = references.map { reference -> requireNotNull(row(reference).outcome).also {
            assertEquals(reference.commandId, it.commandId); assertEquals(reference.inputHash, it.inputHash)
            assertEquals(reference.namespaceHash, it.namespaceHash)
        } }
        return current { graph -> graph.database.withTransaction {
            val tables = CountCommandMigrationFixture.snapshot(graph.database.openHelper.writableDatabase).contents.getValue("tables").jsonObject
            val original = before.map { it.jsonObject }.associateBy { nativeLong(it, "id") }
            val rows = tables.getValue("sync_outbox").jsonArray.map { it.jsonObject }
            val after = rows.associateBy { nativeLong(it, "id") }
            assertEquals(before.size, original.size); assertEquals(rows.size, after.size)
            assertEquals(before.size + expectedCount, rows.size)
            original.forEach { (id, value) -> assertEquals("Prepared outbox native row changed: $id", value, after[id]) }
            assertEquals(rows.size, rows.map { nativeText(it, "operationId") }.toSet().size)
            val actual = graph.syncOutboxDao.getAll()
            assertEquals(after.keys, actual.map { it.id }.toSet())
            val delta = actual.filter { it.id !in original }
            assertEquals(expectedCount, delta.size); assertEquals(expectedCount, delta.map { it.operationId }.toSet().size)
            val sources = outcomes.flatMap { it.evidence.records }.filter { it.role == CountCommandEvidence.Role.ORIGINAL_SOURCE }
            val facts = outcomes.flatMap { it.evidence.records }.filter { it.role == CountCommandEvidence.Role.INSERTED_FACT }
            val effects = outcomes.flatMap { it.effects }
            assertEquals(expectedCount, sources.size); assertEquals(expectedCount, facts.size); assertEquals(expectedCount, effects.size)
            assertEquals(delta.map { it.id }.toSet(), sources.map { it.number("id") }.toSet())
            assertEquals(delta.map { it.operationId }.toSet(), sources.map { it.text("operationId") }.toSet())
            assertEquals(delta.map { it.entityUuid }.toSet(), effects.map { it.factUuid }.toSet())
            assertEquals(expectedCount, delta.map { it.entityUuid }.toSet().size)
            for (queue in delta) {
                val source = sources.single { it.number("id") == queue.id }
                assertNativeEvidence(source, after.getValue(queue.id)) // Includes payloadJson, full retry/base fields and all bytes.
                assertEquals(queue.entityUuid, queue.wireEntityUuid); assertEquals("upsert", queue.action)
                assertNull(queue.payloadJson) // Unsent legacy input; no fabricated network payload.
                val effect = effects.single { it.factUuid == queue.entityUuid }
                val table = when (effect.factKind) {
                    CountCommandOutcome.FactKind.COUNT_ADD -> { assertEquals("completion", queue.recordType); "completions" }
                    CountCommandOutcome.FactKind.METRIC_OBSERVATION -> { assertEquals("metric_log", queue.recordType); "metric_logs" }
                    else -> error("Unexpected effect in fixed addition journey")
                }
                assertEquals(effect.objectUuid, queue.referenceUuid)
                val fact = facts.single { it.table == table && it.text("uuid") == queue.entityUuid }
                val actualFact = tables.getValue(table).jsonArray.map { it.jsonObject }
                    .single { nativeText(it, "uuid") == queue.entityUuid }
                assertNativeEvidence(fact, actualFact) // Values, dates, units, notes and identities bind the real new fact.
                if (table == "completions") assertEquals(effect.objectUuid, nativeText(actualFact, "habitUuid"))
                else assertEquals(effect.objectUuid, requireNotNull(graph.metricDao.getMetricById(nativeLong(actualFact, "metricId"))).uuid)
            }
            for (outcome in outcomes) outcome.primaryRequestId?.let { request ->
                assertTrue(outcome.evidence.records.any { it.role == CountCommandEvidence.Role.ORIGINAL_SOURCE && it.text("operationId") == request })
            }
            delta.toList()
        } }
    }
    @Module @InstallIn(SingletonComponent::class)
    object PreferencesModule {
        @Provides @Singleton fun data(): DataStore<Preferences> = LegacySessionHostPreferences.active().store
        @Provides @Singleton fun cipher(): TokenCipher = LegacySessionHostPreferences.active().cipher
        @Provides @Singleton fun preferences(data: DataStore<Preferences>, calendar: DeviceCalendar, tokens: TokenManager) =
            PreferencesManager(data, calendar, tokens)
        @Provides @Singleton fun connectivity(@ApplicationContext context: Context): ConnectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
}
