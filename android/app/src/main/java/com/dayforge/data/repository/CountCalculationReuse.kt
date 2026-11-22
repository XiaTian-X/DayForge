package com.dayforge.data.repository

import com.dayforge.data.history.CountCalculationBlocks
import com.dayforge.data.local.LocalDataSession
import com.dayforge.data.local.PersonalDataDisplayMemory
import com.dayforge.data.local.TokenManager
import com.dayforge.domain.model.ChallengeRoundHead
import com.dayforge.domain.model.CountDayPolicy
import java.time.LocalDate

/** Bounded, memory-only arithmetic reuse AFTER the original audit. Never an evidence cache,
 * credential, current-root certificate, or a replacement for checking the next input in full.
 * A reader may prepare during a transaction; only its successful outer read END may promote.
 */
internal class CountCalculationReuse(tokens: TokenManager) {
    data class Partition(val session: LocalDataSession, val deviceId: String?, val habitId: Long,
        val habitUuid: String, val round: ChallengeRoundHead?)
    class Ticket internal constructor(internal val owner: CountCalculationReuse,
        internal val epoch: Long, internal val order: Long)
    class Candidate internal constructor(internal val owner: CountCalculationReuse,
        internal val ticket: Ticket, internal val partition: Partition,
        val result: CountCalculationBlocks.Result, internal val units: Long)
    data class Diagnostics(val committedEntries: Int, val promotions: Long, val invalidations: Long,
        val discarded: Long, val lastCalculation: CountCalculationBlocks.Stats?)
    private data class Entry(val order: Long, val state: CountCalculationBlocks.State, val units: Long)
    private val entries = LinkedHashMap<Partition, Entry>(16, 0.75f, true)
    private var epoch = 0L
    private var order = 0L
    private var blocked = false
    private var units = 0L
    private var promotions = 0L
    private var invalidations = 0L
    private var discarded = 0L
    private var lastCalculation: CountCalculationBlocks.Stats? = null
    // Keep a strong reference: TokenManager deliberately holds weak display listeners.
    private val listener = object : PersonalDataDisplayMemory.Listener {
        override fun transition(blocked: Boolean) = synchronized(this@CountCalculationReuse) {
            this@CountCalculationReuse.blocked = blocked
            epoch = Math.addExact(epoch, 1L)
            invalidations++
            entries.clear()
            units = 0L
            lastCalculation = null
        }
    }
    init { tokens.registerPersonalDisplay(listener) }

    @Synchronized fun begin(): Ticket = Ticket(this, epoch, Math.addExact(order, 1L).also { order = it })

    fun prepare(ticket: Ticket, partition: Partition, facts: List<CountCalculationBlocks.Fact>,
        policies: Map<LocalDate, CountDayPolicy>, unknownDates: Set<LocalDate>, today: LocalDate): Candidate {
        check(ticket.owner === this)
        val prior = synchronized(this) {
            if (!blocked && ticket.epoch == epoch) entries[partition]?.state else null
        }
        val result = CountCalculationBlocks.reconcile(prior, facts, policies, unknownDates, today)
        synchronized(this) { if (!blocked && ticket.epoch == epoch) lastCalculation = result.stats }
        // This is an entry budget, not a product/history limit. Oversized results still return
        // normally and are simply not retained across operations.
        return Candidate(this, ticket, partition, result,
            facts.size.toLong() + policies.size.toLong() + unknownDates.size.toLong())
    }

    @Synchronized fun promote(candidate: Candidate) {
        check(candidate.owner === this && candidate.ticket.owner === this)
        if (blocked || candidate.ticket.epoch != epoch || candidate.units > MAX_UNITS ||
            (entries[candidate.partition]?.order ?: Long.MIN_VALUE) >= candidate.ticket.order) {
            discarded++
            return
        }
        entries.remove(candidate.partition)?.let { units -= it.units }
        while (entries.isNotEmpty() && (entries.size >= MAX_ENTRIES || units + candidate.units > MAX_UNITS)) {
            val first = entries.entries.iterator()
            units -= first.next().value.units
            first.remove()
        }
        entries[candidate.partition] = Entry(candidate.ticket.order, candidate.result.state, candidate.units)
        units += candidate.units
        promotions++
    }

    @Synchronized fun diagnostics() = Diagnostics(entries.size, promotions, invalidations, discarded, lastCalculation)

    private companion object {
        const val MAX_ENTRIES = 32
        const val MAX_UNITS = 16_384L
    }
}
