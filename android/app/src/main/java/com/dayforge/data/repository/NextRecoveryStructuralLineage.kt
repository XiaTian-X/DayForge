package com.dayforge.data.repository

import com.dayforge.domain.model.isContractUuid
import java.util.Collections

/** Original logical ancestry and its actual source/receipt; accepted ancestors are proof-only. */
internal data class NextRecoveryStructuralLink(
    val logicalKey: NextRecoveryKey,
    val actualKey: NextRecoveryKey,
    val logicalQueueId: Long,
    val actualQueueId: Long,
    val entityType: String,
    val entityUuid: String,
    val predecessorId: String?,
    val dependencyHash: String?,
    val supersessionHash: String?,
    val state: NextRecoveryRequestState,
    val evidenceHash: String,
    val acceptanceHash: String?,
    val acceptedResultHash: String?
) {
    init {
        require(logicalKey.kind == NEXT_OPERATION && actualKey.kind == NEXT_OPERATION)
        require(logicalQueueId > 0 && actualQueueId >= logicalQueueId &&
            (logicalKey == actualKey) == (logicalQueueId == actualQueueId) &&
            (logicalKey != actualKey) == (supersessionHash != null))
        require(entityType in setOf("plan_node", "metric", "activity_metric_link") && isContractUuid(entityUuid))
        predecessorId?.let { require(isContractUuid(it) && it != logicalKey.requestId && dependencyHash != null) }
        require(state in setOf(NextRecoveryRequestState.NEVER_SENT, NextRecoveryRequestState.UNKNOWN_RESULT,
            NextRecoveryRequestState.PERMANENT_REJECTION, NextRecoveryRequestState.ACCEPTED))
        require((state == NextRecoveryRequestState.ACCEPTED) == (acceptanceHash != null) &&
            (acceptanceHash != null) == (acceptedResultHash != null))
        for (hash in listOfNotNull(dependencyHash, supersessionHash, evidenceHash, acceptanceHash, acceptedResultHash))
            require(hash.length == 64 && hash.all { it in "0123456789abcdef" })
    }
}

/**
 * Bounded descriptors only: never retain all ancestor intents/results as one closure-sized body.
 * SELECT-only original structural chain, not the full dependency closure or disposal permission.
 * In particular a metric delete has no captured D-015 link; the sender's separate current head
 * barrier must still be joined, as must entity references, facts/timers/import/CAS dependencies.
 */
internal class NextRecoveryStructuralLineage internal constructor(
    val actualRequestId: String,
    links: List<NextRecoveryStructuralLink>,
    val checkpointHash: String
) {
    val links: List<NextRecoveryStructuralLink> = Collections.unmodifiableList(links.toList())
    val evidenceHash = nextRequestHash((checkpointHash + "\n" + actualRequestId + "\n" +
        this.links.joinToString("\n") { it.evidenceHash }).toByteArray(Charsets.UTF_8))
}
