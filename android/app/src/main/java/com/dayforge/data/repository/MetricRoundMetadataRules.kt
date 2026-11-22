package com.dayforge.data.repository

import com.dayforge.data.api.dto.ChallengeMetadata
import java.util.Collections

/** A formal metric observation has head=null, not a COUNT birth.
 * Its bound response may repeat a subset of already retained metadata. Other legitimate metadata
 * must go through the original merger. This helper produces values only: CountTrustRoundAck still
 * owns native cursor/marker checks, same-value marker UPDATE and ordered expected effects.
 */
internal object MetricRoundMetadataRules {
    fun unchanged(source: NextRoundOperationIntent, retained: ChallengeMetadata,
        incoming: ChallengeMetadata): ChallengeMetadata? {
        if (source.operation.entityType != "metric_observation" || source.operation.action != "upsert" ||
            source.context.head != null || source.initialCreation || source.restartFrontier != null ||
            source.goalChildFrontier != null) return null
        val checkpoints = retained.checkpoints.associateBy { it.head.activityUuid }
        val births = retained.births.associateBy { it.entityType to it.entityUuid }
        // Incoming must already have passed strict DTO decode and validateRoundResultBinding.
        // Contradictions and extra valid metadata both defer to the original protocol merger;
        // returning null is never successful acceptance or a product rejection.
        if (incoming.checkpoints.any { checkpoints[it.head.activityUuid] != it } ||
            incoming.births.any { births[it.entityType to it.entityUuid] != it }) return null
        return retained.copy(
            checkpoints = Collections.unmodifiableList(retained.checkpoints.sortedBy { it.head.activityUuid }.map {
                it.copy(records = Collections.unmodifiableList(it.records.sortedBy { record -> record.head.generation }))
            }),
            births = Collections.unmodifiableList(retained.births.sortedWith(compareBy({ it.entityType }, { it.entityUuid })))
        )
    }
}
