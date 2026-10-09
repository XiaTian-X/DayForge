package com.dayforge.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.dayforge.data.appearance.AccountIconContext
import com.dayforge.data.appearance.ValidatedConfigBundle
import com.dayforge.data.export.ConfigImportTarget
import com.dayforge.data.export.NextConfigImportPlan
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.IconReference
import com.dayforge.domain.model.isContractUuid
import com.dayforge.domain.service.AccountIconController
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.widget.WidgetRefreshScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class NextConfigImportPreview internal constructor(internal val context: AccountIconContext,
    internal val source: ValidatedConfigBundle)

/** Local commit only: individual network operations and material transfers still need real ACKs. */
internal data class NextConfigImportReceipt(val importId: String, val nodes: Int, val metrics: Int, val links: Int)

internal enum class ConfigImportPhase { PREPARED, LOCAL_COMMITTED, OPERATIONS_ACCEPTED }
internal data class NextConfigImportProgress(val importId: String, val phase: ConfigImportPhase,
    val accepted: Int = 0, val total: Int = 0)
internal class NextConfigRecoveryPreview internal constructor(val importId: String,
    val original: NextConfigImportPreview, val committed: Boolean, val networkTracked: Boolean)

/**
 * Durable explicit import. Replacement stages NEW deletes and waits for exact predecessor ACKs.
 * No clearAllTables, URI reread, cross-database transaction or theme apply.
 * Settings dispatch by persisted protocol evidence; injecting this service cannot activate v5.
 */
