package com.dayforge.data.repository

import com.dayforge.data.api.decodeFrozenSyncRequest
import com.dayforge.data.api.dto.SyncV2Operation
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.local.entity.CountDayEntity
import com.dayforge.data.local.entity.SyncOutboxEntity
import kotlinx.serialization.json.*

/** Original first-count birth order, including days with no surviving effective completions. */
internal class NextCountOrderingStore(private val database: HabitDatabase, private val requests: NextCoreRequestStore) {
    suspend fun requireFactReady(operation: SyncV2Operation, access: LocalSyncAccess) {
        check(database.inTransaction())
        if (operation.entityType != "activity_event" || "count_policy" !in operation.payload) return
        val habit = requireNotNull(database.habitDao().getHabitByUuid(operation.payload.getValue("activity_uuid").jsonPrimitive.content))
        val day = requireNotNull(NextCountDayStore(database).read(habit, operation.payload.getValue("local_date").jsonPrimitive.content))
        require(day.policy == NextCommonFactMapper.countPolicy(operation.payload))
        val first = day.originRequestId ?: return // An already accepted server day has no invented local birth order.
        context(day, access)
        if (operation.operationId != first) {
            requests.requireAcceptedCountInTransaction(access, first)
            return
        }
        day.planPredecessorId?.let {
            val accepted = NextStructuralCausalStore(database).acceptedPlan(it, access)
            require(accepted["public_id"] == JsonPrimitive(habit.uuid))
            if (NextCountDayStore.policyFromPlan(accepted) != day.policy)
                rejectNextRequest(NextRequestException.Reason.COUNT_START_CONFIG_CHANGED)
        }
    }

    suspend fun requireStructureReady(row: SyncOutboxEntity, access: LocalSyncAccess) {
        check(database.inTransaction())
        if (row.recordType != "habit" || row.action != "upsert") return
        val habit = database.habitDao().getHabitByUuid(row.entityUuid) ?: return
        val order = NextStructuralCausalStore(database).logicalOrder(row)
        val dates = database.openHelper.writableDatabase.query(
            "SELECT localDate FROM count_days WHERE habitId=? AND originRequestId IS NOT NULL LIMIT 10001", arrayOf(habit.id))
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        require(dates.size <= 10_000)
        for (date in dates) {
            val day = requireNotNull(NextCountDayStore(database).read(habit, date))
            if (order <= requireNotNull(day.planQueueWatermark)) continue
            context(day, access)
            requests.requireAcceptedCountInTransaction(access, requireNotNull(day.originRequestId))
        }
    }

    private suspend fun context(day: CountDayEntity, access: LocalSyncAccess) {
        val origin = requireNotNull(database.nextRequestDao().origin(NEXT_OPERATION, requireNotNull(day.originRequestId)))
        require(origin.accountId == access.session.authentication.userId &&
            (origin.serverInstanceId == null || origin.serverInstanceId == access.session.serverInstanceId && origin.syncEpoch == access.session.syncEpoch) &&
            (day.capturedDeviceId == null || day.capturedDeviceId == access.deviceId))
        val operation = decodeFrozenSyncRequest(origin.intentJson.toByteArray(Charsets.UTF_8), SyncV2Operation.serializer())
        require(operation.entityUuid == day.firstEventUuid && NextCommonFactMapper.countPolicy(operation.payload) == day.policy)
    }
}
