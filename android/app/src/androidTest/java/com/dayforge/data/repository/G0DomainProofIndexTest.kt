package com.dayforge.data.repository

import android.database.Cursor
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Index tests are not business-validator equivalence. Baseline validation here deliberately
 * covers only the strict structural/source snapshot whose unchanged dependencies are borrowed.
 */
@RunWith(AndroidJUnit4::class)
class G0DomainProofIndexTest : G0SealedDomainFixture() {
    private suspend fun baseline(): G0DomainProofIndex {
        val result = db.withTransaction {
            G0MetricBlocks(db, metric.uuid).capture()
            NextRequestSql.sources(db.openHelper.writableDatabase, "sync_outbox")
            NextRequestSql.sources(db.openHelper.writableDatabase, "timer_command_outbox")
            G0DomainProofIndex.captureVerifiedBaseline(db.openHelper.writableDatabase)
        }
        sealDomain()
        return result
    }
    private fun range(name: String) = G0DomainProofIndex.Dependency.Range("metrics", "name", G0DomainJournal.Cell(Cursor.FIELD_TYPE_STRING, name))
    private fun row(id: Long) = G0DomainProofIndex.Dependency.Row(G0DomainProofIndex.Key("metrics", listOf(G0DomainJournal.Cell(Cursor.FIELD_TYPE_INTEGER, id))))
    private suspend fun change(index: G0DomainProofIndex, name: String) {
        lateinit var staged: G0DomainProofIndex.Advancement
        domainGate.transaction(db, G0DomainSqlGate.Phase.BUSINESS, committed = { index.committed(staged, it) }) {
            db.openHelper.writableDatabase.execSQL("UPDATE metrics SET name=?,description=?,updatedAt=? WHERE id=?", arrayOf<Any>(name, "", millis, metric.id))
            domainGate.certifyChanges { changes ->
                assertEquals(2, changes.size)
                staged = index.prepare(changes)
            }
        }
    }
    @Test fun movedNameInvalidatesBothMembershipAndAbsenceWhileUnrelatedProofSurvives() = runBlocking<Unit> {
        val index = baseline()
        val oldName = index.witness(setOf(range(metric.name)))
        val newName = index.witness(setOf(range("New membership")))
        val target = index.witness(setOf(row(metric.id)))
        val unrelated = index.witness(setOf(range("Unrelated absence")))
        change(index, "New membership")
        for (proof in listOf(oldName, newName, target)) assertEquals("G0_DOMAIN_DEPENDENCY_CHANGED", runCatching { index.requireCurrent(proof) }.exceptionOrNull()?.message)
        index.requireCurrent(unrelated)
    }
    @Test fun stagedChangesDoNotAdvanceWitnessUntilActualCommit() = runBlocking<Unit> {
        val index = baseline(); val proof = index.witness(setOf(row(metric.id)))
        val failure = rejected { domainGate.transaction(db, G0DomainSqlGate.Phase.BUSINESS) {
            db.openHelper.writableDatabase.execSQL("UPDATE metrics SET name=?,description=?,updatedAt=? WHERE id=?", arrayOf<Any>("Rollback", "", millis, metric.id))
            domainGate.certifyChanges { index.prepare(it) }
            index.requireCurrent(proof)
            error("Injected rollback")
        } }
        assertEquals("Injected rollback", failure.message)
        // Index remains unchanged but the connection gate is invalid. Neither one alone grants reuse.
        index.requireCurrent(proof)
        assertTrue(runCatching { domainGate.requireSealedConnection() }.exceptionOrNull() is G0DomainSqlGate.Denied)
    }
    @Test fun sameFinalRowDoesNotResurrectAProofFromBeforeTransientMutation() = runBlocking<Unit> {
        val index = baseline(); val old = index.witness(setOf(row(metric.id)))
        change(index, "Intermediate"); change(index, metric.name)
        assertEquals("G0_DOMAIN_DEPENDENCY_CHANGED", runCatching { index.requireCurrent(old) }.exceptionOrNull()?.message)
    }
    @Test fun forgedOldImageOrReusedAdvancementCannotBePromoted() = runBlocking<Unit> {
        val index = baseline()
        lateinit var prepared: G0DomainProofIndex.Advancement
        lateinit var captured: List<G0DomainJournal.Change>
        domainGate.transaction(db, G0DomainSqlGate.Phase.BUSINESS, committed = { index.committed(prepared, it) }) {
            db.openHelper.writableDatabase.execSQL("UPDATE metrics SET name=?,description=?,updatedAt=? WHERE id=?", arrayOf<Any>("Exact old cells", "", millis, metric.id))
            captured = domainGate.changes()
            val actual = captured.single { it.table == "metrics" }
            val changed = requireNotNull(actual.old).toMutableList()
            changed[1] = G0DomainJournal.Cell(Cursor.FIELD_TYPE_STRING, "Forged")
            assertEquals("G0_DOMAIN_OLD_ROW_MISMATCH", runCatching { index.prepare(listOf(actual.copy(old = changed))) }.exceptionOrNull()?.message)
            domainGate.certifyChanges { prepared = index.prepare(it) }
        }
        assertEquals("G0_DOMAIN_COMMIT_CERTIFICATE_CHANGED", runCatching { index.committed(prepared, captured) }.exceptionOrNull()?.message)
    }
}
