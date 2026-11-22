package com.dayforge.data.repository

import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.businessDate
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.*
import com.dayforge.domain.service.AccountSessionCoordinator
import java.time.*

/** G0 post-commit result boundary. Original scope, source binding and follow-up projection;
 * only the complete fact evidence reader uses the tested pure blocks. No writer capability. */
internal class G0CountResultReader(private val database:HabitDatabase,private val tokens:TokenManager,
    private val sessions:AccountSessionCoordinator,private val editor:NextObjectEditor,private val reuseBlocks:Boolean=true) {
    private val evidence=G0BlockCompletionReader(database,tokens,sessions)
    private val reference=RecurringCompletionEvidenceReader(database,tokens,sessions)
    private suspend fun readEvidence(habit:HabitEntity,today:LocalDate,scope:RecurringDisplayReadScope,useBlocks:Boolean=reuseBlocks)=
        if(useBlocks) evidence.readInTransaction(habit,today,scope) else reference.readInTransaction(habit,today,scope)
    fun close()=evidence.close()
    internal val factBlockHits:Long get()=evidence.blocks.hits
    /** Same dispatch and full preparation snapshot as HabitRepository/RecurringHabitReader.
     * Only pure fact computations share this reader's bounded cache with the later fresh read. */
    internal suspend fun prepareCompletion(id:Long):CompletionActionSnapshot {
        val row=database.habitDao().getVisibleHabitById(id)
        if(row?.appearance!=null && row.completionPolicy=="recurring" &&
            row.habitType in setOf(HabitType.CHECK_IN,HabitType.COUNTING)) {
            return RecurringDisplayReadScope.withSnapshot(database,tokens,sessions,
                AccountSessionCoordinator.Work.FACT_HISTORY_READ) { scope ->
                val edit=editor.habitInTransaction(id,scope)
                CompletionActionSnapshot(edit,completionInTransaction(edit,scope))
            }
        }
        return CompletionActionSnapshot(editor.habit(id))
    }
    /** Actual page entry: one full fresh snapshot, then publication after its successful END
     * under the same account lock. Pure reuse is explicit; no preparation ticket is retained. */
    internal suspend fun readInitialAndPublish(id:Long,sharePure:Boolean,
        block:(RecurringCompletionSnapshot)->Unit)=sessions.exclusive {
        val view=RecurringDisplayReadScope.withSnapshotWhileAccountLocked(database,tokens,sessions) { scope ->
            val edit=editor.habitInTransaction(id,scope)
            check(edit.value?.habitType==HabitType.COUNTING) { "G0_COUNT_SCOPE" }
            completionInTransaction(edit,scope,useBlocks=sharePure && reuseBlocks)
        }
        block(view)
    }
    private fun count(habit:HabitEntity,source:RecurringCompletionEvidence):CountHistory {
        val quantities=source.completions.groupBy { it.businessDate }.mapValues { (_,rows)->
            rows.fold(0L) { sum,row->Math.addExact(sum,row.value.toLong()) }
        }
        return CountHistory(source.today,source.policies,quantities,
            source.policies[source.today] ?: if(source.today in source.unknownDates) null else CountDayPolicy(habit.targetValue,habit.isCountdown),
            source.unknownDates,source.completions,source.roundHead,source.countIdentity)
    }
    private fun checkHistory(source:RecurringCompletionEvidence)=CheckHistory(source.today,source.completions,source.roundHead,source.checkIdentity)
    /** Fresh post-COMMIT read; a new account, device, plan or round cannot replace the click's owner. */
    internal suspend fun readCompletionAfter(captured: CompletionActionSnapshot,
        allowActivation: Boolean, recordedCompletionId: Long? = null): RecurringCompletionSnapshot =
        RecurringDisplayReadScope.withSnapshot(database, tokens, sessions,
            AccountSessionCoordinator.Work.FACT_HISTORY_READ) { scope ->
            readAfterInSnapshot(captured,allowActivation,recordedCompletionId,scope)
        }

    private suspend fun readAfterInSnapshot(captured:CompletionActionSnapshot,allowActivation:Boolean,
        recordedCompletionId:Long?,scope:RecurringDisplayReadScope):RecurringCompletionSnapshot {
            val original = requireNotNull(captured.edit.value) { "COMPLETION_ORIGINAL_REQUIRED" }
            val ticket = requireNotNull(captured.edit.authority) { "COMPLETION_AUTHORITY_REQUIRED" }
            check(ticket.completionDeviceCaptured) { "COMPLETION_DEVICE_NOT_CAPTURED" }
            val edit = editor.habitInTransaction(original.id, scope)
            val current = requireNotNull(edit.value) { "COMPLETION_ACTIVITY_REMOVED" }
            val authority = requireNotNull(edit.authority) { "COMPLETION_ACTIVITY_CHANGED" }
            check(authority.session == ticket.session && authority.capturedDeviceId == ticket.capturedDeviceId &&
                current.uuid == original.uuid && ticket.uuid == original.uuid && ticket.type == "plan_node") {
                "COMPLETION_SCOPE_CHANGED"
            }
            check(ticket.original == NextStructureMapper.writePlan(original)) { "COMPLETION_ORIGINAL_CHANGED" }
            val allowed = if (allowActivation && !original.isActive) original.copy(isActive = true) else original
            check(authority.original == ticket.original || authority.original == NextStructureMapper.writePlan(allowed)) {
                "COMPLETION_PLAN_CHANGED"
            }
            check((authority.rounds == null) == (ticket.rounds == null) &&
                roundHead(authority, original.uuid) == roundHead(ticket, original.uuid)) { "COMPLETION_ROUND_CHANGED" }
            return completionInTransaction(edit, scope, recordedCompletionId)
        }

    private suspend fun completionInTransaction(edit: ObjectEditSnapshot<HabitEntity>,
        scope: RecurringDisplayReadScope, recordedCompletionId: Long? = null, useBlocks:Boolean=reuseBlocks): RecurringCompletionSnapshot {
        val habit = requireNotNull(edit.value) { "COMPLETION_ACTIVITY_REMOVED" }
        check(habit.appearance != null && habit.completionPolicy == "recurring" &&
            habit.habitType in setOf(HabitType.CHECK_IN, HabitType.COUNTING)) { "COMPLETION_ACTIVITY_CHANGED" }
        val authority = requireNotNull(edit.authority)
        val zone = ZoneId.systemDefault()
        val today = Instant.now().atZone(zone).toLocalDate()
        val count = if (habit.habitType == HabitType.COUNTING) count(habit, readEvidence(habit,today,scope,useBlocks)) else null
        val checkHistory = if (habit.habitType == HabitType.CHECK_IN) checkHistory(readEvidence(habit,today,scope,useBlocks)) else null
        val (access, rounds) = scope.borrow(database, tokens)
        // The card remains today's projection. Follow-ups belong to this exact committed fact,
        // including a commit spanning midnight, not a later fact that happens to be newest today.
        val source = recordedCompletionId?.let { id ->
            requireNotNull((count?.completions ?: requireNotNull(checkHistory).completions).singleOrNull { it.id == id }) {
                "COMPLETION_SOURCE_CHANGED"
            }.also { fact -> check(fact.habitUuid == habit.uuid && fact.oneTimeAction == null) { "COMPLETION_SOURCE_CHANGED" } }
        }
        val sourceDay = source?.let { LocalDate.parse(it.recordedLocalDate) } ?: today
        val sourceZone = source?.let { ZoneId.of(it.recordedTimezone) } ?: zone
        val sourceCount = count?.let {
            if (sourceDay == today) it else it.copy(today = sourceDay, todayPolicy = it.policies[sourceDay])
        }
        val sourceChecks = checkHistory?.let { if (sourceDay == today) it else it.copy(today = sourceDay) }
        val followUp = recurringFactSnapshot(habit, access, rounds, sourceCount, sourceChecks, sourceDay, sourceZone, source)
        return RecurringCompletionSnapshot(habit, authority, checkHistory, count, followUp)
    }

    private fun roundHead(authority: ObjectEditAuthority, uuid: String) = authority.rounds?.let {
        it.pendingRestarts[uuid]?.head ?: it.completionRounds.head(uuid) ?: it.pendingInitials[uuid]?.head
    }


    /** Full result revalidation under the original account/snapshot owner, then publication
     * while the account lock remains held. No callback from a diagnostic COMMIT event. */
    suspend fun publish(captured:CompletionActionSnapshot,view:RecurringCompletionSnapshot,
        allowActivation:Boolean,recordedCompletionId:Long?,block:()->Unit)=sessions.exclusive {
        RecurringDisplayReadScope.withSnapshotWhileAccountLocked(database,tokens,sessions) { scope ->
            val current=readAfterInSnapshot(captured,allowActivation,recordedCompletionId,scope)
            check(current.habit==view.habit && current.countHistory==view.countHistory &&
                current.checkHistory==view.checkHistory && current.followUp==view.followUp) { "G0_COUNT_RESULT_CHANGED" }
        }
        block()
    }
    val publishesTogether:Boolean get()=reuseBlocks
    /** No detached result or suspension to UI between proof and publication. The same account
     * lock surrounds the fresh full snapshot, successful read transaction end, and callback.
     * withSnapshotWhileAccountLocked performs the original FINAL fresh authority check. */
    suspend fun readAndPublish(captured:CompletionActionSnapshot,allowActivation:Boolean,
        recordedCompletionId:Long?,block:(RecurringCompletionSnapshot)->Unit)=sessions.exclusive {
        val view=RecurringDisplayReadScope.withSnapshotWhileAccountLocked(database,tokens,sessions) { scope ->
            readAfterInSnapshot(captured,allowActivation,recordedCompletionId,scope)
        }
        block(view)
    }

}
