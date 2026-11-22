package com.dayforge.data.repository

/** Frozen from Room v18 and locked generated DAO SQL; never learns SQL from traffic.
 * V is one native SQLite bind value, not a SQL fragment. Domain validation checks its meaning.
 * Additional statements require an explicit catalogue and fault-matrix change.
 */
internal object G0DomainSqlCatalog {
    val columns = mapOf(
        "habits" to listOf("id", "name", "description", "habitType", "iconResId", "colorHex", "schedule", "targetValue", "isCountdown", "isActive", "uuid", "parentHabitId", "targetCycles", "failMode", "goalSuccess", "activityRate", "activityRateUpdatedAt", "bestTime", "createdAt", "updatedAt", "completionPolicy", "oneTimeConfirmedVersion", "oneTimeConfirmedHeadEventUuid", "oneTimeConfirmedCompletionEventUuid", "appearance", "planMetadata"),
        "completions" to listOf("id", "habitId", "date", "value", "actualCompletedAt", "uuid", "habitUuid", "createdAt", "recordedTimezone", "recordedLocalDate", "timeMetadataSource", "oneTimeAction", "oneTimeExpectedVersion", "oneTimeExpectedHeadEventUuid", "oneTimeRevertsEventUuid"),
        "count_days" to listOf("habitId", "habitUuid", "localDate", "targetValue", "isCountdown", "firstEventUuid", "originRequestId", "originHash", "planPredecessorId", "planPredecessorOriginHash", "planPredecessorDependencyHash", "planQueueWatermark", "capturedDeviceId", "originKind"),
        "timelogs" to listOf("id", "habitId", "startTime", "endTime", "durationSeconds", "isPaused", "pausedAt", "accumulatedPauseMillis", "timerNextCommandSequence", "timerControlGeneration", "timerLastCommandAt", "timerTimezone", "timerActiveElapsedMillis", "timerElapsedRealtimeAnchor", "timerBootCount", "date", "uuid", "createdAt", "updatedAt"),
        "metrics" to listOf("id", "name", "description", "unit", "decimalPlaces", "aggregationType", "targetDirection", "targetValue", "targetValueUpper", "iconResId", "colorHex", "isActive", "uuid", "createdAt", "updatedAt", "appearance"),
        "metric_logs" to listOf("id", "metricId", "date", "value", "unit", "note", "uuid", "createdAt", "updatedAt", "recordedTimezone", "recordedLocalDate", "timeMetadataSource"),
        "habit_metric_links" to listOf("id", "habitId", "habitUuid", "metricId", "metricUuid", "coefficient", "showInHabitDetail", "promptOnComplete", "isActive", "uuid", "createdAt", "updatedAt"),
        "sync_outbox" to listOf("id", "operationId", "recordType", "entityUuid", "wireEntityUuid", "action", "referenceUuid", "payloadJson", "baseRevision", "basePayloadJson", "attemptedAt", "attemptCount", "lastError", "errorCode", "deadLetteredAt", "createdAt"),
        "sync_entity_state" to listOf("entityType", "entityUuid", "revision", "deleted", "payloadJson", "payloadHash", "updatedAt"),
        "sync_conflicts" to listOf("id", "operationId", "recordType", "localEntityUuid", "wireEntityUuid", "entityType", "action", "referenceUuid", "baseRevision", "serverRevision", "basePayloadJson", "localPayloadJson", "serverPayloadJson", "conflictingFieldsJson", "conflictKind", "errorCode", "message", "status", "resolution", "createdAt", "resolvedAt"),
        "sync_control" to listOf("id", "suppressOutbox"),
        "timer_command_outbox" to listOf("id", "commandId", "sessionUuid", "sequence", "commandType", "occurredAt", "expectedControlGeneration", "expectedRevision", "activityUuid", "timezone", "activeElapsedMillis", "attemptCount", "lastError", "errorCode", "deadLetteredAt", "createdAt"),
        "timer_segments" to listOf("id", "sessionUuid", "sequence", "startedAt", "endedAt"),
        "timelog_day_allocations" to listOf("sessionUuid", "habitId", "localDate", "localDateEpoch", "timezone", "durationMillis"),
        "local_fact_submissions" to listOf("operationId", "entityType", "entityUuid", "referenceUuid", "payloadJson"),
        "completion_metric_prompts" to listOf("eventUuid", "activityUuid", "state", "revision", "entriesJson", "recordedAtMillis", "timezone"),
        "one_time_transmissions" to listOf("operationId", "accountId", "serverInstanceId", "syncEpoch", "deviceId", "operationJson", "rejectionJson"),
        "next_recovery_state" to listOf("accountId", "serverInstanceId", "syncEpoch", "deviceId", "generation", "phase", "minimumCursor", "candidateCursor", "snapshotHash", "id"),
        "next_request_origins" to listOf("kind", "requestId", "queueId", "protocol", "accountId", "serverInstanceId", "syncEpoch", "sourceHash", "intentJson"),
        "next_transmissions" to listOf("kind", "requestId", "queueId", "protocol", "accountId", "serverInstanceId", "syncEpoch", "deviceId", "wireHash", "wireBytes"),
        "next_acceptances" to listOf("kind", "requestId", "originHash", "transmissionHash", "resultHash", "resultJson"),
        "next_structural_dependencies" to listOf("operationId", "logicalOrder", "originHash", "predecessorId", "predecessorOriginHash", "predecessorDependencyHash", "capturedDeviceId", "kind"),
        "next_structural_supersessions" to listOf("originalId", "replacementId", "originalQueueId", "replacementQueueId", "originalOriginHash", "originalDependencyHash", "originalSourceHash", "sourceSnapshotJson", "sourceSnapshotHash", "predecessorAcceptedRequestId", "predecessorAcceptanceHash", "replacementOriginHash", "accountId", "serverInstanceId", "syncEpoch", "deviceId", "kind"),
        "next_sync_state" to listOf("accountId", "serverInstanceId", "syncEpoch", "deviceId", "generation", "cursor", "bootstrapHash", "batchHash", "id", "challengeContract"),
        "next_rejections" to listOf("kind", "requestId", "originHash", "transmissionHash", "resultHash", "resultJson"),
        "next_challenge_rounds" to listOf("roundUuid", "activityUuid", "generation", "accountId", "serverInstanceId", "syncEpoch", "sourceDeviceUuid", "operationUuid", "recordJson", "recordHash"),
        "next_challenge_births" to listOf("entityType", "entityUuid", "roundUuid"),
        "next_challenge_state" to listOf("accountId", "serverInstanceId", "syncEpoch", "deviceId", "generation", "cursor", "batchHash", "metadataHash", "id"),
        "next_restart_materializations" to listOf("operationId", "kind", "originHash", "frontierHash", "operationJson", "operationHash"),
        "next_restart_plan_proofs" to listOf("operationId", "originHash", "transmissionHash", "revision", "logSequence", "deviceId", "planJson", "planHash"),
        "next_config_imports" to listOf("importId", "accountId", "serverInstanceId", "syncEpoch", "deviceId", "archiveHash", "identitiesHash", "mode", "state", "receiptHash"),
        "next_config_import_payloads" to listOf("importId", "payloadKind", "part", "bytes"),
        "next_recovery_audits" to listOf("recoveryId", "accountId", "serverInstanceId", "syncEpoch", "deviceId", "rootKind", "rootRequestId", "graphHash", "checkpointHash", "manifestHash", "selectedCount"),
        "next_abandoned_requests" to listOf("kind", "requestId", "recoveryId", "disposition", "sourceHash", "sourceSnapshotHash", "originHash", "transmissionHash", "rejectionHash"),
        "next_recovery_audit_payloads" to listOf("recoveryId", "payloadKey", "part", "bytes"),
        "next_replica_fence" to listOf("recoveryId", "accountId", "serverInstanceId", "oldEpoch", "newEpoch", "authenticationGeneration", "deviceId", "capabilityRevision", "sourceHash", "mode", "id")
    )
    val primaryKeys = mapOf(
        "habits" to listOf("id"),
        "completions" to listOf("id"),
        "count_days" to listOf("habitId", "localDate"),
        "timelogs" to listOf("id"),
        "metrics" to listOf("id"),
        "metric_logs" to listOf("id"),
        "habit_metric_links" to listOf("id"),
        "sync_outbox" to listOf("id"),
        "sync_entity_state" to listOf("entityType", "entityUuid"),
        "sync_conflicts" to listOf("id"),
        "sync_control" to listOf("id"),
        "timer_command_outbox" to listOf("id"),
        "timer_segments" to listOf("id"),
        "timelog_day_allocations" to listOf("sessionUuid", "localDate", "timezone"),
        "local_fact_submissions" to listOf("operationId"),
        "completion_metric_prompts" to listOf("eventUuid"),
        "one_time_transmissions" to listOf("operationId"),
        "next_recovery_state" to listOf("id"),
        "next_request_origins" to listOf("kind", "requestId"),
        "next_transmissions" to listOf("kind", "requestId"),
        "next_acceptances" to listOf("kind", "requestId"),
        "next_structural_dependencies" to listOf("operationId"),
        "next_structural_supersessions" to listOf("originalId"),
        "next_sync_state" to listOf("id"),
        "next_rejections" to listOf("kind", "requestId"),
        "next_challenge_rounds" to listOf("roundUuid"),
        "next_challenge_births" to listOf("entityType", "entityUuid"),
        "next_challenge_state" to listOf("id"),
        "next_restart_materializations" to listOf("operationId"),
        "next_restart_plan_proofs" to listOf("operationId"),
        "next_config_imports" to listOf("importId"),
        "next_config_import_payloads" to listOf("importId", "payloadKind", "part"),
        "next_recovery_audits" to listOf("recoveryId"),
        "next_abandoned_requests" to listOf("kind", "requestId"),
        "next_recovery_audit_payloads" to listOf("recoveryId", "payloadKey", "part"),
        "next_replica_fence" to listOf("id")
    )
    val reads = mapOf(
        "SELECT * FROM metrics WHERE id = ? LIMIT 1" to "I",
        "SELECT * FROM metrics WHERE uuid = ? LIMIT 1" to "S",
        "SELECT * FROM metrics" to "",
        "SELECT * FROM metrics WHERE name = ?" to "S",
        "SELECT id,suppressOutbox FROM sync_control" to "",
        "SELECT count(*) FROM sync_outbox" to "",
        "SELECT * FROM sync_outbox WHERE id = ?" to "I"
    ).plus(buildMap {
        for ((table, where, types) in listOf(
            Triple("next_request_origins", "kind=? AND requestId=?", "SS"),
            Triple("next_transmissions", "kind=? AND requestId=?", "SS"),
            Triple("next_acceptances", "kind=? AND requestId=?", "SS"),
            Triple("next_structural_dependencies", "operationId=?", "S"))) {
            val length = columns.getValue(table).joinToString(" + ") { "COALESCE(length(CAST(`$it` AS BLOB)),0)" }
            put("PRAGMA table_info($table)", "")
            put("SELECT ($length) FROM $table WHERE $where", types)
            put("SELECT * FROM $table WHERE $where", types)
        }
    })
    val checkpointReads = (columns.keys + setOf("android_metadata", "room_master_table", "sqlite_sequence"))
        .associate { "SELECT * FROM main.`$it`" to "" } + mapOf(
            "SELECT type,name,tbl_name,sql FROM main.sqlite_master ORDER BY type,name" to "",
            "PRAGMA main.user_version" to "",
            "PRAGMA main.integrity_check" to "")
    val writes = mapOf(
        "INSERT OR REPLACE INTO `metrics` (`id`,`name`,`description`,`unit`,`decimalPlaces`,`aggregationType`,`targetDirection`,`targetValue`,`targetValueUpper`,`iconResId`,`colorHex`,`isActive`,`uuid`,`createdAt`,`updatedAt`,`appearance`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)" to "VVVVVVVVVVVVVVVV",
        "INSERT OR ABORT INTO `metrics` (`id`,`name`,`description`,`unit`,`decimalPlaces`,`aggregationType`,`targetDirection`,`targetValue`,`targetValueUpper`,`iconResId`,`colorHex`,`isActive`,`uuid`,`createdAt`,`updatedAt`,`appearance`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)" to "VVVVVVVVVVVVVVVV",
        "UPDATE OR ABORT `metrics` SET `id` = ?,`name` = ?,`description` = ?,`unit` = ?,`decimalPlaces` = ?,`aggregationType` = ?,`targetDirection` = ?,`targetValue` = ?,`targetValueUpper` = ?,`iconResId` = ?,`colorHex` = ?,`isActive` = ?,`uuid` = ?,`createdAt` = ?,`updatedAt` = ?,`appearance` = ? WHERE `id` = ?" to "VVVVVVVVVVVVVVVVV",
        "INSERT OR ABORT INTO `sync_outbox` (`id`,`operationId`,`recordType`,`entityUuid`,`wireEntityUuid`,`action`,`referenceUuid`,`payloadJson`,`baseRevision`,`basePayloadJson`,`attemptedAt`,`attemptCount`,`lastError`,`errorCode`,`deadLetteredAt`,`createdAt`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)" to "VVVVVVVVVVVVVVVV",
        "INSERT OR REPLACE INTO `sync_entity_state` (`entityType`,`entityUuid`,`revision`,`deleted`,`payloadJson`,`payloadHash`,`updatedAt`) VALUES (?,?,?,?,?,?,?)" to "VVVVVVV",
        "INSERT OR ABORT INTO `next_request_origins` (`kind`,`requestId`,`queueId`,`protocol`,`accountId`,`serverInstanceId`,`syncEpoch`,`sourceHash`,`intentJson`) VALUES (?,?,?,?,?,?,?,?,?)" to "VVVVVVVVV",
        "INSERT OR ABORT INTO `next_transmissions` (`kind`,`requestId`,`queueId`,`protocol`,`accountId`,`serverInstanceId`,`syncEpoch`,`deviceId`,`wireHash`,`wireBytes`) VALUES (?,?,?,?,?,?,?,?,?,?)" to "VVVVVVVVVV",
        "INSERT OR ABORT INTO `next_acceptances` (`kind`,`requestId`,`originHash`,`transmissionHash`,`resultHash`,`resultJson`) VALUES (?,?,?,?,?,?)" to "VVVVVV",
        "INSERT OR ABORT INTO `next_structural_dependencies` (`operationId`,`logicalOrder`,`originHash`,`predecessorId`,`predecessorOriginHash`,`predecessorDependencyHash`,`capturedDeviceId`,`kind`) VALUES (?,?,?,?,?,?,?,?)" to "VVVVVVVV",
        "INSERT OR ABORT INTO `next_structural_supersessions` (`originalId`,`replacementId`,`originalQueueId`,`replacementQueueId`,`originalOriginHash`,`originalDependencyHash`,`originalSourceHash`,`sourceSnapshotJson`,`sourceSnapshotHash`,`predecessorAcceptedRequestId`,`predecessorAcceptanceHash`,`replacementOriginHash`,`accountId`,`serverInstanceId`,`syncEpoch`,`deviceId`,`kind`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)" to "VVVVVVVVVVVVVVVVV",
        "UPDATE metrics SET name=?,description=?,updatedAt=? WHERE id=?" to "SSII",
        "DELETE FROM sync_outbox WHERE id=?" to "I",
        "UPDATE sync_control SET suppressOutbox=1 WHERE id=1" to "",
        "UPDATE sync_control SET suppressOutbox=0 WHERE id=1" to ""
    )
}
