package com.dayforge.data.repository

import com.dayforge.domain.service.CountCommandCostTiming
import com.dayforge.domain.service.CountCommandCostTiming.Step

import android.database.Cursor
import com.dayforge.data.history.HistoryBlockCodec
import com.dayforge.data.local.*
import com.dayforge.data.local.entity.*
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.*
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.domain.service.ActivityRateCalculator
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** One typed +1 entry. There is no caller-supplied business/SQL callback. A and C own distinct
 * account/Room scopes; pure B owns neither. Unsupported paths return before business DML.
 * The original generic producer remains the only fallback, and must use a retained fact if
 * the original constructor already ran before a late, still-read-only applicability miss.
 */
internal class CountAddExecutor(private val database: HabitDatabase, private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator) {
    sealed interface Result {
        data class Original(val constructedFact: CompletionEntity? = null) : Result
        data class Committed(val id: Long) : Result
    }
    private sealed interface Candidate {
        data class Original(val constructedFact: CompletionEntity? = null) : Candidate
        data class Applied(val id: Long) : Candidate
    }
    private data class Input(val habitId: Long, val habitUuid: String, val ticket: ObjectEditAuthority,
        val identity: CountActionIdentity?, val occurredAt: Instant, val zone: ZoneId)
    private data class Frozen(val access: LocalCoreWriteAccess, val readSet: CountAddReadSet,
        val habit: HabitEntity, val nativeHabit: CountTrustRows.Row, val head: ChallengeRoundHead,
        val known: List<ChallengeRoundHead>, val day: CountTrustRows.Row?, val days: List<CountTrustRows.Row>,
        val summaries: Map<ChallengeRoundHead, JsonObject?>, val months: Map<ChallengeRoundHead, List<String>>,
        val layouts: Map<String, CountTrustRows.Table>, val watermark: Long)

    private val sql get() = database.openHelper.writableDatabase

    sealed interface Preparation
    private class IssuedPreparation(val executor: CountAddExecutor, val input: Input,
        val frozen: Frozen, val plan: CountAddNativePlan) : Preparation
    sealed interface CommandSelection {
        class Applied internal constructor(val id: Long) : CommandSelection
        class Original internal constructor(val constructedFact: CompletionEntity?) : CommandSelection
    }

