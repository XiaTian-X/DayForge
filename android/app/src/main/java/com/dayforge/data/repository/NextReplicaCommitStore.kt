package com.dayforge.data.repository

import android.database.Cursor
import androidx.room.withTransaction
import com.dayforge.data.local.*
import com.dayforge.domain.service.AccountSessionCoordinator
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

internal enum class ReplicaCommitFate { OLD, COMMITTED }

/** Dedicated SQL replacement, not a callback/ordinary writer override. The durable pointer and
 * SQL fence remain active throughout and after COMMIT. Exactly the approved 105 business guards
 * are removed/restored inside ONE SQLite transaction; other connections never see absent guards.
 * Actual complete target tables and new checkpoint prove COMMIT, not a UI/DS phase or cursor alone.
 * Rollback restores both original data and DDL. No DDL or files/HTTP escape this transaction.
 */
internal class NextReplicaCommitStore(private val database: HabitDatabase, private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator) {
    suspend fun fate(stage: ReplicaSwitchStage): ReplicaCommitFate {
        check(!database.inTransaction()) { "SYNC_REPLICA_AMBIENT_TRANSACTION" }
        return sessions.exclusive { database.withTransaction { fateInTransaction(stage) } }
    }

    internal suspend fun fateInTransaction(stage: ReplicaSwitchStage, allowClosedOld: Boolean = false): ReplicaCommitFate {
        check(database.inTransaction())
        require(stage.phase in setOf(ReplicaSwitchPhase.COMMITTING, ReplicaSwitchPhase.SQL_COMMITTED))
        val fence = database.nextReplicaFenceDao().state()
        check(fence == stage.fence || allowClosedOld && fence == stage.fence.copy(mode = com.dayforge.data.local.entity.NextReplicaFenceEntity.CLOSED)) {
            "SYNC_REPLICA_FENCE_CHANGED"
        }
        val state = database.nextSyncStateDao().rows().singleOrNull()
        val target = requireNotNull(stage.target)
        require(ReplicaProjection.schemaHash(database) == target.schemaHash) { "SYNC_REPLICA_FENCE_CHANGED" }
        if (fence == stage.fence && state != null && state.accountId == stage.accountId && state.serverInstanceId == stage.serverInstanceId &&
            state.syncEpoch == stage.newEpoch && state.deviceId == target.deviceId && state.cursor == stage.candidateCursor &&
            state.challengeContract == 1 && state.bootstrapHash == stage.candidateHash && state.batchHash == stage.candidateHash &&
            ReplicaProjection.capture(database).hash == target.projectionHash) return ReplicaCommitFate.COMMITTED
        val source = NextReplicaStorageProof(database).readInTransaction()
        require(NextReplicaSourceClosure.storage(source, requireNotNull(fence)) == stage.sourceHash &&
            state != null && state.accountId == stage.accountId && state.serverInstanceId == stage.serverInstanceId && state.syncEpoch == stage.oldEpoch) {
            "SYNC_REPLICA_COMMIT_STATE_INVALID"
        }
        return ReplicaCommitFate.OLD
    }

