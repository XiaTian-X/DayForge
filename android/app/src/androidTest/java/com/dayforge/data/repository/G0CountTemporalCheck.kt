package com.dayforge.data.repository

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.jsonPrimitive

/** Fresh external time semantics for an already authenticated complete image. This is
 * neither a replacement business validator nor authority for arbitrary decoded Q data.
 * Original validation bound receipt/shadow source times to the request and local fact.
 */
internal object G0CountTemporalCheck {
    suspend fun matches(data:G0HouseholdCountImage.Data):Boolean {
        if(data.today!=LocalDate.now() || data.defaultZone!=ZoneId.systemDefault().id)return false
        val zones=ZoneId.getAvailableZoneIds()
        val facts=data.objects.values.flatMap {it.history.completions}.associateBy {it.uuid}
        for(row in data.objects.values) {
            currentCoroutineContext().ensureActive()
            row.habit.planMetadata?.timezone?.let {if(it !in zones)return false}
        }
        for(operation in data.operations.values) {
            currentCoroutineContext().ensureActive()
            val fact=facts[operation.entityUuid] ?: return false
            val source=operation.payload
            val zoneName=source["timezone"]?.jsonPrimitive?.content ?: return false
            val date=source["local_date"]?.jsonPrimitive?.content ?: return false
            val occurred=source["occurred_at"]?.jsonPrimitive?.content ?: return false
            if(zoneName !in zones || fact.recordedTimezone!=zoneName || fact.recordedLocalDate!=date)return false
            val zone=ZoneId.of(zoneName);val day=LocalDate.parse(date);val instant=Instant.parse(occurred)
            if(instant.atZone(zone).toLocalDate()!=day || day.atStartOfDay(zone).toInstant().toEpochMilli()!=fact.date ||
                instant.toEpochMilli()!=fact.actualCompletedAt)return false
        }
        return true
    }
}
