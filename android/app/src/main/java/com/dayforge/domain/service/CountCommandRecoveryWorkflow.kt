package com.dayforge.domain.service

import android.content.Context
import com.dayforge.data.local.DatabaseEpochValue
import com.dayforge.data.local.DatabaseRuntimeHost
import com.dayforge.data.repository.CountCommandPendingRepository
import com.dayforge.data.repository.CountCommandReference
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** No startup replay. Each explicit action uses its original page publication and the
 * existing same-habit Gate before any recovery path that may enter T1. */
internal class CountCommandRecoveryWorkflow @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val host: DatabaseRuntimeHost, private val gate: CountActionGate
) {
    private fun acquire(admission: CountCommandPendingRepository.Admission): CountActionGate.Lease? =
        when (admission) {
            is CountCommandPendingRepository.Admission.Habit -> gate.tryAcquire(admission.session, admission.subjectUuid, admission.subjectId)
            is CountCommandPendingRepository.Admission.Metric -> gate.tryAcquireMetric(admission.session, admission.subjectUuid)
        }

    suspend fun page(previous: DatabaseEpochValue<CountCommandPendingRepository.Page>? = null): DatabaseEpochValue<CountCommandPendingRepository.Page> =
        if (previous == null) host.snapshot { it.countCommandPendingRepository.page() }
        else host.withSourceGraph(previous.publication) { graph ->
            DatabaseEpochValue(graph.countCommandPendingRepository.page(previous.value), previous.publication)
        }

    suspend fun resume(page: DatabaseEpochValue<CountCommandPendingRepository.Page>, reference: CountCommandReference,
        isConsumerCurrent: () -> Boolean = { true },
        accept: suspend (CountCommandPendingRepository.Result) -> Boolean): CountCommandPendingRepository.FollowUp? {
        require(page.value.entries.any { it.reference == reference }) { "COMMAND_DISCOVERY_SELECTION_CHANGED" }
        return host.withSourceGraph(page.publication) { graph ->
            val repository = graph.countCommandPendingRepository
            val admission = repository.prepare(reference)
            val lease = acquire(admission)
                ?: error("COUNT_ACTION_BUSY")
            try {
                val result = repository.resume(context, admission)
                check(accept(result)) { "DATABASE_EPOCH_SELECTION_CHANGED" }
                when (result) {
                    is CountCommandPendingRepository.Result.Unresolved -> null
                    is CountCommandPendingRepository.Result.IndependentEndAvailable -> null
                    is CountCommandPendingRepository.Result.IndependentEnded -> {
                        if (result.pending) afterCommittedAction { repository.acknowledgeIndependentEnd(result, isConsumerCurrent) }
                        null
                    }
                    is CountCommandPendingRepository.Result.EndAvailable -> null // Display only; explicit End is a distinct action.
                    is CountCommandPendingRepository.Result.FollowUpEnded -> null
                    is CountCommandPendingRepository.Result.Invalidated -> {
                        if (result.pending) afterCommittedAction { repository.acknowledgeInvalidated(result, isConsumerCurrent) }
                        null
                    }
                    is CountCommandPendingRepository.Result.StandaloneMetricSaved -> {
                        if (result.pending) afterCommittedAction { repository.acknowledgeIndependent(result, isConsumerCurrent) }
                        null
                    }
                    is CountCommandPendingRepository.Result.MetricParent -> repository.followUp(result.reference)
                    is CountCommandPendingRepository.Result.MetricSaved -> {
                        if (result.pending && result.preferenceReady) afterCommittedAction {
                            repository.acknowledgeMetrics(result, isConsumerCurrent)
                        }
                        null
                    }
                    is CountCommandPendingRepository.Result.Saved -> if (!result.pending) null else if (!result.receipt.requiresMetricFollowUp) {
                        // Actual public result-model acceptance, not pixels/startActivity/finish.
                        afterCommittedAction { repository.acknowledge(result.receipt) }
                        null
                    } else repository.followUp(result.receipt.reference)
                }
            } finally { gate.release(lease) }
        }
    }
    suspend fun endIndependent(page: DatabaseEpochValue<CountCommandPendingRepository.Page>,
        original: CountCommandPendingRepository.Result.IndependentEndAvailable, isPlanCurrent: () -> Boolean,
        isResultCurrent: () -> Boolean, accept: suspend (CountCommandPendingRepository.Result.IndependentEnded) -> Boolean) {
        require(page.value.entries.any { it.reference == original.reference })
        host.withSourceGraph(page.publication) { graph ->
            require(isPlanCurrent()) { "COMMAND_RECOVERY_CONSUMER_CLOSED" }
            val repository = graph.countCommandPendingRepository
            val admission = repository.prepare(original.reference)
            require(admission is CountCommandPendingRepository.Admission.Metric &&
                admission.scope == com.dayforge.data.repository.CountCommandStore.LocationScope.CURRENT)
            val lease = acquire(admission) ?: error("COUNT_ACTION_BUSY")
            try {
                val ended = repository.endIndependent(original, isPlanCurrent)
                check(accept(ended)) { "DATABASE_EPOCH_SELECTION_CHANGED" }
                if (ended.pending) afterCommittedAction { repository.acknowledgeIndependentEnd(ended, isResultCurrent) }
            } finally { gate.release(lease) }
        }
    }

    suspend fun endFollowUp(page: DatabaseEpochValue<CountCommandPendingRepository.Page>,
        original: CountCommandPendingRepository.Result.EndAvailable, isConsumerCurrent: () -> Boolean,
        accept: suspend (CountCommandPendingRepository.Result.FollowUpEnded) -> Boolean) {
        require(page.value.entries.any { it.reference == original.reference }) { "COMMAND_DISCOVERY_SELECTION_CHANGED" }
        host.withSourceGraph(page.publication) { graph ->
            require(isConsumerCurrent()) { "COMMAND_RECOVERY_CONSUMER_CLOSED" }
            val repository = graph.countCommandPendingRepository
            val admission = repository.prepare(original.reference)
            val lease = acquire(admission) ?: error("COUNT_ACTION_BUSY")
            try {
                // No proof refresh here. The exact rendered value must survive every current
                // source/member/g/permission check, or this explicit decision is rejected.
                val ended = repository.endFollowUp(original, isConsumerCurrent)
                check(accept(ended)) { "DATABASE_EPOCH_SELECTION_CHANGED" }
            } finally { gate.release(lease) }
        }
    }

}
