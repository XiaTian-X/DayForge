package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.toDisplayMillis
import com.dayforge.domain.model.CountHistory
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*

/** G0 synthetic calendar history, through the original producer and original send/ACK.
 * This does not grant a first-day write, multiple habits, or more than 128 facts to the
 * short owner. Dates are explicit captured inputs; the device clock is never changed.
 */
abstract class G0ShortCountCalendarFixture : G0ShortCountScreenFixture() {
    internal data class CalendarSeed(
        val today: LocalDate,
        val zone: ZoneId,
        val dates: List<LocalDate>,
        val factIds: List<Long>,
        val requestIds: List<String>,
        val quantities: Map<LocalDate, Long>,
        val reference: CountHistory
    )

    /** Zero denotes today. Repetition is deliberate: fact count and day count differ.
     * Callers may omit zero to exercise the existing missing-current-day rejection.
     */
    internal suspend fun seedCalendarAccepted(daysAgo: List<Long>): CalendarSeed {
        require(daysAgo.size in 1..128 && daysAgo.all { it >= 0L })
        assertEquals(0, count("completions"))
        assertEquals(0, count("next_request_origins"))
        assertEquals(0, count("sync_outbox"))
        configureAction(target = 1_000_000)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val dates = daysAgo.map { today.minusDays(it) }
        val born = dates.minOrNull()!!.minusDays(1).atStartOfDay(zone).toInstant()

        // Synthetic structure setup only, BEFORE the first immutable source exists.
        // Keep the structure metadata and legacy timestamp projections consistent.
        habit = habit.copy(createdAt = born.toEpochMilli(), updatedAt = born.toEpochMilli(),
            planMetadata = requireNotNull(habit.planMetadata).copy(
                creationTimestamp = born.toString(), timezone = zone.id))
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.habitDao().update(habit)
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        assertEquals(0, count("next_request_origins"))
        assertEquals(0, count("sync_outbox"))

        val (http, _) = channel { successReply(it) }
        val requests = sender(http)
        val facts = mutableListOf<Long>()
        val requestIds = mutableListOf<String>()
        for (date in dates) {
            check(ZoneId.systemDefault() == zone && LocalDate.now(zone) == today) {
                "G0_CALENDAR_SETUP_DATE_CHANGED"
            }
            val occurred = if (date == today) java.time.Instant.now()
                else date.atTime(12, 0).atZone(zone).toInstant()
            val fact = CompletionEntity(habitId = habit.id, habitUuid = habit.uuid,
                date = date.toDisplayMillis(zone), value = 1,
                actualCompletedAt = occurred.toEpochMilli(), createdAt = occurred.toEpochMilli(),
                recordedTimezone = zone.id, recordedLocalDate = date.toString())
            producer().write(local()) {
                NextCountDayStore(db).capture(habit, fact)
                db.completionDao().insertForSync(fact)
            }
            val row = db.syncOutboxDao().getEntityIntents("completion", fact.uuid).single()
            val delivery = requireNotNull(requests.sendOperation(access(), row.operationId))
            assertEquals(NextOperationAcceptance.COMMITTED, requests.acceptOperation(delivery))
            facts += requireNotNull(db.completionDao().getCompletionByUuid(fact.uuid)).id
            requestIds += row.operationId
        }
        habit = requireNotNull(db.habitDao().getHabitById(habit.id))
        val reference = CountHistoryReader(db, tokens, sessions).read(habit, today)
        val quantities = dates.groupingBy { it }.eachCount().mapValues { it.value.toLong() }
        assertEquals(quantities, reference.quantities)
        assertEquals(dates.toSet(), reference.policies.keys)
        assertEquals(dates.size, reference.completions.size)
        assertEquals(0, count("sync_outbox"))
        assertEquals(dates.size, count("next_acceptances"))
        check(ZoneId.systemDefault() == zone && LocalDate.now(zone) == today) {
            "G0_CALENDAR_SETUP_DATE_CHANGED"
        }
        return CalendarSeed(today, zone, dates.toList(), facts.toList(), requestIds.toList(),
            quantities, reference)
    }

    /** Test fault below the application capability boundary; never a candidate SQL grant. */
    internal fun corruptCalendarFile(sql: String, args: Array<Any>) {
        android.database.sqlite.SQLiteDatabase.openDatabase(app.getDatabasePath("habit_database").path,
            null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL(sql, args)
        }
    }

    internal suspend fun assertCalendarColdRejectedWithoutWrites() {
        val before = pageNativeState()
        assertTrue(runCatching { openCount() }.isFailure)
        assertEquals(before, pageNativeState())
    }
}
