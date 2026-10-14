package com.dayforge.data.repository

import android.database.Cursor
import com.dayforge.data.api.dto.SyncV2Change
import com.dayforge.data.local.HabitDatabase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Shared SELECT-only authority validation; never trusts Room coercion or a missing receipt. */
internal class NextAuthorityShadowReader(private val database: HabitDatabase) {
    suspend fun readInTransaction(onValidated: (SyncV2Change) -> Unit = {}) {
        check(database.inTransaction())
        val sql = database.openHelper.writableDatabase
        sql.query("SELECT entityType,entityUuid FROM sync_entity_state ORDER BY entityType,entityUuid").use { cursor ->
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                require(cursor.getType(0) == Cursor.FIELD_TYPE_STRING && cursor.getType(1) == Cursor.FIELD_TYPE_STRING)
                val type = cursor.getString(0); val id = cursor.getString(1)
                requireNotNull(NextRequestSql.rowHash(sql, "sync_entity_state", "entityType=? AND entityUuid=?", arrayOf(type, id)))
                val shadow = requireNotNull(database.syncOutboxDao().getState(type, id))
                require(shadow.revision > 0 && shadow.payloadJson != null && shadow.payloadHash == syncPayloadHash(shadow.payloadJson))
                val body = Json.parseToJsonElement(shadow.payloadJson).jsonObject
                val changed = requireNotNull(body["updated_at"] as? JsonPrimitive).also { require(it.isString) }.content
                val change = SyncV2Change(0, type, id, if (shadow.deleted) "delete" else "upsert", shadow.revision, body, changed)
                when (type) {
                    "plan_node", "metric" -> if (shadow.deleted) NextStructureMapper.validateTombstone(body, type, id, shadow.revision)
                        else if (type == "plan_node") NextStructureMapper.readPlan(body, id, shadow.revision)
                        else NextStructureMapper.readMetric(body, id, shadow.revision)
                    "activity_metric_link" -> NextCommonFactMapper.validateLinkSnapshot(change)
                    "metric_observation" -> NextCommonFactMapper.validateOrdinaryFactSnapshot(change)
                    "activity_event" -> {
                        require(!shadow.deleted)
                        when {
                            body["one_time"].let { it != null && it != JsonNull } -> OneTimeServerFact(body, shadow.revision)
                            body["event_type"] == JsonPrimitive("duration_session") -> NextCommonFactMapper.validateDurationSnapshot(change)
                            else -> NextCommonFactMapper.validateOrdinaryFactSnapshot(change)
                        }
                    }
                    else -> error("Unsupported authority shadow")
                }
                onValidated(change)
            }
        }
    }
}
