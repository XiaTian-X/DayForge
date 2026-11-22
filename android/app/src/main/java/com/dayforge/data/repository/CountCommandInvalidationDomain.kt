package com.dayforge.data.repository

import android.database.Cursor
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalCoreWriteAccess
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitType
import com.dayforge.domain.service.AccountSessionCoordinator
import java.time.LocalDate
import kotlinx.serialization.json.*

/** Structured mismatch only after original native/domain validation. No exception is
 * interpreted as obsolescence, and no missing object or target is guessed to be deleted.
 * This observation is not NoEffects or permission to write a terminal Status. */
internal object CountCommandInvalidationDomain {
    enum class Reason { ORIGINAL_COUNT_PLAN_CHANGED, ORIGINAL_COUNT_ROUND_CHANGED }
    class Changed(val reason: Reason, val hash: String)
    suspend fun potentialChange(database: HabitDatabase, tokens: TokenManager, sessions: AccountSessionCoordinator,
        intent: CountCommandRecords.Intent, access: LocalCoreWriteAccess): Boolean =
        observe(database, tokens, sessions, intent, access, validateHistory = false) != null

    suspend fun validated(database: HabitDatabase, tokens: TokenManager, sessions: AccountSessionCoordinator,
        intent: CountCommandRecords.Intent, access: LocalCoreWriteAccess): Changed? =
        observe(database, tokens, sessions, intent, access, validateHistory = true)

    private suspend fun observe(database: HabitDatabase, tokens: TokenManager, sessions: AccountSessionCoordinator,
        intent: CountCommandRecords.Intent, access: LocalCoreWriteAccess, validateHistory: Boolean): Changed? {
        check(database.inTransaction())
        if (!CountCommandReauthentication.supportsCountInvalidation(intent) || intent.capture != CountCommandRecords.Capture.EXECUTION_INPUT) return null
        val input = intent.body().getValue("input").jsonObject
        val raw = CountTrustRows(database.openHelper.writableDatabase)
        val target = if (intent.action == CountCommandRecords.Action.COUNT_UNDO) {
            val frozen = input.getValue("target_native").jsonObject
            val row = readOne(database, raw, "completions", CountCommandRecords.integer(frozen, "id"))
                ?: return null // Preserve the original legal TARGET_REMOVED_BEFORE_MUTATION branch.
            if (CountCommandProducerPlan.frozenCompletion(row.completion()) != frozen) return null
            row
        } else null
        val native = readOne(database, raw, "habits", CountCommandRecords.integer(input, "habit_id")) ?: return null
        val habit = decode(native)
        val visible = database.habitDao().getVisibleHabitById(habit.id) ?: return null
        require(visible == habit) { "COMMAND_INVALIDATION_NATIVE_ACTIVITY_CHANGED" }
        if (habit.uuid != CountCommandRecords.uuid(input, "habit_uuid") || habit.habitType != HabitType.COUNTING) return null
        require(habit.activityRate in 0..100)
        val legacy = CountCommandLegacyInput.isLegacy(intent)
        val currentPlan: JsonObject
        val currentRound: JsonElement
        var acceptedSuccessor: JsonObject? = null
        if (legacy) {
            if (habit.appearance != null || input.getValue("identity_plan") !is JsonObject) return null
            NextChallengeStore(database).requirePlainInTransaction()
            currentPlan = requireNotNull(CountActionReader.legacyIdentity(habit, access).plan)
            // SyncV2Mapper samples the default zone. A zone change alone is not a stored
            // plan successor and this slice must not create a new date/zone invalidation policy.
            val oldZone = input.getValue("identity_plan").jsonObject.getValue("activity").jsonObject.getValue("timezone")
            if (currentPlan.getValue("activity").jsonObject.getValue("timezone") != oldZone) return null
            currentRound = JsonNull
        } else {
            if (habit.appearance == null || habit.completionPolicy != "recurring") return null
            currentPlan = NextStructureMapper.writePlan(habit) // Includes the original full structural decoder.
            val rounds = NextCoreLocalIntentStore(database, tokens, sessions).captureDisplayedRoundsInTransaction()
            currentRound = CountCommandInputs.round(rounds, habit.uuid)
            if (CountCommandRecords.canonical(input.getValue("round")) != CountCommandRecords.canonical(currentRound)) {
                acceptedSuccessor = acceptedRoundSuccessor(database, rounds, input.getValue("round"), habit.uuid, validateHistory) ?: return null
            }
            val today = if (intent.action == CountCommandRecords.Action.COUNT_ADD) LocalDate.parse(CountCommandRecords.text(input, "local_date"))
                else LocalDate.parse(CountCommandRecords.text(input.getValue("target_native").jsonObject, "recordedLocalDate"))
            if (validateHistory) RecurringCompletionEvidenceReader(database, tokens, sessions).readInTransaction(habit, today)
        }
        val originalPlan = input.getValue(if (legacy) "identity_plan" else "plan")
        val reason = if (acceptedSuccessor != null) Reason.ORIGINAL_COUNT_ROUND_CHANGED else {
            if (CountCommandRecords.canonical(originalPlan) == CountCommandRecords.canonical(currentPlan)) return null
            Reason.ORIGINAL_COUNT_PLAN_CHANGED
        }
        require(tokens.localCoreWriteAccess() == access) { "COMMAND_INVALIDATION_AUTHORITY_CHANGED" }
        return Changed(reason, CountCommandRecords.hash("generic-invalidation-domain", buildJsonObject {
            put("habit", native.value); put("target", target?.value ?: JsonNull)
            put("plan", currentPlan); put("round", currentRound); put("reason", reason.name)
            acceptedSuccessor?.let { put("accepted_successor", it) }
        }.toString()))
    }

