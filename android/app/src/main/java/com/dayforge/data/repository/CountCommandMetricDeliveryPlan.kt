package com.dayforge.data.repository

import android.database.Cursor
import com.dayforge.data.history.HistoryBlockCodec
import com.dayforge.data.history.HistoryBlockUpdater
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.TokenManager
import com.dayforge.domain.service.CountCommandCostTiming
import com.dayforge.domain.service.CountCommandCostTiming.Step as CostStep
import java.util.Collections
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Pure original one-step rules. These values have no authentication or signing authority. */
internal object CountCommandMetricDeliveryRules {
    data class Removal(val batch: CountCommandRowPlan.Batch, val pending: CountCommandPendingRules.Frontier)

    fun remove(frontier: CountCommandPendingRules.Frontier, current: CountCommandRowPlan.Aggregate,
        previous: CountCommandRowPlan.Aggregate?, next: CountCommandRowPlan.Aggregate?): Removal? {
        if (current.status.delivery == CountCommandRecords.Delivery.DELIVERED) return null
        val terminal = CountCommandTransitionRules.delivered(current.status)
        val removal = CountCommandPendingRules.remove(frontier, current.status, terminal, previous?.status, next?.status)
        val old = listOfNotNull(current, previous, next).associateBy { it.intent.commandId }
        val after = removal.changes.map { change -> old.getValue(change.after.commandId).let {
            require(it.status == change.before) { "COMMAND_METRIC_VIRTUAL_BEFORE_CHANGED" }
            it.withStatus(change.after)
        } }
        return Removal(CountCommandRowPlan.Batch(old.values.toList(), after), removal.after)
    }

    fun nextHeader(before: CountCommandRootCodec.Header, removal: Removal,
        patch: HistoryBlockUpdater.Patch): CountCommandRootCodec.Header = before.copy(
        catalog = patch.root, pending = removal.pending, revision = Math.addExact(before.revision, 1L)).also {
        CountCommandRootCodec.requireNext(before, it)
    }
}

/** A closed child-then-parent T2 plan. H1/H2 are real pure patch results, never fabricated
 * Authenticated roots. Only this issuer can construct a plan; no caller supplies headers,
 * revision deltas, native mutation lists or a signing callback. The original actual primary
 * transaction/Job must still be alive when the private store publishes or signs it. */
