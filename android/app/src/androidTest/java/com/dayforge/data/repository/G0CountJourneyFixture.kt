package com.dayforge.data.repository

import com.dayforge.domain.service.AccountIconController
import kotlinx.coroutines.*
import org.junit.After

abstract class G0CountJourneyFixture:G0RecurringActionFixture() {
    internal val journeyOwner=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private val readers=mutableListOf<G0CountResultReader>()
    private val icons=mutableListOf<AccountIconController>()
    private val journeys=mutableListOf<G0CountJourney>()
    internal open val reuseCountPreparation:Boolean get()=false
    internal open val reuseCountPreparationAfterResult:Boolean get()=false
    internal fun resultReader(reuse:Boolean=true):G0CountResultReader {
        val icon=AccountIconController({ error("G0_COUNT_UNEXPECTED_ICON_ACCESS") },tokens);icons+=icon
        return G0CountResultReader(db,tokens,sessions,NextObjectEditor(db,tokens,sessions,icon),reuse).also { readers+=it }
    }
    internal fun journey(reuse:Boolean=true,prepareBlocks:Boolean=reuseCountPreparation,
        afterResult:Boolean=reuseCountPreparationAfterResult,loadOnOpen:Boolean=false,shareInitialPure:Boolean=false,
        prioritizeDashboard:Boolean=false):G0CountJourney =
        G0CountJourney(actions().repository,resultReader(reuse),tokens,app,journeyOwner,habit.id,prepareBlocks,afterResult,loadOnOpen,shareInitialPure) { command ->
            if(prioritizeDashboard) sessions.withInteractiveReadPriority(command) else command()
        }.also { journeys+=it }
    internal fun restoreForJourney(image:ByteArray) {
        storage.restoreClosedDatabase(image)
        // The shared image helper deliberately restores the ordinary production builder.
        // Reattach this fixture's wrapped connection before measurement or fault injection.
        storage.reopen()
    }
    @After fun stopJourneys()=runBlocking<Unit> {
        journeys.forEach { it.close() };journeyOwner.coroutineContext.job.cancelAndJoin()
        readers.forEach { it.close() };icons.forEach { it.close() }
    }
}
