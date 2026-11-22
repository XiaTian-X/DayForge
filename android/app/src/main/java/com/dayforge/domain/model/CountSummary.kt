package com.dayforge.domain.model

import java.time.LocalDate
import java.util.Collections

/** An immutable calculation result, not evidence of ownership or permission to display it. */
class CountSummary private constructor(
    val today: LocalDate,
    internal val values: Values,
    val todayPolicy: CountDayPolicy?,
    val roundHead: ChallengeRoundHead?,
    val actionIdentity: CountActionIdentity?
) {
    val policies: Map<LocalDate, CountDayPolicy> get() = values.policies
    val quantities: Map<LocalDate, Long> get() = values.quantities
    val unknownDates: Set<LocalDate> get() = values.unknownDates
    val qualifiedDates: Set<LocalDate> get() = values.qualifiedDates
    val firstDate: LocalDate? get() = values.firstDate
    val totalQuantity: Long get() = values.totalQuantity
    val participatingDates: Set<LocalDate> get() = values.participatingDates
    val todayLatestCompletionId: Long? get() = values.latestIds[today]
    val todayQuantity: Long get() = quantities[today] ?: 0L
    val completedToday: Boolean get() = todayPolicy?.let { todayQuantity >= it.targetValue } == true

    override fun equals(other: Any?): Boolean = other is CountSummary &&
        today == other.today && policies == other.policies && quantities == other.quantities &&
        todayPolicy == other.todayPolicy && unknownDates == other.unknownDates &&
        values.latestIds == other.values.latestIds && roundHead == other.roundHead &&
        actionIdentity == other.actionIdentity

    override fun hashCode(): Int {
        var result = today.hashCode()
        result = 31 * result + policies.hashCode()
        result = 31 * result + quantities.hashCode()
        result = 31 * result + (todayPolicy?.hashCode() ?: 0)
        result = 31 * result + unknownDates.hashCode()
        result = 31 * result + values.latestIds.hashCode()
        result = 31 * result + (roundHead?.hashCode() ?: 0)
        return 31 * result + (actionIdentity?.hashCode() ?: 0)
    }

    override fun toString(): String = "CountSummary(today=$today, quantities=$quantities, " +
        "policies=$policies, todayPolicy=$todayPolicy, unknownDates=$unknownDates, " +
        "totalQuantity=$totalQuantity, participatingDates=$participatingDates, " +
        "todayLatestCompletionId=$todayLatestCompletionId, roundHead=$roundHead, actionIdentity=$actionIdentity)"

    /** Only arithmetic inputs/results live here. Fresh view provenance is supplied separately. */
    internal class Values private constructor(
        val policies: Map<LocalDate, CountDayPolicy>,
        val quantities: Map<LocalDate, Long>,
        val unknownDates: Set<LocalDate>,
        val qualifiedDates: Set<LocalDate>,
        val firstDate: LocalDate?,
        calculateTotal: () -> Long,
        val participatingDates: Set<LocalDate>,
        val latestIds: Map<LocalDate, Long>
    ) {
        // The old public History API accepts independent Long quantities. A synthetic aggregate
        // may exceed Long even though each day is representable; only this new total accessor fails.
        // The calculation closes over owned immutable data, never a caller's mutable input map.
        val totalQuantity: Long by lazy(LazyThreadSafetyMode.PUBLICATION, calculateTotal)

        companion object {
            fun create(
                policies: Map<LocalDate, CountDayPolicy>,
                quantities: Map<LocalDate, Long>,
                unknownDates: Set<LocalDate>,
                latestIds: Map<LocalDate, Long>
            ): Values {
                val ownedPolicies = immutableMap(policies)
                val ownedQuantities = immutableMap(quantities)
                val ownedLatest = immutableMap(latestIds)
                return Values(
                    ownedPolicies, ownedQuantities, immutableSet(unknownDates),
                    immutableSet(ownedQuantities.keys.filterTo(linkedSetOf()) { date ->
                        ownedPolicies[date]?.let { ownedQuantities.getValue(date) >= it.targetValue } == true
                    }),
                    ownedQuantities.keys.minOrNull(),
                    { ownedQuantities.values.fold(0L, Math::addExact) },
                    immutableSet(ownedLatest.keys), ownedLatest
                )
            }

            /** Combine disjoint month results without requalifying their individual days. */
            fun combine(parts: Collection<Values>): Values {
                val ownedParts = parts.toList()
                val policies = linkedMapOf<LocalDate, CountDayPolicy>()
                val quantities = linkedMapOf<LocalDate, Long>()
                val unknown = linkedSetOf<LocalDate>()
                val qualified = linkedSetOf<LocalDate>()
                val participating = linkedSetOf<LocalDate>()
                val latest = linkedMapOf<LocalDate, Long>()
                var first: LocalDate? = null
                ownedParts.forEach { part ->
                    check(part.policies.keys.none(policies::containsKey))
                    check(part.quantities.keys.none(quantities::containsKey))
                    check(part.latestIds.keys.none(latest::containsKey))
                    policies.putAll(part.policies)
                    quantities.putAll(part.quantities)
                    unknown.addAll(part.unknownDates)
                    qualified.addAll(part.qualifiedDates)
                    participating.addAll(part.participatingDates)
                    latest.putAll(part.latestIds)
                    part.firstDate?.let { candidate ->
                        if (first?.let { candidate < it } != false) first = candidate
                    }
                }
                return Values(immutableMap(policies), immutableMap(quantities), immutableSet(unknown),
                    immutableSet(qualified), first,
                    { ownedParts.fold(0L) { total, part -> Math.addExact(total, part.totalQuantity) } },
                    immutableSet(participating), immutableMap(latest))
            }

            private fun <K, V> immutableMap(input: Map<K, V>): Map<K, V> =
                Collections.unmodifiableMap(LinkedHashMap(input))

            private fun <T> immutableSet(input: Set<T>): Set<T> =
                Collections.unmodifiableSet(LinkedHashSet(input))
        }
    }

    internal companion object {
        fun fromValues(today: LocalDate, values: Values, todayPolicy: CountDayPolicy?,
            roundHead: ChallengeRoundHead?, actionIdentity: CountActionIdentity?): CountSummary =
            CountSummary(today, values, todayPolicy, roundHead, actionIdentity)
    }
}