    suspend fun prepareForCommand(expected: HabitEntity, ticket: ObjectEditAuthority, identity: CountActionIdentity?,
        occurredAt: Instant, zone: ZoneId): Preparation? {
        NextEvidenceReadSession.requireWritePhase()
        // An existing outer writer is a legal original-producer shape. It cannot lend
        // this path two independent A/C transactions, so fall back before any new work.
        if (database.inTransaction()) return null
        if (!expected.isActive || expected.habitType != HabitType.COUNTING || expected.appearance == null ||
            expected.completionPolicy != "recurring" || ticket.rounds == null ||
            ticket.rounds.pendingInitials.isNotEmpty() || ticket.rounds.pendingRestarts.isNotEmpty()) return null
        check(ticket.type == "plan_node" && ticket.uuid == expected.uuid &&
            ticket.original == NextStructureMapper.writePlan(expected)) { "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED" }
        val rounds = requireNotNull(ticket.rounds)
        val ownedRounds = NextRoundWriteScope(rounds.access.copy(capabilities = Collections.unmodifiableSet(rounds.access.capabilities.toSet())),
            rounds.metadataJson, Collections.unmodifiableMap(rounds.pendingInitials.toMap()),
            Collections.unmodifiableMap(rounds.pendingRestarts.toMap()))
        val ownedTicket = ObjectEditAuthority(ticket.session, ticket.uuid, ticket.type,
            Json.parseToJsonElement(ticket.original.toString()).jsonObject, ownedRounds,
            ticket.goalChildUuids?.let { Collections.unmodifiableList(it.toList()) },
            ticket.capturedDeviceId, ticket.completionDeviceCaptured)
        val input = Input(expected.id, expected.uuid, ownedTicket,
            identity?.copy(plan = identity.plan?.let { Json.parseToJsonElement(it.toString()).jsonObject }), occurredAt, zone)
        val cost = CountCommandCostTiming.begin(CountCommandCostTiming.Phase.A)
        try {
        val frozen = withContext(Dispatchers.IO) { sessions.exclusive(AccountSessionCoordinator.Work.NEXT_WRITE) {
            // A has no publication/write grant: one original core credential check plus
            // current registered metadata binds its private frozen input. C retains the
            // original producer's full entry and final fresh checks before any commit.
            val access = captureAccess(input)
            val owner = CountTrustRuntime.forDatabase(database) ?: return@exclusive null
            if (owner.isRetired) return@exclusive null
            val completed = owner.countAddPreparation(database) {
                NextEvidenceReadSession.read(database) {
                    CountTrustKernel(database, tokens, sessions).withOpen(access) { context ->
                        if (context == null) null else freeze(input, context)
                    }
                }
            } ?: return@exclusive null
            completed.end.requireCandidate(completed.value)
            completed.value
        } } ?: return null

        // Strict displayed-ticket decoding and all old calculation-block/domain parsing are
        // outside both the account lock and Room. Allocation-dependent bounded work stays C.
        val planned = withContext(Dispatchers.Default) {
            val shown = requireNotNull(input.ticket.rounds).completionRounds.head(frozen.habit.uuid)
            require(shown == frozen.head) { "SYNC_CHALLENGE_STALE_ACTION" }
            CountCommandCostTiming.measure(Step.A_PLAN) {
                CountAddNativePlan.prepare(frozen.access, frozen.habit, frozen.nativeHabit,
                    input.occurredAt, input.zone, frozen.head, frozen.day, frozen.days,
                    frozen.summaries, frozen.known, frozen.months, frozen.layouts, frozen.watermark)
            }
        } ?: return null
        return IssuedPreparation(this, input, frozen, planned)
        } finally { CountCommandCostTiming.end(cost) }
    }

    suspend fun run(expected: HabitEntity, ticket: ObjectEditAuthority, identity: CountActionIdentity?,
        occurredAt: Instant, zone: ZoneId): Result {
        val prepared = prepareForCommand(expected, ticket, identity, occurredAt, zone) ?: return Result.Original()
        val issued = requireOwned(prepared)
        val input = issued.input; val frozen = issued.frozen; val planned = issued.plan
        return withContext(Dispatchers.IO) { sessions.exclusive(AccountSessionCoordinator.Work.NEXT_WRITE) {
            val access = entry(input)
            if (access != frozen.access) rejectNextRequest(NextRequestException.Reason.STALE_ACCESS)
            val owner = CountTrustRuntime.forDatabase(database) ?: return@exclusive Result.Original()
            if (owner.isRetired) return@exclusive Result.Original()
            val completed = owner.countAddSelection<Candidate>(database) {
                val schema = mainSchemaVersion()
                val kernel = CountTrustKernel(database, tokens, sessions)
                kernel.withCapture(access) { capture ->
                    check(mainSchemaVersion() == schema) { "COUNT_ADD_SCHEMA_CHANGED" }
                    // Sole null selection: no capture, no apply, no fact construction and
                    // no business DML. Only this branch may discard a soft-qualified C owner.
                    if (capture == null) null
                    else if (!frozen.readSet.matchesCurrent(owner, capture.context)) Candidate.Original()
                    else {
                        check(mainSchemaVersion() == schema) { "COUNT_ADD_SCHEMA_CHANGED" }
                        apply(input, frozen, planned, owner, capture, schema)
                    }
                }
            }
            // A rolled-back/unknown END has no completed result. No retry or UI success is
            // possible through this branch until the actual matching transaction returned.
            if (completed == null) return@exclusive Result.Original()
            completed.end.requireCandidate(completed.value)
            currentCoroutineContext().ensureActive()
            when (val candidate = completed.value) {
                null -> Result.Original()
                is Candidate.Applied -> Result.Committed(candidate.id)
                is Candidate.Original -> Result.Original(candidate.constructedFact)
            }
        } }
    }
    private fun requireOwned(prepared: Preparation): IssuedPreparation =
        (prepared as? IssuedPreparation ?: error("COUNT_ADD_FOREIGN_PREPARATION")).also {
            require(it.executor === this) { "COUNT_ADD_FOREIGN_PREPARATION" }
        }

