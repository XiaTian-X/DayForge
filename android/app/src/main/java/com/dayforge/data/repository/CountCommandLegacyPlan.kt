package com.dayforge.data.repository

import android.database.Cursor
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.entity.CompletionEntity
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Private legacy T1 frame. The primary boundary is captured before first business DML.
 * Expected OLD/NEW values come from frozen input and original scalar allocation hooks;
 * the actual shared journal is only checked against that independently constructed plan.
 */
internal class CountCommandLegacyPlan private constructor(
    private val database: HabitDatabase, val intent: CountCommandRecords.Intent,
    private val caller: Job?, private val token: Any,
    private val boundary: CountTrustRuntime.PrimaryBusinessBoundary,
    private val plan: LegacyCountEffectPlan?, private val watermark: Long,
    private val selectedTarget: CompletionEntity?
) {
    private val owner get() = requireNotNull(CountTrustRuntime.forDatabase(database))
    private val input = intent.body().getValue("input").jsonObject
    private var activation: Long? = null
    private var fact: CompletionEntity? = null
    private var inserted: Long? = null
    private var rate: LegacyCountEffectPlan.RateAllocation? = null
    private var noEffect: CountCommandRecords.NoEffectReason? = null
    private var bound: LegacyCountEffectPlan.Bound? = null
    private var prepared = false
    private var closed = false
    val reservedFactUuid: String? get() = if (intent.action == CountCommandRecords.Action.COUNT_ADD)
        CountCommandRecords.uuid(input, "reserved_fact_uuid") else null

    private suspend fun live() {
        currentCoroutineContext().ensureActive()
        check(!closed && database.inTransaction() && currentCoroutineContext()[Job] === caller) { "COMMAND_LEGACY_FRAME_EXPIRED" }
        owner.requirePrimaryReadToken(database, token)
    }
    suspend fun activation(at: Long) {
        live(); check(!prepared && activation == null && requireNotNull(plan).requiresActivation)
        activation = at
    }
    suspend fun constructed(value: CompletionEntity) {
        live(); check(!prepared && fact == null && noEffect == null && intent.action == CountCommandRecords.Action.COUNT_ADD)
        require(value == requireNotNull(plan).completionAtOriginalConstruction(value.createdAt)) { "COMMAND_LEGACY_FACT_INPUT_CHANGED" }
        fact = value.copy()
    }
    suspend fun inserted(id: Long) { live(); check(!prepared && fact != null && inserted == null && id > 0); inserted = id }
    suspend fun selected(value: CompletionEntity) {
        live(); check(!prepared && intent.action == CountCommandRecords.Action.COUNT_UNDO && value == selectedTarget) {
            "COMMAND_LEGACY_TARGET_CHANGED"
        }
    }
    suspend fun rate(value: Int, at: Long) {
        live(); check(!prepared && noEffect == null && rate == null)
        rate = LegacyCountEffectPlan.RateAllocation(value, at)
    }
    suspend fun noEffect(reason: CountCommandRecords.NoEffectReason) {
        live(); check(!prepared && noEffect == null && fact == null && inserted == null && activation == null && rate == null)
        require(reason == if (intent.action == CountCommandRecords.Action.COUNT_ADD)
            CountCommandRecords.NoEffectReason.COUNTDOWN_COMPLETE else if (CountCommandLegacyWidgetInput.matches(intent))
            CountCommandRecords.NoEffectReason.NO_CURRENT_COUNT else CountCommandRecords.NoEffectReason.TARGET_REMOVED_BEFORE_MUTATION)
        if (intent.action == CountCommandRecords.Action.COUNT_UNDO) require(selectedTarget == null)
        noEffect = reason
    }

    /** The only factory of a legacy Proven. The actual signed result is independently
     * checked before staging, then the same entire business boundary is checked after stage.
     */
    suspend fun proven(): CountCommandProducerPlan.Proven = CountCommandProducerPlan.Proven.verifiedLegacy(this)

    internal suspend fun verifyAndResult(): CountCommandOutcome {
        live(); check(!prepared); prepared = true
        val expected = if (noEffect == null) requireNotNull(plan).bind(fact, inserted, activation, allocations(), requireNotNull(rate)) else null
        bound = expected
        verifyAfterStage()
        val records = arrayListOf<CountCommandEvidence.Record>()
        if (expected != null) for (effect in expected.effects) {
            val row = when (effect.table) {
                "completions" -> effect.after ?: requireNotNull(effect.before)
                "sync_outbox" -> requireNotNull(effect.after)
                else -> continue
            }
            val role = when (effect.table) {
                "sync_outbox" -> CountCommandEvidence.Role.ORIGINAL_SOURCE
                else -> if (effect.after == null) CountCommandEvidence.Role.DELETED_FACT else CountCommandEvidence.Role.INSERTED_FACT
            }
            records += CountCommandEvidence.Record.frozen(effect.table, role, row.table.columns.associateWith { name ->
                val cell = row.cell(name)
                when (cell.type) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_INTEGER -> cell.value as Long
                    Cursor.FIELD_TYPE_STRING -> cell.value as String
                    else -> error("COMMAND_LEGACY_EVIDENCE_NATIVE_TYPE")
                }
            })
        }
        val factRecord = records.singleOrNull { it.table == "completions" }
        val source = records.singleOrNull { it.table == "sync_outbox" && it.text("recordType") == "completion" }
        val added = intent.action == CountCommandRecords.Action.COUNT_ADD
        val evidence = CountCommandEvidence.frozen(CountCommandEvidence.Profile.LEGACY_V4, intent, records)
        val body = buildJsonObject {
            put("format", CountCommandRecords.FORMAT); put("command_id", intent.commandId); put("input_hash", intent.inputHash)
            put("namespace_hash", intent.namespace.hash); put("action", intent.action.name)
            put("kind", if (noEffect == null) "APPLIED" else "NO_EFFECT")
            put("primary_fact_uuid", factRecord?.text("uuid")?.let(::JsonPrimitive) ?: JsonNull)
            put("primary_request_id", source?.text("operationId")?.let(::JsonPrimitive) ?: JsonNull)
            put("reason", noEffect?.name?.let(::JsonPrimitive) ?: JsonNull)
            put("follow_up", if (noEffect == null && added) "RECHECK_ORIGINAL_RULES" else "NONE")
            put("effects", JsonArray(listOfNotNull(factRecord?.let { record -> buildJsonObject {
                put("kind", if (added) "COUNT_ADD" else "LOCAL_COUNT_DELETE"); put("fact_uuid", record.text("uuid"))
                put("object_uuid", CountCommandRecords.uuid(input, "habit_uuid")); put("reverts_fact_uuid", JsonNull)
            } })))
            put("origins", JsonArray(emptyList())); put("evidence", evidence.body())
        }
        val bytes = CountCommandRecords.canonical(body).toByteArray(Charsets.UTF_8)
        val context = currentCoroutineContext()
        return CountCommandOutcome.decode(bytes, bytes.size) { context.ensureActive() }.also {
            CountCommandLegacyEvidence.verify(intent, it)
        }
    }

    suspend fun verifyAfterStage() {
        live(); check(prepared)
        owner.maintenance(database) {
            val actual = owner.primaryBusinessChanges(boundary)
            val expected = bound
            if (expected == null) {
                check(noEffect != null && actual.isEmpty()) { "COMMAND_LEGACY_NO_EFFECT_WROTE_BUSINESS" }
            } else {
                expected.verifyActual(actual)
                val native = CountTrustRows(database.openHelper.writableDatabase)
                expected.verifyFinal(expected.finalRows.mapValues { (name, after) ->
                    val identity = after ?: requireNotNull(expected.effects.first { it.before?.name == name }.before)
                    native.byIdentity(identity.table.name, identity.identity)
                })
            }
        }
    }

    private suspend fun allocations(): List<LegacyCountEffectPlan.SourceAllocation> {
        live()
        val expected = if (requireNotNull(plan).requiresActivation) 2 else 1
        val sql = database.openHelper.writableDatabase
        sql.query("SELECT typeof(id),typeof(operationId),length(CAST(operationId AS BLOB)),typeof(createdAt) " +
            "FROM sync_outbox WHERE id>? ORDER BY id LIMIT ?", arrayOf<Any>(watermark, expected + 1)).use { c ->
            var rows = 0
            while (c.moveToNext()) {
                rows++
                require(c.getString(0) == "integer" && c.getString(1) == "text" && c.getLong(2) in 36..144 && c.getString(3) == "integer") {
                    "COMMAND_LEGACY_ALLOCATION_TYPE"
                }
            }
            require(rows == expected) { "COMMAND_LEGACY_SOURCE_ALLOCATION_COUNT" }
        }
        return sql.query("SELECT id,operationId,createdAt FROM sync_outbox WHERE id>? ORDER BY id LIMIT ?",
            arrayOf<Any>(watermark, expected + 1)).use { c -> buildList {
            while (c.moveToNext()) add(LegacyCountEffectPlan.SourceAllocation(c.getLong(0), c.getString(1), c.getLong(2)))
        } }
    }

    internal suspend fun databaseForProof(): HabitDatabase { live(); check(prepared); return database }
    fun close() { closed = true }
    companion object {
        /** Same primary journal and independent OLD/NEW algorithm; only the original Widget
         * clock/selection coordinates are allocated here, before any business write.
         */
        suspend fun captureWidget(database: HabitDatabase, intent: CountCommandRecords.Intent,
            at: Instant, zone: ZoneId, selected: CompletionEntity?, added: Boolean): CountCommandLegacyPlan {
            require(CountCommandLegacyWidgetInput.matches(intent))
            val input = intent.body().getValue("input").jsonObject
            val claim = CountCommandLegacyWidgetInput.claim(input)
            require(added == (intent.action == CountCommandRecords.Action.COUNT_ADD) && zone.id == claim.timezone &&
                at.atZone(zone).toLocalDate().toString() == claim.date) { "COMMAND_LEGACY_WIDGET_SELECTION_CHANGED" }
            if (!added) require(selected?.uuid == claim.completionUuid) { "LEGACY_WIDGET_FACT_CHANGED" }
            val owner = requireNotNull(CountTrustRuntime.forDatabase(database)); val caller = currentCoroutineContext()[Job]
            return owner.maintenance(database) {
                val token = owner.capturePrimaryReadToken(database)
                val boundary = owner.capturePrimaryBusinessBoundary()
                val sql = database.openHelper.writableDatabase; val raw = CountTrustRows(sql)
                val habit = raw.read("habits", "id=?", arrayOf(claim.identity.habitId)).single()
                require(habit.text("uuid") == claim.identity.habitUuid)
                val watermark = NextRequestSql.watermark(sql, "sync_outbox")
                val actual = if (added) null else selected?.let { raw.read("completions", "id=?", arrayOf(it.id)).single() }
                if (actual != null) require(actual.completion() == selected) { "LEGACY_WIDGET_FACT_CHANGED" }
                val plan = if (added) LegacyCountEffectPlan.add(habit, LegacyCountEffectPlan.Action.Add(
                    CountCommandRecords.uuid(input, "reserved_fact_uuid"), at, zone, 1),
                    raw.table("completions"), raw.table("sync_outbox"), watermark)
                else actual?.let { LegacyCountEffectPlan.delete(habit, it, raw.table("sync_outbox"), watermark) }
                CountCommandLegacyPlan(database, intent, caller, token, boundary, plan, watermark, actual?.completion())
            }
        }

        suspend fun capture(database: HabitDatabase, intent: CountCommandRecords.Intent): CountCommandLegacyPlan {
            require(CountCommandLegacyInput.isLegacy(intent))
            val owner = requireNotNull(CountTrustRuntime.forDatabase(database)); val caller = currentCoroutineContext()[Job]
            return owner.maintenance(database) {
                val token = owner.capturePrimaryReadToken(database)
                val boundary = owner.capturePrimaryBusinessBoundary()
                val sql = database.openHelper.writableDatabase; val raw = CountTrustRows(sql)
                val input = intent.body().getValue("input").jsonObject
                val habit = raw.read("habits", "id=?", arrayOf(CountCommandRecords.integer(input, "habit_id"))).single()
                require(habit.text("uuid") == CountCommandRecords.uuid(input, "habit_uuid"))
                val watermark = NextRequestSql.watermark(sql, "sync_outbox")
                val target = if (intent.action == CountCommandRecords.Action.COUNT_UNDO)
                    CountCommandProducerPlan.restoredCompletion(input.getValue("target_native").jsonObject) else null
                val actual = target?.let { raw.read("completions", "id=?", arrayOf(it.id)).singleOrNull() }
                if (actual != null) require(actual.completion() == target) { "OBJECT_WRITE_FACT_CHANGED" }
                val plan = if (intent.action == CountCommandRecords.Action.COUNT_ADD)
                    LegacyCountEffectPlan.add(habit, LegacyCountEffectPlan.Action.Add(CountCommandRecords.uuid(input, "reserved_fact_uuid"),
                        Instant.parse(CountCommandRecords.text(input, "occurred_at")), ZoneId.of(CountCommandRecords.text(input, "timezone")),
                        CountCommandRecords.integer(input, "quantity").toInt()), raw.table("completions"), raw.table("sync_outbox"), watermark)
                else actual?.let { LegacyCountEffectPlan.delete(habit, it, raw.table("sync_outbox"), watermark) }
                CountCommandLegacyPlan(database, intent, caller, token, boundary, plan, watermark, actual?.completion())
            }
        }
    }
}
