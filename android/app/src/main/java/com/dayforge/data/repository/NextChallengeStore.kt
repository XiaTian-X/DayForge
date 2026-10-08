package com.dayforge.data.repository

import android.database.Cursor
import com.dayforge.data.api.dto.*
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.local.entity.*
import com.dayforge.domain.model.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Internal transaction participant, not an account/HTTP entry or restart acceptance API. */
internal class NextChallengeStore(private val database: HabitDatabase) {
    private val json = Json { encodeDefaults = true }
    private val dao get() = database.nextChallengeDao()

    suspend fun activeInTransaction(access: LocalSyncAccess): Pair<NextSyncStateEntity, ChallengeMetadata> {
        check(database.inTransaction())
        require(access.deviceId != null)
        requireNotNull(NextRequestSql.rowHash(database.openHelper.writableDatabase, "next_sync_state", "1=1", emptyArray()))
        val state = database.nextSyncStateDao().rows().single()
        require(state.challengeContract == 1)
        return state to requireNotNull(readInTransaction(access, state))
    }

    /** Sidecar-only ACK merge; caller commits the original receipt and business in the SAME transaction. */
    suspend fun acknowledgeInTransaction(access: LocalSyncAccess, incoming: ChallengeMetadata): ChallengeMetadata {
        val (state, _) = activeInTransaction(access)
        val before = otherTablesProof()
        mergeInTransaction(access, state, state, incoming)
        check(otherTablesProof() == before) { "SYNC_CHALLENGE_ACK_SIDE_EFFECT" }
        return activeInTransaction(access).second
    }

