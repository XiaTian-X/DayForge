package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.history.HistoryBlockCodec
import com.dayforge.data.history.HistoryBlockUpdater
import com.dayforge.data.local.*
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import com.dayforge.domain.service.AccountSessionCoordinator
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*

/** Real SQL old two-step oracle. It never calls the new composite Plan/Rules/Seal entry.
 * Closed testbed images give both writers identical authenticated inputs and real ENDs. */
abstract class CountCommandMetricDeliveryCompositeFixture : NextObjectEditorFixture() {
    internal val native = CountCommandMetricDeliveryCompositeNative()
    init { storage.isolatedBuilder = native::build }
    internal val sql get() = db.openHelper.writableDatabase
    internal val runtime get() = requireNotNull(CountTrustRuntime.forDatabase(db))
    internal lateinit var parent: CountCommandStore.Reference
    internal lateinit var child: CountCommandStore.Reference
    internal val intervening = arrayListOf<CountCommandStore.Reference>()
    internal data class Step(val before: CountCommandRootCodec.Header,
        val current: CountCommandRowPlan.Aggregate, val previous: CountCommandRowPlan.Aggregate?,
        val next: CountCommandRowPlan.Aggregate?, val batch: CountCommandRowPlan.Batch,
        val pending: CountCommandPendingRules.Frontier, val patch: HistoryBlockUpdater.Patch,
        val after: CountCommandRootCodec.Header)

    internal suspend fun group(between: Int = 0, outerNeighbours: Boolean = false) {
        tokens.getOrCreateInstallationId(); register()
        db.withTransaction {
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.habitMetricLinkDao().insertOrIgnore(HabitMetricLinkEntity(habitId = habit.id, habitUuid = habit.uuid,
                metricId = metric.id, metricUuid = metric.uuid, promptOnComplete = true))
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        if (outerNeighbours) preparedNeighbour()
        val countReceipt = requireNotNull(creatingHabits().logCompletionWithReceipt(app, habit.id,
            authority = requireNotNull(editor.habit(habit.id).authority), expectedHabitUuid = habit.uuid).command)
        parent = countReceipt.committed.reference
        repeat(between) { intervening += preparedNeighbour() }
        val source = WidgetFactReader(db, tokens, sessions, preferences, CountHistoryReader(db, tokens, sessions))
            .read(requireNotNull(db.habitDao().getVisibleHabitById(habit.id)))
        val targets = editor.metrics(listOf(metric.id))
        child = requireNotNull(creatingMetrics().recordValuesWithReceipt(listOf(MetricValueDraft(metric.id, 4.5, "composite oracle")),
            millis, authority = requireNotNull(targets.single().authority), expectedMetrics = targets.map { requireNotNull(it.value) },
            promptHabit = source.habit, promptFact = source.claim, promptCompletionSource = true, parentCommand = countReceipt).command).committed.reference
        if (outerNeighbours) preparedNeighbour()
        assertEquals(1, count("completions")); assertEquals(1, count("metric_logs"))
        assertEquals(CountCommandRecords.Delivery.METRICS_COMMITTED, commands().resolve(parent).historical.status.delivery)
        assertEquals(CountCommandRecords.Execution.APPLIED, commands().resolve(child).historical.status.execution)
        // Warm only immutable reference schema before snapshot; both writers still validate it.
        ReplicaBusinessReferenceSchema.read(app)
    }
    private suspend fun preparedNeighbour(): CountCommandStore.Reference {
        val authority = requireNotNull(editor.habit(habit.id).authority)
        val intent = CountCommandInputs.add(db, UUID.randomUUID().toString(), habit, authority, Instant.now(), ZoneId.systemDefault(), 1)
        val owner = commands(); val prepared = owner.register(intent)
        owner.releaseUnadmitted(requireNotNull(prepared.ticket))
        return prepared.reference
    }
    internal fun commands() = CountCommandStore(db, tokens, sessions)
    internal suspend fun image() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().contents }
    internal fun reopen() {
        storage.reopen(); editor = NextObjectEditor(db, tokens, sessions, icons); creator = NextObjectCreator(db, tokens, sessions, icons)
    }
    internal fun closedImage(): ByteArray = storage.snapshotClosedDatabase().also { reopen() }
    internal fun restore(image: ByteArray) { storage.restoreClosedDatabase(image); reopen() }
    internal fun assertCompleteImage(expected: JsonObject, actual: JsonObject) {
        // The equality covers every schema row, native cell/type, node rowid and root/HMAC.
        assertTrue("Complete main/schema/native image differs", expected == actual)
    }

