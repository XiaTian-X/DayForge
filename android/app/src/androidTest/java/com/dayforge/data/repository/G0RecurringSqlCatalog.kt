package com.dayforge.data.repository

/** Opt-in READ phase only. Exact locked DAO/source templates, no traffic learning or SQL prefixes.
 * Batch sizes match the original boundedRowHashes allocation contract (1..128, then streaming).
 */
internal object G0RecurringSqlCatalog {
    val reads=buildMap<String,String> {
        put("SELECT * FROM habits WHERE id = ?","I")
        put("SELECT * FROM count_days WHERE habitId=? AND localDate=?","IS")
        put("SELECT * FROM count_days WHERE habitId=? ORDER BY localDate","I")
        put("SELECT * FROM completions WHERE habitId = ? ORDER BY recordedLocalDate DESC, COALESCE(actualCompletedAt, date) DESC, id DESC","I")
        put("SELECT * FROM completions WHERE habitId = ? AND recordedLocalDate >= ? AND recordedLocalDate < ?","ISS")
        put("""SELECT value,habitUuid,oneTimeAction,recordedLocalDate,
            date,actualCompletedAt,createdAt,recordedTimezone,timeMetadataSource,
            oneTimeExpectedVersion,oneTimeExpectedHeadEventUuid,oneTimeRevertsEventUuid,uuid FROM completions WHERE habitId=?""","I")
        put("SELECT challengeContract FROM next_sync_state","")
        put("SELECT EXISTS(SELECT 1 FROM next_challenge_state) OR EXISTS(SELECT 1 FROM next_challenge_rounds) OR EXISTS(SELECT 1 FROM next_challenge_births)","")
        put("SELECT * FROM next_replica_fence ORDER BY id","")
        put("SELECT entityUuid FROM sync_entity_state WHERE entityType=? AND payloadJson LIKE ? AND payloadJson LIKE ?","SSS")
        put("SELECT requestId FROM next_request_origins WHERE kind=? AND intentJson LIKE ? AND intentJson LIKE ?","SSS")
        put("SELECT requestId FROM next_request_origins WHERE kind=? AND intentJson LIKE ? AND intentJson LIKE ? LIMIT 10001","SSS")
        put("SELECT entityUuid FROM sync_entity_state WHERE entityType='activity_event' AND payloadJson LIKE ? AND payloadJson LIKE ? LIMIT 10001","SS")
        put("SELECT * FROM sync_entity_state WHERE entityType = ? AND entityUuid = ?","SS")
        put("SELECT total_changes()","")
        put("PRAGMA database_list","")
        put("PRAGMA \"main\".schema_version","")
        put("PRAGMA \"temp\".schema_version","")
        for((table,where,types) in listOf(
            Triple("count_days","habitId=? AND localDate=?","IS"),
            Triple("sync_entity_state","entityType=? AND entityUuid=?","SS"),
            Triple("sync_outbox","id=?","I"))) {
            val length=G0DomainSqlCatalog.columns.getValue(table).joinToString(" + ") { "COALESCE(length(CAST(`$it` AS BLOB)),0)" }
            put("PRAGMA table_info($table)","")
            put("SELECT ($length) FROM $table WHERE $where",types)
            put("SELECT * FROM $table WHERE $where",types)
        }
        for((table,column,discriminator) in listOf(Triple("next_request_origins","requestId","kind"),Triple("sync_entity_state","entityUuid","entityType"))) {
            val length=G0DomainSqlCatalog.columns.getValue(table).joinToString(" + ") { "COALESCE(length(CAST(`$it` AS BLOB)),0)" }
            put("PRAGMA table_info($table)","")
            for(size in 1..128) {
                val placeholders=(1..size).joinToString(",") { "?" };val types="S".repeat(size+1)
                val where="$discriminator=? AND `$column` IN ($placeholders)"
                put("SELECT `$column`,($length) FROM $table WHERE $where",types)
                put("SELECT * FROM $table WHERE $where",types)
                val daoWhere=if(table=="next_request_origins") "kind=? AND requestId IN ($placeholders)" else "entityType = ? AND entityUuid IN ($placeholders)"
                put("SELECT * FROM $table WHERE $daoWhere",types)
            }
        }
    }
}
