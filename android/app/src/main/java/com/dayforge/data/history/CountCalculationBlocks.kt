package com.dayforge.data.history

import com.dayforge.domain.model.ChallengeRoundHead
import com.dayforge.domain.model.CountActionIdentity
import com.dayforge.domain.model.CountDayPolicy
import com.dayforge.domain.model.CountSummary
import java.time.LocalDate
import java.time.YearMonth
import java.util.Collections

/** Pure arithmetic after the caller's complete evidence check. This state grants no trust. */
internal object CountCalculationBlocks {
    data class Fact(val uuid: String, val id: Long, val date: LocalDate, val quantity: Long)

    data class Stats(
        val inputFacts: Int,
        val reusedBlocks: Int,
        val changedBlocks: Int,
        val addedContributions: Int,
        val removedContributions: Int,
        val sealedReusedBlocks: Int = 0,
        /** Changed current or future months; historical corrections remain changedBlocks too. */
        val activeUpdatedBlocks: Int = 0
    )

    data class Result(val state: State, val stats: Stats)

    class State private constructor(
        private val blocks: Map<YearMonth, Block>,
        private val values: CountSummary.Values,
        val factCount: Int
    ) {
        /** Identity, permissions and the current day rule are never retained in arithmetic state. */
        fun summary(today: LocalDate, todayPolicy: CountDayPolicy?, roundHead: ChallengeRoundHead?,
            actionIdentity: CountActionIdentity?): CountSummary =
            CountSummary.fromValues(today, values, todayPolicy, roundHead, actionIdentity)

        internal fun blockIdentity(month: YearMonth): Any? = blocks[month]

        internal companion object {
            fun reconcile(previous: State?, facts: List<Fact>, policies: Map<LocalDate, CountDayPolicy>,
                unknownDates: Set<LocalDate>, today: LocalDate): Result {
                // Input membership is deliberately checked in full: this is O(n), not a delta proof.
                val ids = hashSetOf<Long>()
                val uuids = hashSetOf<String>()
                val factsByMonth = linkedMapOf<YearMonth, MutableMap<String, Fact>>()
                facts.forEach { fact ->
                    require(fact.uuid.isNotBlank() && fact.quantity > 0)
                    require(ids.add(fact.id) && uuids.add(fact.uuid)) { "Duplicate count fact identity" }
                    factsByMonth.getOrPut(YearMonth.from(fact.date)) { linkedMapOf() }[fact.uuid] = fact
                }
                val ownedPolicies = LinkedHashMap(policies)
                val policiesByMonth = ownedPolicies.entries.groupBy { YearMonth.from(it.key) }
                val unknownByMonth = LinkedHashSet(unknownDates).groupBy(YearMonth::from)
                val months = sortedSetOf<YearMonth>().apply {
                    addAll(previous?.blocks?.keys.orEmpty())
                    addAll(factsByMonth.keys)
                    addAll(policiesByMonth.keys)
                    addAll(unknownByMonth.keys)
                }
                val currentMonth = YearMonth.from(today)
                val blocks = linkedMapOf<YearMonth, Block>()
                var reused = 0
                var changed = 0
                var added = 0
                var removed = 0
                var sealedReused = 0
                var activeUpdated = 0
                months.forEach { month ->
                    val old = previous?.blocks?.get(month)
                    val nextFacts = factsByMonth[month].orEmpty()
                    val nextPolicies = policiesByMonth[month].orEmpty().associate { it.key to it.value }
                    val nextUnknown = unknownByMonth[month].orEmpty().toSet()
                    if (old != null && old.facts == nextFacts && old.values.policies == nextPolicies &&
                        old.values.unknownDates == nextUnknown) {
                        blocks[month] = old
                        reused++
                        if (month < currentMonth) sealedReused++
                    } else {
                        changed++
                        if (month >= currentMonth) activeUpdated++
                        // Start from the last result, remove complete old contributions, then add new ones.
                        // All mutations are private to this candidate, including on arithmetic failure.
                        val quantities = old?.values?.quantities?.toMutableMap() ?: linkedMapOf()
                        old?.facts?.values?.forEach { fact ->
                            if (nextFacts[fact.uuid] != fact) {
                                val remaining = Math.subtractExact(quantities.getValue(fact.date), fact.quantity)
                                check(remaining >= 0)
                                if (remaining == 0L) quantities.remove(fact.date) else quantities[fact.date] = remaining
                                removed++
                            }
                        }
                        nextFacts.values.forEach { fact ->
                            if (old?.facts?.get(fact.uuid) != fact) {
                                quantities[fact.date] = Math.addExact(quantities[fact.date] ?: 0L, fact.quantity)
                                added++
                            }
                        }
                        if (nextFacts.isNotEmpty() || nextPolicies.isNotEmpty() || nextUnknown.isNotEmpty()) {
                            val latest = linkedMapOf<LocalDate, Long>()
                            nextFacts.values.forEach { fact ->
                                latest[fact.date] = maxOf(latest[fact.date] ?: Long.MIN_VALUE, fact.id)
                            }
                            blocks[month] = Block(Collections.unmodifiableMap(LinkedHashMap(nextFacts)),
                                CountSummary.Values.create(nextPolicies, quantities, nextUnknown, latest))
                        }
                    }
                }
                val state = if (changed == 0 && previous != null) previous else {
                    val combined = CountSummary.Values.combine(blocks.values.map { it.values })
                    // Production facts have positive Int values and List size is Int: their whole
                    // total always fits Long. Reject overflow in the broader pure Long-fact API.
                    combined.totalQuantity
                    State(Collections.unmodifiableMap(blocks), combined, facts.size)
                }
                return Result(state, Stats(facts.size, reused, changed, added, removed,
                    sealedReused, activeUpdated))
            }
        }
    }

    private class Block(val facts: Map<String, Fact>, val values: CountSummary.Values)

    fun reconcile(previous: State?, facts: List<Fact>, policies: Map<LocalDate, CountDayPolicy>,
        unknownDates: Set<LocalDate>, today: LocalDate): Result =
        State.reconcile(previous, facts, policies, unknownDates, today)
}
