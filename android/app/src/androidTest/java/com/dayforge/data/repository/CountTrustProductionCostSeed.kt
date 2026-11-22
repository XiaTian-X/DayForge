package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.decodeFrozenSyncRequest
import com.dayforge.data.api.dto.NextSyncOperationResult
import com.dayforge.data.api.dto.NextSyncPushRequest
import com.dayforge.data.api.dto.NextSyncPushResponse
import com.dayforge.data.api.encodeSyncRequest
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.CountTrustSchema
import com.dayforge.data.local.entity.*
import com.dayforge.data.local.toDisplayMillis
import com.dayforge.domain.model.CountDayPolicy
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.junit.Assert.*

/** Production-builder counterpart of the existing household seed algorithm. The historical
 * terminal envelopes are constructed fixture data, not N measured HTTP journeys. Every fact,
 * origin and day comes from the original producer; every constructed acceptance is checked by
 * the original request validator and complete original history reader before qualification.
 * No G0 builder, SQL capability, transaction wrapper or trusted-root issuer is used here.
 */
internal class CountTrustProductionCostSeed(private val fixture: NextCoreRequestFixture) {
    data class Seed(val history: Int, val habits: List<HabitEntity>, val today: LocalDate,
        val zone: ZoneId, val http: NextSyncHttp, val facts: List<CompletionEntity>)
    private data class Entry(val fact: CompletionEntity, val queue: SyncOutboxEntity,
        val origin: NextRequestOriginEntity, val sourceHash: String, val originHash: String,
        val day: CountDayEntity)