    suspend fun commandEntry(prepared: Preparation): LocalCoreWriteAccess {
        val issued = requireOwned(prepared)
        return entry(issued.input).also {
            if (it != issued.frozen.access) rejectNextRequest(NextRequestException.Reason.STALE_ACCESS)
        }
    }

    /** Only the private primary producer calls this in its already-admitted actual T1. No
     * transaction/END is created here. An applicability miss leaves the participant PREPARING
     * so the original complete producer can continue inside this SAME T1.
     */
    suspend fun commitInPrimaryTransaction(prepared: Preparation, access: LocalCoreWriteAccess,
        participant: CountCommandStore.Participant): CommandSelection {
        val issued = requireOwned(prepared)
        require(database.inTransaction() && access == issued.frozen.access)
        require(participant.frozenIntent.action == CountCommandRecords.Action.COUNT_ADD)
        val frozenInput = participant.frozenIntent.body().getValue("input").jsonObject
        require(CountCommandRecords.uuid(frozenInput, "habit_uuid") == issued.input.habitUuid &&
            CountCommandRecords.integer(frozenInput, "habit_id") == issued.input.habitId &&
            CountCommandRecords.integer(frozenInput, "quantity") ==1L &&
            Instant.parse(CountCommandRecords.text(frozenInput, "occurred_at")) == issued.input.occurredAt &&
            ZoneId.of(CountCommandRecords.text(frozenInput, "timezone")) == issued.input.zone)
        val owner = CountTrustRuntime.forDatabase(database) ?: return CommandSelection.Original(null)
        if (owner.isRetired) return CommandSelection.Original(null)
        val schema = mainSchemaVersion()
        val kernel = CountTrustKernel(database, tokens, sessions)
        return kernel.withCapture(access) { capture ->
            check(mainSchemaVersion() == schema) { "COUNT_ADD_SCHEMA_CHANGED" }
            if (capture == null || !issued.frozen.readSet.matchesCurrent(owner, capture.context)) CommandSelection.Original(null)
            else when (val result = apply(issued.input, issued.frozen, issued.plan, owner, capture, schema, participant)) {
                is Candidate.Original -> CommandSelection.Original(result.constructedFact)
                is Candidate.Applied -> CommandSelection.Applied(result.id)
            }
        }
    }


    private suspend fun entry(input: Input): LocalCoreWriteAccess {
        val access = captureAccess(input)
        val scope = requireNotNull(input.ticket.rounds)
        require(tokens.syncAuthenticationSnapshot(scope.access) != null) { "SYNC_CHALLENGE_CONTEXT_CHANGED" }
        return access
    }

    private suspend fun captureAccess(input: Input): LocalCoreWriteAccess {
        val access = tokens.localCoreWriteAccess()
        if (access == null || access.session != input.ticket.session)
            rejectNextRequest(NextRequestException.Reason.STALE_ACCESS)
        val scope = requireNotNull(input.ticket.rounds)
        require(tokens.localSyncAccess() == scope.access && access.capturedDeviceId == scope.access.deviceId) {
            "SYNC_CHALLENGE_CONTEXT_CHANGED"
        }
        return access
    }

