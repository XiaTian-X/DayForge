package com.dayforge.data.repository

import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalCoreWriteAccess
import com.dayforge.data.local.TokenManager
import com.dayforge.data.model.HabitType
import com.dayforge.domain.service.AccountSessionCoordinator
import java.time.LocalDate
import kotlinx.serialization.json.*

/** Original-domain classification only, never permission to close a pending obligation.
 * A retained formal fact requires a complete accepted round successor; a removed fact
 * requires the separate positive original/revert witness. Legacy prompts retain their
 * original habit-only rule; no exception or absence is interpreted as obsolescence. */
internal object CountCommandObsoleteFollowUp {
    enum class Reason { FORMAL_ACCEPTED_ROUND_SUCCESSOR, FORMAL_ORIGINAL_COUNT_REVERTED, FORMAL_ACTIVITY_DELETE_ACCEPTED }
    class Checked(val reason: Reason, val hash: String)

    suspend fun read(database: HabitDatabase, tokens: TokenManager, sessions: AccountSessionCoordinator,
        intent: CountCommandRecords.Intent, outcome: CountCommandOutcome, access: LocalCoreWriteAccess): Checked? {
        check(database.inTransaction())
        if (CountCommandLegacyInput.isLegacy(intent) || intent.action != CountCommandRecords.Action.COUNT_ADD ||
            intent.capture != CountCommandRecords.Capture.EXECUTION_INPUT ||
            outcome.kind != CountCommandOutcome.Kind.APPLIED ||
            outcome.followUp != CountCommandOutcome.FollowUp.RECHECK_ORIGINAL_RULES) return null
        outcome.requireIntent(intent)
        require(outcome.evidence.profile == CountCommandEvidence.Profile.FORMAL_V5)
        val input = intent.body().getValue("input").jsonObject
        val habitUuid = CountCommandRecords.uuid(input, "habit_uuid")
        val factUuid = requireNotNull(outcome.primaryFactUuid)
        val raw = CountTrustRows(database.openHelper.writableDatabase)
        val native = CountCommandInvalidationDomain.readOne(database, raw, "habits", CountCommandRecords.integer(input, "habit_id"))
            ?: return CountCommandDeletedFollowUp.read(database, tokens, intent, outcome, access)?.let {
                Checked(Reason.FORMAL_ACTIVITY_DELETE_ACCEPTED, it)
            }
        val habit = CountCommandInvalidationDomain.decode(native)
        if (habit.uuid != habitUuid || habit.habitType != HabitType.COUNTING || habit.appearance == null ||
            habit.completionPolicy != "recurring") return null
        require(database.habitDao().getVisibleHabitById(habit.id) == habit) { "COMMAND_FOLLOWUP_NATIVE_ACTIVITY_CHANGED" }
        val rounds = NextCoreLocalIntentStore(database, tokens, sessions).captureDisplayedRoundsInTransaction()
        val successor = CountCommandInvalidationDomain.acceptedRoundSuccessor(database, rounds,
            input.getValue("round"), habitUuid, validateSources = true)
        val frozen = outcome.evidence.records.single { it.role == CountCommandEvidence.Role.INSERTED_FACT && it.text("uuid") == factUuid }
        require(frozen.table == "completions" && frozen.nullableText("habitUuid") == habitUuid && frozen.number("habitId") == habit.id)
        val fact = CountCommandInvalidationDomain.readOne(database, raw, "completions", frozen.number("id"))
        if (fact == null) {
            val reverted = CountCommandRevertedFollowUp.read(database, tokens, sessions, intent, outcome,
                access, habit, native, frozen, rounds) ?: return null
            return Checked(Reason.FORMAL_ORIGINAL_COUNT_REVERTED, reverted)
        }
        if (successor == null) return null
        require(fact.text("uuid") == factUuid && fact.long("habitId") == habit.id && fact.nullableText("habitUuid") == habitUuid) {
            "COMMAND_FOLLOWUP_ORIGINAL_FACT_CHANGED"
        }
        val origin = outcome.evidence.records.single { it.role == CountCommandEvidence.Role.ORIGINAL_ORIGIN &&
            outcome.origins.single { origin -> origin.entityUuid == factUuid }.requestId == it.text("requestId") }
        val original = requireNotNull(roundOperationIntent(origin.text("intentJson")))
        require(original.operation.entityUuid == factUuid && original.context.head != null &&
            Json.encodeToJsonElement(requireNotNull(original.context.head)) == successor.getValue("old_head")) {
            "COMMAND_FOLLOWUP_ORIGINAL_ROUND_CHANGED"
        }
        // Complete retained native facts, day policy, source identity, accepted birth and
        // round chain are verified by the original recovery reader, not by a head comparison.
        val history = RecurringCompletionEvidenceReader(database, tokens, sessions).recoveryInTransaction(habit,
            LocalDate.parse(frozen.text("recordedLocalDate")))
        val currentFact = fact.completion()
        require(history.completions.singleOrNull { it.uuid == factUuid } == currentFact) { "COMMAND_FOLLOWUP_ORIGINAL_FACT_CHANGED" }
        // ACK legitimately supplies server created_at. Preserve that existing mapper rule:
        // bind the immutable original business fields separately, while the complete reader
        // above validates EVERY current native field against the actual accepted snapshot.
        CountFactEvidenceRules.verifyPendingFields(habitUuid, currentFact.value, currentFact.actualCompletedAt,
            currentFact.date, currentFact.recordedLocalDate, currentFact.recordedTimezone,
            history.policies[LocalDate.parse(currentFact.recordedLocalDate)], original.operation.payload)
        require(tokens.localCoreWriteAccess() == access) { "COMMAND_FOLLOWUP_AUTHORITY_CHANGED" }
        return Checked(Reason.FORMAL_ACCEPTED_ROUND_SUCCESSOR, CountCommandRecords.hash("obsolete-followup-domain", buildJsonObject {
            put("habit", native.value); put("fact", fact.value); put("original", origin.body()); put("successor", successor)
        }.toString()))
    }
}
