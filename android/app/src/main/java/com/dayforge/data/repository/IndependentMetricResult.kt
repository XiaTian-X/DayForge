package com.dayforge.data.repository

import com.dayforge.data.local.LocalDataSession
import com.dayforge.data.local.entity.MetricEntity

/** Frozen user action. Capture performs no T0/business write. Keep its reference before
 * execute so an unknown T0/T1 does not lose its original identity. */
internal class IndependentMetricAttempt internal constructor(internal val owner: IndependentMetricExecutor,
    internal val intent: CountCommandRecords.Intent?, internal val metric: MetricEntity,
    internal val authority: ObjectEditAuthority?, internal val session: LocalDataSession,
    internal val value: Double, internal val note: String, internal val recordedAt: Long, internal val timezone: String,
    internal val opaque: com.dayforge.data.local.LocalOpaqueMetricAccess? = null) {
    val reference: CountCommandReference? get() = if (opaque != null) null else requireNotNull(intent).let { CountCommandReference.from(CountCommandStore.Reference(it.commandId, it.inputHash, it.namespace.hash, it.namespace.dataset)) }
    val metricUuid: String get() = metric.uuid
    internal val executionIdentity: String = intent?.commandId ?: java.util.UUID.randomUUID().toString()
    internal var started = false
}

/** Historical save, not current presentation authority. */
internal class IndependentMetricSaved internal constructor(val factId: Long, val factUuid: String,
    val metricUuid: String, val value: Double, val unit: String,
    internal val commit: CountCommandStore.DurableCommit?, val deliveryAllowed: Boolean = true) {
    val reference: CountCommandReference? get() = commit?.let { CountCommandReference.from(it.reference) }
}
internal sealed interface IndependentMetricResolution {
    data class Saved(val value: IndependentMetricSaved) : IndependentMetricResolution
    data class Ended(val value: IndependentMetricEnded) : IndependentMetricResolution
    data class Unregistered(val reference: CountCommandReference) : IndependentMetricResolution
    data class Unresolved(val reference: CountCommandReference, val reason: String) : IndependentMetricResolution
}

/** Complete authenticated no-effects observation for an explicit cancel. Contains no live
 * SQL/Job/lease and cannot execute or silently change the original input. */
internal class IndependentMetricEnd internal constructor(internal val plan: CountCommandStore.IndependentEndPlan) {
    val reference: CountCommandReference get() = CountCommandReference.from(plan.reference)
}
internal class IndependentMetricEnded internal constructor(internal val result: CountCommandStore.EndedIndependent) {
    val reference: CountCommandReference get() = CountCommandReference.from(result.reference)
    val pendingDelivery: Boolean get() = result.pending
    val deliveryAllowed: Boolean get() = result.deliveryAllowed
}
