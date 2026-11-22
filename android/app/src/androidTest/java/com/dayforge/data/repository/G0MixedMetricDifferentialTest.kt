package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.toDisplayMillis
import com.dayforge.data.model.HabitType
import java.time.ZonedDateTime
import org.junit.runner.RunWith

/** Inherits all four full 39-table original-vs-candidate write/send/ACK/successor comparisons. */
@RunWith(AndroidJUnit4::class)
open class G0MixedMetricDifferentialTest : G0ShortMetricDifferentialTest() {
    override val admitRecurringReads=true
    override suspend fun prepareComparison() {
        register();freshGateForColdOpen(recurringReads=true);storage.reopen()
        val now=ZonedDateTime.now();val date=now.toLocalDate();var checkId=0L
        producer().write(local()) { checkId=db.habitDao().insert(habit.copy(id=0,uuid=id(7600),name="Mixed check",habitType=HabitType.CHECK_IN)) }
        for((identity,value) in listOf(habit.id to 2,habit.id to 3,checkId to 1)) {
            val row=requireNotNull(db.habitDao().getHabitById(identity))
            producer().write(local()) {
                val fact=CompletionEntity(habitId=row.id,habitUuid=row.uuid,date=date.toDisplayMillis(),actualCompletedAt=now.toInstant().toEpochMilli(),value=value)
                if(row.habitType==HabitType.COUNTING) NextCountDayStore(db).capture(row,fact)
                db.completionDao().insertForSync(fact)
            }
        }
    }
}
