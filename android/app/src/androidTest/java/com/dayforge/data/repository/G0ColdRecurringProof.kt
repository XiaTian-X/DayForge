package com.dayforge.data.repository

import com.dayforge.data.local.*
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitType
import com.dayforge.domain.service.AccountSessionCoordinator
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlinx.coroutines.currentCoroutineContext

/** Cold-only result and COMPLETE query observations, begun with an empty evidence session.
 * Nothing is reusable until the surrounding cold transaction commits and its gate is sealed.
 * No persisted proof, permission cache, SQL handle or write authority is exposed to the UI.
 */
internal class G0ColdRecurringProof private constructor(
    private val db:HabitDatabase,private val tokens:TokenManager,private val sessions:AccountSessionCoordinator,
    private val content:G0RecurringReadOwner.Content,private val time:ZonedDateTime,
    private val core:LocalCoreWriteAccess,private val registered:LocalSyncAccess?,
    private val proof:G0ObservedReadProof?,private val changes:Long,val failure:String?
) {
    fun requireMatches(plans:List<HabitEntity>,today:LocalDate) {
        check(content.habits==plans && time.toLocalDate()==today && time.zone==ZonedDateTime.now().zone) { "G0_COLD_READ_INPUT_CHANGED" }
    }
    fun requireUnchangedWithinCold() { check(totalChanges(db)==changes) { "G0_COLD_READ_SNAPSHOT_CHANGED" } }
    fun seal(authority:G0AuthorityOwner,gate:G0DomainSqlGate,lease:G0AuthorityOwner.Lease,stamp:G0DomainSqlGate.ReadStamp):Seed? {
        check(!db.inTransaction());gate.requireSealedConnection();authority.checkLease(lease)
        return proof?.let { Seed(this,authority,gate,lease,stamp,gate.revision) }
    }
    class Seed internal constructor(private val source:G0ColdRecurringProof,private val authority:G0AuthorityOwner,
        private val gate:G0DomainSqlGate,private val lease:G0AuthorityOwner.Lease,
        private val stamp:G0DomainSqlGate.ReadStamp,private val revision:Long) {
        fun borrow(owner:G0RecurringReadOwner,db:HabitDatabase,tokens:TokenManager,sessions:AccountSessionCoordinator,
            authority:G0AuthorityOwner,gate:G0DomainSqlGate,ids:List<Long>,time:ZonedDateTime):Pair<G0RecurringReadOwner.Batch,G0ObservedReadProof>? {
            if(db!==source.db || tokens!==source.tokens || sessions!==source.sessions || authority!==this.authority || gate!==this.gate ||
                ids!=source.content.habits.map { it.id } || time.zone!=source.time.zone || time.toLocalDate()!=source.time.toLocalDate()) return null
            if(runCatching { authority.checkLease(lease);gate.requireSealedConnection() }.isFailure) return null
            return G0RecurringReadOwner.Batch(owner,source.content,source.core,source.registered,lease,revision,source.time,stamp) to requireNotNull(source.proof)
        }
    }
    companion object {
        private fun totalChanges(db:HabitDatabase)=db.openHelper.writableDatabase.query("SELECT total_changes()").use { check(it.moveToFirst());it.getLong(0) }
        suspend fun capture(db:HabitDatabase,tokens:TokenManager,sessions:AccountSessionCoordinator,recording:G0ObservedReadProof.Capture):G0ColdRecurringProof {
            // Recording after any cached audit could silently omit a dependency. Fail closed.
            check(db.inTransaction() && currentCoroutineContext()[NextEvidenceReadSession]==null) { "G0_COLD_READ_REQUIRES_EMPTY_SESSION" }
            val time=ZonedDateTime.now();val changes=totalChanges(db)
            val plans=db.habitDao().getAllHabitsOnce().sortedBy { it.id }.filter {
                it.habitType in setOf(HabitType.COUNTING,HabitType.CHECK_IN) && it.completionPolicy=="recurring"
            }
            val core=requireNotNull(tokens.localCoreWriteAccess());val registered=tokens.localSyncAccess()
            val content=G0ObservedReadProof.capture(recording) {
                NextEvidenceReadSession.read(db) { G0RecurringContent.load(db,tokens,sessions,plans.map { it.id },time) }
            }
            check(tokens.isCurrentDisplayReadAuthority(core,registered))
            check(totalChanges(db)==changes)
            return G0ColdRecurringProof(db,tokens,sessions,content,time,core,registered,recording.finish(),changes,recording.reason)
        }
    }
}