@Singleton
internal class NextConfigImportRepository @Inject constructor(
    private val database: HabitDatabase, private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator, private val icons: AccountIconController,
    private val creator: NextObjectCreator, @param:ApplicationContext private val appContext: Context
) {
    private val journal get() = NextConfigImportStore(database)

    suspend fun preview(source: ValidatedConfigBundle): NextConfigImportPreview = withContext(Dispatchers.IO) {
        val context = icons.capture()
        guarded(context) { requireEmptyReplica(); journal.requireNoOtherPending() }
        NextConfigImportPreview(context, source)
    }

    /** Destructive counts/readiness only; does not enable replacement or grant a write ticket. */
    suspend fun previewReplacement(source: ValidatedConfigBundle): NextConfigReplacementPreview = withContext(Dispatchers.IO) {
        val context = icons.capture()
        guarded(context) {
            journal.requireNoOtherPending()
            NextConfigReplacementInspector(database).capture(NextConfigImportPreview(context, source))
        }
    }

    /** UI must discard/reopen a changed preview; an old source URI is never read again here. */
    suspend fun recheckReplacement(preview: NextConfigReplacementPreview) = withContext(Dispatchers.IO) {
        guarded(preview.original.context) {
            journal.requireNoOtherPending()
            val current = NextConfigReplacementInspector(database).capture(preview.original)
            check(current.fingerprint == preview.fingerprint && current.counts == preview.counts &&
                current.blockers == preview.blockers) { "CONFIG_REPLACEMENT_PREVIEW_CHANGED" }
            check(current.eligible) { "CONFIG_REPLACEMENT_NOT_READY" }
        }
    }

    /** Explicit destructive confirmation; save BOTH new identities and old delete IDs before I/O. */
    suspend fun confirmReplacement(preview: NextConfigReplacementPreview): String = withContext(Dispatchers.IO) {
        guarded(preview.original.context) {
            journal.requireNoOtherPending(); journal.requireNoActiveReplacement()
            val current = NextConfigReplacementInspector(database).capture(preview.original)
            check(current.fingerprint == preview.fingerprint && current.counts == preview.counts &&
                current.blockers == preview.blockers) { "CONFIG_REPLACEMENT_PREVIEW_CHANGED" }
            check(current.eligible) { "CONFIG_REPLACEMENT_NOT_READY" }
            val plan = NextConfigImportPlan(preview.original.source, NextConfigImportPlan.allocate(preview.original.source,
                ConfigImportTarget.from(preview.original.context), Instant.now()))
            val network = ConfigNetworkPlan.capture(database, plan, current.fingerprint)
            journal.prepareReplacement(plan, network)
            plan.importId
        }
    }

    /** Confirmation allocates once and commits the original mapping before any material file work. */
    suspend fun confirm(preview: NextConfigImportPreview): String = withContext(Dispatchers.IO) {
        guarded(preview.context) {
            requireEmptyReplica(); journal.requireNoOtherPending()
            val plan = NextConfigImportPlan(preview.source, NextConfigImportPlan.allocate(preview.source,
                ConfigImportTarget.from(preview.context), Instant.now()))
            journal.prepare(plan)
            plan.importId
        }
    }

    /** A cold-start recovery notice, not authority or proof that its original file is still present. */
    suspend fun pendingImportId(): String? = withContext(Dispatchers.IO) {
        val context = icons.capture()
        guarded(context) { journal.pending(context) }
    }

    /** Only the unique prepared/active group or the caller's exact last group; no history adoption. */
    suspend fun progress(knownImportId: String? = null): NextConfigImportProgress? = withContext(Dispatchers.IO) {
        val context = icons.capture()
        guarded(context) {
            val pending = journal.pending(context)
            journal.requireNoOtherPending(pending)
            pending?.let { return@guarded NextConfigImportProgress(it, ConfigImportPhase.PREPARED) }
            val entry = journal.activeNetwork() ?: knownImportId?.let { journal.network(it) } ?: return@guarded null
            val accepted = NextConfigImportBarrier(database).acceptedCount(entry, requireNotNull(tokens.localSyncAccess()))
            NextConfigImportProgress(entry.plan.importId, if (entry.accepted == null) ConfigImportPhase.LOCAL_COMMITTED
                else ConfigImportPhase.OPERATIONS_ACCEPTED, accepted, entry.plan.steps.size)
        }
    }

    /** Resupplied file is checked against the saved identity/hash BEFORE offering recovery/cancel. */
    suspend fun previewRecovery(importId: String, source: ValidatedConfigBundle): NextConfigRecoveryPreview = withContext(Dispatchers.IO) {
        val context = icons.capture()
        val entry = guarded(context) { journal.read(context, importId, source) }
        NextConfigRecoveryPreview(importId, NextConfigImportPreview(context, source), entry.committed, entry.network != null)
    }

    /** Cold recovery explicitly resupplies the same validated frozen file; never adopts new bytes. */
    suspend fun resume(importId: String, source: ValidatedConfigBundle,
        expectedContext: AccountIconContext? = null): NextConfigImportReceipt = withContext(Dispatchers.IO) {
        require(isContractUuid(importId))
        val context = icons.capture()
        check(expectedContext == null || context.access == expectedContext.access) { "CONFIG_IMPORT_SESSION_CHANGED" }
        val entry = guarded(context) {
            journal.read(context, importId, source).also {
                if (!it.committed) requirePrepared(context, it)
                else if (it.network != null) {
                    val barrier = NextConfigImportBarrier(database)
                    barrier.finishIfAccepted(requireNotNull(tokens.localSyncAccess()))
                    barrier.requireTerminalIfMarked(journal.network(importId), requireNotNull(tokens.localSyncAccess()))
                }
            }
        }
        val plan = entry.plan
        if (entry.committed) return@withContext receipt(plan)
        // The business lock/transaction does not span actual private-file or other database I/O.
        if (plan.iconPack != null) icons.installConfiguration(context, plan)
        val ticket = creator.capture()
        check(ticket.session == context.access.session) { "CONFIG_IMPORT_SESSION_CHANGED" }
        for (row in plan.metrics) icons.authorizeEditReference(ticket.session, requireNotNull(row.appearance).icon,
            IconReference.Role("metric.default"), false)
        val result = creator.habits(plan.habits, ticket) {
            requireAccess(context)
            val original = journal.read(context, importId, source)
            require(original.plan.identities == plan.identities) { "CONFIG_IMPORT_JOURNAL_CHANGED" }
            if (!original.committed) {
                requirePrepared(context, original)
                if (original.network != null) stageReplacement(original.network)
                val metricIds = plan.metrics.associate { it.uuid to database.metricDao().insert(it) }
                val habitIds = plan.habits.associate { it.uuid to database.habitDao().insert(it) }
                for (link in plan.links(habitIds, metricIds)) database.habitMetricLinkDao().insert(link)
                val sql = database.openHelper.writableDatabase
                val fresh = database.syncOutboxDao().getAll()
                val expected = original.network?.steps?.associate { it.entityUuid to it.operationId } ?: plan.creationOperationIds
                require(fresh.size == expected.size && fresh.map { it.entityUuid }.toSet() == expected.keys &&
                    fresh.all { it.action == if (it.entityUuid in plan.creationOperationIds) "upsert" else "delete" }) {
                    "CONFIG_IMPORT_CREATED_WORK_CHANGED"
                }
                val sources = fresh.associate { row ->
                    val operationId = expected.getValue(row.entityUuid)
                    if (row.action == "upsert")
                        sql.execSQL("UPDATE sync_outbox SET operationId=? WHERE id=?", arrayOf<Any>(operationId, row.id))
                    else require(row.operationId == operationId)
                    val saved = requireNotNull(database.syncOutboxDao().getById(row.id))
                    original.network?.steps?.single { it.operationId == operationId }?.requireOperation(
                        NextCoreLocalIntentStore(database, tokens, sessions).operation(saved))
                    operationId to ConfigImportedSource(saved.id, NextRequestSql.sourceHash(saved))
                }
                // This receipt, business rows and original outbox/origins commit or roll back together.
                if (original.network == null) journal.committed(plan, sources)
                else journal.committedReplacement(original.network, sources)
            }
            requireAccess(context)
            receipt(plan) to !original.committed
        }
        if (entry.network != null) guarded(context) {
            NextConfigImportBarrier(database).finishIfAccepted(requireNotNull(tokens.localSyncAccess()))
        }
        if (result.second) WidgetRefreshScheduler.request(appContext)
        result.first
    }

    private suspend fun requirePrepared(context: AccountIconContext, entry: NextConfigImportStore.Entry) {
        journal.requireNoOtherPending(entry.plan.importId)
        if (entry.network == null) requireEmptyReplica()
        else {
            journal.requireNoActiveReplacement()
            val current = NextConfigReplacementInspector(database).capture(
                NextConfigImportPreview(context, entry.plan.source), entry.plan.importId)
            check(current.fingerprint == entry.network.fingerprint) { "CONFIG_REPLACEMENT_PREVIEW_CHANGED" }
            check(current.eligible) { "CONFIG_REPLACEMENT_NOT_READY" }
        }
    }

    /** Same original producer TX as all new creates; old plan/fact rows stay until real delete ACK. */
    private suspend fun stageReplacement(network: ConfigNetworkPlan) {
        check(database.inTransaction())
        val sql = database.openHelper.writableDatabase
        val old = database.habitDao().getAllHabitsOnce().associateBy { it.uuid }
        val links = database.habitMetricLinkDao().getAllLinksOnce().associateBy { it.uuid }
        val metrics = database.metricDao().getAllMetricsOnce().associateBy { it.uuid }
        NextRequestSql.requireOutboxEnabled(sql)
        // Explicit destructive replacement discards old metric/link projections, not their source
        // journals/shadows. Suppression avoids inventing a second set of cascade-delete requests.
        sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
        for (link in links.values) database.habitMetricLinkDao().delete(link)
        for (metric in metrics.values) database.metricDao().delete(metric)
        sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        for (step in network.steps.filter { it.action == "delete" }) {
            when (step.entityType) {
                "plan_node" -> {
                    val row = old.getValue(step.entityUuid)
                    val goal = row.habitType == HabitType.GOAL
                    NextPlanDeletionStore(database).stage(row, if (goal) "cascade_children" else null,
                        if (goal) old.values.filter { it.parentHabitId == row.uuid }.map { it.uuid }.sorted() else emptyList(),
                        step.operationId)
                }
                else -> database.syncOutboxDao().insert(SyncOutboxEntity(operationId = step.operationId,
                    recordType = if (step.entityType == "metric") "metric" else "link", entityUuid = step.entityUuid,
                    wireEntityUuid = step.entityUuid, action = "delete", referenceUuid = links[step.entityUuid]?.habitUuid))
            }
        }
    }

    /** Explicitly abandon only a prepared local import. Already declared material is retained. */
    suspend fun cancelPrepared(importId: String, source: ValidatedConfigBundle,
        expectedContext: AccountIconContext? = null) = withContext(Dispatchers.IO) {
        val context = icons.capture()
        check(expectedContext == null || context.access == expectedContext.access) { "CONFIG_IMPORT_SESSION_CHANGED" }
        guarded(context) {
            val entry = journal.read(context, importId, source)
            check(!entry.committed) { "CONFIG_IMPORT_ALREADY_COMMITTED" }
            val sql = database.openHelper.writableDatabase
            sql.execSQL("DELETE FROM next_config_import_payloads WHERE importId=?", arrayOf<Any>(importId))
            sql.execSQL("DELETE FROM next_config_imports WHERE importId=?", arrayOf<Any>(importId))
        }
    }

    private suspend fun <T> guarded(context: AccountIconContext, block: suspend () -> T): T = sessions.exclusive {
        requireAccess(context)
        database.withTransaction {
            requireAccess(context)
            val result = block(); requireAccess(context); result
        }
    }

    private suspend fun requireAccess(context: AccountIconContext) {
        check(tokens.localIconAccess() == context.access) { "CONFIG_IMPORT_SESSION_CHANGED" }
        val access = requireNotNull(tokens.localCoreWriteAccess()) { "CONFIG_IMPORT_ACCESS_DENIED" }
        check(access.session == context.access.session && access.capturedDeviceId == context.access.deviceId &&
            context.access.canDeclare && access.capabilities?.contains("structure.write") == true) { "CONFIG_IMPORT_ACCESS_DENIED" }
        val sync = requireNotNull(tokens.localSyncAccess()) { "CONFIG_IMPORT_ACCESS_DENIED" }
        check(sync.session == access.session && sync.deviceId == context.access.deviceId &&
            sync.capabilityRevision == context.access.capabilityRevision && tokens.syncAuthenticationSnapshot(sync) != null) {
            "CONFIG_IMPORT_SESSION_CHANGED"
        }
        val sql = database.openHelper.writableDatabase
        // Called both before and inside Room; no business coercion or partial checkpoint adoption.
        if (database.inTransaction()) {
            NextRequestSql.rowHash(sql, "next_sync_state", "1=1", emptyArray())
            val cursor = database.nextSyncStateDao().rows().let { require(it.size <= 1); it.singleOrNull() }
            if (cursor != null) check(cursor.accountId == context.namespace.accountId &&
                cursor.serverInstanceId == context.namespace.serverInstanceId && cursor.syncEpoch == context.namespace.syncEpoch &&
                cursor.deviceId == context.access.deviceId) { "CONFIG_IMPORT_REPLICA_CHANGED" }
            NextChallengeStore(database).readInTransaction(sync, cursor)
        }
    }

    private fun requireEmptyReplica() {
        check(database.inTransaction())
        val sql = database.openHelper.writableDatabase
        NextRequestSql.requireOutboxEnabled(sql)
        // Any facts, retained work, conflicts, staged recovery or old shadow must not be discarded.
        for (table in listOf("habits", "metrics", "habit_metric_links", "completions", "timelogs", "metric_logs",
            "count_days", "timer_segments", "timelog_day_allocations", "sync_outbox", "timer_command_outbox",
            "sync_entity_state", "sync_conflicts", "local_fact_submissions", "completion_metric_prompts",
            "one_time_transmissions", "next_recovery_state", "next_rejections", "next_request_origins",
            "next_transmissions", "next_acceptances", "next_structural_dependencies", "next_structural_supersessions",
            "next_restart_materializations", "next_restart_plan_proofs", "next_challenge_births")) {
            sql.query("SELECT 1 FROM $table LIMIT 1").use {
                check(!it.moveToFirst()) { "CONFIG_IMPORT_REPLACEMENT_NOT_READY" }
            }
        }
    }

    private fun receipt(plan: NextConfigImportPlan) = NextConfigImportReceipt(plan.importId, plan.habits.size,
        plan.metrics.size, plan.creationOperationIds.size - plan.habits.size - plan.metrics.size)
}
