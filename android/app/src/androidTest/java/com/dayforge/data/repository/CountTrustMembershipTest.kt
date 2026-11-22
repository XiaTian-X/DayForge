package com.dayforge.data.repository

import android.database.Cursor
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.history.CountCalculationBlocks
import com.dayforge.data.local.CountTrustJournal
import com.dayforge.domain.model.CountDayPolicy
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exact routing and arithmetic differential tests; these ordinary row values grant no trust. */
@RunWith(AndroidJUnit4::class)
class CountTrustMembershipTest : NextCoreRequestFixture() {
    private suspend fun rows(table: String) = db.withTransaction { CountTrustRows(db.openHelper.writableDatabase).read(table) }
    private fun changed(row: CountTrustRows.Row, vararg changes: Pair<String, Any?>): CountTrustRows.Row {
        val overrides = changes.toMap()
        return CountTrustRows.Row(row.table, row.table.columns.mapIndexed { index, key ->
            if (key !in overrides) row.cells[index] else cell(overrides[key])
        })
    }
    private fun cell(value: Any?) = CountTrustJournal.Cell(when (value) {
        null -> Cursor.FIELD_TYPE_NULL
        is Long -> Cursor.FIELD_TYPE_INTEGER
        is String -> Cursor.FIELD_TYPE_STRING
        else -> error("Unsupported fixture type")
    }, value)

    @Test fun exactDecodedKeysRouteOriginsAndShadowsWithoutWholeHistoryMembership() = runBlocking<Unit> {
        register(); val queue = completion()
        val origin = rows("next_request_origins").single()
        val originKeys = CountTrustMembership.memberships(origin)
        assertEquals(origin.name, originKeys[CountTrustMembership.key("origin.entity", "activity_event", queue.wireEntityUuid)])
        assertEquals(origin.name, originKeys[CountTrustMembership.key("origin.date", habit.uuid, "2026-10-06")])
        val operation = decodeNextOperationIntent(origin.text("intentJson"))
        val body = JsonObject(operation.payload + ("note" to JsonPrimitive("家庭:🍀 [\"引用\"]"))).toString()
            .replace("activity_uuid", "activity_\\u0075uid")
        val table = db.withTransaction { CountTrustRows(db.openHelper.writableDatabase).table("sync_entity_state") }
        val data = mapOf<String, Any?>("entityType" to "activity_event", "entityUuid" to queue.wireEntityUuid,
            "revision" to 1L, "deleted" to 0L, "payloadJson" to body, "payloadHash" to syncPayloadHash(body), "updatedAt" to millis)
        val shadow = CountTrustRows.Row(table, table.columns.map { cell(data[it]) })
        assertEquals(shadow.name, CountTrustMembership.memberships(shadow)[CountTrustMembership.key("shadow.date", habit.uuid, "2026-10-06")])
        assertFalse(originKeys.keys.any { it.contains(".habit\"") || it.contains("all/table") })
        assertNotEquals(CountTrustMembership.key("entity", "a:b", "c"), CountTrustMembership.key("entity", "a", "b:c"))
        val duplicate = body.dropLast(1) + ",\"activity_uuid\":\"${habit.uuid}\"}"
        assertNotNull(rejected { CountTrustMembership.memberships(changed(shadow,
            "payloadJson" to duplicate, "payloadHash" to syncPayloadHash(duplicate))) })
        val control = rows("sync_control").single()
        assertEquals(control.name, CountTrustMembership.memberships(control)[CountTrustMembership.key("control.id", "1")])
    }

    @Test fun sealedMonthAndIncrementalActiveMonthMatchOriginalArithmeticIncludingUnknownDates() = runBlocking<Unit> {
        register(); completion()
        val first = rows("completions").single(); val day = rows("count_days").single()
        val nextDate = LocalDate.parse("2026-10-07"); val emptyUnknown = LocalDate.parse("2026-10-09")
        val second = changed(first, "id" to first.long("id") + 1, "uuid" to id(211), "recordedLocalDate" to nextDate.toString(), "value" to 5L)
        val month = YearMonth.parse("2026-10"); val date = LocalDate.parse("2026-10-06")
        val unknown = setOf(nextDate, emptyUnknown)
        val full = CountTrustMembership.summary(habit.uuid, month, listOf(first, second), listOf(day), unknown)
        val actual = CountTrustMembership.values(full)
        val original = CountCalculationBlocks.reconcile(null, listOf(
            CountCalculationBlocks.Fact(first.text("uuid"), first.long("id"), date, first.long("value")),
            CountCalculationBlocks.Fact(second.text("uuid"), second.long("id"), nextDate, second.long("value"))),
            mapOf(date to CountDayPolicy(day.int("targetValue"), day.bool("isCountdown"))), unknown, date)
            .state.summary(date, day.day().policy, null, null)
        assertEquals(original.quantities, actual.quantities); assertEquals(original.policies, actual.policies)
        assertEquals(original.unknownDates, actual.unknownDates); assertEquals(original.totalQuantity, actual.totalQuantity)
        val added = changed(first, "id" to first.long("id") + 2, "uuid" to id(212), "value" to 7L)
        val updated = CountTrustMembership.update(full, listOf(first), listOf(added), listOf(day), listOf(added), unknown)
        val rebuilt = CountTrustMembership.summary(habit.uuid, month, listOf(added, second), listOf(day), unknown)
        assertEquals(rebuilt, updated)
        assertEquals(added.long("id"), CountTrustMembership.values(updated).latestIds[date])
        val removed = CountTrustMembership.update(updated, listOf(added), emptyList(), listOf(day), emptyList(), unknown)
        assertEquals(CountTrustMembership.summary(habit.uuid, month, listOf(second), listOf(day), unknown), removed)
        assertTrue(date in CountTrustMembership.values(removed).policies)
        assertEquals(original.quantities, CountTrustMembership.values(full).quantities) // Old immutable candidate untouched.
    }

    @Test fun wrongPartitionDuplicateFactsAndIncompleteLatestWitnessRejectWithoutChangingOldResult() = runBlocking<Unit> {
        register(); completion(); val fact = rows("completions").single(); val days = rows("count_days")
        val month = YearMonth.parse("2026-10")
        val full = CountTrustMembership.summary(habit.uuid, month, listOf(fact), days)
        for (bad in listOf(changed(fact, "habitUuid" to id(333)), changed(fact, "recordedLocalDate" to "2026-11-01"),
            changed(fact, "value" to "3"))) assertNotNull(rejected {
            CountTrustMembership.summary(habit.uuid, month, listOf(bad), days)
        })
        assertNotNull(rejected { CountTrustMembership.summary(habit.uuid, month, listOf(fact, fact), days) })
        assertNotNull(rejected { CountTrustMembership.summary(habit.uuid, month, listOf(fact), days + days) })
        assertNotNull(rejected { CountTrustMembership.update(full, listOf(fact), listOf(fact), days, emptyList(), emptySet()) })
        assertEquals(full, CountTrustMembership.summary(habit.uuid, month, listOf(fact), days))
    }
}
