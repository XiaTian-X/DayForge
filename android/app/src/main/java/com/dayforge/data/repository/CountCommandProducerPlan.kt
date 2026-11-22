package com.dayforge.data.repository

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dayforge.data.api.dto.SyncV2Operation
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.local.entity.MetricLogEntity
import com.dayforge.data.local.entity.NextRequestOriginEntity
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.domain.model.CountDayPolicy
import com.dayforge.domain.model.isContractUuid
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Original pre-write objects produce expected payloads; actual journal is never used to
 * manufacture permission. The producer adapter retains this object privately across its
 * domain callback and supplies the COMPLETE created-origin list after original source proof.
 * This adds command identity binding; original domain/source/round validation is not removed.
 */
internal class CountCommandProducerPlan private constructor(private val database: HabitDatabase,
    private val intent: CountCommandRecords.Intent, private val expected: List<Expected>, private val facts: List<Fact>,
    private val reason: CountCommandRecords.NoEffectReason?, private val followUp: CountCommandOutcome.FollowUp,
    private val captureJob: Job?, private val lifetimeToken: Any, private val metricResolution: JsonObject? = null,
    private val metricBoundary: CountTrustRuntime.PrimaryBusinessBoundary? = null, private val metricWatermark: Long? = null,
    private val metricVerificationJob: Job? = null) {
    private var independentEffects: LegacyCountEffectPlan.Bound? = null
    private data class Expected(val type: String, val uuid: String, val action: String, val base: Long?, val payload: String,
        val negativeZeroFact: String? = null, val wirePayload: String? = null)
    private data class Fact(val table: String, val uuid: String, val values: Map<String, Any?>,
        val result: CountCommandOutcome.Effect)
    data class Produced(val source: SyncOutboxEntity, val operation: SyncV2Operation, val origin: NextRequestOriginEntity,
        val immutableOriginHash: String)
    private var used = false
    private var allocatedIds: List<Long>? = null
    suspend fun bindInsertedIds(ids: List<Long>) {
        currentCoroutineContext().ensureActive()
        check(!used && allocatedIds == null && currentCoroutineContext()[Job] === captureJob && facts.isNotEmpty())
        requireNotNull(CountTrustRuntime.forDatabase(database)).requirePrimaryReadToken(database, lifetimeToken)
        require(ids.size == facts.size && ids.all { it > 0 } && ids.distinct().size == ids.size)
        allocatedIds = ids.toList()
    }

    class Proven private constructor(private val database: HabitDatabase, private val token: Any, private val job: Job?,
        private val result: CountCommandOutcome) {
        private var consumed = false
        suspend fun consume(database: HabitDatabase, intent: CountCommandRecords.Intent): CountCommandOutcome {
            currentCoroutineContext().ensureActive()
            check(!consumed && this.database === database && currentCoroutineContext()[Job] === job)
            requireNotNull(CountTrustRuntime.forDatabase(database)).requirePrimaryReadToken(database, token)
            result.requireIntent(intent); consumed = true; return result
        }
        companion object {
            internal suspend fun verifiedIndependentMetric(plan: IndependentMetricLegacyPlan): Proven {
                val outcome = plan.result()
                val database = plan.databaseForProof()
                val owner = requireNotNull(CountTrustRuntime.forDatabase(database))
                val caller = currentCoroutineContext()[Job]
                val token = owner.maintenance(database) { owner.capturePrimaryReadToken(database) }
                return Proven(database, token, caller, outcome)
            }
            internal suspend fun verifiedLegacyMetrics(plan: CountCommandLegacyMetricPlan): Proven {
                val outcome = plan.result()
                val database = plan.databaseForProof()
                val owner = requireNotNull(CountTrustRuntime.forDatabase(database))
                val caller = currentCoroutineContext()[Job]
                val token = owner.maintenance(database) { owner.capturePrimaryReadToken(database) }
                return Proven(database, token, caller, outcome)
            }
            internal suspend fun verifiedLegacy(plan: CountCommandLegacyPlan): Proven {
                val outcome = plan.verifyAndResult()
                val database = plan.databaseForProof()
                val owner = requireNotNull(CountTrustRuntime.forDatabase(database))
                val caller = currentCoroutineContext()[Job]
                val token = owner.maintenance(database) { owner.capturePrimaryReadToken(database) }
                return Proven(database, token, caller, outcome)
            }
            internal suspend fun verified(plan: CountCommandProducerPlan, actual: List<Produced>,
                completeKeys: Set<Pair<String, String>>): Proven {
                val outcome = plan.verifyActual(actual, completeKeys)
                val owner = requireNotNull(CountTrustRuntime.forDatabase(plan.database))
                val caller = currentCoroutineContext()[Job]
                val token = owner.maintenance(plan.database) { owner.capturePrimaryReadToken(plan.database) }
                return Proven(plan.database, token, caller, outcome)
            }
        }
    }

    /** Invoked only by the producer's local adapter after all original created origins have
     * been checked, before command staging. The completeKeys come from the original producer
     * accumulator, not a query of a filtered subset. No callback receives that adapter.
     */
    suspend fun finish(actual: List<Produced>, completeKeys: Set<Pair<String, String>>): Proven = Proven.verified(this, actual, completeKeys)

    private suspend fun verifyActual(actual: List<Produced>, completeKeys: Set<Pair<String, String>>): CountCommandOutcome {
        currentCoroutineContext().ensureActive(); check(!used && database.inTransaction())
        requireNotNull(CountTrustRuntime.forDatabase(database)).requirePrimaryReadToken(database, lifetimeToken)
        used = true
        require(actual.size == expected.size && actual.map { it.origin.kind to it.origin.requestId }.toSet() == completeKeys &&
            completeKeys.size == actual.size) { "COMMAND_PRODUCER_EFFECT_SET_CHANGED" }
        val byIdentity = actual.associateBy { it.operation.entityType to it.operation.entityUuid }
        require(byIdentity.size == actual.size)
        val sql = database.openHelper.writableDatabase
        for (planned in expected) {
            val item = requireNotNull(byIdentity[planned.type to planned.uuid])
            require(item.operation.action == planned.action && item.operation.baseRevision == planned.base &&
                CountCommandRecords.canonical(item.operation.payload) == storedPayload(sql, planned) &&
                item.operation.operationId == item.source.operationId && item.origin.requestId == item.source.operationId &&
                item.origin.queueId == item.source.id && isContractUuid(item.origin.requestId) && item.origin.protocol == 5 &&
                item.origin.accountId == intent.namespace.account && item.origin.serverInstanceId == intent.namespace.server &&
                item.origin.syncEpoch == intent.namespace.epoch && item.origin.sourceHash == NextRequestSql.sourceHash(item.source)) { "COMMAND_PRODUCER_PAYLOAD_CHANGED" }
            require(NextRequestSql.rowHash(database, "sync_outbox", "id=?", arrayOf(item.source.id)) == item.origin.sourceHash) { "COMMAND_SOURCE_CHANGED" }
            require(item.origin.kind == NEXT_OPERATION && database.nextRequestDao().origin(item.origin.kind, item.origin.requestId) == item.origin &&
                decodeNextOperationIntent(item.origin.intentJson) == item.operation) { "COMMAND_ORIGIN_CHANGED" }
            require(NextRequestSql.rowHash(database, "next_request_origins", "kind=? AND requestId=?",
                arrayOf(item.origin.kind, item.origin.requestId)) == item.immutableOriginHash) { "COMMAND_ORIGIN_CHANGED" }
        }
        if (facts.isNotEmpty()) {
            val ids = requireNotNull(allocatedIds) { "COMMAND_INSERT_IDS_NOT_BOUND" }
            facts.forEachIndexed { i, fact -> requireStoredFact(sql, fact, ids[i]) }
        }
        val effects = facts.map { it.result }.ifEmpty { expected.filter { it.type == "activity_event" }.map { e ->
            CountCommandOutcome.Effect(CountCommandOutcome.FactKind.COUNT_REVERT, e.uuid,
                CountCommandRecords.uuid(intent.body().getValue("input").jsonObject, "habit_uuid"),
                CountCommandRecords.uuid(intent.body().getValue("input").jsonObject, "target_fact_uuid"))
        } }
        val primary = effects.firstOrNull()?.factUuid
        val primaryRequest = primary?.let { id -> actual.singleOrNull { it.operation.entityUuid == id }?.origin?.requestId }
        val retained = arrayListOf<CountCommandEvidence.Record>()
        facts.forEachIndexed { i, fact ->
            val fields = LinkedHashMap(fact.values)
            // The independently bound native REAL zero is the only SQLite normalization
            // permitted here; raw T0 negative-zero bits remain unchanged in the input hash.
            if (fact.table == "metric_logs" && (fields["value"] as? Double)?.toRawBits() == Long.MIN_VALUE) {
                sql.query("SELECT value FROM metric_logs WHERE id=? LIMIT 2", arrayOf(requireNotNull(allocatedIds)[i])).use { c ->
                    require(c.moveToFirst() && c.getType(0) == Cursor.FIELD_TYPE_FLOAT)
                    val zero = c.getDouble(0); require(zero.isFinite() && zero == 0.0 && !c.moveToNext()); fields["value"] = zero
                }
            }
            retained += CountCommandEvidence.Record.frozen(fact.table, CountCommandEvidence.Role.INSERTED_FACT,
                linkedMapOf<String, Any?>("id" to requireNotNull(allocatedIds)[i]) + fields)
        }
        if (intent.action == CountCommandRecords.Action.COUNT_UNDO && reason == null) {
            val frozen = intent.body().getValue("input").jsonObject.getValue("target_native").jsonObject
            val fields = frozen.mapValues { (_, value) -> if (value == JsonNull) null else value.jsonPrimitive.let {
                if (it.isString) it.content else requireNotNull(it.longOrNull)
            } }
            retained += CountCommandEvidence.Record.frozen("completions", CountCommandEvidence.Role.DELETED_FACT, fields)
        }
        actual.forEach { item -> retained += CountCommandEvidence.source(item.source); retained += CountCommandEvidence.origin(item.origin) }
        if (metricResolution != null) {
            require(actual.size == 1 && facts.size == 1 && metricBoundary != null)
            val item = actual.single(); val effect = effects.single(); val planned = expected.single()
            val source = SyncOutboxEntity(id = item.source.id, operationId = item.source.operationId, recordType = "metric_log",
                entityUuid = effect.factUuid, wireEntityUuid = effect.factUuid, action = "upsert",
                referenceUuid = effect.objectUuid, createdAt = item.source.createdAt)
            require(source.id > requireNotNull(metricWatermark) && source.createdAt % 1000L == 0L &&
                isContractUuid(source.operationId) && item.source == source) { "COMMAND_METRIC_SOURCE_SHAPE_CHANGED" }
            val operation = SyncV2Operation(source.operationId, "metric_observation", effect.factUuid, "upsert", null,
                storedIndependentWirePayload(sql, planned))
            require(item.operation == operation)
            val body = if (IndependentMetricInput.input(intent).getValue("round_profile").jsonPrimitive.boolean)
                com.dayforge.data.api.encodeSyncRequest(NextRoundOperationIntent.serializer(), NextRoundOperationIntent(1, operation,
                    com.dayforge.data.api.dto.ChallengeSourceContext(operation.operationId, null), requireNotNull(intent.namespace.device)))
            else com.dayforge.data.api.encodeSyncRequest(SyncV2Operation.serializer(), operation)
            val origin = NextRequestOriginEntity(NEXT_OPERATION, source.operationId, source.id, 5, intent.namespace.account,
                intent.namespace.server, intent.namespace.epoch, NextRequestSql.sourceHash(source), body.toString(Charsets.UTF_8))
            require(item.origin == origin) { "COMMAND_METRIC_ORIGIN_SHAPE_CHANGED" }
            val raw = CountTrustRows(sql)
            fun native(record: CountCommandEvidence.Record): CountTrustRows.Row {
                val table = raw.table(record.table)
                return CountTrustRows.Row(table, table.columns.map(record::cell))
            }
            val fact = native(retained.single { it.role == CountCommandEvidence.Role.INSERTED_FACT })
            independentEffects = LegacyCountEffectPlan.Bound(listOf(
                listOf(CountTrustAck.Effect("metric_logs", null, fact),
                    CountTrustAck.Effect("sync_outbox", null, native(CountCommandEvidence.source(source)))),
                listOf(CountTrustAck.Effect("next_request_origins", null, native(CountCommandEvidence.origin(origin))))))
            verifyIndependentAfterStage()
        }
        val profile = if (CountCommandLegacyInput.isLegacy(intent)) {
            require(reason != null && retained.isEmpty() && expected.isEmpty() && facts.isEmpty())
            CountCommandEvidence.Profile.LEGACY_V4
        } else CountCommandEvidence.Profile.FORMAL_V5
        val evidence = metricResolution?.let { CountCommandEvidence.independent(intent, it, retained) }
            ?: CountCommandEvidence.frozen(profile, intent, retained)
        val body = buildJsonObject {
            put("evidence", evidence.body())
            metricResolution?.let { put("metric_resolution", it) }
            put("format", intent.format); put("command_id", intent.commandId); put("input_hash", intent.inputHash)
            put("namespace_hash", intent.namespace.hash); put("action", intent.action.name)
            put("kind", if (reason == null) "APPLIED" else "NO_EFFECT")
            put("primary_fact_uuid", primary?.let(::JsonPrimitive) ?: JsonNull)
            put("primary_request_id", primaryRequest?.let(::JsonPrimitive) ?: JsonNull)
            put("reason", reason?.name?.let(::JsonPrimitive) ?: JsonNull); put("follow_up", followUp.name)
            put("effects", JsonArray(effects.map { e -> buildJsonObject {
                put("kind", e.factKind.name); put("fact_uuid", e.factUuid); put("object_uuid", e.objectUuid)
                put("reverts_fact_uuid", e.revertsFactUuid?.let(::JsonPrimitive) ?: JsonNull)
            } }))
            put("origins", JsonArray(actual.map { p -> buildJsonObject {
                put("request_id", p.origin.requestId); put("operation_id", p.operation.operationId); put("entity_type", p.operation.entityType)
                put("entity_uuid", p.operation.entityUuid); put("operation_type", p.operation.action); put("origin_hash", p.immutableOriginHash)
            } }))
        }
        val bytes = CountCommandRecords.canonical(body).toByteArray(Charsets.UTF_8)
        val context = currentCoroutineContext()
        return CountCommandOutcome.decode(bytes, bytes.size) { context.ensureActive() }
    }

    /** Complete independent 3-event journal, including effects of later command/root writes.
     * The original producer source/round/footer proofs remain separate and are not removed. */
    internal suspend fun verifyIndependentAfterStage() {
        if (metricResolution == null) return
        currentCoroutineContext().ensureActive()
        // Allocation is bound to the nested local-preparation Job; this footer belongs to
        // the outer producer Job frozen before BUSINESS. Neither capability may cross T1.
        require(currentCoroutineContext()[Job] === metricVerificationJob && database.inTransaction())
        val owner = requireNotNull(CountTrustRuntime.forDatabase(database))
        owner.requirePrimaryReadToken(database, lifetimeToken)
        owner.maintenance(database) {
            val effects = requireNotNull(independentEffects)
            effects.verifyActual(owner.primaryBusinessChanges(requireNotNull(metricBoundary)))
            val raw = CountTrustRows(database.openHelper.writableDatabase)
            effects.verifyFinal(effects.finalRows.mapValues { (_, row) -> requireNotNull(row).let { raw.byIdentity(it.table.name, it.identity) } })
        }
    }

    companion object {
        /** Called at the existing original point where CompletionEntity has been constructed,
         * before its insertion. The capture owner separately freezes any preceding reactivation.
         */
        suspend fun countAdd(database: HabitDatabase, intent: CountCommandRecords.Intent, habit: HabitEntity,
            fact: CompletionEntity, day: CountDayPolicy?, reactivationBase: Long?): CountCommandProducerPlan {
            require(intent.action == CountCommandRecords.Action.COUNT_ADD && intent.capture == CountCommandRecords.Capture.EXECUTION_INPUT && fact.id == 0L)
            val input = intent.body().getValue("input").jsonObject
            require(fact.uuid == CountCommandRecords.uuid(input, "reserved_fact_uuid") && habit.uuid == CountCommandRecords.uuid(input, "habit_uuid") &&
                fact.habitUuid == habit.uuid && fact.habitId == habit.id && habit.id == CountCommandRecords.integer(input, "habit_id") && fact.value.toLong() == CountCommandRecords.integer(input, "quantity") &&
                fact.actualCompletedAt == Instant.parse(CountCommandRecords.text(input, "occurred_at")).toEpochMilli() &&
                fact.recordedTimezone == CountCommandRecords.text(input, "timezone") && fact.recordedLocalDate == CountCommandRecords.text(input, "local_date"))
            require(fact.oneTimeAction == null && fact.oneTimeExpectedVersion == null && fact.oneTimeExpectedHeadEventUuid == null && fact.oneTimeRevertsEventUuid == null)
            require((habit.appearance == null) == (day == null))
            val body = SyncV2Mapper.completion(fact, habit)
            val payload = if (day == null) body else JsonObject(body + ("count_policy" to day.toJson()))
            val operations = arrayListOf(Expected("activity_event", fact.uuid, "upsert", null, CountCommandRecords.canonical(payload)))
            if (!habit.isActive) operations += Expected("plan_node", habit.uuid, "upsert", reactivationBase,
                CountCommandRecords.canonical(NextStructureMapper.writePlan(habit.copy(isActive = true))))
            return create(database, intent, operations, listOf(Fact("completions", fact.uuid, completion(fact),
                CountCommandOutcome.Effect(CountCommandOutcome.FactKind.COUNT_ADD, fact.uuid, habit.uuid, null))), null,
                CountCommandOutcome.FollowUp.RECHECK_ORIGINAL_RULES)
        }

        /** The DELETE producer captures zone at its original point and trigger time remains
         * the original source.createdAt. Only the private pre-reserved revert identity changes
         * where randomness originates; no UI/T0 time is substituted into the wire.
         */
        suspend fun undo(database: HabitDatabase, intent: CountCommandRecords.Intent, source: SyncOutboxEntity, capturedZone: ZoneId): CountCommandProducerPlan {
            require(intent.action == CountCommandRecords.Action.COUNT_UNDO && intent.capture == CountCommandRecords.Capture.EXECUTION_INPUT)
            val input = intent.body().getValue("input").jsonObject
            val target = CountCommandRecords.uuid(input, "target_fact_uuid"); val habit = CountCommandRecords.uuid(input, "habit_uuid")
            require(source.recordType == "completion" && source.action == "delete" && source.entityUuid == target && source.referenceUuid == habit && source.createdAt % 1000L == 0L)
            val time = Instant.ofEpochMilli(source.createdAt)
            val payload = buildJsonObject {
                put("activity_uuid", habit); put("event_type", "revert"); put("reverts_event_uuid", target)
                put("occurred_at", time.toString()); put("timezone", capturedZone.id); put("local_date", time.atZone(capturedZone).toLocalDate().toString()); put("source_type", "app")
            }
            return create(database, intent, listOf(Expected("activity_event", CountCommandRecords.uuid(input, "reserved_revert_uuid"),
                "upsert", null, CountCommandRecords.canonical(payload))), emptyList(), null, CountCommandOutcome.FollowUp.NONE)
        }

        suspend fun noEffect(database: HabitDatabase, intent: CountCommandRecords.Intent, reason: CountCommandRecords.NoEffectReason): CountCommandProducerPlan {
            require(intent.action != CountCommandRecords.Action.LINKED_METRICS)
            if (intent.capture == CountCommandRecords.Capture.VERIFIED_NO_EFFECT)
                require(CountCommandRecords.text(intent.body().getValue("input").jsonObject, "reason") == reason.name)
            else require(reason == if (intent.action == CountCommandRecords.Action.COUNT_ADD)
                CountCommandRecords.NoEffectReason.COUNTDOWN_COMPLETE else CountCommandRecords.NoEffectReason.TARGET_REMOVED_BEFORE_MUTATION)
            return create(database, intent, emptyList(), emptyList(), reason, CountCommandOutcome.FollowUp.NONE)
        }

        suspend fun independentMetric(database: HabitDatabase, intent: CountCommandRecords.Intent,
            logs: List<MetricLogEntity>, metrics: Map<Long, MetricEntity>,
            businessBoundary: CountTrustRuntime.PrimaryBusinessBoundary, verificationJob: Job?): CountCommandProducerPlan {
            val input = IndependentMetricInput.input(intent)
            require(!IndependentMetricInput.isLegacy(intent) && logs.size == 1)
            val log = logs.single(); val metric = metrics.getValue(log.metricId)
            require(log.id == 0L && log.metricId == CountCommandRecords.integer(input, "metric_id") &&
                metric.uuid == CountCommandRecords.uuid(input, "metric_uuid") &&
                log.uuid == CountCommandRecords.uuid(input, "reserved_fact_uuid") && log.unit == metric.unit &&
                log.value.toRawBits() == IndependentMetricInput.value(input).toRawBits() &&
                log.note == CountCommandRecords.text(input, "note") && log.date == CountCommandRecords.integer(input, "recorded_at") &&
                log.recordedTimezone == CountCommandRecords.text(input, "timezone"))
            val resolution = IndependentMetricInput.resolution(intent, metric)
            // Freeze both semantic input and original mapper order BEFORE insertion. The
            // canonical form is not a replacement for the immutable origin's wire bytes.
            val wirePayload = SyncV2Mapper.metricObservation(log, metric.uuid)
            val expected = Expected("metric_observation", log.uuid, "upsert", null,
                CountCommandRecords.canonical(wirePayload), log.uuid.takeIf { log.value.toRawBits() == Long.MIN_VALUE },
                wirePayload = wirePayload.toString())
            val fact = Fact("metric_logs", log.uuid, metricLog(log), CountCommandOutcome.Effect(
                CountCommandOutcome.FactKind.METRIC_OBSERVATION, log.uuid, metric.uuid, null))
            return create(database, intent, listOf(expected), listOf(fact), null, CountCommandOutcome.FollowUp.NONE,
                resolution, businessBoundary, verificationJob)
        }

        suspend fun metrics(database: HabitDatabase, intent: CountCommandRecords.Intent, logs: List<MetricLogEntity>, metrics: Map<Long, MetricEntity>): CountCommandProducerPlan {
            require(intent.action == CountCommandRecords.Action.LINKED_METRICS && logs.isNotEmpty())
            val captured = CountTrustKernel.frozenMetricInput(database, intent, logs, metrics)
            val input = captured?.input(intent) ?: intent.body().getValue("input").jsonObject
            val values = input.getValue("metrics").jsonArray.map { it.jsonObject }.associateBy { CountCommandRecords.uuid(it, "reserved_fact_uuid") }
            require(values.size == logs.size && logs.map { it.uuid }.toSet() == values.keys)
            val expected = arrayListOf<Expected>(); val facts = arrayListOf<Fact>()
            logs.forEach { log ->
                val original = values.getValue(log.uuid); val metric = captured?.metric(log.metricId) ?: metrics.getValue(log.metricId)
                require(log.id == 0L && log.metricId == CountCommandRecords.integer(original, "metric_id") &&
                    metric.uuid == CountCommandRecords.uuid(original, "metric_uuid") && log.unit == metric.unit &&
                    log.value.toRawBits().toULong().toString(16).padStart(16, '0') == CountCommandRecords.text(original, "value_bits") &&
                    log.note == CountCommandRecords.text(original, "note") && log.date == CountCommandRecords.integer(input, "recorded_at") &&
                    log.recordedTimezone == CountCommandRecords.text(input, "timezone"))
                expected += Expected("metric_observation", log.uuid, "upsert", null,
                    CountCommandRecords.canonical(captured?.observation(log, metric.uuid) ?: SyncV2Mapper.metricObservation(log, metric.uuid)),
                    log.uuid.takeIf { log.value.toRawBits() == Long.MIN_VALUE })
                facts += Fact("metric_logs", log.uuid, metricLog(log), CountCommandOutcome.Effect(CountCommandOutcome.FactKind.METRIC_OBSERVATION, log.uuid, metric.uuid, null))
            }
            return create(database, intent, expected, facts, null, CountCommandOutcome.FollowUp.NONE)
        }

        /** Only the original -0.0 input case has a native allocation normalization. Preserve
         * its raw T0 bits/hash; bind wire value to the exact REAL zero read by the old DAO path.
         * No rounding/tolerance/other numeric normalization is admitted here.
         */
        private fun storedPayload(sql: SupportSQLiteDatabase, expected: Expected): String {
            val uuid = expected.negativeZeroFact ?: return expected.payload
            require(expected.type == "metric_observation" && uuid == expected.uuid)
            val value = sql.query("SELECT typeof(value),value FROM metric_logs WHERE uuid=? LIMIT 2", arrayOf(uuid)).use { c ->
                require(c.moveToFirst() && c.getType(0) == Cursor.FIELD_TYPE_STRING && c.getString(0) == "real" &&
                    c.getType(1) == Cursor.FIELD_TYPE_FLOAT)
                c.getDouble(1).also { require(it.isFinite() && it == 0.0 && !c.moveToNext()) }
            }
            val planned = Json.parseToJsonElement(expected.payload).jsonObject
            require(planned.getValue("value").jsonPrimitive.double.toRawBits() == Long.MIN_VALUE)
            return CountCommandRecords.canonical(JsonObject(planned + ("value" to JsonPrimitive(value))))
        }

        /** All fields and their order come from the frozen pre-write mapper input. Only
         * the existing signed-zero rule may bind SQLite's exact REAL-zero representation;
         * neither an observed origin nor an observed operation supplies expected bytes.
         */
        private fun storedIndependentWirePayload(sql: SupportSQLiteDatabase, expected: Expected): JsonObject {
            require(expected.type == "metric_observation")
            val payload = Json.parseToJsonElement(requireNotNull(expected.wirePayload)).jsonObject
            require(CountCommandRecords.canonical(payload) == expected.payload) { "COMMAND_METRIC_WIRE_INPUT_CHANGED" }
            if (expected.negativeZeroFact == null) return payload
            val normalized = Json.parseToJsonElement(storedPayload(sql, expected)).jsonObject
            return buildJsonObject {
                for ((key, value) in payload) put(key, if (key == "value") normalized.getValue("value") else value)
            }
        }

        private suspend fun create(database: HabitDatabase, intent: CountCommandRecords.Intent, expected: List<Expected>, facts: List<Fact>,
            reason: CountCommandRecords.NoEffectReason?, followUp: CountCommandOutcome.FollowUp, metricResolution: JsonObject? = null,
            metricBoundary: CountTrustRuntime.PrimaryBusinessBoundary? = null, metricVerificationJob: Job? = null): CountCommandProducerPlan {
            require((metricResolution == null) == (metricBoundary == null)) { "COMMAND_METRIC_PREPARING_BOUNDARY_REQUIRED" }
            require(metricResolution != null || metricVerificationJob == null)
            val owner = requireNotNull(CountTrustRuntime.forDatabase(database))
            val caller = currentCoroutineContext()[Job]
            val token = owner.maintenance(database) { owner.capturePrimaryReadToken(database) }
            val watermark = if (metricResolution == null) null else NextRequestSql.watermark(database.openHelper.writableDatabase, "sync_outbox")
            return CountCommandProducerPlan(database, intent, expected.toList(), facts.toList(), reason, followUp, caller, token,
                metricResolution, metricBoundary, watermark, metricVerificationJob)
        }

        private fun requireStoredFact(sql: SupportSQLiteDatabase, fact: Fact, boundId: Long) {
            val fields = fact.values
            val ids = sql.query("SELECT id FROM `${fact.table}` WHERE uuid=? ORDER BY id LIMIT 2", arrayOf(fact.uuid)).use { c ->
                buildList { while (c.moveToNext()) { require(c.getType(0) == Cursor.FIELD_TYPE_INTEGER); add(c.getLong(0)) } }
            }
            require(ids == listOf(boundId)) { "COMMAND_INSERT_ID_CHANGED" }
            val args = arrayListOf<Any?>()
            val clauses = fields.map { (name, value) -> when (value) {
                null -> "`$name` IS NULL"
                is String -> { args += value; "typeof(`$name`)='text' AND CAST(`$name` AS BLOB)=CAST(? AS BLOB)" }
                is Long -> { args += value; "typeof(`$name`)='integer' AND `$name` IS ?" }
                is Double -> { require(value.isFinite()); args += value; "typeof(`$name`)='real' AND `$name` IS ?" }
                else -> error("COMMAND_EXPECTED_FACT_TYPE")
            } }.joinToString(" AND ")
            args += ids.single()
            sql.query("SELECT CASE WHEN $clauses THEN 1 ELSE 0 END FROM `${fact.table}` WHERE id=? LIMIT 2", args.toTypedArray()).use {
                require(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_INTEGER && it.getLong(0) == 1L && !it.moveToNext()) { "COMMAND_NATIVE_FACT_CHANGED" }
            }
        }
        /** Shared exact representation at the original target-selection and mutation points;
         * parsing this object does not replace original fact/source/authority validation.
         */
        internal fun requireSelectedNative(database: HabitDatabase, fact: CompletionEntity) {
            requireStoredFact(database.openHelper.writableDatabase, Fact("completions", fact.uuid, completion(fact),
                CountCommandOutcome.Effect(CountCommandOutcome.FactKind.COUNT_ADD, fact.uuid, requireNotNull(fact.habitUuid), null)), fact.id)
        }
        internal fun frozenCompletion(f: CompletionEntity): JsonObject = buildJsonObject {
            put("id", f.id)
            for ((key, value) in completion(f)) put(key, when (value) {
                null -> JsonNull
                is String -> JsonPrimitive(value)
                is Long -> JsonPrimitive(value)
                else -> error("COMMAND_COMPLETION_REPRESENTATION")
            })
        }
        internal fun restoredCompletion(value: JsonObject): CompletionEntity {
            CountCommandRecords.fields(value, "id", "habitId", "date", "value", "actualCompletedAt", "uuid", "habitUuid", "createdAt",
                "recordedTimezone", "recordedLocalDate", "timeMetadataSource", "oneTimeAction", "oneTimeExpectedVersion",
                "oneTimeExpectedHeadEventUuid", "oneTimeRevertsEventUuid")
            fun long(name: String) = CountCommandRecords.integer(value, name)
            fun nullableLong(name: String) = if (value.getValue(name) == JsonNull) null else long(name)
            fun text(name: String) = CountCommandRecords.text(value, name)
            fun nullableText(name: String) = if (value.getValue(name) == JsonNull) null else text(name)
            fun int(name: String) = long(name).also { require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) }.toInt()
            val result = CompletionEntity(id = long("id"), habitId = long("habitId"), date = long("date"), value = int("value"),
                actualCompletedAt = nullableLong("actualCompletedAt"), uuid = text("uuid"), habitUuid = nullableText("habitUuid"), createdAt = long("createdAt"),
                recordedTimezone = text("recordedTimezone"), recordedLocalDate = text("recordedLocalDate"), timeMetadataSource = text("timeMetadataSource"),
                oneTimeAction = nullableText("oneTimeAction"), oneTimeExpectedVersion = nullableLong("oneTimeExpectedVersion")?.also {
                    require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) }?.toInt(),
                oneTimeExpectedHeadEventUuid = nullableText("oneTimeExpectedHeadEventUuid"), oneTimeRevertsEventUuid = nullableText("oneTimeRevertsEventUuid"))
            require(result.id > 0 && result.habitId > 0 && CountCommandRecords.canonical(frozenCompletion(result)) == CountCommandRecords.canonical(value))
            return result
        }
        private fun completion(f: CompletionEntity): Map<String, Any?> = linkedMapOf("habitId" to f.habitId, "date" to f.date,
            "value" to f.value.toLong(), "actualCompletedAt" to f.actualCompletedAt, "uuid" to f.uuid, "habitUuid" to f.habitUuid,
            "createdAt" to f.createdAt, "recordedTimezone" to f.recordedTimezone, "recordedLocalDate" to f.recordedLocalDate,
            "timeMetadataSource" to f.timeMetadataSource, "oneTimeAction" to f.oneTimeAction, "oneTimeExpectedVersion" to f.oneTimeExpectedVersion?.toLong(),
            "oneTimeExpectedHeadEventUuid" to f.oneTimeExpectedHeadEventUuid, "oneTimeRevertsEventUuid" to f.oneTimeRevertsEventUuid)
        private fun metricLog(f: MetricLogEntity): Map<String, Any?> = linkedMapOf("metricId" to f.metricId, "date" to f.date, "value" to f.value,
            "unit" to f.unit, "note" to f.note, "uuid" to f.uuid, "createdAt" to f.createdAt, "updatedAt" to f.updatedAt,
            "recordedTimezone" to f.recordedTimezone, "recordedLocalDate" to f.recordedLocalDate, "timeMetadataSource" to f.timeMetadataSource)
    }
}