    suspend fun create(history: Int): Seed = with(fixture) {
        require(history in setOf(8, 80, 730))
        register()
        val zone = ZoneId.systemDefault(); val today = LocalDate.now(zone)
        val born = today.minusDays(history / 2L + 1).atStartOfDay(zone).toInstant()
        val first = habit.copy(name = "Household A", targetValue = 3, isCountdown = false,
            createdAt = born.toEpochMilli(), updatedAt = born.toEpochMilli(),
            planMetadata = requireNotNull(habit.planMetadata).copy(creationTimestamp = born.toString(), timezone = zone.id))
        val second = first.copy(id = 0, uuid = id(4801), name = "Household B", targetValue = 7)
        val rows = db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.habitDao().delete(timerHabit)
            db.habitDao().update(first)
            val saved = second.copy(id = db.habitDao().insert(second))
            val link = HabitMetricLinkEntity(habitId = first.id, habitUuid = first.uuid,
                metricId = metric.id, metricUuid = metric.uuid, promptOnComplete = true,
                uuid = id(9800), createdAt = millis, updatedAt = millis)
            db.habitMetricLinkDao().insert(link)
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
            listOf(first, saved)
        }
        habit = first
        preferences.setNeverAskAgain(local(), habit.uuid, false)
        assertEquals(0, count("sync_outbox")); assertEquals(0, count("completions"))
        val core = requireNotNull(tokens.localCoreWriteAccess())
        val registered = access()
        val produced = producer().write(core.session) {
            List(history) { index ->
                currentCoroutineContext().ensureActive()
                require(LocalDate.now(zone) == today && ZoneId.systemDefault() == zone)
                val row = rows[index % 2]; val day = today.minusDays(index / 2L + 1)
                val at = day.atTime(12, 0).atZone(zone).toInstant()
                val fact = CompletionEntity(habitId = row.id, habitUuid = row.uuid,
                    date = day.toDisplayMillis(zone), value = 1, actualCompletedAt = at.toEpochMilli(),
                    createdAt = at.toEpochMilli(), recordedTimezone = zone.id, recordedLocalDate = day.toString())
                NextCountDayStore(db).capture(row, fact)
                fact.copy(id = db.completionDao().insertForSync(fact))
            }
        }
        val http = channel { successReply(it) }.first
        val acceptedFacts = sessions.exclusive {
            require(tokens.localCoreWriteAccess() == core && tokens.isCurrentSyncAuthority(registered))
            db.withTransaction {
                val sql = db.openHelper.writableDatabase
                val native = CountTrustRows(sql)
                val mutable = setOf("completions", "sync_outbox", "next_transmissions", "next_acceptances", "sync_entity_state")
                val unchanged = (CountTrustSchema.BUSINESS_TABLES - mutable).associateWith { table -> native.read(table).map { it.value } }
                val entries = produced.map { fact ->
                    val queue = db.syncOutboxDao().getEntityIntents("completion", fact.uuid).single()
                    val origin = requireNotNull(originalIntent(queue))
                    val sourceHash = requireNotNull(NextRequestSql.rowHash(db, "sync_outbox", "id=?", arrayOf(queue.id)))
                    assertEquals(sourceHash, NextRequestSql.sourceHash(queue)); assertEquals(sourceHash, origin.sourceHash)
                    assertEquals(core.session.authentication.userId, origin.accountId)
                    assertEquals(5, origin.protocol); assertEquals(queue.id, origin.queueId)
                    val day = requireNotNull(NextCountDayStore(db).read(rows.single { it.id == fact.habitId }, fact.recordedLocalDate))
                    assertEquals(origin.requestId, day.originRequestId)
                    Entry(fact, queue, origin, sourceHash, requireNotNull(NextRequestSql.rowHash(db,
                        "next_request_origins", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, origin.requestId))), day)
                }
                val mapped = entries.map { entry ->
                    currentCoroutineContext().ensureActive()
                    val operation = decodeNextOperationIntent(entry.origin.intentJson)
                    assertNull(roundOperationIntent(entry.origin.intentJson)); assertNull(countPlanFrontier(entry.origin.intentJson))
                    assertEquals("activity_event", operation.entityType); assertEquals("upsert", operation.action)
                    val device = requireNotNull(registered.deviceId)
                    val wire = encodeSyncRequest(NextSyncPushRequest.serializer(), NextSyncPushRequest(device, listOf(operation)))
                    assertEquals(operation, validateNextOperationEnvelope(entry.origin.intentJson, wire, device))
                    db.nextRequestDao().insertTransmission(NextTransmissionEntity(NEXT_OPERATION, entry.origin.requestId,
                        entry.queue.id, 5, entry.origin.accountId, requireNotNull(registered.session.serverInstanceId),
                        requireNotNull(registered.session.syncEpoch), device, nextRequestHash(wire), wire))
                    val response = decodeFrozenSyncRequest(successReply(MaterialSocketServer.Input("POST",
                        "/api/v2/sync/push", emptyMap(), wire)).bytes, NextSyncPushResponse.serializer())
                    val result = response.results.single().copy(status = "applied")
                    val change = NextOrdinaryResultMapper.validate(operation, result, device)
                    val owner = rows.single { it.uuid == entry.fact.habitUuid }
                    val actual = NextCommonFactMapper.completion(change, owner, entry.fact)
                    assertEquals(entry.fact.copy(createdAt = actual.createdAt), actual)
                    val shadow = SyncEntityStateEntity(change.entityType, change.entityUuid, change.revision,
                        payloadJson = change.payload.toString(), payloadHash = syncPayloadHash(change.payload.toString()))
                    NextRequestSql.requireOutboxEnabled(sql)
                    sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
                    assertEquals(actual.id, db.completionDao().upsert(actual)); db.syncOutboxDao().upsertState(shadow)
                    sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
                    val sentHash = requireNotNull(NextRequestSql.rowHash(db, "next_transmissions", "kind=? AND requestId=?",
                        arrayOf(NEXT_OPERATION, entry.origin.requestId)))
                    val bytes = encodeSyncRequest(NextSyncOperationResult.serializer(), result)
                    decodeFrozenSyncRequest(bytes, NextSyncOperationResult.serializer())
                    db.nextRequestDao().insertAcceptance(NextAcceptanceEntity(NEXT_OPERATION, entry.origin.requestId,
                        entry.originHash, sentHash, nextRequestHash(bytes), bytes.toString(Charsets.UTF_8)))
                    assertEquals(entry.sourceHash, NextRequestSql.rowHash(db, "sync_outbox", "id=?", arrayOf(entry.queue.id)))
                    db.syncOutboxDao().deleteById(entry.queue.id)
                    actual
                }
                val original = sender(http)
                for (entry in entries) {
                    currentCoroutineContext().ensureActive()
                    original.requireAcceptedCountInTransaction(registered, entry.origin.requestId)
                    assertEquals(entry.origin, db.nextRequestDao().origin(NEXT_OPERATION, entry.origin.requestId))
                    assertEquals(entry.day, NextCountDayStore(db).read(rows.single { it.id == entry.fact.habitId }, entry.fact.recordedLocalDate))
                }
                val reader = RecurringCompletionEvidenceReader(db, tokens, sessions)
                for (row in rows) {
                    val evidence = reader.readInTransaction(row, today)
                    val expected = mapped.filter { it.habitId == row.id }
                    assertEquals(expected.toSet(), evidence.completions.toSet())
                    assertEquals(expected.map { LocalDate.parse(it.recordedLocalDate) }.associateWith {
                        CountDayPolicy(row.targetValue, false) }, evidence.policies)
                    assertTrue(evidence.unknownDates.isEmpty()); assertNull(evidence.roundHead)
                }
                assertEquals(unchanged, unchanged.keys.associateWith { table -> native.read(table).map { it.value } })
                for (table in listOf("completions", "count_days", "next_request_origins", "next_transmissions", "next_acceptances", "sync_entity_state"))
                    assertEquals(table, history, count(table))
                assertEquals(0, count("sync_outbox"))
                require(tokens.localCoreWriteAccess() == core && tokens.isCurrentSyncAuthority(registered))
                mapped
            }
        }
        require(LocalDate.now(zone) == today && ZoneId.systemDefault() == zone)
        Seed(history, rows, today, zone, http, acceptedFacts)
    }
}