    private suspend fun otherTablesProof(): String {
        val sql = database.openHelper.writableDatabase
        val names = sql.query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }.filterNot { it.startsWith("next_challenge_") }
        val digest = MessageDigest.getInstance("SHA-256")
        fun bytes(value: ByteArray) { digest.update(ByteBuffer.allocate(4).putInt(value.size).array()); digest.update(value) }
        for (table in names) {
            require(table.matches(Regex("[a-zA-Z_][a-zA-Z_0-9]*")))
            bytes(table.toByteArray(Charsets.UTF_8))
            sql.query("SELECT * FROM $table ORDER BY rowid").use { row -> while (row.moveToNext()) {
                currentCoroutineContext().ensureActive()
                for (column in 0 until row.columnCount) {
                    digest.update(row.getType(column).toByte())
                    when (row.getType(column)) {
                        Cursor.FIELD_TYPE_NULL -> Unit
                        Cursor.FIELD_TYPE_INTEGER -> bytes(ByteBuffer.allocate(8).putLong(row.getLong(column)).array())
                        Cursor.FIELD_TYPE_FLOAT -> bytes(ByteBuffer.allocate(8).putDouble(row.getDouble(column)).array())
                        Cursor.FIELD_TYPE_STRING -> bytes(row.getString(column).toByteArray(Charsets.UTF_8))
                        Cursor.FIELD_TYPE_BLOB -> bytes(row.getBlob(column))
                    }
                }
            } }
        }
        return nextRequestHash(digest.digest())
    }

    suspend fun requirePlainInTransaction() {
        check(database.inTransaction())
        require(!dao.hasAny()) { "SYNC_CHALLENGE_PROFILE_REQUIRED" }
        database.openHelper.writableDatabase.query("SELECT challengeContract FROM next_sync_state").use {
            while (it.moveToNext()) require(it.getType(0) == Cursor.FIELD_TYPE_INTEGER && it.getLong(0) == 0L) {
                "SYNC_CHALLENGE_PROFILE_REQUIRED"
            }
        }
    }

    suspend fun requireQuiescentInTransaction() {
        check(database.inTransaction())
        NextRequestSql.requireOutboxEnabled(database.openHelper.writableDatabase)
        NextRequestSql.sources(database.openHelper.writableDatabase, "sync_outbox")
        NextRequestSql.sources(database.openHelper.writableDatabase, "timer_command_outbox")
        require(database.syncOutboxDao().count() == 0 && database.syncOutboxDao().countDeadLetters() == 0 &&
            database.timeLogDao().getPendingTimerCommands(1).isEmpty() && database.timeLogDao().countRejectedTimerCommands() == 0 &&
            database.syncConflictDao().getUnresolved().isEmpty() && database.nextSyncStateDao().rejections().isEmpty()) {
            "SYNC_CHALLENGE_UPLOAD_NOT_CONNECTED"
        }
    }

    /** Audit SQLite before Room coercion, then rebuild every complete immutable chain. */
    suspend fun readInTransaction(access: LocalSyncAccess, cursor: NextSyncStateEntity?): ChallengeMetadata? {
        check(database.inTransaction())
        val sql = database.openHelper.writableDatabase
        for ((table, keys) in listOf("next_challenge_state" to listOf("id"),
            "next_challenge_rounds" to listOf("roundUuid"), "next_challenge_births" to listOf("entityType", "entityUuid"))) {
            sql.query("SELECT ${keys.joinToString(",")} FROM $table ORDER BY rowid").use { raw ->
                while (raw.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val args: Array<Any> = keys.mapIndexed<String, Any> { i, key ->
                        if (key == "id") {
                            require(raw.getType(i) == Cursor.FIELD_TYPE_INTEGER && raw.getLong(i) == 1L)
                            raw.getLong(i)
                        } else { require(raw.getType(i) == Cursor.FIELD_TYPE_STRING); raw.getString(i) }
                    }.toTypedArray()
                    requireNotNull(NextRequestSql.rowHash(sql, table, keys.joinToString(" AND ") { "$it=?" }, args))
                }
            }
        }
        val marker = dao.states().singleOrNull()
        val rounds = dao.rounds()
        val births = dao.births()
        if (marker == null) {
            require(rounds.isEmpty() && births.isEmpty()) { "SYNC_CHALLENGE_CHECKPOINT_MISSING" }
            require(cursor?.challengeContract != 1) { "SYNC_CHALLENGE_CHECKPOINT_MISSING" }
            return null
        }
        requireNotNull(cursor) { "SYNC_CHALLENGE_CURSOR_MISSING" }
        require(cursor.challengeContract == 1) { "SYNC_CHALLENGE_PROFILE_CHANGED" }
        require(marker.accountId == access.session.authentication.userId &&
            marker.serverInstanceId == access.session.serverInstanceId && marker.syncEpoch == access.session.syncEpoch &&
            marker.deviceId == access.deviceId && marker.accountId == cursor.accountId &&
            marker.serverInstanceId == cursor.serverInstanceId && marker.syncEpoch == cursor.syncEpoch &&
            marker.deviceId == cursor.deviceId && marker.generation == cursor.generation && marker.cursor == cursor.cursor &&
            marker.batchHash == cursor.batchHash) { "SYNC_CHALLENGE_CONTEXT_CHANGED" }
        val records = rounds.map { row ->
            require(row.accountId == marker.accountId && row.serverInstanceId == marker.serverInstanceId && row.syncEpoch == marker.syncEpoch)
            require(row.recordHash == syncPayloadHash(row.recordJson))
            json.decodeFromString<ChallengeRoundRecord>(row.recordJson).also {
                require(it.head.roundUuid == row.roundUuid && it.head.activityUuid == row.activityUuid &&
                    it.head.generation.toLong() == row.generation && it.sourceDeviceUuid == row.sourceDeviceUuid &&
                    it.restartOperationUuid == row.operationUuid)
            }
        }
        val byId = records.associateBy { it.head.roundUuid }
        val metadata = canonical(ChallengeMetadata(1, records.groupBy { it.head.activityUuid }.map { (id, history) ->
            ChallengeCheckpoint(rebuildChallengeHistory(id, history), history)
        }, births.map { ChallengeBirth(it.entityType, it.entityUuid, requireNotNull(byId[it.roundUuid]).head) }))
        require(marker.metadataHash == syncPayloadHash(json.encodeToString(metadata))) { "SYNC_CHALLENGE_PROOF_CHANGED" }
        return metadata
    }

    /** Append-only union: omission never erases history, head rollback or birth reassignment rejects. */
    suspend fun mergeInTransaction(access: LocalSyncAccess, current: NextSyncStateEntity?, next: NextSyncStateEntity,
        incoming: ChallengeMetadata) {
        check(database.inTransaction())
        val before = readInTransaction(access, current)
        val histories = before?.checkpoints.orEmpty().associateBy { it.head.activityUuid }.toMutableMap()
        for (checkpoint in incoming.checkpoints) {
            val previous = histories[checkpoint.head.activityUuid]
            if (previous != null) {
                require(checkpoint.head.generation >= previous.head.generation) { "SYNC_CHALLENGE_HEAD_BEHIND" }
                val records = checkpoint.records.associateBy { it.head.generation }
                require(previous.records.all { records[it.head.generation] == it }) { "SYNC_CHALLENGE_HISTORY_CHANGED" }
            }
            histories[checkpoint.head.activityUuid] = checkpoint
        }
        val births = before?.births.orEmpty().associateBy { it.entityType to it.entityUuid }.toMutableMap()
        for (birth in incoming.births) {
            val previous = births[birth.entityType to birth.entityUuid]
            require(previous == null || previous == birth) { "SYNC_CHALLENGE_BIRTH_CHANGED" }
            births[birth.entityType to birth.entityUuid] = birth
        }
        val merged = canonical(ChallengeMetadata(1, histories.values.toList(), births.values.toList()))
        val oldRecords = before?.checkpoints.orEmpty().flatMap { it.records }.associateBy { it.head.roundUuid }
        for (record in merged.checkpoints.flatMap { it.records }) if (record.head.roundUuid !in oldRecords) {
            currentCoroutineContext().ensureActive()
            val body = json.encodeToString(record)
            dao.insert(NextChallengeRoundEntity(record.head.roundUuid, record.head.activityUuid, record.head.generation.toLong(),
                next.accountId, next.serverInstanceId, next.syncEpoch, record.sourceDeviceUuid, record.restartOperationUuid,
                body, syncPayloadHash(body)))
        }
        val oldBirths = before?.births.orEmpty().map { it.entityType to it.entityUuid }.toSet()
        for (birth in merged.births) if (birth.entityType to birth.entityUuid !in oldBirths)
            dao.insert(NextChallengeBirthEntity(birth.entityType, birth.entityUuid, birth.head.roundUuid))
        val marker = NextChallengeStateEntity(next.accountId, next.serverInstanceId, next.syncEpoch, next.deviceId,
            next.generation, next.cursor, next.batchHash, syncPayloadHash(json.encodeToString(merged)))
        if (before == null) check(dao.insert(marker) == 1L) else check(dao.update(marker) == 1)
        // Deferred FK allows the initial marker and ACTIVE cursor to be committed atomically.
        check(readInTransaction(access, next) == merged)
    }

    private fun canonical(metadata: ChallengeMetadata) = ChallengeMetadata(1,
        metadata.checkpoints.sortedBy { it.head.activityUuid }.map { it.copy(records = it.records.sortedBy { row -> row.head.generation }) },
        metadata.births.sortedWith(compareBy({ it.entityType }, { it.entityUuid })))
}
