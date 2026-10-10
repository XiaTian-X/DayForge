package com.dayforge.data.repository

import android.database.Cursor
import androidx.room.withTransaction
import com.dayforge.data.api.decodeFrozenSyncRequest
import com.dayforge.data.api.dto.*
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.NextRequestOriginEntity
import com.dayforge.domain.model.OneTimeIntent
import com.dayforge.domain.model.isContractUuid
import com.dayforge.domain.service.AccountSessionCoordinator
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Read-only internal preview. No confirmation authority, network, persistent cache or UI action. */
internal class NextRecoveryDependencyReader(private val database: HabitDatabase, private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator, private val core: NextCoreRequestStore,
    private val timers: NextTimerRequestStore, private val once: NextOneTimeRequestStore,
    private val restart: NextChallengeRestartRepository) {

    suspend fun preview(root: NextRecoveryKey): NextRecoveryDependencyPreview = sessions.exclusive {
        val authority = requireNotNull(tokens.localCoreWriteAccess())
        val access = requireNotNull(tokens.localSyncAccess()).let { it.copy(capabilities = it.capabilities.toSet()) }
        require(authority.session == access.session && tokens.syncAuthenticationSnapshot(access) != null)
        database.withTransaction {
            NextProtocolAdmission.requireRoundsOrEmpty(database, access)
            val checkpoint = NextChallengeStore(database).activeInTransaction(access)
            val result = readInTransaction(access, root)
            check(NextChallengeStore(database).activeInTransaction(access) == checkpoint &&
                tokens.localCoreWriteAccess() == authority && tokens.localSyncAccess() == access &&
                tokens.syncAuthenticationSnapshot(access) != null) { "SYNC_RECOVERY_ACCESS_CHANGED" }
            result
        }
    }

    /** Transaction participant; never reacquire the non-reentrant account mutex. */
    internal suspend fun readInTransaction(access: LocalSyncAccess, root: NextRecoveryKey): NextRecoveryDependencyPreview {
        check(database.inTransaction())
        NextProtocolAdmission.requireRoundsOrEmpty(database, access)
        return Index(access).read(root)
    }

    /** Compact metadata only. Full payloads are validated one at a time, not retained in the graph. */
    private data class Entry(val key: NextRecoveryKey, val type: String, val uuid: String,
        val action: String, val order: Long, val references: List<Pair<String, String>>,
        val revert: String? = null, val onceIntent: OneTimeIntent? = null,
        val timer: NextRecoveryTimerFrontier? = null, val count: NextRecoveryCountFrontier? = null)

    private inner class Index(private val access: LocalSyncAccess) {
        private val sql get() = database.openHelper.writableDatabase
        private val nodes = linkedMapOf<NextRecoveryKey, NextRecoveryNode>()
        private val entries = linkedMapOf<NextRecoveryKey, Entry>()
        private val aliases = linkedMapOf<NextRecoveryKey, NextRecoveryKey>()
        private val edges = linkedSetOf<NextRecoveryDependency>()
        private val groups = mutableListOf<NextRecoveryDependencyGroup>()
        private val pending = linkedSetOf<NextRecoveryKey>()
        private val required = ArrayDeque<NextRecoveryKey>()
        private val structuralProofs = mutableMapOf<NextRecoveryKey, NextRecoveryStructuralReference>()
        private val acceptedTimers = mutableMapOf<NextRecoveryKey, NextRecoveryTimerAcceptedProof>()
        private val captured = NextRecoveryCapturedFrontierStore(database) { id, expected ->
            require(expected == access)
            val key = NextRecoveryKey(NEXT_OPERATION, id); ensure(key)
            requireNotNull(structuralProofs[actual(key)])
        }
        private lateinit var contextHash: String
        private lateinit var retired: Set<NextRecoveryKey>
        private var importEntry: NextConfigImportStore.NetworkEntry? = null

        private fun digest(vararg parts: String) = nextRequestHash(parts.joinToString(";").toByteArray(Charsets.UTF_8))
        private suspend fun hash(table: String, key: NextRecoveryKey) =
            NextRequestSql.rowHash(sql, table, "kind=? AND requestId=?", arrayOf(key.kind, key.requestId))
        private fun actual(key: NextRecoveryKey) = aliases[key] ?: key

        suspend fun read(root: NextRecoveryKey): NextRecoveryDependencyPreview {
            check(database.inTransaction())
            NextRequestSql.requireOutboxEnabled(sql)
            // Only fully verified historical groups may leave the pending graph. An orphan or
            // damaged audit still rejects the entire preview, and is never an accepted ancestor.
            retired = NextRecoveryAuditStore(database).validatedMembers(access)
            if (root in retired) rejectNextRequest(NextRequestException.Reason.LOCALLY_ABANDONED)
            require(database.syncConflictDao().getUnresolved().isEmpty())
            val operations = sources(NEXT_OPERATION)
            val commands = sources(NEXT_TIMER)
            require(operations.size + commands.size <= 10_000)
            pending += operations.map { it.second }; pending += commands.map { it.second }
            require(pending.none { it in retired }) { "SYNC_RECOVERY_RETIRED_SOURCE_PRESENT" }
            require(root in pending)
            contextHash = digest(requireNotNull(NextRequestSql.rowHash(sql, "next_sync_state", "1=1", emptyArray())),
                requireNotNull(NextRequestSql.rowHash(sql, "next_challenge_state", "1=1", emptyArray())),
                access.session.authentication.userId, access.session.authentication.generation,
                requireNotNull(access.session.serverInstanceId), requireNotNull(access.session.syncEpoch),
                requireNotNull(access.capabilityRevision).toString(), JsonArray(access.capabilities.sorted().map(::JsonPrimitive)).toString(),
                requireNotNull(access.deviceId))
            // Auditing a latest ancestry once populates its older links; no per-node full-chain scan.
            for ((_, key) in operations.asReversed() + commands) ensure(key)
            drain()
            joinAcceptedReferences()
            importEntry = NextConfigImportStore(database).activeNetwork()
            for (step in importEntry?.plan?.steps.orEmpty()) ensure(NextRecoveryKey(NEXT_OPERATION, step.operationId))
            drain()
            joinDeletedLinkEndpoints()
            joinHeadsAndReferences()
            joinBirthWatermarks()
            joinTimerSequences()
            joinRevertsAndOnce()
            joinImport()
            drain()
            // An orphan rejection is corruption, not a smaller apparently healthy closure.
            for (key in keys("SELECT kind,requestId FROM next_rejections ORDER BY kind,requestId", excluded = retired))
                require(key in pending && nodes[actual(key)]?.state in setOf(NextRecoveryRequestState.PERMANENT_REJECTION,
                    NextRecoveryRequestState.UNKNOWN_RESULT))
            return NextRecoveryDependencyGraph(nodes.values.toList(), edges.toList(), groups, aliases).preview(root)
        }

        private suspend fun sources(kind: String): List<Pair<Long, NextRecoveryKey>> {
            val table = NextRequestSql.table(kind)
            val hashes = NextRequestSql.sources(sql, table)
            val column = if (kind == NEXT_OPERATION) "operationId" else "commandId"
            val result = sql.query("SELECT id,$column FROM $table ORDER BY id LIMIT 10001").use { c -> buildList {
                while (c.moveToNext()) {
                    require(c.getType(0) == Cursor.FIELD_TYPE_INTEGER && c.getLong(0) > 0 && c.getType(1) == Cursor.FIELD_TYPE_STRING)
                    add(c.getLong(0) to NextRecoveryKey(kind, c.getString(1)))
                }
            } }
            require(result.map { it.first }.toSet() == hashes.keys && result.map { it.second }.distinct().size == result.size)
            return result
        }

        private suspend fun keys(query: String, args: Array<Any> = emptyArray(),
            excluded: Set<NextRecoveryKey> = emptySet()): List<NextRecoveryKey> =
            sql.query(query, args).use { c -> buildList {
                while (c.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    require(c.getType(0) == Cursor.FIELD_TYPE_STRING && c.getType(1) == Cursor.FIELD_TYPE_STRING)
                    val key = NextRecoveryKey(c.getString(0), c.getString(1))
                    if (key !in excluded) add(key)
                }
                require(size <= 10_000) { "SYNC_RECOVERY_REFERENCE_LIMIT" }
            } }

        private suspend fun original(key: NextRecoveryKey): NextRequestOriginEntity {
            requireNotNull(hash("next_request_origins", key))
            val value = requireNotNull(database.nextRequestDao().origin(key.kind, key.requestId))
            require(value.kind == key.kind && value.requestId == key.requestId && value.protocol == 5 && value.queueId > 0 &&
                value.accountId == access.session.authentication.userId && value.serverInstanceId == access.session.serverInstanceId &&
                value.syncEpoch == access.session.syncEpoch)
            return value
        }

        private fun put(node: NextRecoveryNode) {
            val bound = node.copy(evidenceHash = digest(contextHash, node.evidenceHash))
            val old = nodes.putIfAbsent(node.key, bound)
            require(old == null || old.copy(protections = bound.protections) == bound) { "SYNC_RECOVERY_PROOF_CHANGED" }
            require(nodes.size <= NEXT_RECOVERY_PROOF_NODE_LIMIT) { "SYNC_RECOVERY_PROOF_LIMIT" }
        }

        private fun dependency(from: NextRecoveryKey, to: NextRecoveryKey, reason: NextRecoveryDependencyReason,
            proof: String) {
            require(from != to)
            edges += NextRecoveryDependency(from, to, reason, digest(contextHash, proof))
            if (from !in nodes) required.addLast(from)
            if (to !in nodes) required.addLast(to)
        }

        private suspend fun drain() {
            while (required.isNotEmpty()) { currentCoroutineContext().ensureActive(); ensure(required.removeFirst()) }
        }

        private suspend fun ensure(key: NextRecoveryKey) {
            currentCoroutineContext().ensureActive()
            require(key !in retired) { "SYNC_RECOVERY_RETIRED_DEPENDENCY" }
            if (key in nodes) return
            val source = original(key)
            if (key.kind == NEXT_TIMER) {
                val round = requireNotNull(roundTimerIntent(source.intentJson))
                require(round.capturedDeviceId == access.deviceId)
                val command = round.timer.command
                val frontier = if (key in pending) captured.pending(access, key) else null
                val accepted = if (frontier == null) timers.recoveryAcceptedProof(access, key.requestId) else null
                require(accepted == null || accepted.command == command)
                accepted?.let { acceptedTimers[key] = it }
                val timer = frontier?.timer ?: NextRecoveryTimerFrontier(command.sessionId,
                    requireNotNull(round.context.head).activityUuid, command.sequence, command.commandType,
                    round.timer.planQueueWatermark, command.startPolicy)
                val proof = frontier?.evidenceHash ?: requireNotNull(accepted).evidenceHash
                put(NextRecoveryNode(key, NextRecoveryCategory.TIMER, frontier?.state ?: NextRecoveryRequestState.ACCEPTED,
                    "timer_session", command.sessionId, proof, frontier?.protections ?: emptySet()))
                entries[key] = Entry(key, "timer_session", command.sessionId, command.commandType, source.queueId,
                    listOf("plan_node" to timer.activityUuid), timer = timer)
                frontier?.dependencies?.forEach { dependency(it.predecessor, it.successor, it.reason, it.evidenceHash) }
                if (frontier == null) {
                    round.timer.planPredecessorId?.let { id ->
                        val lineage = NextStructuralCausalStore(database).recoveryLineage(id, access)
                        val captured = lineage.links.first()
                        require(command.commandType == "start" && captured.entityType == "plan_node" &&
                            captured.entityUuid == timer.activityUuid && captured.logicalQueueId <= requireNotNull(timer.planQueueWatermark))
                        dependency(NextRecoveryKey(NEXT_OPERATION, id), key, NextRecoveryDependencyReason.TIMER_POLICY, lineage.evidenceHash)
                    }
                    round.restartFrontier?.let { ref -> dependency(NextRecoveryKey(NEXT_OPERATION, ref.operationId), key,
                        NextRecoveryDependencyReason.RESTART_FRONTIER, ref.originHash) }
                }
                return
            }
            val proposal = restartProposal(source.intentJson)
            if (proposal != null) {
                val proof = restart.recoveryProof(access, key.requestId)
                require(proof.origin == source && proof.proposal == proposal)
                put(NextRecoveryNode(key, NextRecoveryCategory.RESTART, proof.state, "challenge_restart",
                    proposal.head.activityUuid, proof.evidenceHash, setOf(NextRecoveryProtection.ROUND_STATE)))
                entries[key] = Entry(key, "challenge_restart", proposal.head.activityUuid, "upsert", source.queueId,
                    listOf("plan_node" to proposal.head.activityUuid))
                proposal.planFrontier?.let { dependency(NextRecoveryKey(NEXT_OPERATION, it.operationId), key,
                    NextRecoveryDependencyReason.RESTART_FRONTIER, it.originHash + it.dependencyHash) }
                proposal.restartFrontier?.let { dependency(NextRecoveryKey(NEXT_OPERATION, it.operationId), key,
                    NextRecoveryDependencyReason.RESTART_FRONTIER, it.originHash) }
                proposal.terminals.forEach { dependency(NextRecoveryKey(NEXT_TIMER, it.commandId), key,
                    NextRecoveryDependencyReason.RESTART_FRONTIER, it.originHash) }
                return
            }
            val round = requireNotNull(roundOperationIntent(source.intentJson)); val operation = round.operation
            require(round.capturedDeviceId == access.deviceId)
            if (operation.entityType in setOf("plan_node", "metric", "activity_metric_link")) {
                val lineage = NextStructuralCausalStore(database).recoveryLineage(key.requestId, access)
                var older = lineage.checkpointHash
                for (link in lineage.links.asReversed()) {
                    val reference = NextRecoveryStructuralReference(link, digest(link.evidenceHash, older))
                    require(structuralProofs.putIfAbsent(link.actualKey, reference).let { it == null || it == reference })
                    older = reference.evidenceHash
                }
                for (link in lineage.links) {
                    val node = NextRecoveryNode(link.actualKey, NextRecoveryCategory.STRUCTURE, link.state,
                        link.entityType, link.entityUuid, digest(lineage.checkpointHash, link.evidenceHash))
                    put(node)
                    if (link.logicalKey != link.actualKey) {
                        require(aliases.putIfAbsent(link.logicalKey, link.actualKey).let { it == null || it == link.actualKey })
                        put(node.copy(key = link.logicalKey, state = NextRecoveryRequestState.SUPERSEDED,
                            evidenceHash = requireNotNull(link.supersessionHash)))
                    }
                    link.predecessorId?.let { dependency(NextRecoveryKey(NEXT_OPERATION, it), link.actualKey,
                        NextRecoveryDependencyReason.STRUCTURAL_PREDECESSOR, requireNotNull(link.dependencyHash)) }
                    if (link.actualKey !in entries) {
                        val original = original(link.actualKey)
                        val actualRound = requireNotNull(roundOperationIntent(original.intentJson))
                        entries[link.actualKey] = entry(actualRound.operation, link.logicalQueueId)
                        if (link.actualKey in pending) {
                            actualRound.goalChildFrontier?.forEach { child ->
                                NextGoalChildFrontierStore(database).validateCaptured(child, actualRound.operation, access)
                                dependency(NextRecoveryKey(NEXT_OPERATION, child.operationId), link.actualKey,
                                    NextRecoveryDependencyReason.GOAL_CHILD_FRONTIER, child.originHash + child.dependencyHash)
                            }
                            actualRound.restartFrontier?.let { ref ->
                                NextRestartBindingStore(database).captured(access, ref, original.queueId - 1)
                                dependency(NextRecoveryKey(NEXT_OPERATION, ref.operationId), link.actualKey,
                                    NextRecoveryDependencyReason.RESTART_FRONTIER, ref.originHash)
                                nodes[link.actualKey] = nodes.getValue(link.actualKey).copy(protections = setOf(NextRecoveryProtection.ROUND_STATE))
                            }
                        }
                    }
                }
                require(key in nodes)
            } else if (operation.payload["one_time"] is JsonObject) {
                val proof = once.recoveryProof(access, key.requestId)
                require(proof.origin == source && proof.operation == operation)
                put(NextRecoveryNode(key, NextRecoveryCategory.ONE_TIME, proof.state, operation.entityType,
                    operation.entityUuid, proof.evidenceHash, setOf(NextRecoveryProtection.REAL_FACT, NextRecoveryProtection.ONE_TIME_STATE)))
                entries[key] = entry(operation, source.queueId)
            } else {
                val frontier = if (key in pending) captured.pending(access, key) else null
                val accepted = if (frontier == null) core.recoveryAcceptedFactProof(access, key.requestId) else null
                require(accepted == null || accepted.first == operation)
                put(NextRecoveryNode(key, NextRecoveryCategory.FACT, frontier?.state ?: NextRecoveryRequestState.ACCEPTED,
                    operation.entityType, operation.entityUuid, frontier?.evidenceHash ?: requireNotNull(accepted).second,
                    frontier?.protections ?: setOf(NextRecoveryProtection.REAL_FACT)))
                entries[key] = entry(operation, source.queueId).copy(count = frontier?.count)
                frontier?.dependencies?.forEach { dependency(it.predecessor, it.successor, it.reason, it.evidenceHash) }
            }
        }

        private fun entry(operation: SyncV2Operation, order: Long): Entry {
            val references = listOf("parent_uuid" to "plan_node", "activity_uuid" to "plan_node", "metric_uuid" to "metric")
                .mapNotNull { (field, type) -> text(operation.payload[field])?.let { type to it } }
            val onceIntent = operation.payload["one_time"]?.takeUnless { it == JsonNull }?.let {
                Json.decodeFromJsonElement<OneTimeIntent>(it)
            }
            return Entry(NextRecoveryKey(NEXT_OPERATION, operation.operationId), operation.entityType, operation.entityUuid,
                operation.action, order, references, text(operation.payload["reverts_event_uuid"]), onceIntent)
        }

        private fun text(value: JsonElement?): String? = value?.takeUnless { it == JsonNull }?.let {
            require(it is JsonPrimitive && it.isString && isContractUuid(it.content)); it.content
        }

        private fun structures() = entries.values.filter { it.type in setOf("plan_node", "metric", "activity_metric_link") &&
            it.key in pending }.groupBy { it.type to it.uuid }.mapValues { (_, values) -> values.sortedBy { it.order } }

        /** A fact can already have its real ACK while an earlier local definition is rejected.
         * Query only the referenced parent, then validate actual origin/wire/receipt and order.
         * Presence of an ACK row is merely candidate discovery, never acceptance classification. */
        private suspend fun joinAcceptedReferences() {
            for ((identity, values) in structures()) {
                if (identity.first !in setOf("plan_node", "metric")) continue
                val writes = values.filter { it.action == "upsert" }
                if (writes.isEmpty()) continue
                val candidates = keys("SELECT o.kind,o.requestId FROM next_request_origins o " +
                    "INNER JOIN next_acceptances a ON a.kind=o.kind AND a.requestId=o.requestId " +
                    "WHERE o.kind=? AND (o.intentJson LIKE ? OR a.resultJson LIKE ?) LIMIT 10001",
                    arrayOf(NEXT_OPERATION, "%${identity.second}%", "%${identity.second}%"))
                for (key in candidates) {
                    val source = original(key)
                    if (restartProposal(source.intentJson) != null) continue
                    val operation = requireNotNull(roundOperationIntent(source.intentJson)).operation
                    if (operation.entityType !in setOf("activity_event", "metric_observation")) continue
                    val descriptor = entry(operation, source.queueId)
                    if (identity !in descriptor.references) continue
                    val before = writes.getOrNull(firstAfter(writes, source.queueId - 1) - 1) ?: continue
                    ensure(key); require(nodes.getValue(key).state == NextRecoveryRequestState.ACCEPTED)
                    dependency(before.key, key, NextRecoveryDependencyReason.ENTITY_REFERENCE,
                        nodes.getValue(before.key).evidenceHash + nodes.getValue(key).evidenceHash)
                }
            }
            drain()
        }

        private suspend fun joinDeletedLinkEndpoints() {
            for (value in entries.values.toList().filter { it.key in pending && it.type == "activity_metric_link" && it.action == "delete" }) {
                val operation = requireNotNull(roundOperationIntent(original(value.key).intentJson)).operation
                val earlier = entries.values.filter { it.type == value.type && it.uuid == value.uuid && it.action == "upsert" &&
                    it.order < value.order }.maxByOrNull { it.order }
                val references: List<Pair<String, String>>
                val proof: String
                if (earlier != null) {
                    references = earlier.references; proof = nodes.getValue(earlier.key).evidenceHash
                } else {
                    val shadowHash = requireNotNull(NextRequestSql.rowHash(sql, "sync_entity_state", "entityType=? AND entityUuid=?",
                        arrayOf(value.type, value.uuid)))
                    val state = requireNotNull(database.syncOutboxDao().getState(value.type, value.uuid))
                    val body = requireNotNull(state.payloadJson)
                    require(!state.deleted && state.revision == operation.baseRevision && state.payloadHash == syncPayloadHash(body)) {
                        "SYNC_RECOVERY_REFERENCE_BASE_CHANGED"
                    }
                    val payload = decodeFrozenSyncRequest(body.toByteArray(Charsets.UTF_8), JsonObject.serializer())
                    NextCommonFactMapper.validateLinkSnapshot(SyncV2Change(0, value.type, value.uuid, "upsert", state.revision,
                        payload, payload.getValue("updated_at").jsonPrimitive.content))
                    references = listOf("plan_node" to requireNotNull(text(payload["activity_uuid"])),
                        "metric" to requireNotNull(text(payload["metric_uuid"])))
                    proof = shadowHash
                }
                require(references.toSet().containsAll(value.references))
                entries[value.key] = value.copy(references = references)
                nodes[value.key] = nodes.getValue(value.key).let { it.copy(evidenceHash = digest(it.evidenceHash, proof)) }
            }
        }

        private fun firstAfter(values: List<Entry>, watermark: Long): Int {
            var low = 0; var high = values.size
            while (low < high) {
                val middle = low + (high - low) / 2
                if (values[middle].order <= watermark) low = middle + 1 else high = middle
            }
            return low
        }

        private suspend fun joinHeadsAndReferences() {
            val structures = structures()
            val writes = structures.mapValues { (_, values) -> values.filter { it.action == "upsert" } }
            for (values in structures.values) for ((before, after) in values.zipWithNext())
                dependency(before.key, after.key, NextRecoveryDependencyReason.STRUCTURAL_PREDECESSOR,
                    nodes.getValue(before.key).evidenceHash + nodes.getValue(after.key).evidenceHash)
            for (value in entries.values.toList().filter { it.key in pending }) {
                if (value.action == "delete") continue // Endpoint removal is ordered in the opposite direction below.
                // Timer and structure AUTOINCREMENT counters are different clocks. A non-start
                // command uses its proved session sequence, never its numeric queue ID as Plan order.
                if (value.timer != null && value.timer.planQueueWatermark == null) continue
                val watermark = value.timer?.planQueueWatermark ?: value.count?.planQueueWatermark ?: value.order - 1
                for (reference in value.references) {
                    val candidates = writes[reference].orEmpty()
                    val predecessor = candidates.getOrNull(firstAfter(candidates, watermark) - 1)
                    if (predecessor != null && predecessor.key != value.key)
                        dependency(predecessor.key, value.key, NextRecoveryDependencyReason.ENTITY_REFERENCE,
                            nodes.getValue(predecessor.key).evidenceHash + nodes.getValue(value.key).evidenceHash)
                }
            }
            val consumers = entries.values.filter { it.key in pending }.flatMap { value ->
                value.references.distinct().map { it to value.key }
            }.groupBy({ it.first }, { it.second })
            for ((identity, values) in structures) {
                val deletion = values.firstOrNull { it.action == "delete" } ?: continue
                val before = consumers[identity].orEmpty().filter { it != deletion.key }.distinct()
                if (before.isNotEmpty()) groups += NextRecoveryDependencyGroup(before, listOf(deletion.key),
                    NextRecoveryDependencyReason.ENTITY_REFERENCE, digest(contextHash, nodes.getValue(deletion.key).evidenceHash))
            }
        }

        private suspend fun joinBirthWatermarks() {
            for ((identity, values) in structures()) {
                if (identity.first != "plan_node") continue
                val writes = values.filter { it.action == "upsert" }
                if (writes.isEmpty()) continue
                val habit = database.habitDao().getHabitByUuid(identity.second)
                if (habit != null) {
                    val dates = sql.query("SELECT localDate FROM count_days WHERE habitId=? AND originRequestId IS NOT NULL LIMIT 10001",
                        arrayOf(habit.id)).use { c -> buildList { while (c.moveToNext()) {
                            require(c.getType(0) == Cursor.FIELD_TYPE_STRING); add(c.getString(0))
                        }; require(size <= 10_000) } }
                    for (date in dates) {
                        val day = requireNotNull(NextCountDayStore(database).read(habit, date))
                        val birth = NextRecoveryKey(NEXT_OPERATION, requireNotNull(day.originRequestId))
                        ensure(birth)
                        require(entries.getValue(birth).uuid == day.firstEventUuid && day.capturedDeviceId == access.deviceId)
                        day.planPredecessorId?.let { id ->
                            val lineage = NextStructuralCausalStore(database).recoveryLineage(id, access)
                            require(lineage.links.first().entityType == "plan_node" && lineage.links.first().entityUuid == habit.uuid &&
                                lineage.links.first().logicalQueueId <= requireNotNull(day.planQueueWatermark))
                            dependency(NextRecoveryKey(NEXT_OPERATION, id), birth, NextRecoveryDependencyReason.COUNT_POLICY, lineage.evidenceHash)
                        }
                        writes.getOrNull(firstAfter(writes, requireNotNull(day.planQueueWatermark)))?.let { after ->
                            dependency(birth, after.key, NextRecoveryDependencyReason.FIRST_COUNT_FACT,
                                requireNotNull(NextRequestSql.rowHash(sql, "count_days", "habitId=? AND localDate=?", arrayOf(habit.id, date))))
                        }
                    }
                }
                for ((origin, intent) in NextTimerPolicyStore(database).starts(activityUuid = identity.second)) {
                    val birth = NextRecoveryKey(NEXT_TIMER, origin.requestId); ensure(birth)
                    require(entries.getValue(birth).timer?.activityUuid == identity.second)
                    writes.getOrNull(firstAfter(writes, requireNotNull(intent.planQueueWatermark)))?.let { after ->
                        dependency(birth, after.key, NextRecoveryDependencyReason.TIMER_POLICY, nodes.getValue(birth).evidenceHash)
                    }
                }
            }
            drain()
        }

        private suspend fun joinTimerSequences() {
            val sessions = entries.values.mapNotNull { it.timer?.sessionUuid }.distinct()
            for (session in sessions) {
                for (key in keys("SELECT kind,requestId FROM next_request_origins WHERE kind=? AND intentJson LIKE ? LIMIT 10001",
                    arrayOf(NEXT_TIMER, "%$session%"))) {
                    val source = original(key)
                    if (requireNotNull(roundTimerIntent(source.intentJson)).timer.command.sessionId == session) ensure(key)
                }
                val sequence = entries.values.filter { it.timer?.sessionUuid == session }.sortedBy { it.timer!!.sequence }
                require(sequence.map { it.timer!!.sequence }.distinct().size == sequence.size)
                // A remote prefix requires its own real session proof, never an invented local start.
                require(sequence.first().timer!!.sequence == 1) { "SYNC_RECOVERY_REMOTE_TIMER_PREFIX_REQUIRED" }
                for ((before, after) in sequence.zipWithNext()) {
                    require(after.timer!!.sequence == before.timer!!.sequence + 1) { "SYNC_RECOVERY_TIMER_SEQUENCE_GAP" }
                    val previous = acceptedTimers[before.key]
                    val current = acceptedTimers[after.key]
                    if (previous != null && current != null)
                        NextTimerResultMapper.validateAfter(previous.session, current.command, current.session)
                    dependency(before.key, after.key, NextRecoveryDependencyReason.TIMER_SEQUENCE,
                        nodes.getValue(before.key).evidenceHash + nodes.getValue(after.key).evidenceHash)
                }
            }
            drain()
        }

        private suspend fun joinRevertsAndOnce() {
            for (value in entries.values.toList().filter { it.key in pending }) {
                value.revert?.let { reference -> eventDependency(reference, value, NextRecoveryDependencyReason.REVERT_TARGET) }
                value.onceIntent?.expectedHeadEventUuid?.let { reference ->
                    eventDependency(reference, value, NextRecoveryDependencyReason.ONE_TIME_CAS)
                }
            }
            drain()
        }

        private suspend fun eventDependency(uuid: String, after: Entry, reason: NextRecoveryDependencyReason) {
            var candidates = entries.values.filter { it.type == "activity_event" && it.uuid == uuid ||
                it.timer?.let { timer -> timer.sessionUuid == uuid && timer.commandType == "stop" } == true }
            if (candidates.isEmpty()) {
                for (key in keys("SELECT kind,requestId FROM next_request_origins WHERE kind=? AND intentJson LIKE ? LIMIT 10001",
                    arrayOf(NEXT_OPERATION, "%$uuid%"))) {
                    val source = original(key)
                    if (restartProposal(source.intentJson) != null) continue
                    val operation = requireNotNull(roundOperationIntent(source.intentJson)).operation
                    if (operation.entityType == "activity_event" && operation.entityUuid == uuid) ensure(key)
                }
                candidates = entries.values.filter { it.type == "activity_event" && it.uuid == uuid }
            }
            require(candidates.size <= 1) { "SYNC_RECOVERY_AMBIGUOUS_EVENT" }
            val before = candidates.singleOrNull()
            if (before != null) {
                require(before.references.firstOrNull { it.first == "plan_node" } == after.references.firstOrNull { it.first == "plan_node" })
                if (reason == NextRecoveryDependencyReason.ONE_TIME_CAS) {
                    val previous = requireNotNull(before.onceIntent); val current = requireNotNull(after.onceIntent)
                    require(previous.expectedVersion.toLong() + 1 == current.expectedVersion.toLong())
                }
                dependency(before.key, after.key, reason, nodes.getValue(before.key).evidenceHash + nodes.getValue(after.key).evidenceHash)
            } else {
                val proof = baseline(uuid, after, reason)
                nodes[after.key] = nodes.getValue(after.key).let { it.copy(evidenceHash = digest(it.evidenceHash, proof)) }
            }
        }

        private suspend fun baseline(uuid: String, after: Entry, reason: NextRecoveryDependencyReason): String {
            val type = "activity_event"
            val proof = requireNotNull(NextRequestSql.rowHash(sql, "sync_entity_state", "entityType=? AND entityUuid=?", arrayOf(type, uuid)))
            val shadow = requireNotNull(database.syncOutboxDao().getState(type, uuid))
            val body = requireNotNull(shadow.payloadJson)
            require(!shadow.deleted && shadow.revision > 0 && shadow.payloadHash == syncPayloadHash(body))
            val payload = decodeFrozenSyncRequest(body.toByteArray(Charsets.UTF_8), JsonObject.serializer())
            require(payload["activity_uuid"] == JsonPrimitive(after.references.first { it.first == "plan_node" }.second))
            val change = SyncV2Change(0, type, uuid, "upsert", shadow.revision, payload,
                payload.getValue("updated_at").jsonPrimitive.content)
            if (payload["one_time"] is JsonObject) {
                val fact = OneTimeServerFact(payload, shadow.revision)
                require(after.onceIntent != null)
                if (reason == NextRecoveryDependencyReason.ONE_TIME_CAS)
                    require(fact.proof.oneTimeStateAfter.version == after.onceIntent.expectedVersion &&
                        fact.proof.oneTimeStateAfter.headEventUuid == uuid)
                else require(fact.proof.oneTime.action == "complete")
            } else {
                require(after.onceIntent == null && payload["event_type"] != JsonPrimitive("revert"))
                if (payload["event_type"] == JsonPrimitive("duration_session")) NextCommonFactMapper.validateDurationSnapshot(change)
                else NextCommonFactMapper.validateOrdinaryFactSnapshot(change)
                val metadata = NextChallengeStore(database).activeInTransaction(access).second
                val birth = metadata.births.single { it.entityType == "activity_event" && it.entityUuid == uuid }
                require(birth.head == requireNotNull(roundOperationIntent(original(after.key).intentJson)).context.head)
            }
            return proof
        }

        private suspend fun joinImport() {
            val entry = importEntry ?: return
            val target = entry.plan.target
            require(target.accountId == access.session.authentication.userId && target.serverInstanceId == access.session.serverInstanceId &&
                target.syncEpoch == access.session.syncEpoch && target.deviceId == access.deviceId)
            val members = entry.plan.steps.associateBy { NextRecoveryKey(NEXT_OPERATION, it.operationId) }
            for ((key, step) in members) {
                ensure(key)
                step.requireOperation(decodeNextOperationIntent(original(key).intentJson))
                require(actual(key) == key)
                if (nodes.getValue(key).state == NextRecoveryRequestState.ACCEPTED) {
                    // ensure() already audited the complete real receipt without sender permission.
                    // A read-only preview must not acquire structure.write just to inspect an ACK.
                    val saved = requireNotNull(structuralProofs[key]).first
                    require(saved.actualKey == key && saved.logicalKey == key && saved.state == NextRecoveryRequestState.ACCEPTED)
                }
                nodes[key] = nodes.getValue(key).let { it.copy(category = NextRecoveryCategory.CONFIG_IMPORT,
                    protections = it.protections + NextRecoveryProtection.IMPORT_GROUP,
                    evidenceHash = digest(it.evidenceHash, entry.sourceProof, entry.receipt.networkHash)) }
            }
            for (phase in entry.plan.steps.map { it.phase }.distinct()) {
                val before = members.filterValues { it.phase < phase }.keys.toList()
                val after = members.filterValues { it.phase == phase }.keys.toList()
                if (before.isNotEmpty()) groups += NextRecoveryDependencyGroup(before, after,
                    NextRecoveryDependencyReason.IMPORT_PHASE, digest(contextHash, entry.sourceProof, phase.toString()))
            }
            val externalPhases = mutableMapOf<Int, MutableList<NextRecoveryKey>>()
            val byEntity = members.entries.groupBy { it.value.entityUuid }
            for (value in entries.values.toList().filter { it.key in pending && it.key !in members && it.key.kind == NEXT_OPERATION }) {
                val references = (value.references.map { it.second } + value.uuid).toSet()
                val parents = references.flatMap { byEntity[it].orEmpty() }.associate { it.key to it.value }
                if (parents.isEmpty()) continue
                val phase = parents.values.maxOf { it.phase }
                externalPhases.getOrPut(phase) { mutableListOf() }.add(value.key)
                for (parent in parents.keys) dependency(parent, value.key, NextRecoveryDependencyReason.IMPORT_PHASE, entry.sourceProof)
            }
            for ((phase, after) in externalPhases) {
                val before = members.filterValues { it.phase < phase }.keys.toList()
                if (before.isNotEmpty()) groups += NextRecoveryDependencyGroup(before, after,
                    NextRecoveryDependencyReason.IMPORT_PHASE, digest(contextHash, entry.sourceProof, phase.toString(), "external"))
            }
        }
    }
}