    /** The original writer requires the exact displayed head. Only a strictly later accepted
     * head in the same fully validated retained chain makes that old selection obsolete.
     * Pending projections, profile changes and same-head ACK/metadata progress are not this
     * proof. The caller still performs full NoEffects and original retained-fact/birth audits;
     * Store revalidates this witness plus current Root/member/g/authority in its separate T1. */
    internal suspend fun acceptedRoundSuccessor(database: HabitDatabase, scope: NextRoundWriteScope?, original: JsonElement,
        activity: String, validateSources: Boolean): JsonObject? {
        if (scope == null) return null
        val frozen = original as? JsonObject ?: return null
        if (frozen["profile"] != JsonPrimitive("rounds/v1")) return null
        require(frozen.keys == setOf("profile", "head", "initial", "restart"))
        val head = frozen.getValue("head").takeUnless { it == JsonNull }?.let {
            Json.decodeFromJsonElement<com.dayforge.domain.model.ChallengeRoundHead>(it)
        } ?: return null
        require(head.activityUuid == activity)
        if (activity in scope.pendingInitials || activity in scope.pendingRestarts) return null
        val current = scope.completionRounds.head(activity) ?: return null
        if (current.generation <= head.generation || !scope.completionRounds.contains(activity, head)) return null
        // captureDisplayedRoundsInTransaction used the original native metadata/context/hash
        // and complete immutable-chain reader. Remote accepted records need no invented local
        // receipt; when local proof exists, retain the original complete restart ACK/Plan gate.
        val metadata = Json.decodeFromString<com.dayforge.data.api.dto.ChallengeMetadata>(scope.metadataJson)
        val records = metadata.checkpoints.single { it.head.activityUuid == activity }.records
        val localProofs = if (!validateSources) emptyList() else records.filter { it.head.generation > head.generation }.mapNotNull { record ->
            val operation = requireNotNull(record.restartOperationUuid)
            val origin = NextRequestSql.rowHash(database, "next_request_origins", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, operation))
            val materialized = NextRequestSql.rowHash(database, "next_restart_materializations", "operationId=?", arrayOf(operation))
            val receipt = NextRequestSql.rowHash(database, "next_acceptances", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, operation))
            val plan = NextRequestSql.rowHash(database, "next_restart_plan_proofs", "operationId=?", arrayOf(operation))
            if (origin == null && materialized == null && receipt == null && plan == null) null else {
                requireNotNull(origin) { "COMMAND_INVALIDATION_RESTART_SOURCE_REQUIRED" }
                val originalSource = NextRestartStore(database).original(scope.access, operation)
                require(originalSource.hash == origin && originalSource.value.head == record.head &&
                    originalSource.value.deviceId == record.sourceDeviceUuid)
                val accepted = NextRestartBindingStore(database).ready(scope.access, originalSource.reference)
                buildJsonObject { put("operation_id", operation); put("origin_hash", origin); put("accepted_plan_hash", accepted.second) }
            }
        }
        return buildJsonObject {
            put("old_head", frozen.getValue("head"))
            put("accepted_metadata", Json.parseToJsonElement(scope.metadataJson))
            if (validateSources) put("local_restart_proofs", JsonArray(localProofs))
        }
    }

    /** Exact original storage-reader row bound, before the selected native row is materialized.
     * This is a scheduling/resource preflight, not a schema or NoEffects proof. */
    internal fun readOne(database: HabitDatabase, raw: CountTrustRows, table: String, id: Long): CountTrustRows.Row? {
        require(table in setOf("habits", "completions"))
        val columns = raw.table(table).columns
        val length = columns.joinToString("+") { "COALESCE(length(CAST(`$it` AS BLOB)),0)" }
        val found = database.openHelper.writableDatabase.query("SELECT ($length) FROM main.`$table` WHERE id=? LIMIT 2", arrayOf(id)).use { c ->
            if (!c.moveToFirst()) false else {
                require(c.getType(0) == Cursor.FIELD_TYPE_INTEGER && c.getLong(0) in 0..(com.dayforge.data.api.SYNC_REQUEST_LIMIT + 4096L) && !c.moveToNext()) {
                    "SYNC_RECOVERY_INVALID_STORAGE"
                }
                true
            }
        }
        val rows = raw.read(table, "id=?", arrayOf(id))
        require(rows.size == if (found) 1 else 0) { "COMMAND_INVALIDATION_NATIVE_ACTIVITY_CHANGED" }
        return rows.singleOrNull()
    }

    /** Same complete native decoder used by the existing typed COUNT pure plan. */
    internal fun decode(row: CountTrustRows.Row): HabitEntity = with(row) {
        val codec = com.dayforge.data.local.entity.HabitTypeConverter()
        fun nullableInt(name: String) = nullableLong(name)?.also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }?.toInt()
        HabitEntity(id = long("id"), name = text("name"), description = text("description"),
            habitType = codec.toHabitType(text("habitType")), iconResId = int("iconResId"), colorHex = text("colorHex"),
            schedule = codec.toSchedule(text("schedule")), targetValue = int("targetValue"), isCountdown = bool("isCountdown"),
            isActive = bool("isActive"), uuid = text("uuid"), parentHabitId = nullableText("parentHabitId"),
            targetCycles = nullableInt("targetCycles"), failMode = codec.toFailMode(text("failMode")),
            goalSuccess = if (cell("goalSuccess").type == Cursor.FIELD_TYPE_NULL) null else bool("goalSuccess"),
            activityRate = int("activityRate"), activityRateUpdatedAt = long("activityRateUpdatedAt"),
            bestTime = nullableLong("bestTime"), createdAt = long("createdAt"), updatedAt = long("updatedAt"),
            completionPolicy = nullableText("completionPolicy"), oneTimeConfirmedVersion = nullableInt("oneTimeConfirmedVersion"),
            oneTimeConfirmedHeadEventUuid = nullableText("oneTimeConfirmedHeadEventUuid"),
            oneTimeConfirmedCompletionEventUuid = nullableText("oneTimeConfirmedCompletionEventUuid"),
            appearance = codec.toAppearance(nullableText("appearance")), planMetadata = codec.toPlanMetadata(nullableText("planMetadata")))
    }
}