internal class CountCommandMetricDeliveryPlan private constructor(
    private val database: HabitDatabase,
    private val runtime: CountTrustRuntime,
    private val token: Any,
    private val caller: Job,
    private val original: CountCommandSeal.Authenticated,
    private val childStep: Step?,
    private val parentStep: Step?,
    mutations: List<CountCommandRowPlan.Mutation>,
    newBlocks: Map<String, HistoryBlockCodec.Bytes>
) {
    private data class Step(val removal: CountCommandMetricDeliveryRules.Removal,
        val patch: HistoryBlockUpdater.Patch, val header: CountCommandRootCodec.Header)
    @Volatile private var active = true
    private var signed = false
    val mutations: List<CountCommandRowPlan.Mutation> = Collections.unmodifiableList(mutations.toList())
    val newBlocks: Map<String, HistoryBlockCodec.Bytes> = Collections.unmodifiableMap(LinkedHashMap(newBlocks))
    val steps: Int get() = (if (childStep == null) 0 else 1) + (if (parentStep == null) 0 else 1)
    val finalHeader: CountCommandRootCodec.Header get() = parentStep?.header ?: childStep?.header ?: original.header

    suspend fun requireOriginal(expected: CountCommandSeal.Authenticated) {
        currentCoroutineContext().ensureActive()
        require(active && original === expected && currentCoroutineContext()[Job] === caller) { "COMMAND_METRIC_PLAN_EXPIRED" }
        runtime.requirePrimaryReadToken(database, token)
    }

    /** The dedicated signer accepts this closed sequence only. Ordinary advance stays +1. */
    suspend fun headerForSigning(expected: CountCommandSeal.Authenticated): CountCommandRootCodec.Header {
        requireOriginal(expected)
        require(!signed && steps in 1..2) { "COMMAND_METRIC_PLAN_ALREADY_CONSUMED" }
        var previous = original.header
        childStep?.let {
            require(CountCommandMetricDeliveryRules.nextHeader(previous, it.removal, it.patch) == it.header)
            previous = it.header
        }
        parentStep?.let {
            require(CountCommandMetricDeliveryRules.nextHeader(previous, it.removal, it.patch) == it.header)
            previous = it.header
        }
        require(previous == finalHeader)
        signed = true
        return previous
    }

    fun close() { active = false }

    companion object {
        suspend fun prepare(database: HabitDatabase, tokens: TokenManager, runtime: CountTrustRuntime,
            original: CountCommandSeal.Authenticated, namespace: CountCommandRecords.Namespace,
            parentRef: CountCommandStore.Reference, childRef: CountCommandStore.Reference): CountCommandMetricDeliveryPlan {
            val context = currentCoroutineContext()
            val caller = requireNotNull(context[Job])
            context.ensureActive()
            val token = runtime.capturePrimaryReadToken(database)
            val sql = database.openHelper.writableDatabase
            val lifetime = { runtime.requirePrimaryReadToken(database, token); context.ensureActive() }
            suspend fun requireCaller() {
                currentCoroutineContext().ensureActive()
                require(currentCoroutineContext()[Job] === caller) { "COMMAND_METRIC_PLAN_EXPIRED" }
                lifetime()
            }
            val rows = CountCommandNativeRows(sql, lifetime)
            require(CountCommandNativeRows.sameRoot(rows.root(), original.native())) { "COMMAND_METRIC_PLAN_ROOT_CHANGED" }
            require(original.header.namespace == namespace && original.header.accountBinding == namespace.catalogBinding) {
                "COMMAND_NAMESPACE_NOT_BOUND"
            }
            for (ref in listOf(parentRef, childRef)) {
                require(ref.datasetId == original.header.datasetId) { "COMMAND_DATASET_REPLACED" }
                require(ref.namespaceHash == namespace.hash) { "COMMAND_REFERENCE_NAMESPACE_CHANGED" }
            }
            val nativeById = linkedMapOf<String, CountCommandRowPlan.Aggregate>()
            val virtual = linkedMapOf<String, CountCommandRowPlan.Aggregate>()
            val blocks = linkedMapOf<String, HistoryBlockCodec.Bytes>()
            val physical = linkedMapOf<String, CountCommandRowPlan.Mutation>()
            var header = original.header

            suspend fun readBaseline(id: String): CountCommandRowPlan.Aggregate {
                requireCaller()
                nativeById[id]?.let { return it }
                val row = CountCommandCostTiming.measure(CostStep.T2_BASELINE_READ) {
                    CountCommandDirectory(sql, namespace.catalogBinding, requireNotNull(original.header.catalog), lifetime, lifetime).read { reader ->
                        val member = reader.member(id)
                        if (member == null) {
                            CountCommandRowPlan.Table.entries.forEach { rows.requireAbsent(it, id) }
                            throw IllegalArgumentException("COMMAND_NATIVE_MEMBER_MISSING")
                        }
                        CountCommandCostTiming.measure(CostStep.T2_BASELINE_AGGREGATE) {
                            rows.aggregateForMetricDelivery(member, lifetime).also { require(it.intent.namespace.catalogBinding == namespace.catalogBinding && CountCommandRootCodec.admits(original.header, it.intent.format)) }
                        }
                    }
                }
                requireCaller()
                nativeById[id] = row
                return row
            }
            suspend fun virtualRow(id: String): CountCommandRowPlan.Aggregate {
                requireCaller()
                return virtual[id] ?: readBaseline(id)
            }
            val parent = readBaseline(parentRef.commandId); val child = readBaseline(childRef.commandId)
            require(parent.intent.inputHash == parentRef.inputHash && child.intent.inputHash == childRef.inputHash &&
                parent.status.child == childRef.commandId && child.status.parent == parentRef.commandId &&
                child.status.execution == CountCommandRecords.Execution.APPLIED &&
                child.outcome?.action == CountCommandRecords.Action.LINKED_METRICS &&
                parent.status.delivery in setOf(CountCommandRecords.Delivery.METRICS_COMMITTED, CountCommandRecords.Delivery.DELIVERED))

            fun requireNodeCollision(digest: String, bytes: HistoryBlockCodec.Bytes) {
                lifetime()
                val body = bytes.toByteArray()
                require(body.size in 1..HistoryBlockCodec.MAX_NODE_BYTES && HistoryBlockCodec.hash(body) == digest)
                sql.query("SELECT typeof(body),length(body),CASE WHEN typeof(body)='blob' AND length(body)=? THEN body END " +
                    "FROM count_command_nodes WHERE digest=? LIMIT 2", arrayOf<Any>(body.size, digest)).use { c ->
                    if (c.moveToFirst()) require(c.getType(0) == Cursor.FIELD_TYPE_STRING && c.getString(0) == "blob" &&
                        c.getType(1) == Cursor.FIELD_TYPE_INTEGER && c.getLong(1) == body.size.toLong() &&
                        c.getType(2) == Cursor.FIELD_TYPE_BLOB && c.getBlob(2).contentEquals(body) && !c.moveToNext())
                }
            }
            suspend fun step(ref: CountCommandStore.Reference, checkBeforeNextStep: Boolean): Step? {
                val aggregate = virtualRow(ref.commandId)
                if (aggregate.status.delivery == CountCommandRecords.Delivery.DELIVERED) return null
                val previous = aggregate.status.previous?.let { virtualRow(it) }
                val next = aggregate.status.next?.let { virtualRow(it) }
                val removal = requireNotNull(CountCommandMetricDeliveryRules.remove(header.pending, aggregate, previous, next))
                val patch = CountCommandDirectory(sql, namespace.catalogBinding, requireNotNull(header.catalog), lifetime, lifetime,
                    nodeOverlay = blocks).read { it.replace(removal.batch.catalogEdits) }
                val fresh = CountCommandMetricDeliveryRules.nextHeader(header, removal, patch)
                for (mutation in removal.batch.mutations) {
                    require(mutation.after.table == CountCommandRowPlan.Table.STATUS && mutation.before != null)
                    val old = physical[mutation.after.commandId]
                    physical[mutation.after.commandId] = if (old == null) mutation else {
                        require(old.after == mutation.before) { "COMMAND_METRIC_VIRTUAL_BEFORE_CHANGED" }
                        CountCommandRowPlan.Mutation(old.before, mutation.after)
                    }
                }
                removal.batch.after.forEach { virtual[it.intent.commandId] = it }
                for ((digest, bytes) in patch.newBlocks) {
                    val body = bytes.toByteArray()
                    require(body.size in 1..HistoryBlockCodec.MAX_NODE_BYTES && HistoryBlockCodec.hash(body) == digest)
                    if (checkBeforeNextStep) requireNodeCollision(digest, bytes)
                    val previousBytes = blocks[digest]
                    require(previousBytes == null || previousBytes == bytes) { "COMMAND_METRIC_NODE_COLLISION" }
                    if (previousBytes == null) blocks[digest] = bytes
                }
                header = fresh
                requireCaller()
                return Step(removal, patch, fresh)
            }
            val first = CountCommandCostTiming.measure(CostStep.T2_CHILD_STEP) {
                step(childRef, checkBeforeNextStep = true)
            }
            if (first != null && parent.status.delivery != CountCommandRecords.Delivery.DELIVERED) {
                // The true two-step case retains the original intermediate open's meaningful
                // environment check. A one-step case keeps its check in the final native open.
                // H1 remains unsigned/pure, so no native H1/MAC is invented.
                requireCaller()
                header.registration?.let {
                    require(tokens.registeredInstallationId() == it.installationId) { "COMMAND_INSTALLATION_CHANGED" }
                }
                requireCaller()
            }
            val second = CountCommandCostTiming.measure(CostStep.T2_PARENT_STEP) {
                step(parentRef, checkBeforeNextStep = false)
            }
            requireCaller()
            return CountCommandMetricDeliveryPlan(database, runtime, token, caller, original,
                first, second, physical.values.toList(), blocks)
        }
    }
}
