package com.dayforge.domain.model

import com.dayforge.data.local.entity.CompletionEntity
import java.time.LocalDate
import java.util.Collections

/** Owned detail inputs plus the same immutable summary used by consumers that need no rows. */
class CountHistory private constructor(
    val summary: CountSummary,
    val completions: List<CompletionEntity>
) {
    constructor(
        today: LocalDate,
        policies: Map<LocalDate, CountDayPolicy>,
        quantities: Map<LocalDate, Long>,
        todayPolicy: CountDayPolicy?,
        unknownDates: Set<LocalDate>,
        completions: List<CompletionEntity>,
        roundHead: ChallengeRoundHead? = null,
        actionIdentity: CountActionIdentity? = null
    ) : this(capture(today, policies, quantities, todayPolicy, unknownDates, completions,
        roundHead, actionIdentity))

    private constructor(captured: CountHistory) : this(captured.summary, captured.completions)

    val today: LocalDate get() = summary.today
    val policies: Map<LocalDate, CountDayPolicy> get() = summary.policies
    val quantities: Map<LocalDate, Long> get() = summary.quantities
    val todayPolicy: CountDayPolicy? get() = summary.todayPolicy
    val unknownDates: Set<LocalDate> get() = summary.unknownDates
    /** Proven displayed round; null on the unchanged plain profile. */
    val roundHead: ChallengeRoundHead? get() = summary.roundHead
    val actionIdentity: CountActionIdentity? get() = summary.actionIdentity
    val todayQuantity: Long get() = summary.todayQuantity
    val completedToday: Boolean get() = summary.completedToday
    val qualifiedDates: Set<LocalDate> get() = summary.qualifiedDates
    val firstDate: LocalDate? get() = summary.firstDate

    // Preserve the original data-class API. A copy always derives a new result from its inputs.
    fun copy(
        today: LocalDate = this.today,
        policies: Map<LocalDate, CountDayPolicy> = this.policies,
        quantities: Map<LocalDate, Long> = this.quantities,
        todayPolicy: CountDayPolicy? = this.todayPolicy,
        unknownDates: Set<LocalDate> = this.unknownDates,
        completions: List<CompletionEntity> = this.completions,
        roundHead: ChallengeRoundHead? = this.roundHead,
        actionIdentity: CountActionIdentity? = this.actionIdentity
    ): CountHistory = CountHistory(today, policies, quantities, todayPolicy, unknownDates,
        completions, roundHead, actionIdentity)

    operator fun component1() = today
    operator fun component2() = policies
    operator fun component3() = quantities
    operator fun component4() = todayPolicy
    operator fun component5() = unknownDates
    operator fun component6() = completions
    operator fun component7() = roundHead
    operator fun component8() = actionIdentity

    override fun equals(other: Any?): Boolean = other is CountHistory &&
        today == other.today && policies == other.policies && quantities == other.quantities &&
        todayPolicy == other.todayPolicy && unknownDates == other.unknownDates &&
        completions == other.completions && roundHead == other.roundHead && actionIdentity == other.actionIdentity

    override fun hashCode(): Int {
        var result = today.hashCode()
        result = 31 * result + policies.hashCode()
        result = 31 * result + quantities.hashCode()
        result = 31 * result + (todayPolicy?.hashCode() ?: 0)
        result = 31 * result + unknownDates.hashCode()
        result = 31 * result + completions.hashCode()
        result = 31 * result + (roundHead?.hashCode() ?: 0)
        return 31 * result + (actionIdentity?.hashCode() ?: 0)
    }

    override fun toString(): String = "CountHistory(today=$today, policies=$policies, " +
        "quantities=$quantities, todayPolicy=$todayPolicy, unknownDates=$unknownDates, " +
        "completions=$completions, roundHead=$roundHead, actionIdentity=$actionIdentity)"

    companion object {
        /** The caller supplies matching, already verified summary and fresh detail rows. */
        internal fun fromSummary(summary: CountSummary, completions: List<CompletionEntity>): CountHistory =
            CountHistory(summary, Collections.unmodifiableList(ArrayList(completions)))

        private fun capture(today: LocalDate, policies: Map<LocalDate, CountDayPolicy>,
            quantities: Map<LocalDate, Long>, todayPolicy: CountDayPolicy?, unknownDates: Set<LocalDate>,
            completions: List<CompletionEntity>, roundHead: ChallengeRoundHead?,
            actionIdentity: CountActionIdentity?): CountHistory {
            val ownedRows = Collections.unmodifiableList(ArrayList(completions))
            val latest = linkedMapOf<LocalDate, Long>()
            ownedRows.forEach { row ->
                val date = LocalDate.parse(row.recordedLocalDate)
                latest[date] = maxOf(latest[date] ?: Long.MIN_VALUE, row.id)
            }
            // The public constructor has always accepted quantities independently of detail rows.
            val values = CountSummary.Values.create(policies, quantities, unknownDates, latest)
            return CountHistory(CountSummary.fromValues(today, values, todayPolicy, roundHead,
                actionIdentity), ownedRows)
        }
    }
}
