package com.dayforge.data.repository

import com.dayforge.data.local.*
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.CountHistory
import com.dayforge.domain.model.CheckHistory
import com.dayforge.domain.service.AccountSessionCoordinator
import java.time.ZonedDateTime
import java.util.Collections

/** Same original validators and immutable projection for cold admission and ordinary reads. */
internal object G0RecurringContent {
    suspend fun load(db:HabitDatabase,tokens:TokenManager,sessions:AccountSessionCoordinator,requested:List<Long>,time:ZonedDateTime,displayScope:RecurringDisplayReadScope?=null):G0RecurringReadOwner.Content {
        val counts=CountHistoryReader(db,tokens,sessions);val checks=CheckHistoryReader(db,tokens,sessions)
        val rows=requested.map { requireNotNull(db.habitDao().getHabitById(it)) }
        val countResults=linkedMapOf<Long,CountHistory>();val checkResults=linkedMapOf<Long,CheckHistory>()
        for(row in rows) when(row.habitType) {
            HabitType.COUNTING -> {
                val value=if(displayScope==null) counts.readInTransaction(row,time.toLocalDate()) else counts.readInTransaction(row,time.toLocalDate(),displayScope)
                countResults[row.id]=value.copy(policies=Collections.unmodifiableMap(value.policies.toMap()),
                    quantities=Collections.unmodifiableMap(value.quantities.toMap()),unknownDates=Collections.unmodifiableSet(value.unknownDates.toSet()),
                    completions=Collections.unmodifiableList(value.completions.toList()))
            }
            HabitType.CHECK_IN -> {
                val value=if(displayScope==null) checks.readInTransaction(row,time.toLocalDate()) else checks.readInTransaction(row,time.toLocalDate(),displayScope)
                checkResults[row.id]=value.copy(completions=Collections.unmodifiableList(value.completions.toList()))
            }
            else -> error("G0_RECURRING_TYPE_UNSUPPORTED")
        }
        return G0RecurringReadOwner.Content(Collections.unmodifiableList(rows),Collections.unmodifiableMap(countResults),Collections.unmodifiableMap(checkResults))
    }
}