    private suspend fun freeze(input: Input, context: CountTrustKernel.Context): Frozen? {
        if (!context.formal) return null
        val owner = requireNotNull(CountTrustRuntime.forDatabase(database))
        val builder = CountAddReadSet.Builder.create(owner, context)
        return context.index.withReadScope {
            val habitRows = builder.rows(CountTrustMembership.key("habit.uuid", input.habitUuid),
                "habits", mapOf("uuid" to input.habitUuid))
            val nativeHabit = habitRows.singleOrNull() ?: return@withReadScope null
            val habit = requireNotNull(database.habitDao().getHabitById(input.habitId)) { "OBJECT_WRITE_NOT_FOUND" }
            check(habit.uuid == input.ticket.uuid && NextStructureMapper.writePlan(habit) == input.ticket.original) {
                "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED"
            }
            if (!habit.isActive || habit.habitType != HabitType.COUNTING || habit.completionPolicy != "recurring" ||
                !context.eligible(habit)) return@withReadScope null
            builder.leaf("eligible:${habit.uuid}")
            builder.leaf(CountTrustRoundEvidence.COVERAGE_KEY)
            val head = CountTrustRoundEvidence.decode(requireNotNull(builder.leaf(CountTrustRoundEvidence.headKey(habit.uuid))))
            val known = requireNotNull(builder.leaf(CountTrustRoundEvidence.knownKey(habit.uuid))).jsonArray
                .map(CountTrustRoundEvidence::decode)
            check(head in known)
            // Outstanding structure can involve predecessors/deletion/restart semantics. The
            // original writer handles them once; this is not a new product rejection.
            val structure = builder.rows(CountTrustMembership.key("outbox.record", "habit", habit.uuid),
                "sync_outbox", mapOf("recordType" to "habit", "entityUuid" to habit.uuid))
            if (structure.isNotEmpty()) return@withReadScope null
            val cursor = builder.rows(CountTrustMembership.key("cursor.id", "1"), "next_sync_state", mapOf("id" to 1)).single()
            val marker = builder.rows(CountTrustMembership.key("challenge.id", "1"), "next_challenge_state",
                mapOf("id" to 1), markerProjection = true).single()
            check(cursor.long("challengeContract") == 1L)
            for (field in listOf("accountId", "serverInstanceId", "syncEpoch", "deviceId", "generation", "cursor", "batchHash"))
                check(cursor.cell(field) == marker.cell(field)) { "SYNC_CHALLENGE_CONTEXT_CHANGED" }
            val registered = requireNotNull(input.ticket.rounds).access
            check(cursor.text("accountId") == registered.session.authentication.userId &&
                cursor.text("serverInstanceId") == registered.session.serverInstanceId &&
                cursor.text("syncEpoch") == registered.session.syncEpoch && cursor.text("deviceId") == registered.deviceId)
            val round = builder.rows(CountTrustMembership.key("round.uuid", head.roundUuid), "next_challenge_rounds",
                mapOf("roundUuid" to head.roundUuid)).single()
            val record = Json.decodeFromString(ChallengeRoundRecord.serializer(), round.text("recordJson"))
            check(record.head == head)
            record.restartIntent?.let { restart ->
                val shadow = builder.rows(CountTrustMembership.key("shadow.entity", "plan_node", habit.uuid),
                    "sync_entity_state", mapOf("entityType" to "plan_node", "entityUuid" to habit.uuid)).single().shadow()
                require(!shadow.deleted && shadow.revision >= Math.addExact(restart.expectedPlanRevision, 1L) &&
                    shadow.payloadHash == syncPayloadHash(requireNotNull(shadow.payloadJson))) { "SYNC_CHALLENGE_PLAN_CATCHUP_REQUIRED" }
            }
            val date = input.occurredAt.atZone(input.zone).toLocalDate(); val month = YearMonth.from(date)
            val days = builder.rows(CountTrustMembership.key("day.month", habit.uuid, month.toString()), "count_days",
                mapOf("habitId" to habit.id), "localDate", month.atDay(1).toString(), month.plusMonths(1).atDay(1).toString())
            val day = days.singleOrNull { it.day().localDate == date.toString() }
            if (day == null) {
                // Equivalent to the already supported unbound-date scope when all three exact
                // retained namespaces are absent. Nonempty legal/unknown shapes use the old path.
                val facts = builder.rows(CountTrustMembership.key("completion.date", habit.uuid, date.toString()),
                    "completions", mapOf("habitId" to habit.id, "recordedLocalDate" to date.toString()))
                val origins = builder.leaf(CountTrustMembership.key("origin.date", habit.uuid, date.toString()))?.jsonArray.orEmpty()
                val shadows = builder.leaf(CountTrustMembership.key("shadow.date", habit.uuid, date.toString()))?.jsonArray.orEmpty()
                if (facts.isNotEmpty() || origins.isNotEmpty() || shadows.isNotEmpty()) return@withReadScope null
            } else {
                val value = day.day()
                if (value.planPredecessorId != null) return@withReadScope null
                check(NextCountDayStore(database).read(habit, date.toString()) == value)
                if (value.originRequestId != null) builder.rows(CountTrustMembership.key("origin.request", NEXT_OPERATION, value.originRequestId),
                    "next_request_origins", mapOf("kind" to NEXT_OPERATION, "requestId" to value.originRequestId))
                else builder.rows(CountTrustMembership.key("shadow.entity", "activity_event", value.firstEventUuid),
                    "sync_entity_state", mapOf("entityType" to "activity_event", "entityUuid" to value.firstEventUuid))
            }
            val touched = if (day == null) known else listOf(head)
            val summaries = touched.associateWith { builder.leaf(CountTrustRoundEvidence.summaryKey(habit.uuid, month, it))?.jsonObject }
            val months = touched.associateWith { builder.leaf(CountTrustRoundEvidence.monthsKey(habit.uuid, it))
                ?.jsonArray?.map { value -> value.jsonPrimitive.content }.orEmpty() }
            val layouts = listOf("habits", "completions", "count_days", "sync_outbox", "next_request_origins").associateWith(context.rows::table)
            val watermark = NextRequestSql.watermark(sql, "sync_outbox")
            Frozen(context.access, builder.seal(), habit, nativeHabit, head, known, day, days, summaries, months, layouts, watermark)
        }
    }