    suspend fun commit(stage: ReplicaSwitchStage, candidate: ValidatedReplicaCandidate) {
        check(!database.inTransaction()) { "SYNC_REPLICA_AMBIENT_TRANSACTION" }
        require(stage.phase == ReplicaSwitchPhase.COMMITTING && !candidate.hasUnfinishedTimer &&
            candidate.original == stage.original && candidate.newEpoch == stage.newEpoch &&
            candidate.cursor == stage.candidateCursor && candidate.contentHash == stage.candidateHash &&
            candidate.projection.hash == stage.target?.projectionHash) { "SYNC_RECOVERY_PREVIEW_CHANGED" }
        sessions.exclusive { database.withTransaction {
            currentCoroutineContext().ensureActive()
            check(tokens.activeReplicaStage() == stage && tokens.localSyncAccess() == stage.original &&
                tokens.syncAuthenticationSnapshot(stage.original) != null && tokens.registeredInstallationId() == stage.installationId) {
                "SYNC_REPLICA_STAGE_AUTHORITY_CHANGED"
            }
            if (fateInTransaction(stage) == ReplicaCommitFate.COMMITTED) return@withTransaction
            val sql = database.openHelper.writableDatabase
            val before = ReplicaSqlSchema.read(sql)
            val expected = ReplicaProjection.TABLES.flatMap { table -> listOf("insert", "update", "delete").map {
                "replica_fence_${table}_$it"
            } }.toSet()
            val guards = sql.query("SELECT name,sql FROM sqlite_master WHERE type='trigger' AND name LIKE 'replica_fence_%' ORDER BY name").use { c ->
                buildMap { while (c.moveToNext()) {
                    require(c.getType(0) == Cursor.FIELD_TYPE_STRING && c.getType(1) == Cursor.FIELD_TYPE_STRING)
                    if (c.getString(0) in expected) put(c.getString(0), c.getString(1))
                } }
            }
            require(guards.keys == expected) { "SYNC_REPLICA_FENCE_CHANGED" }
            guards.keys.forEach { sql.execSQL("DROP TRIGGER `$it`") }
            sql.execSQL("PRAGMA defer_foreign_keys=ON")
            sql.query("PRAGMA defer_foreign_keys").use { require(it.moveToFirst() && it.getInt(0) == 1) }
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            val tables = candidate.projection.tables
            for (table in ReplicaProjection.TABLES.sorted().filterNot { it == "sync_control" }) {
                currentCoroutineContext().ensureActive(); sql.execSQL("DELETE FROM `$table`")
            }
            for (table in ReplicaProjection.TABLES.sorted().filterNot { it == "sync_control" }) {
                val columns = sql.query("PRAGMA table_info(`$table`)").use { c -> buildSet {
                    while (c.moveToNext()) { require(c.getType(1) == Cursor.FIELD_TYPE_STRING); add(c.getString(1)) }
                } }
                for (raw in tables.getValue(table).jsonArray) {
                    currentCoroutineContext().ensureActive()
                    val row = raw.jsonObject
                    require(row.keys == columns + "__recovery_rowid") { "SYNC_REPLICA_PROJECTION_INVALID" }
                    val names = row.keys.toList()
                    val values = names.map { name -> value(row.getValue(name).jsonObject) }.toTypedArray()
                    val fields = names.joinToString(",") { if (it == "__recovery_rowid") "rowid" else "`$it`" }
                    sql.execSQL("INSERT INTO `$table`($fields) VALUES(${names.joinToString(",") { "?" }})", values)
                }
            }
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
            guards.values.forEach { sql.execSQL(it) }
            require(ReplicaSqlSchema.read(sql) == before) { "SYNC_REPLICA_FENCE_CHANGED" }
            sql.query("PRAGMA foreign_key_check").use { require(!it.moveToFirst()) { "SYNC_RECOVERY_FOREIGN_KEY_INVALID" } }
            check(database.nextReplicaFenceDao().state() == stage.fence)
            require(ReplicaProjection.capture(database).hash == candidate.projection.hash) { "SYNC_REPLICA_PROJECTION_CHANGED" }
            require(fateInTransaction(stage) == ReplicaCommitFate.COMMITTED)
            check(tokens.activeReplicaStage() == stage && tokens.localSyncAccess() == stage.original) { "SYNC_REPLICA_STAGE_AUTHORITY_CHANGED" }
            currentCoroutineContext().ensureActive()
        } }
    }

    private fun value(cell: JsonObject): Any? = when (cell.getValue("sqlite_type").jsonPrimitive.int) {
        Cursor.FIELD_TYPE_NULL -> { require(cell.getValue("value") == JsonNull); null }
        Cursor.FIELD_TYPE_INTEGER -> cell.getValue("value").jsonPrimitive.long
        Cursor.FIELD_TYPE_STRING -> cell.getValue("value").jsonPrimitive.also { require(it.isString) }.content
        Cursor.FIELD_TYPE_FLOAT -> Double.fromBits(cell.getValue("value").jsonPrimitive.content.toLong(16))
        Cursor.FIELD_TYPE_BLOB -> cell.getValue("value").jsonPrimitive.content.let { text ->
            require(text.length % 2 == 0 && text.matches(Regex("[0-9a-f]*")))
            ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
        else -> error("SYNC_REPLICA_PROJECTION_INVALID")
    }
}
