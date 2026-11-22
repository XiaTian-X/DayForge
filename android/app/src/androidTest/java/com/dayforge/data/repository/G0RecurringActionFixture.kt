package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.model.CheckInResult
import com.dayforge.data.model.HabitType
import com.dayforge.domain.service.AccountIconController
import com.dayforge.domain.service.CheckInService
import com.dayforge.domain.service.CompletionActionTiming
import org.junit.After
import org.junit.Assert.*

/** Original reference path only. Boot is deliberately unsealed here; this is not admission
 * for fact writes in the metric certificate. All business calls use the original services.
 */
abstract class G0RecurringActionFixture:G0SealedDomainFixture() {
    @get:org.junit.Rule(order=-2) val widgetRefresh=com.dayforge.widget.IsolatedWidgetRefreshRule()
    private val iconOwners=mutableListOf<AccountIconController>()
    internal data class Actions(val repository:HabitRepository,val service:CheckInService)
    internal fun actions():Actions {
        // Existing fact mutations preserve appearance and never require file/material access.
        // Any unexpected attempt fails, rather than supplying permissive mocked authorization.
        val icons=AccountIconController({ error("G0_FACT_ACTION_UNEXPECTED_ICON_ACCESS") },tokens)
        iconOwners+=icons
        val editor=NextObjectEditor(db,tokens,sessions,icons)
        val counts=CountHistoryReader(db,tokens,sessions)
        val checks=CheckHistoryReader(db,tokens,sessions)
        val repository=HabitRepository(db.habitDao(),db.completionDao(),db.timeLogDao(),db,
            nextObjectEditor=editor,countHistoryReader=counts,checkHistoryReader=checks,
            recurringHabitReader=RecurringHabitReader(db,tokens,sessions,editor,counts,checks),
            widgetFactReader=WidgetFactReader(db,tokens,sessions,preferences,counts,checks),
            countActions=CountActionReader(db,tokens,sessions))
        return Actions(repository,CheckInService(repository,db.completionDao(),db.timeLogDao()))
    }
    @After fun closeActionIcons() { iconOwners.forEach { it.close() } }
    internal suspend fun configureAction(check:Boolean=false,countdown:Boolean=false,target:Int=10) {
        register()
        // Synthetic fixture construction, before any measured or sealed operation.
        habit=habit.copy(habitType=if(check) HabitType.CHECK_IN else HabitType.COUNTING,
            targetValue=if(check) 1 else target,isCountdown=countdown)
        db.withTransaction {
            val sql=db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.habitDao().update(habit)
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
    }
    internal suspend fun increment(actions:Actions):CheckInResult.Success =
        CompletionActionTiming.withOperation(CompletionActionTiming.Operation.COUNT_INCREMENT) {
            val result=actions.service.incrementCount(app,habit.id)
            assertTrue(result is CheckInResult.Success)
            (result as CheckInResult.Success).also { assertNotNull(it.followUp) }
        }
    internal suspend fun currentCount(actions:Actions):Long {
        val current=requireNotNull(actions.repository.getHabitById(habit.id))
        assertEquals(habit.uuid,current.uuid)
        assertEquals(HabitType.COUNTING,current.habitType)
        return actions.repository.getCountHistory(current).todayQuantity
    }
    internal suspend fun rejectWithoutChanges(block:suspend ()->Unit):Throwable {
        val before=pageNativeState()
        val failure=try { block();null } catch(error:Exception) { error }
        assertNotNull("Original operation unexpectedly accepted",failure)
        assertEquals(before,pageNativeState())
        return requireNotNull(failure)
    }
    internal suspend fun ackOnlyOperation() {
        val row=db.syncOutboxDao().getAll().single()
        assertEquals("completion",row.recordType)
        assertNotNull(db.nextRequestDao().origin(NEXT_OPERATION,row.operationId))
        val (http,_)=channel { successReply(it) }
        val requests=sender(http)
        val delivery=requireNotNull(requests.sendOperation(access(),row.operationId))
        assertEquals(NextOperationAcceptance.COMMITTED,requests.acceptOperation(delivery))
        assertEquals(NextOperationAcceptance.REPLAYED,requests.acceptOperation(delivery))
        assertTrue(db.syncOutboxDao().getAll().isEmpty())
        assertNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION,row.operationId))
    }
}
