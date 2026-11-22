package com.dayforge.data.repository

/** Exact dependency declarations for the recurring read profile. Not SQL authorization.
 * A newly authorized statement gets NO reuse merely because it mentions a familiar table.
 */
internal object G0RecurringQueryDependencies {
    val tables=buildMap<String,Set<String>> {
        fun one(table:String,vararg queries:String) { for(sql in queries) put(sql,setOf(table)) }
        one("habits","SELECT * FROM habits WHERE id = ?")
        one("count_days","SELECT * FROM count_days WHERE habitId=? AND localDate=?","SELECT * FROM count_days WHERE habitId=? ORDER BY localDate")
        one("completions",
            "SELECT * FROM completions WHERE habitId = ? ORDER BY recordedLocalDate DESC, COALESCE(actualCompletedAt, date) DESC, id DESC",
            "SELECT * FROM completions WHERE habitId = ? AND recordedLocalDate >= ? AND recordedLocalDate < ?",
            """SELECT value,habitUuid,oneTimeAction,recordedLocalDate,
            date,actualCompletedAt,createdAt,recordedTimezone,timeMetadataSource,
            oneTimeExpectedVersion,oneTimeExpectedHeadEventUuid,oneTimeRevertsEventUuid,uuid FROM completions WHERE habitId=?""")
        one("next_sync_state","SELECT challengeContract FROM next_sync_state")
        put("SELECT EXISTS(SELECT 1 FROM next_challenge_state) OR EXISTS(SELECT 1 FROM next_challenge_rounds) OR EXISTS(SELECT 1 FROM next_challenge_births)",
            setOf("next_challenge_state","next_challenge_rounds","next_challenge_births"))
        one("next_replica_fence","SELECT * FROM next_replica_fence ORDER BY id")
        one("sync_entity_state",
            "SELECT entityUuid FROM sync_entity_state WHERE entityType=? AND payloadJson LIKE ? AND payloadJson LIKE ?",
            "SELECT entityUuid FROM sync_entity_state WHERE entityType='activity_event' AND payloadJson LIKE ? AND payloadJson LIKE ? LIMIT 10001",
            "SELECT * FROM sync_entity_state WHERE entityType = ? AND entityUuid = ?")
        one("next_request_origins",
            "SELECT requestId FROM next_request_origins WHERE kind=? AND intentJson LIKE ? AND intentJson LIKE ?",
            "SELECT requestId FROM next_request_origins WHERE kind=? AND intentJson LIKE ? AND intentJson LIKE ? LIMIT 10001")
        one("sync_control","SELECT id,suppressOutbox FROM sync_control")
        one("sync_outbox","SELECT count(*) FROM sync_outbox","SELECT * FROM sync_outbox WHERE id = ?")
        for((table,where) in listOf(
            "count_days" to "habitId=? AND localDate=?","sync_entity_state" to "entityType=? AND entityUuid=?","sync_outbox" to "id=?",
            "next_request_origins" to "kind=? AND requestId=?","next_transmissions" to "kind=? AND requestId=?",
            "next_acceptances" to "kind=? AND requestId=?","next_structural_dependencies" to "operationId=?")) {
            val length=G0DomainSqlCatalog.columns.getValue(table).joinToString(" + ") { "COALESCE(length(CAST(`$it` AS BLOB)),0)" }
            one(table,"SELECT ($length) FROM $table WHERE $where","SELECT * FROM $table WHERE $where")
        }
        for((table,column,discriminator) in listOf(Triple("next_request_origins","requestId","kind"),Triple("sync_entity_state","entityUuid","entityType"))) {
            val length=G0DomainSqlCatalog.columns.getValue(table).joinToString(" + ") { "COALESCE(length(CAST(`$it` AS BLOB)),0)" }
            for(size in 1..128) {
                val placeholders=(1..size).joinToString(",") { "?" }
                val where="$discriminator=? AND `$column` IN ($placeholders)"
                val daoWhere=if(table=="next_request_origins") "kind=? AND requestId IN ($placeholders)" else "entityType = ? AND entityUuid IN ($placeholders)"
                one(table,"SELECT `$column`,($length) FROM $table WHERE $where","SELECT * FROM $table WHERE $where","SELECT * FROM $table WHERE $daoWhere")
            }
        }
    }
}
