package com.dayforge.data.repository

import android.database.Cursor
import androidx.room.withTransaction
import com.dayforge.data.local.*
import com.dayforge.domain.service.AccountSessionCoordinator
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

internal enum class ReplicaCommitFate { OLD, COMMITTED }
internal enum class ReplicaCatchupFate { PREVIOUS, COMMITTED }
internal enum class ReplicaReleaseFate { FROZEN, CLOSED }

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
        require(stage.phase in setOf(ReplicaSwitchPhase.COMMITTING, ReplicaSwitchPhase.SQL_COMMITTED,
            ReplicaSwitchPhase.DESTINATION_BOUND, ReplicaSwitchPhase.CATCHUP_BOUND))
        val fence = ReplicaPresentationRepository(database, tokens, sessions).readFenceInTransaction()
        check(fence == stage.fence || allowClosedOld && fence == stage.fence.copy(mode = com.dayforge.data.local.entity.NextReplicaFenceEntity.CLOSED)) {
            "SYNC_REPLICA_FENCE_CHANGED"
        }
        val snapshot = stage.publishedSnapshot
        require(ReplicaProjection.schemaHash(database) == snapshot.target.schemaHash) { "SYNC_REPLICA_FENCE_CHANGED" }
        if (fence == stage.fence && matches(stage, snapshot)) return ReplicaCommitFate.COMMITTED
        require(stage.phase != ReplicaSwitchPhase.CATCHUP_BOUND) { "SYNC_REPLICA_COMMIT_STATE_INVALID" }
        val state = database.nextSyncStateDao().rows().singleOrNull()
        val source = NextReplicaStorageProof(database).readInTransaction()
        require(NextReplicaSourceClosure.storage(source, requireNotNull(fence)) == stage.sourceHash &&
            state != null && state.accountId == stage.accountId && state.serverInstanceId == stage.serverInstanceId && state.syncEpoch == stage.oldEpoch) {
            "SYNC_REPLICA_COMMIT_STATE_INVALID"
        }
        return ReplicaCommitFate.OLD
    }

    /** Both sides are complete typed SQL/checkpoint proofs. An intent or DS cursor is not fate. */
    internal suspend fun catchupFateInTransaction(stage: ReplicaSwitchStage): ReplicaCatchupFate {
        check(database.inTransaction())
        require(stage.phase == ReplicaSwitchPhase.CATCHUP_COMMITTING)
        val control = ReplicaPresentationRepository(database, tokens, sessions).inspectWhileAccountLocked()
        check(control.problem == ReplicaPresentationProblem.RECOVERY_PENDING && control.phase == stage.phase &&
            tokens.activeReplicaStage() == stage && database.nextReplicaFenceDao().state() == stage.fence) {
            "SYNC_REPLICA_STAGE_CHANGED"
        }
        val intent = requireNotNull(stage.catchup)
        require(ReplicaProjection.schemaHash(database) == intent.previous.target.schemaHash) { "SYNC_REPLICA_FENCE_CHANGED" }
        return when {
            matches(stage, intent.next) -> ReplicaCatchupFate.COMMITTED
            matches(stage, intent.previous) -> ReplicaCatchupFate.PREVIOUS
            else -> error("SYNC_REPLICA_CATCHUP_STATE_INVALID")
        }
    }

    suspend fun catchupFate(stage: ReplicaSwitchStage): ReplicaCatchupFate {
        check(!database.inTransaction()) { "SYNC_REPLICA_AMBIENT_TRANSACTION" }
        return sessions.exclusive { database.withTransaction { catchupFateInTransaction(stage) } }
    }

    /** Closing SQL alone never opens consumers: RELEASING and the pointer remain durable. */
    internal suspend fun releaseFateInTransaction(stage: ReplicaSwitchStage): ReplicaReleaseFate {
        check(database.inTransaction())
        require(stage.phase == ReplicaSwitchPhase.RELEASING)
        val actual = ReplicaPresentationRepository(database, tokens, sessions).readFenceInTransaction()
        check(tokens.activeReplicaStage() == stage &&
            (actual == stage.fence || actual == stage.fence.copy(mode = com.dayforge.data.local.entity.NextReplicaFenceEntity.CLOSED)) &&
            ReplicaProjection.schemaHash(database) == stage.publishedSnapshot.target.schemaHash &&
            matches(stage, stage.publishedSnapshot)) { "SYNC_REPLICA_RELEASE_STATE_INVALID" }
        tokens.replicaDestinationAccess(stage)
        return if (actual == stage.fence) ReplicaReleaseFate.FROZEN else ReplicaReleaseFate.CLOSED
    }

    suspend fun releaseFate(stage: ReplicaSwitchStage): ReplicaReleaseFate {
        check(!database.inTransaction()) { "SYNC_REPLICA_AMBIENT_TRANSACTION" }
        return sessions.exclusive { database.withTransaction { releaseFateInTransaction(stage) } }
    }

    /** A newer server snapshot after interrupted close still advances forward under the pointer.
     * Re-freezing protects the next replacement, never restores the old replica or cancels it.
     */
    suspend fun refreezeForCatchup(stage: ReplicaSwitchStage) {
        check(!database.inTransaction()) { "SYNC_REPLICA_AMBIENT_TRANSACTION" }
        sessions.exclusive { database.withTransaction {
            if (releaseFateInTransaction(stage) == ReplicaReleaseFate.CLOSED)
                check(database.nextReplicaFenceDao().update(stage.fence) == 1)
            check(releaseFateInTransaction(stage) == ReplicaReleaseFate.FROZEN)
        } }
    }

    suspend fun closeForRelease(stage: ReplicaSwitchStage) {
        check(!database.inTransaction()) { "SYNC_REPLICA_AMBIENT_TRANSACTION" }
        sessions.exclusive { database.withTransaction {
            if (releaseFateInTransaction(stage) == ReplicaReleaseFate.FROZEN)
                check(database.nextReplicaFenceDao().update(stage.fence.copy(mode =
                    com.dayforge.data.local.entity.NextReplicaFenceEntity.CLOSED)) == 1)
            check(releaseFateInTransaction(stage) == ReplicaReleaseFate.CLOSED)
        } }
    }

    private suspend fun matches(stage: ReplicaSwitchStage, snapshot: ReplicaStageSnapshot): Boolean {
        check(database.inTransaction())
        val state = database.nextSyncStateDao().rows().singleOrNull() ?: return false
        return state.accountId == stage.accountId && state.serverInstanceId == stage.serverInstanceId &&
            state.syncEpoch == stage.newEpoch && state.deviceId == snapshot.target.deviceId && state.cursor == snapshot.cursor &&
            state.challengeContract == 1 && state.bootstrapHash == snapshot.contentHash && state.batchHash == snapshot.contentHash &&
            ReplicaProjection.capture(database).hash == snapshot.target.projectionHash
    }

    suspend fun catchup(stage: ReplicaSwitchStage, candidate: ValidatedReplicaCandidate) {
        check(!database.inTransaction()) { "SYNC_REPLICA_AMBIENT_TRANSACTION" }
        require(stage.phase == ReplicaSwitchPhase.CATCHUP_COMMITTING && !candidate.hasUnfinishedTimer)
        val next = requireNotNull(stage.catchup).next
        require(candidate.newEpoch == stage.newEpoch && candidate.cursor == next.cursor &&
            candidate.contentHash == next.contentHash && candidate.projection.hash == next.target.projectionHash) {
            "SYNC_RECOVERY_PREVIEW_CHANGED"
        }
        sessions.exclusive { database.withTransaction {
            val actual = tokens.replicaDestinationAccess(stage)
            check(candidate.original == actual && tokens.syncAuthenticationSnapshot(actual) != null &&
                actual.session.authentication.generation == stage.catchup.authenticationGeneration) { "SYNC_REPLICA_STAGE_AUTHORITY_CHANGED" }
            if (catchupFateInTransaction(stage) == ReplicaCatchupFate.COMMITTED) return@withTransaction
            replaceProjection(candidate.projection)
            require(catchupFateInTransaction(stage) == ReplicaCatchupFate.COMMITTED)
            check(tokens.replicaDestinationAccess(stage) == actual && tokens.syncAuthenticationSnapshot(actual) != null) {
                "SYNC_REPLICA_STAGE_AUTHORITY_CHANGED"
            }
            currentCoroutineContext().ensureActive()
        } }
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
            replaceProjection(candidate.projection)
            check(database.nextReplicaFenceDao().state() == stage.fence)
            require(fateInTransaction(stage) == ReplicaCommitFate.COMMITTED)
            check(tokens.activeReplicaStage() == stage && tokens.localSyncAccess() == stage.original) { "SYNC_REPLICA_STAGE_AUTHORITY_CHANGED" }
            currentCoroutineContext().ensureActive()
        } }
    }

    /** One transaction owns exact guard removal, all 35 typed tables and DDL/FK restoration. */
    private suspend fun replaceProjection(projection: ReplicaProjection) {
        check(database.inTransaction())
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
        val tables = projection.tables
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
        require(ReplicaProjection.capture(database).hash == projection.hash) { "SYNC_REPLICA_PROJECTION_CHANGED" }
        currentCoroutineContext().ensureActive()
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