    private suspend fun apply(input: Input, frozen: Frozen, plan: CountAddNativePlan,
        owner: CountTrustRuntime, capture: CountTrustKernel.Capture, schema: Long,
        command: CountCommandStore.Participant? = null): Candidate {
        val context = capture.context
        val tables = listOf("sync_outbox", "timer_command_outbox")
        NextRequestSql.requireOutboxEnabled(sql)
        // Preserve the COMPLETE original queue contract. This remaining O(backlog) is explicit;
        // a membership directory alone is not the queue's size/type/length/watermark certificate.
        val before = tables.associateWith { NextRequestSql.sources(sql, it) }
        val watermarks = tables.associateWith { NextRequestSql.watermark(sql, it) }
        input.identity?.let {
            it.requireAuthority(input.ticket)
            check(tokens.localCoreWriteAccess()?.capturedDeviceId == it.deviceId) { "COUNT_SESSION_CHANGED" }
        }
        NextPlanDeletionStore(database).requireWritable(input.ticket.uuid)
        val habit = requireNotNull(database.habitDao().getHabitById(frozen.habit.id)) { "OBJECT_WRITE_NOT_FOUND" }
        check(habit == frozen.habit && NextStructureMapper.writePlan(habit) == input.ticket.original) { "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED" }
        if (plan.noEffect) {
            command?.let { participant ->
                val proof = CountCommandProducerPlan.noEffect(database, participant.frozenIntent,
                    CountCommandRecords.NoEffectReason.COUNTDOWN_COMPLETE)
                participant.stage(proof.finish(emptyList(), emptySet()))
            }
            finalScope(input)
            if (tokens.localCoreWriteAccess() != frozen.access) rejectNextRequest(NextRequestException.Reason.STALE_ACCESS)
            owner.maintenance(database) {
                check(!owner.hasPendingChanges(capture.boundary)) { "COUNT_ADD_NO_EFFECT_WROTE_DATA" }
            }
            check(mainSchemaVersion() == schema) { "COUNT_ADD_SCHEMA_CHANGED" }
            return Candidate.Applied(0)
        }

        val reserved = command?.frozenIntent?.body()?.getValue("input")?.jsonObject?.let {
            CountCommandRecords.uuid(it, "reserved_fact_uuid")
        } ?: UUID.randomUUID().toString()
        val stage = plan.beginAtOriginalConstructionPoint(reserved, System.currentTimeMillis())
        val fact = stage.completion
        // Rare allocated-identity collisions can have original legal semantics. Reuse this
        // same constructed fact in the original path, BEFORE any DML, instead of inventing a
        // new rejection or silently allocating a different UUID/createdAt during fallback.
        val collision = context.checked(CountTrustMembership.key("completion.uuid", fact.uuid), "completions", "uuid=?", arrayOf(fact.uuid)).isNotEmpty() ||
            context.checked(CountTrustMembership.key("timer.uuid", fact.uuid), "timelogs", "uuid=?", arrayOf(fact.uuid)).isNotEmpty() ||
            context.checked(CountTrustMembership.key("outbox.record", "completion", fact.uuid), "sync_outbox",
                "recordType=? AND entityUuid=?", arrayOf("completion", fact.uuid)).isNotEmpty() ||
            context.checked(CountTrustMembership.key("shadow.entity", "activity_event", fact.uuid), "sync_entity_state",
                "entityType=? AND entityUuid=?", arrayOf("activity_event", fact.uuid)).isNotEmpty() ||
            context.members(CountTrustMembership.key("origin.entity", "activity_event", fact.uuid)).isNotEmpty() ||
            context.members(CountTrustMembership.key("shadow.revertTarget", fact.uuid)).isNotEmpty() ||
            context.members(CountTrustMembership.key("birth.entity", "activity_event", fact.uuid)).isNotEmpty()
        if (collision) {
            owner.maintenance(database) {
                check(!owner.hasPendingChanges(capture.boundary)) { "COUNT_ADD_FALLBACK_WROTE_DATA" }
            }
            check(mainSchemaVersion() == schema) { "COUNT_ADD_SCHEMA_CHANGED" }
            return Candidate.Original(fact)
        }
        if (before.getValue("sync_outbox").size >= 10000)
            rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
        val sourcePlan = requireNotNull(CountLocalSourcePlan.freeze(habit, fact, watermarks.getValue("sync_outbox")))
        val commandPlan = command?.let { participant ->
            CountCommandProducerPlan.countAdd(database, participant.frozenIntent, habit, fact,
                frozen.day?.day()?.policy ?: CountDayPolicy(habit.targetValue, habit.isCountdown), reactivationBase = null)
        }
        command?.beginBusiness()
        stage.initialDay?.let { database.countDayDao().insert(it) }
        val id = database.completionDao().insert(fact)
        commandPlan?.bindInsertedIds(listOf(id))
        check(sourcePlan.verifiedStoredFact(sql, id) == fact.copy(id = id)) { "COUNT_ADD_FACT_CHANGED" }
        val rate = originalRate(context, habit, fact)
        database.habitDao().updateActivityRate(habit.id, rate.rate, rate.instant)
        val ids = sql.query("SELECT id FROM main.sync_outbox WHERE recordType=? AND entityUuid=? ORDER BY id LIMIT 2",
            arrayOf("completion", fact.uuid)).use { c -> buildList { while (c.moveToNext()) {
                check(c.getType(0) == Cursor.FIELD_TYPE_INTEGER); add(c.getLong(0))
            } } }
        check(ids.size == 1)
        val queueId = ids.single()
        if (queueId <= watermarks.getValue("sync_outbox")) rejectNextRequest(NextRequestException.Reason.OLD_INTENT)
        val sourceHash = requireNotNull(NextRequestSql.rowHash(sql, "sync_outbox", "id=?", arrayOf(queueId)))
        val source = requireNotNull(database.syncOutboxDao().getById(queueId))
        check(sourcePlan.bind(id, fact.copy(id = id), source) == source) { "COUNT_ADD_SOURCE_CHANGED" }
        val bound = stage.bind(id, queueId, source.operationId, source.createdAt, rate)
        check(bound.source == source && bound.origin.sourceHash == sourceHash) { "COUNT_ADD_SOURCE_CHANGED" }
        if (frozen.access.capabilities != null && "facts.append" !in frozen.access.capabilities)
            rejectNextRequest(NextRequestException.Reason.PERMISSION_DENIED)
        val dao = database.nextRequestDao()
        if (dao.origin(NEXT_OPERATION, source.operationId) != null || dao.transmission(NEXT_OPERATION, source.operationId) != null ||
            dao.acceptance(NEXT_OPERATION, source.operationId) != null) rejectNextRequest(NextRequestException.Reason.REQUEST_ID_REUSED)
        dao.insertOrigin(bound.origin)
        check(NextRequestSql.rowHash(sql, "next_request_origins", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, source.operationId)) ==
            CountAddOriginHash.hash(bound.origin) && dao.origin(NEXT_OPERATION, source.operationId) == bound.origin) { "COUNT_ADD_ORIGIN_CHANGED" }
        bound.finalDay?.let { check(database.countDayDao().update(it) == 1) }
        NextRequestSql.requireOutboxEnabled(sql)
        if (command != null) {
            // First prove the complete independently planned4/6 business effects. The later
            // finish repeats that same barrier AFTER command DML, then alone signs COUNT.
            CountCommandCostTiming.measure(Step.EFFECTS_BEFORE_COMMAND) { verifyEffects(owner, capture, bound) }
            val produced = CountCommandProducerPlan.Produced(source, decodeNextOperationIntent(bound.origin.intentJson),
                bound.origin, CountAddOriginHash.hash(bound.origin))
            command.stage(requireNotNull(commandPlan).finish(listOf(produced), setOf(NEXT_OPERATION to source.operationId)))
        }
        finalScope(input)
        check(mainSchemaVersion() == schema) { "COUNT_ADD_SCHEMA_CHANGED" }
        finish(owner, capture, bound, schema)
        if (tokens.localCoreWriteAccess() != frozen.access) rejectNextRequest(NextRequestException.Reason.STALE_ACCESS)
        return Candidate.Applied(id)
    }

    private fun originalRate(context: CountTrustKernel.Context, habit: HabitEntity,
        fact: CompletionEntity): CountTrustKernel.RateCalculation {
        val now = System.currentTimeMillis(); val zone = ZoneId.systemDefault()
        val dates = ActivityRateCalculator.generateWindowCheckInDays(habit.schedule, habit.createdAt, now, zone)
            .map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        val completed = dates.filter { date ->
            val actual = sql.query("SELECT EXISTS(SELECT 1 FROM completions WHERE habitId=? AND recordedLocalDate=?)",
                arrayOf<Any>(habit.id, date.toString())).use { c ->
                check(c.moveToFirst() && c.getType(0) == Cursor.FIELD_TYPE_INTEGER && c.getLong(0) in 0..1)
                (c.getLong(0) == 1L).also { check(!c.moveToNext()) }
            }
            val expected = fact.recordedLocalDate == date.toString() ||
                context.members(CountTrustMembership.key("completion.date", habit.uuid, date.toString())).isNotEmpty()
            check(actual == expected) { "COUNT_TRUST_RATE_DEPENDENCY_CHANGED" }
            actual
        }
        return CountTrustKernel.RateCalculation(ActivityRateCalculator.calculate(habit.schedule, habit.createdAt, completed, now, zone), now, zone)
    }

    private suspend fun finalScope(input: Input) {
        val scope = requireNotNull(input.ticket.rounds)
        require(tokens.localSyncAccess() == scope.access && tokens.syncAuthenticationSnapshot(scope.access) != null)
    }

    private suspend fun verifyEffects(owner: CountTrustRuntime, capture: CountTrustKernel.Capture,
        bound: CountAddNativePlan.Bound) = owner.maintenance(database) { verifyEffectsWhileMaintaining(owner, capture, bound) }

    private fun verifyEffectsWhileMaintaining(owner: CountTrustRuntime, capture: CountTrustKernel.Capture,
        bound: CountAddNativePlan.Bound): Triple<String, Long, Long> {
        val context = capture.context
        CountCommandCostTiming.measure(Step.FINAL_CONTENTS) { bound.verifyFinalContents(sql) }
        val changes = requireNotNull(CountCommandCostTiming.measure(Step.EFFECTS_JOURNAL) {
            owner.readCountAddEffects(capture.boundary, bound.journalExpectation())
        }) {
            "COUNT_ADD_UNEXPECTED_EFFECT"
        }
        CountCommandCostTiming.measure(Step.EFFECTS_COMPARE) { bound.verifyActual(changes) }
        CountCommandCostTiming.measure(Step.FINAL_CONTENTS) { bound.verifyFinalContents(sql) }
        val control = sql.query("SELECT storageId,generation,dirty FROM count_trust_control WHERE id=1").use { c ->
            check(c.moveToFirst() && c.getType(0) == Cursor.FIELD_TYPE_STRING &&
                c.getType(1) == Cursor.FIELD_TYPE_INTEGER && c.getType(2) == Cursor.FIELD_TYPE_INTEGER)
            Triple(c.getString(0), c.getLong(1), c.getLong(2)).also { check(!c.moveToNext()) }
        }
        check(CountTrustSeal.binding(context.access, control.first) == context.header.binding && control.third == 1L &&
            control.second == Math.addExact(context.header.generation, changes.size.toLong())) { "COUNT_TRUST_JOURNAL_INCOMPLETE" }
        return control
    }

    private suspend fun finish(owner: CountTrustRuntime, capture: CountTrustKernel.Capture,
        bound: CountAddNativePlan.Bound, schema: Long) {
        try {
            owner.maintenance(database) {
                val context = capture.context
                val control = CountCommandCostTiming.measure(Step.EFFECTS_AFTER_COMMAND) {
                    verifyEffectsWhileMaintaining(owner, capture, bound)
                }
                // Preserve the original optional-promotion boundary: changing the default
                // zone after the original rate calculation must not sign that stale rate as
                // a reusable current block. The already verified business commit remains valid.
                if (bound.rate.zone != ZoneId.systemDefault()) return@maintenance
                CountCommandCostTiming.measure(Step.COUNT_INDEX_PROMOTION) { context.index.withReadScope {
                    val keys = bound.requiredKeys()
                    val edits = bound.edits(keys.associateWith { context.index.get(it) }).toMutableMap()
                    when (context.index.get(CountDisplayBlocks.COVERAGE_KEY)) {
                        null -> Unit // Optional older read-model coverage retains the original reader.
                        JsonPrimitive(CountDisplayBlocks.COVERAGE) -> edits.putAll(
                            CountDisplayBlocks.advance(bound.displayChanges, context.index::get))
                        else -> error("COUNT_DISPLAY_COVERAGE_CHANGED")
                    }
                    context.index.putAll(edits)
                } }
                check(context.environment == CountTrustValidationEnvironment.current()) { "COUNT_TRUST_ENVIRONMENT_CHANGED" }
                check(tokens.localCoreWriteAccess() == context.access) { "COUNT_SESSION_CHANGED" }
                check(mainSchemaVersion() == schema) { "COUNT_ADD_SCHEMA_CHANGED" }
                val signed = CountCommandCostTiming.measure(Step.COUNT_SIGN) {
                    CountTrustSeal().prepare(context.header.binding, context.header.schemaHash,
                        context.index.root, Math.addExact(context.header.revision, 1L), control.second)
                }
                CountCommandCostTiming.measure(Step.COUNT_SEAL_STORE) { CountTrustSeal.store(sql, signed) }
            }
        } catch (error: HistoryBlockCodec.Rejected) {
            if (error.code != HistoryBlockCodec.Code.BUDGET) throw error
            // Same optional-representation fallback as the original kernel. Independent
            // domain/effects/native verification already completed; the old root stays dirty.
        } catch (error: IllegalArgumentException) {
            if (error.message != "COUNT_TRUST_VALUE_BUDGET") throw error
        }
    }

    private fun mainSchemaVersion(): Long = sql.query("PRAGMA main.schema_version").use {
        check(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_INTEGER)
        it.getLong(0).also { _ -> check(!it.moveToNext()) }
    }
}