    private inner class OldScope(val authenticated: CountCommandSeal.Authenticated,
        val namespace: CountCommandRecords.Namespace, val rows: CountCommandNativeRows, val lifetime: () -> Unit) {
        val header get() = authenticated.header
        suspend fun required(id: String): CountCommandRowPlan.Aggregate =
            CountCommandDirectory(sql, namespace.catalogBinding, requireNotNull(header.catalog), lifetime, lifetime).read { reader ->
                val member = reader.member(id)
                if (member == null) {
                    CountCommandRowPlan.Table.entries.forEach { rows.requireAbsent(it, id) }
                }
                rows.aggregate(requireNotNull(member) { "COMMAND_NATIVE_MEMBER_MISSING" }, lifetime).also { require(it.intent.namespace.catalogBinding == namespace.catalogBinding) }
            }
        fun reference(ref: CountCommandStore.Reference) {
            require(ref.datasetId == header.datasetId) { "COMMAND_DATASET_REPLACED" }
            require(ref.namespaceHash == namespace.hash) { "COMMAND_REFERENCE_NAMESPACE_CHANGED" }
        }
    }
    private suspend fun openOld(access: LocalCoreWriteAccess): OldScope {
        currentCoroutineContext().ensureActive()
        val token = runtime.capturePrimaryReadToken(db); val caller = currentCoroutineContext()
        val lifetime = { runtime.requirePrimaryReadToken(db, token); caller.ensureActive() }
        CountTrustSchema.requireControl(sql)
        val storage = sql.query("SELECT storageId FROM count_trust_control WHERE id=1 LIMIT 2").use {
            require(it.moveToFirst() && it.getType(0) == android.database.Cursor.FIELD_TYPE_STRING)
            it.getString(0).also { _ -> require(!it.moveToNext()) }
        }
        CountCommandPrimarySchema.requireStatisticsPreflight(sql)
        val schema = runtime.schemaSnapshot(); CountCommandV20Schema.previousSchema(schema)
        val rows = CountCommandNativeRows(sql, lifetime)
        val authenticated = CountCommandSeal().authenticate(rows.root(), storage, CountCommandPrimarySchema.hash(schema)) {
            CountCommandRootAnchor.read(sql).hash
        }
        val namespace = CountCommandRecords.Namespace(access.session.authentication.userId, access.session.serverInstanceId,
            access.session.syncEpoch, access.capturedDeviceId, storage, authenticated.header.datasetId)
        require(authenticated.header.mode == CountCommandRootCodec.Mode.ACCOUNT_BOUND && authenticated.header.namespace == namespace) {
            "COMMAND_NAMESPACE_NOT_BOUND"
        }
        require(authenticated.header.accountBinding == namespace.catalogBinding) { "COMMAND_STORAGE_ACCOUNT_CHANGED" }
        authenticated.header.registration?.let { require(tokens.registeredInstallationId() == it.installationId) { "COMMAND_INSTALLATION_CHANGED" } }
        return OldScope(authenticated, namespace, rows, lifetime)
    }
    private fun persistOldNode(digest: String, bytes: ByteArray, lifetime: () -> Unit): Boolean {
        lifetime()
        require(bytes.size in 1..HistoryBlockCodec.MAX_NODE_BYTES && HistoryBlockCodec.hash(bytes) == digest)
        var exists = false
        sql.query("SELECT typeof(body),length(body),CASE WHEN typeof(body)='blob' AND length(body)=? THEN body END " +
            "FROM count_command_nodes WHERE digest=? LIMIT 2", arrayOf<Any>(bytes.size, digest)).use {
            if (it.moveToFirst()) {
                require(it.getType(0) == android.database.Cursor.FIELD_TYPE_STRING && it.getString(0) == "blob" &&
                    it.getType(1) == android.database.Cursor.FIELD_TYPE_INTEGER &&
                    it.getLong(1) == bytes.size.toLong() && it.getType(2) == android.database.Cursor.FIELD_TYPE_BLOB &&
                    it.getBlob(2).contentEquals(bytes) && !it.moveToNext())
                exists = true
            }
        }
        if (!exists) {
            sql.execSQL("INSERT INTO count_command_nodes(digest,body) VALUES(?,?)", arrayOf(digest, bytes.copyOf()))
            sql.query("SELECT CASE WHEN typeof(body)='blob' AND length(body)=? THEN body END FROM count_command_nodes WHERE digest=? LIMIT 2",
                arrayOf<Any>(bytes.size, digest)).use {
                require(it.moveToFirst() && it.getType(0) == android.database.Cursor.FIELD_TYPE_BLOB &&
                    it.getBlob(0).contentEquals(bytes) && !it.moveToNext()) { "COMMAND_NODE_WRITE_CHANGED" }
            }
        }
        return !exists
    }
    internal suspend fun oldTwoStepOracle(): List<Step> {
        val referenceSchema = ReplicaBusinessReferenceSchema.read(app)
        return sessions.exclusive(AccountSessionCoordinator.Work.NEXT_WRITE) {
            val access = requireNotNull(tokens.localCoreWriteAccess()); val installation = tokens.registeredInstallationId()
            val completed = runtime.primaryTransaction(db) {
                ApprovedBusinessSchema.requireCleanupInTransaction(sql, referenceSchema)
                val steps = runtime.maintenance(db) {
                    var scope = openOld(access); scope.reference(parent); scope.reference(child)
                    val p = scope.required(parent.commandId); val c = scope.required(child.commandId)
                    require(p.intent.inputHash == parent.inputHash && c.intent.inputHash == child.inputHash &&
                        p.status.child == child.commandId && c.status.parent == parent.commandId &&
                        c.status.execution == CountCommandRecords.Execution.APPLIED &&
                        c.outcome?.action == CountCommandRecords.Action.LINKED_METRICS &&
                        p.status.delivery in setOf(CountCommandRecords.Delivery.METRICS_COMMITTED, CountCommandRecords.Delivery.DELIVERED))
                    val boundary = runtime.captureCommandBoundary(); val expected = arrayListOf<CountCommandJournal.Expected>()
                    val result = arrayListOf<Step>()
                    for (ref in listOf(child, parent)) {
                        val current = scope.required(ref.commandId)
                        if (current.status.delivery == CountCommandRecords.Delivery.DELIVERED) continue
                        val previous = current.status.previous?.let { scope.required(it) }
                        val next = current.status.next?.let { scope.required(it) }
                        val removal = CountCommandPendingRules.remove(scope.header.pending, current.status,
                            CountCommandTransitionRules.delivered(current.status), previous?.status, next?.status)
                        val old = listOfNotNull(current, previous, next).associateBy { it.intent.commandId }
                        val after = removal.changes.map { change -> old.getValue(change.after.commandId).let {
                            CountCommandRowPlan.Aggregate(it.intent, change.after, it.outcome)
                        } }
                        val batch = CountCommandRowPlan.Batch(old.values.toList(), after)
                        val patch = CountCommandDirectory(sql, scope.namespace.catalogBinding, requireNotNull(scope.header.catalog),
                            scope.lifetime, scope.lifetime).read { it.replace(batch.catalogEdits) }
                        for (mutation in batch.mutations) {
                            scope.rows.apply(mutation)
                            expected += CountCommandJournal.Expected(mutation.after.table.sqlName, "UPDATE", mutation.after.commandId)
                        }
                        for ((digest, bytes) in patch.newBlocks) if (persistOldNode(digest, bytes.toByteArray(), scope.lifetime))
                            expected += CountCommandJournal.Expected("count_command_nodes", "INSERT", digest)
                        val fresh = scope.header.copy(catalog = patch.root, pending = removal.after,
                            revision = Math.addExact(scope.header.revision, 1L))
                        scope.lifetime()
                        val before = CountCommandRootAnchor.read(sql)
                        CountCommandRootAnchor.requireMatches(scope.header, scope.authenticated.native().mac, before.hash)
                        require(before.storage == scope.header.storageId)
                        val signed = CountCommandSeal().advance(scope.authenticated, fresh)
                        val anchor = before.copy(hash = CountCommandRootAnchor.digest(signed))
                        expected += CountCommandJournal.Expected(CountCommandJournal.ROOT, "UPDATE", "1")
                        expected += CountCommandJournal.Expected(CountCommandJournal.CONTROL, "UPDATE", "1",
                            CountCommandJournal.AnchorState(before.storage, before.generation, before.dirty, before.hash),
                            CountCommandJournal.AnchorState(anchor.storage, anchor.generation, anchor.dirty, anchor.hash))
                        scope.rows.replaceRoot(scope.authenticated.native(), signed)
                        require(CountCommandRootAnchor.update(sql, before, requireNotNull(anchor.hash)) == anchor)
                        result += Step(scope.header, current, previous, next, batch, removal.after, patch, fresh)
                        scope = openOld(access)
                    }
                    runtime.verifyCommandBoundary(boundary, expected)
                    result
                }
                runtime.maintenance(db) { runtime.sealCommandOperation() }
                require(tokens.localCoreWriteAccess() == access) { "COMMAND_FINAL_AUTHORITY_CHANGED" }
                require(tokens.registeredInstallationId() == installation) { "COMMAND_INSTALLATION_CHANGED" }
                steps
            }
            completed.end.requireCandidate(completed.value)
            completed.value
        }
    }
    internal suspend fun candidate() = commands().markMetricDelivered(parent, child)
    internal suspend fun differential(): List<Step> {
        val baseline = image(); val disk = closedImage(); assertCompleteImage(baseline, image())
        native.reset()
        val steps = withContext(native.cohort.asContextElement(true)) { oldTwoStepOracle() }
        val originalCounts = native.counts(); val expected = image()
        restore(disk); assertCompleteImage(baseline, image())
        native.reset()
        withContext(native.cohort.asContextElement(true)) { candidate() }
        val actualCounts = native.counts(); val actualImage = image(); assertCompleteImage(expected, actualImage)
        assertEquals(steps.size, originalCounts.roots); assertEquals(steps.size, originalCounts.anchors)
        assertEquals(if (steps.isEmpty()) 0 else 1, actualCounts.roots)
        assertEquals(actualCounts.roots, actualCounts.anchors)
        assertEquals(steps.sumOf { it.batch.mutations.size }, originalCounts.statuses)
        assertEquals(steps.flatMap { it.batch.mutations }.map { it.after.commandId }.distinct().size, actualCounts.statuses)
        assertEquals(originalCounts.nodes, actualCounts.nodes)
        assertEquals(0, originalCounts.extras); assertEquals(0, actualCounts.extras)
        val stable = actualImage; native.reset(); withContext(native.cohort.asContextElement(true)) { candidate() }
        assertCompleteImage(stable, image()); assertEquals(0, native.counts().roots); assertEquals(0, native.counts().statuses)
        return steps
    }
    internal suspend fun rejectionBoth(extraWrite: Boolean = false, preparationWrite: Boolean = false, expected: String? = null) {
        val before = image(); val disk = closedImage(); assertCompleteImage(before, image())
        native.reset(extraWrite, if (preparationWrite) parent.commandId else null)
        val original = rejected { withContext(native.cohort.asContextElement(true)) { oldTwoStepOracle() } }
        if (extraWrite || preparationWrite) assertEquals(1, native.counts().extras)
        reopen(); assertCompleteImage(before, image())
        restore(disk); native.reset(extraWrite, if (preparationWrite) parent.commandId else null)
        val actual = rejected { withContext(native.cohort.asContextElement(true)) { candidate() } }
        if (extraWrite || preparationWrite) assertEquals(1, native.counts().extras)
        reopen(); assertCompleteImage(before, image())
        if (expected != null) {
            assertTrue(generateSequence(original) { it.cause }.any { it.message == expected })
            assertTrue(generateSequence(actual) { it.cause }.any { it.message == expected })
        } else assertEquals(original.javaClass, actual.javaClass)
    }
}
