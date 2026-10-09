package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.appearance.ConfigBundleOutput
import com.dayforge.data.appearance.ConfigFileFixture
import com.dayforge.data.appearance.IconPackVersion
import com.dayforge.data.export.ConfigImportIdentities
import com.dayforge.data.export.ConfigImportTarget
import com.dayforge.data.export.NextConfigImportPlan
import com.dayforge.domain.model.IconReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real material controller + original creator/producer, not a durable replacement import service. */
@RunWith(AndroidJUnit4::class)
class NextConfigImportConstructionTest : NextObjectEditorFixture() {
    private suspend fun prepare(): NextConfigImportPlan {
        register()
        val source = ConfigBundleOutput.create(ConfigFileFixture.manifest(), ConfigFileFixture::content)
        val context = icons.capture()
        return NextConfigImportPlan(source, NextConfigImportPlan.allocate(source, ConfigImportTarget.from(context),
            java.time.Instant.parse("2026-10-09T00:00:00Z")))
    }

    /** Test participant in the existing atomic producer; only brand-new trigger rows get saved IDs. */
    private suspend fun construct(plan: NextConfigImportPlan) {
        val ticket = creator.capture()
        for (row in plan.metrics) icons.authorizeEditReference(ticket.session, row.appearance!!.icon,
            IconReference.Role("metric.default"), false)
        creator.habits(plan.habits, ticket) {
            val sql = db.openHelper.writableDatabase
            val watermark = NextRequestSql.watermark(sql, "sync_outbox")
            val metrics = plan.metrics.associate { it.uuid to db.metricDao().insert(it) }
            val habits = plan.habits.associate { it.uuid to db.habitDao().insert(it) }
            for (row in plan.links(habits, metrics)) db.habitMetricLinkDao().insert(row)
            val fresh = db.syncOutboxDao().getAll().filter { it.id > watermark }
            assertEquals(plan.creationOperationIds.keys, fresh.map { it.entityUuid }.toSet())
            for (row in fresh) sql.execSQL("UPDATE sync_outbox SET operationId=? WHERE id=?",
                arrayOf<Any>(plan.creationOperationIds.getValue(row.entityUuid), row.id))
        }
    }

    @Test fun realInstalledReferencesAndAllConstructedRowsEnterOriginalAtomicProducerWithSavedIdsAndColdReopen() = runBlocking<Unit> {
        val plan = prepare(); val context = icons.capture(); val choice = iconMetadata.selection(context)
        val receipt = icons.installConfiguration(context, plan)
        assertEquals(IconPackVersion(plan.iconPack!!.packId, 1), receipt.version)
        construct(plan)
        val work = db.syncOutboxDao().getAll()
        assertEquals(plan.creationOperationIds.values.toSet(), work.map { it.operationId }.toSet())
        for (row in work) {
            val origin = db.nextRequestDao().origin(NEXT_OPERATION, row.operationId)!!
            assertEquals(id(1), origin.accountId); assertEquals(row.operationId, decodeNextOperationIntent(origin.intentJson).operationId)
            assertEquals(NextRequestSql.sourceHash(row), origin.sourceHash)
        }
        val once = db.habitDao().getAllHabitsOnce().single { it.completionPolicy == "one_and_done" }
        assertFalse(onceRepository().read(once.id).completed)
        assertEquals(0, db.completionDao().getByHabitOnce(once.id).size)
        assertEquals(1, db.habitMetricLinkDao().getAllLinksOnce().size)
        assertEquals(choice, iconMetadata.selection(context))
        val all = db.habitDao().getAllHabitsOnce()
        storage.reopen()
        assertEquals(all, db.habitDao().getAllHabitsOnce()); assertEquals(work, db.syncOutboxDao().getAll())
        assertFalse(onceRepository().read(once.id).completed)
    }

    @Test fun businessFailureRollsBackRowsAndOriginalRequestsButKeepsOwnedFilesAndMappingForExactRetry() = runBlocking<Unit> {
        val plan = prepare(); val context = icons.capture(); icons.installConfiguration(context, plan)
        val habits = db.habitDao().getAllHabitsOnce(); val metrics = db.metricDao().getAllMetricsOnce()
        val work = db.syncOutboxDao().getAll()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER config_link_fault BEFORE INSERT ON habit_metric_links " +
            "BEGIN SELECT RAISE(ABORT,'business construction fault'); END")
        assertNotNull(runCatching { construct(plan) }.exceptionOrNull())
        assertEquals(habits, db.habitDao().getAllHabitsOnce()); assertEquals(metrics, db.metricDao().getAllMetricsOnce())
        assertEquals(work, db.syncOutboxDao().getAll()); assertTrue(db.habitMetricLinkDao().getAllLinksOnce().isEmpty())
        assertEquals(plan.iconPack, iconMetadata.pack(context, plan.iconPack!!.packId, 1))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER config_link_fault")
        val restored = NextConfigImportPlan(plan.source, ConfigImportIdentities.decode(plan.identities.encode().toByteArray()))
        assertEquals(plan.creationOperationIds, restored.creationOperationIds)
        assertEquals(plan.habits, restored.habits)
        icons.installConfiguration(context, restored); construct(restored)
        assertEquals(plan.creationOperationIds.values.toSet(), db.syncOutboxDao().getAll().map { it.operationId }.toSet())
    }
}
