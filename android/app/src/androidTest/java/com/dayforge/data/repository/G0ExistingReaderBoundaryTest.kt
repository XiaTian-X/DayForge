package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.ZonedDateTime
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Preserve integration failures. Reusing existing repositories does not grant an unsealed handle. */
@RunWith(AndroidJUnit4::class)
class G0ExistingReaderBoundaryTest : G0SealedDomainFixture() {
    private fun dashboard()=G0DetailedDashboardReadRepository(db,tokens,sessions,
        ReplicaPresentationRepository(db,tokens,sessions),CountHistoryReader(db,tokens,sessions),CheckHistoryReader(db,tokens,sessions))
    private fun rawMetricReads()=MetricRepository(db,db.metricDao(),db.metricLogDao(),db.habitDao(),db.habitMetricLinkDao())
    private suspend fun unchanged() {
        storage.isolatedBuilder=null;storage.reopen()
        assertEquals(metric.name,db.metricDao().getMetricById(metric.id)!!.name)
        assertEquals(0,count("sync_outbox"));assertEquals(0,count("next_request_origins"));assertEquals(0,count("next_acceptances"))
    }
    private fun isDenied(failure:Throwable)=generateSequence(failure) { it.cause }.any { it is G0DomainSqlGate.Denied }
    @Test fun originalDashboardCannotStartAnUnownedSealedRead()=runBlocking<Unit> {
        register();val reader=dashboard();sealDomain()
        val failure=rejected { withTimeout(3_000) { reader.read(ZonedDateTime.now()) } }
        assertTrue(failure.toString(),isDenied(failure));unchanged()
    }
    @Test fun wrappingDashboardInAnotherTransactionCannotCreateASafeBorrowedScope()=runBlocking<Unit> {
        register();val reader=dashboard();sealDomain()
        val failure=rejected { withTimeout(3_000) {
            domainGate.transaction(db,G0DomainSqlGate.Phase.READ) { reader.read(ZonedDateTime.now()) }
        } }
        assertEquals("DISPLAY_READ_SCOPE_NESTED",failure.message);unchanged()
    }
    @Test fun metricOverviewAndPromptPreflightQueriesCannotEscapeSealedOwner()=runBlocking<Unit> {
        val repo=rawMetricReads();sealDomain()
        val failures=listOf(
            rejected { repo.getLatestLog(metric.id) },
            rejected { repo.getLogsInRange(metric.id,0,Long.MAX_VALUE) },
            rejected { repo.getLinkedMetricSnapshots(habit.id) })
        assertTrue(failures.all(::isDenied));unchanged()
    }
}
