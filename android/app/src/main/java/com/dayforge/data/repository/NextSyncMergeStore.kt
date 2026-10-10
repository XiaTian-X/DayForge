package com.dayforge.data.repository

import android.database.Cursor
import androidx.room.withTransaction
import com.dayforge.data.api.dto.*
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.NextSyncStateEntity
import com.dayforge.domain.service.AccountSessionCoordinator
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Complete cache merge and ACTIVE cursor share one authenticated Room COMMIT. */
internal class NextSyncMergeStore(
    private val database: HabitDatabase,
    private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator,
    private val once: OneTimeAcceptedEventStore,
    private val timers: NextTimerRequestStore
) {
    private val json = Json { encodeDefaults = true }

    suspend fun state(access: LocalSyncAccess, challengeProfile: Boolean = false): NextSyncStateEntity? = sessions.exclusive {
        authorize(access)
        database.withTransaction {
            val state = readState(access)
            val rounds = NextChallengeStore(database)
            if (challengeProfile) {
                val metadata = rounds.readInTransaction(access, state)
                // An old plain cursor cannot skip the mandatory challenge bootstrap.
                if (metadata == null) null else state
            } else { rounds.requirePlainInTransaction(); state }
        }
    }

    suspend fun challengeMetadata(access: LocalSyncAccess): ChallengeMetadata? = sessions.exclusive {
        authorize(access)
        database.withTransaction { NextChallengeStore(database).readInTransaction(access, readState(access)) }
    }

    suspend fun challengeBootstrapExpectation(access: LocalSyncAccess): NextSyncStateEntity? = sessions.exclusive {
        authorize(access)
        database.withTransaction {
            val state = readState(access)
            require(NextChallengeStore(database).readInTransaction(access, state) == null) { "SYNC_CURSOR_CHANGED" }
            state
        }
    }

    suspend fun bootstrap(access: LocalSyncAccess, expected: NextSyncStateEntity?, response: NextSyncBootstrapResponse): NextSyncStateEntity {
        val frozen = json.decodeFromString<NextSyncBootstrapResponse>(json.encodeToString(response))
        return bootstrapPrepared(access, expected, frozen, syncPayloadHash(json.encodeToString(frozen)), null)
    }

    suspend fun bootstrap(access: LocalSyncAccess, expected: NextSyncStateEntity?, response: RoundSyncBootstrapResponse,
        cacheOnly: Boolean = false, emptyBaseline: Boolean = false): NextSyncStateEntity {
        val frozen = json.decodeFromString<RoundSyncBootstrapResponse>(json.encodeToString(response))
        return bootstrapPrepared(access, expected, NextSyncBootstrapResponse(frozen.changes.filter { it.entityType != "challenge_round" },
            frozen.nextCursor, frozen.serverTime, frozen.oneTimeCheckpoints), syncPayloadHash(json.encodeToString(frozen)), frozen.metadata(), cacheOnly, emptyBaseline)
    }

    private suspend fun bootstrapPrepared(access: LocalSyncAccess, expected: NextSyncStateEntity?, frozen: NextSyncBootstrapResponse,
        hash: String, metadata: ChallengeMetadata?, cacheOnly: Boolean = false, emptyBaseline: Boolean = false): NextSyncStateEntity {
        require(frozen.nextCursor >= 0)
        Instant.parse(frozen.serverTime)
        frozen.changes.forEach { change ->
            require(Instant.parse(change.changedAt) == Instant.parse(change.payload.getValue("updated_at").jsonPrimitive.content))
            change.originDeviceId?.let { require(com.dayforge.domain.model.isContractUuid(it)) }
        }
        val next = NextSyncStateEntity(access.session.authentication.userId,
            requireNotNull(access.session.serverInstanceId), requireNotNull(access.session.syncEpoch),
            requireNotNull(access.deviceId), nextGeneration(expected), frozen.nextCursor, hash, hash,
            challengeContract = if (metadata == null) 0 else 1)
        return sessions.exclusive {
            authorize(access)
            database.withTransaction {
                val current = readState(access)
                val rounds = NextChallengeStore(database)
                if (emptyBaseline) {
                    check(expected == null && current == null) { "SYNC_CURSOR_CHANGED" }
                    NextProtocolAdmission.requireRoundsOrEmpty(database, access)
                }
                if (cacheOnly) rounds.requireQuiescentInTransaction()
                if (metadata == null) rounds.requirePlainInTransaction() else rounds.readInTransaction(access, current)
                if (current == next) return@withTransaction current
                require(current == expected) { "SYNC_CURSOR_CHANGED" }
                val recovery = database.nextRecoveryDao().state()
                recovery?.let {
                    require(it.accountId == next.accountId && it.serverInstanceId == next.serverInstanceId &&
                        it.syncEpoch == next.syncEpoch && it.deviceId == next.deviceId) { "SYNC_RECOVERY_CONTEXT_CHANGED" }
                }
                require(frozen.nextCursor >= maxOf(expected?.cursor ?: 0,
                    recovery?.candidateCursor ?: recovery?.minimumCursor ?: 0)) { "SYNC_SNAPSHOT_BEHIND" }
                requireInitializedCache()
                auditShadows()
                val sources = pendingSources()
                pruneCache(frozen)
                once.restoreAcceptedDataInTransaction(context(access), frozen)
                check(pendingSources() == sources)
                if (metadata != null) mergeRounds(access, current, next, metadata)
                check(pendingSources() == sources)
                if (cacheOnly) rounds.requireQuiescentInTransaction()
                commitState(access, current, next)
            }
        }
    }

    suspend fun page(access: LocalSyncAccess, expected: NextSyncStateEntity, response: NextSyncPullResponse): NextSyncStateEntity {
        val frozen = json.decodeFromString<NextSyncPullResponse>(json.encodeToString(response))
        return pagePrepared(access, expected, frozen, syncPayloadHash(json.encodeToString(frozen)), null)
    }

    suspend fun page(access: LocalSyncAccess, expected: NextSyncStateEntity, response: RoundSyncPullResponse,
        cacheOnly: Boolean = false): NextSyncStateEntity {
        val frozen = json.decodeFromString<RoundSyncPullResponse>(json.encodeToString(response))
        return pagePrepared(access, expected, NextSyncPullResponse(frozen.changes, frozen.nextCursor, frozen.hasMore, frozen.serverTime),
            syncPayloadHash(json.encodeToString(frozen)), frozen.metadata(), cacheOnly)
    }

    private suspend fun pagePrepared(access: LocalSyncAccess, expected: NextSyncStateEntity, frozen: NextSyncPullResponse,
        hash: String, metadata: ChallengeMetadata?, cacheOnly: Boolean = false): NextSyncStateEntity {
        require(frozen.nextCursor >= expected.cursor && frozen.changes.size <= 1000)
        Instant.parse(frozen.serverTime)
        var previous = expected.cursor
        frozen.changes.forEach { change ->
            require(change.sequence > previous && change.sequence <= frozen.nextCursor)
            Instant.parse(change.changedAt)
            previous = change.sequence
        }
        require(!frozen.hasMore || frozen.changes.isNotEmpty() && previous == frozen.nextCursor)
        val next = expected.copy(generation = nextGeneration(expected), cursor = frozen.nextCursor,
            batchHash = hash, challengeContract = if (metadata == null) 0 else 1)
        return sessions.exclusive {
            authorize(access)
            database.withTransaction {
                val current = requireNotNull(readState(access))
                val rounds = NextChallengeStore(database)
                if (cacheOnly) rounds.requireQuiescentInTransaction()
                if (metadata == null) rounds.requirePlainInTransaction() else requireNotNull(rounds.readInTransaction(access, current))
                if (current == next) return@withTransaction current
                require(current == expected) { "SYNC_CURSOR_CHANGED" }
                auditShadows()
                val sources = pendingSources()
                if (metadata != null) for (change in frozen.changes)
                    NextRestartStore(database).capturePlanFrame(access, change, metadata)
                val restartPlans = cacheProof(listOf("next_restart_plan_proofs"))
                for (change in frozen.changes) {
                    currentCoroutineContext().ensureActive()
                    // Immutable round records are validated by the full sidecar, never structure-merged.
                    if (change.entityType == "challenge_round") { require(metadata != null); continue }
                    require(change.revision > 0 && change.payload["public_id"] == JsonPrimitive(change.entityUuid))
                    val updated = change.payload.getValue("updated_at").let { require(it is JsonPrimitive && it.isString); Instant.parse(it.content) }
                    require(updated == Instant.parse(change.changedAt))
                    if (change.operation == "delete") NextRemoteDeletionStore(database).applyInTransaction(change)
                    else {
                        require(change.operation == "upsert")
                        if (change.entityType !in setOf("plan_node", "metric") && missingParentFact(change)) continue
                        when (change.entityType) {
                            "plan_node", "metric" -> NextStructureStore(database).restoreInTransaction(listOf(change), incremental = true)
                            "activity_event" -> if (change.payload["one_time"].let { it != null && it != JsonNull })
                                once.applyInTransaction(context(access), listOf(change), contiguous = true)
                            else restoreFacts(access, change)
                            "metric_observation", "activity_metric_link" -> restoreFacts(access, change)
                            else -> error("Unsupported sync entity")
                        }
                    }
                }
                check(pendingSources() == sources)
                if (metadata != null) mergeRounds(access, current, next, metadata)
                check(pendingSources() == sources)
                check(cacheProof(listOf("next_restart_plan_proofs")) == restartPlans)
                if (cacheOnly) rounds.requireQuiescentInTransaction()
                if (metadata == null && frozen.changes.isEmpty() && frozen.nextCursor == expected.cursor) current
                else commitState(access, current, next)
            }
        }
    }

    private suspend fun restoreFacts(access: LocalSyncAccess, change: SyncV2Change) {
        NextCommonFactStore(database).restoreInTransaction(listOf(change), requireNotNull(access.deviceId),
            acceptedTimerCompletion = { timers.completionProofInTransaction(access, it) }, incremental = true)
    }

    /** Historical log pages may precede a parent tombstone already ACKed locally. */
    private suspend fun missingParentFact(change: SyncV2Change): Boolean {
        val body = change.payload
        val parents = when (change.entityType) {
            "activity_event" -> listOf("plan_node" to body.getValue("activity_uuid").jsonPrimitive.content)
            "metric_observation" -> listOf("metric" to body.getValue("metric_uuid").jsonPrimitive.content)
            "activity_metric_link" -> listOf("plan_node" to body.getValue("activity_uuid").jsonPrimitive.content,
                "metric" to body.getValue("metric_uuid").jsonPrimitive.content)
            else -> error("Unsupported sync entity")
        }
        val missing = parents.filter { (type, id) -> if (type == "plan_node") database.habitDao().getHabitByUuid(id) == null
            else database.metricDao().getMetricByUuid(id) == null }
        if (missing.isEmpty()) return false
        for ((type, id) in missing) {
            val shadow = requireNotNull(database.syncOutboxDao().getState(type, id)) { "SYNC_PARENT_MISSING" }
            require(shadow.deleted && shadow.payloadJson != null && shadow.payloadHash == syncPayloadHash(shadow.payloadJson))
            NextStructureMapper.validateTombstone(Json.parseToJsonElement(shadow.payloadJson).jsonObject, type, id, shadow.revision)
        }
        when {
            change.entityType == "activity_metric_link" -> NextCommonFactMapper.validateLinkSnapshot(change)
            body["one_time"].let { it != null && it != JsonNull } -> OneTimeServerFact(body, change.revision)
            body["event_type"] == JsonPrimitive("duration_session") -> NextCommonFactMapper.validateDurationSnapshot(change)
            else -> NextCommonFactMapper.validateOrdinaryFactSnapshot(change)
        }
        val dao = database.syncOutboxDao()
        require((dao.getAll() + dao.getDeadLetters()).none { it.wireEntityUuid == change.entityUuid }) { "SYNC_LOCAL_WORK_REQUIRES_RESOLUTION" }
        val old = dao.getState(change.entityType, change.entityUuid)
        if (old != null) {
            require(old.payloadJson != null && old.payloadHash == syncPayloadHash(old.payloadJson))
            val previous = Json.parseToJsonElement(old.payloadJson).jsonObject
            if (change.entityType == "activity_metric_link" && old.revision > change.revision) return true
            require(!old.deleted && (change.entityType == "activity_metric_link" || old.revision == change.revision && previous == body))
            require(previous["created_at"] == body["created_at"])
            if (old.revision == change.revision) require(previous == body)
        }
        require(database.completionDao().getCompletionByUuid(change.entityUuid) == null &&
            database.timeLogDao().getTimeLogByUuid(change.entityUuid) == null &&
            database.metricLogDao().getLogByUuid(change.entityUuid) == null &&
            database.habitMetricLinkDao().getLinkByUuid(change.entityUuid) == null)
        val shadow = com.dayforge.data.local.entity.SyncEntityStateEntity(change.entityType, change.entityUuid,
            change.revision, payloadJson = body.toString(), payloadHash = syncPayloadHash(body.toString()))
        dao.upsertState(shadow); check(dao.getState(change.entityType, change.entityUuid) == shadow)
        return true
    }

    /** Audit singleton/type/length before Room coercion; bind every read to current account/replica. */
    private suspend fun readState(access: LocalSyncAccess): NextSyncStateEntity? {
        check(database.inTransaction())
        database.openHelper.writableDatabase.query("SELECT id FROM next_sync_state ORDER BY id").use {
            if (it.moveToFirst()) require(it.getType(0) == Cursor.FIELD_TYPE_INTEGER && it.getLong(0) == 1L && !it.moveToNext())
        }
        val raw = NextRequestSql.rowHash(database.openHelper.writableDatabase, "next_sync_state", "1=1", emptyArray())
        val rows = database.nextSyncStateDao().rows()
        check(rows.size <= 1 && (raw == null) == rows.isEmpty())
        return rows.singleOrNull()?.also {
            require(it.accountId == access.session.authentication.userId && it.serverInstanceId == access.session.serverInstanceId &&
                it.syncEpoch == access.session.syncEpoch && it.deviceId == access.deviceId) { "SYNC_CURSOR_CONTEXT_CHANGED" }
        }
    }

    private suspend fun commitState(access: LocalSyncAccess, current: NextSyncStateEntity?, next: NextSyncStateEntity): NextSyncStateEntity {
        val before = cacheProof()
        val dao = database.nextSyncStateDao()
        if (current == null) check(dao.insert(next) == 1L) else check(dao.update(next) == 1)
        check(readState(access) == next && cacheProof() == before)
        NextChallengeStore(database).readInTransaction(access, next)
        NextRequestSql.requireOutboxEnabled(database.openHelper.writableDatabase)
        authorize(access)
        currentCoroutineContext().ensureActive()
        return next // Caller receives it only after the outer Room COMMIT succeeds.
    }

    private fun nextGeneration(current: NextSyncStateEntity?): Long {
        require(current?.generation != Long.MAX_VALUE) { "SYNC_CURSOR_EXHAUSTED" }
        return (current?.generation ?: 0L) + 1
    }

    private suspend fun requireInitializedCache() {
        require(database.habitDao().getAllHabitsOnce().all { it.appearance != null && it.planMetadata != null &&
            (it.habitType == com.dayforge.data.model.HabitType.GOAL || it.completionPolicy != null) } &&
            database.metricDao().getAllMetricsOnce().all { it.appearance != null }) { "SYNC_OLD_CACHE_REQUIRES_ACTIVATION" }
    }

    /** Full snapshot absence prunes only clean cache, never queues, receipts or follow-up intents. */
    private suspend fun pruneCache(snapshot: NextSyncBootstrapResponse) {
        val incoming = snapshot.changes.groupBy { it.entityType }.mapValues { (_, changes) -> changes.map { it.entityUuid }.toSet() }
        val pending = database.syncOutboxDao().getAll() + database.syncOutboxDao().getDeadLetters()
        val conflicts = database.syncConflictDao().getUnresolved()
        val habits = database.habitDao().getAllHabitsOnce()
        val metrics = database.metricDao().getAllMetricsOnce()
        val commands = database.timeLogDao().getPendingTimerCommands(Int.MAX_VALUE) + database.timeLogDao().getRejectedTimerCommands()
        val prompts = database.completionFollowUpDao().pendingPrompts()
        val protectedPlans = pending.filter { it.recordType in setOf("habit", RESTART_RECORD) }.map { it.entityUuid }.toMutableSet()
        protectedPlans += pending.mapNotNull { it.referenceUuid }.filter { ref -> habits.any { it.uuid == ref } }
        protectedPlans += conflicts.filter { it.entityType == "plan_node" }.map { it.localEntityUuid }
        protectedPlans += conflicts.mapNotNull { it.referenceUuid }.filter { ref -> habits.any { it.uuid == ref } }
        protectedPlans += prompts.map { it.activityUuid }
        for (command in commands) command.activityUuid?.let { protectedPlans += it }
        for (habit in habits) if (database.timeLogDao().getAllTimeLogsForHabit(habit.id).any { it.endTime == null ||
            commands.any { command -> command.sessionUuid == it.uuid } }) protectedPlans += habit.uuid
        protectedPlans += incoming["plan_node"].orEmpty()
        // A retained local child must not be orphaned by snapshot reconciliation.
        var changed: Boolean
        do {
            changed = false
            for (habit in habits) if (habit.uuid in protectedPlans && habit.parentHabitId != null)
                changed = protectedPlans.add(habit.parentHabitId) || changed
        } while (changed)
        val protectedMetrics = pending.filter { it.recordType == "metric" }.map { it.entityUuid }.toMutableSet()
        protectedMetrics += pending.mapNotNull { it.referenceUuid }.filter { ref -> metrics.any { it.uuid == ref } }
        protectedMetrics += conflicts.filter { it.entityType == "metric" }.map { it.localEntityUuid }
        protectedMetrics += conflicts.mapNotNull { it.referenceUuid }.filter { ref -> metrics.any { it.uuid == ref } }
        for (link in database.habitMetricLinkDao().getAllLinksOnce()) {
            val habit = habits.single { it.id == link.habitId }
            val metric = metrics.single { it.id == link.metricId }
            if (prompts.any { it.activityUuid == habit.uuid } || pending.any { it.recordType == "link" && it.entityUuid == link.uuid }) {
                protectedPlans += habit.uuid; protectedMetrics += metric.uuid
            }
        }
        // Link-held parents can themselves have a parent. Close the retained structure again.
        do {
            changed = false
            for (habit in habits) if (habit.uuid in protectedPlans && habit.parentHabitId != null)
                changed = protectedPlans.add(habit.parentHabitId) || changed
        } while (changed)
        protectedMetrics += incoming["metric"].orEmpty()
        val sql = database.openHelper.writableDatabase
        NextRequestSql.requireOutboxEnabled(sql)
        sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
        for (habit in habits.filter { it.uuid !in protectedPlans }.sortedBy { it.habitType == com.dayforge.data.model.HabitType.GOAL }) {
            require(database.syncOutboxDao().getState("plan_node", habit.uuid) != null) { "SYNC_UNPROVEN_LOCAL_CACHE" }
            database.habitDao().delete(habit)
            check(database.habitDao().getHabitByUuid(habit.uuid) == null)
        }
        for (metric in metrics.filter { it.uuid !in protectedMetrics }) {
            require(database.syncOutboxDao().getState("metric", metric.uuid) != null) { "SYNC_UNPROVEN_LOCAL_CACHE" }
            database.metricDao().delete(metric)
            check(database.metricDao().getMetricByUuid(metric.uuid) == null)
        }
        for (fact in database.completionDao().getAllCompletionsOnce()) if (fact.uuid !in incoming["activity_event"].orEmpty() &&
            pending.none { it.entityUuid == fact.uuid || it.wireEntityUuid == fact.uuid } && prompts.none { it.eventUuid == fact.uuid }) {
            require(database.syncOutboxDao().getState("activity_event", fact.uuid) != null) { "SYNC_UNPROVEN_LOCAL_FACT" }
            database.completionDao().delete(fact)
            check(database.completionDao().getCompletionByUuid(fact.uuid) == null)
        }
        for (habit in database.habitDao().getAllHabitsOnce()) for (timer in database.timeLogDao().getAllTimeLogsForHabit(habit.id)) {
            if (timer.endTime != null && timer.uuid !in incoming["activity_event"].orEmpty() &&
                commands.none { it.sessionUuid == timer.uuid } && pending.none { it.entityUuid == timer.uuid || it.wireEntityUuid == timer.uuid }) {
                require(database.syncOutboxDao().getState("activity_event", timer.uuid) != null) { "SYNC_UNPROVEN_LOCAL_TIMER" }
                database.timeLogDao().deleteTimerSegments(timer.uuid)
                database.timeLogDao().deleteDayAllocations(timer.uuid)
                check(database.timeLogDao().delete(timer) == 1)
            }
        }
        // Clean observations/links can disappear independently of their surviving parents.
        for (metric in database.metricDao().getAllMetricsOnce()) for (log in database.metricLogDao().getAllLogsForMetric(metric.id)) {
            if (log.uuid !in incoming["metric_observation"].orEmpty() && pending.none { it.recordType == "metric_log" && it.entityUuid == log.uuid } &&
                conflicts.none { it.localEntityUuid == log.uuid }) {
                require(database.syncOutboxDao().getState("metric_observation", log.uuid) != null) { "SYNC_UNPROVEN_LOCAL_FACT" }
                database.metricLogDao().delete(log)
                check(database.metricLogDao().getLogByUuid(log.uuid) == null)
            }
        }
        for (link in database.habitMetricLinkDao().getAllLinksOnce()) if (link.uuid !in incoming["activity_metric_link"].orEmpty() &&
            pending.none { it.recordType == "link" && it.entityUuid == link.uuid } && conflicts.none { it.localEntityUuid == link.uuid } && prompts.none {
                it.activityUuid == database.habitDao().getHabitById(link.habitId)?.uuid }) {
            require(database.syncOutboxDao().getState("activity_metric_link", link.uuid) != null) { "SYNC_UNPROVEN_LOCAL_LINK" }
            database.habitMetricLinkDao().delete(link)
            check(database.habitMetricLinkDao().getLinkByUuid(link.uuid) == null)
        }
        sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
    }

    private suspend fun pendingSources() = listOf("sync_outbox", "timer_command_outbox").associateWith {
        NextRequestSql.sources(database.openHelper.writableDatabase, it)
    } to cacheProof(listOf("next_request_origins", "next_transmissions", "next_acceptances",
        "next_structural_dependencies", "next_structural_supersessions", "next_restart_materializations"))

    private suspend fun mergeRounds(access: LocalSyncAccess, current: NextSyncStateEntity?, next: NextSyncStateEntity,
        metadata: ChallengeMetadata) {
        val otherTables = cacheTables.filterNot { it.startsWith("next_challenge_") }
        val before = cacheProof(otherTables)
        NextChallengeStore(database).mergeInTransaction(access, current, next, metadata)
        check(cacheProof(otherTables) == before) // Round insertion triggers cannot mutate business or original work.
    }

    /** Room must not coerce a damaged revision/deleted flag or BLOB body into trusted authority. */
    private suspend fun auditShadows() {
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
            }
        }
    }

    /** Detect late cursor triggers altering data or durable work after their merge validation. */
    private val cacheTables = listOf("habits", "metrics", "completions", "count_days", "timelogs", "timer_segments", "timelog_day_allocations",
        "metric_logs", "habit_metric_links", "sync_entity_state", "sync_outbox", "timer_command_outbox", "sync_conflicts",
        "local_fact_submissions", "completion_metric_prompts", "one_time_transmissions", "next_request_origins",
        "next_transmissions", "next_acceptances", "next_structural_dependencies", "next_structural_supersessions", "next_recovery_state", "next_rejections",
        "next_challenge_state", "next_challenge_rounds", "next_challenge_births", "next_restart_materializations", "next_restart_plan_proofs")

    private suspend fun cacheProof(tables: List<String> = cacheTables): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun bytes(value: ByteArray) { digest.update(ByteBuffer.allocate(4).putInt(value.size).array()); digest.update(value) }
        for (table in tables) {
            bytes(table.toByteArray(Charsets.UTF_8))
            database.openHelper.writableDatabase.query("SELECT * FROM $table ORDER BY rowid").use { row ->
                while (row.moveToNext()) {
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
                }
            }
        }
        return nextRequestHash(digest.digest())
    }

    private fun context(access: LocalSyncAccess) = OneTimeSyncContext(access.session, requireNotNull(access.deviceId))
    private suspend fun authorize(access: LocalSyncAccess) {
        if (tokens.syncAuthenticationSnapshot(access) == null) rejectNextRequest(NextRequestException.Reason.STALE_ACCESS)
        require(access.deviceId != null && "sync.read" in access.capabilities)
    }
}
