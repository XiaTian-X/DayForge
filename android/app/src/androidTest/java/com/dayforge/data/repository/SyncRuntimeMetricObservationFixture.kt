package com.dayforge.data.repository

import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.domain.service.AccountIconController
import org.junit.Assert.*

/** Real formal-metric entry for these two runtime fixtures only. Appearance does not select
 * a round protocol: the actual editor snapshot must match the explicitly expected profile.
 * No outer account lock/Room transaction, synthetic fact/source or network request is used.
 */
internal suspend fun NextCoreRequestFixture.recordRuntimeMetricObservation(
    expectedRound: Boolean, note: String
): SyncOutboxEntity {
    assertFalse("The actual editor must own its producer transaction", db.inTransaction())
    val previousLogs = db.metricLogDao().getAllLogsForMetric(metric.id).map { it.id }.toSet()
    val previousQueue = db.syncOutboxDao().getAll().map { it.operationId }.toSet()
    val previousOrigins = db.openHelper.writableDatabase.query("SELECT count(*) FROM main.next_request_origins").use {
        assertTrue(it.moveToFirst()); it.getLong(0).also { _ -> assertFalse(it.moveToNext()) }
    }
    val previousNetwork = servers.map { it.requests.size }
    val icons = AccountIconController(app, tokens, sessions)
    val inserted = try {
        val editor = NextObjectEditor(db, tokens, sessions, icons)
        val selected = editor.metric(metric.id)
        val actual = requireNotNull(selected.value)
        val authority = requireNotNull(selected.authority)
        assertEquals(metric.uuid, actual.uuid); assertNotNull(actual.appearance)
        assertEquals("The original plain/round contract must not be inferred from appearance",
            expectedRound, authority.rounds != null)
        val repository = MetricRepository(db, db.metricDao(), db.metricLogDao(), db.habitDao(),
            db.habitMetricLinkDao(), nextObjectEditor = editor)
        repository.recordValue(actual.id, 21.125, note, millis,
            authority = authority, expectedMetricUuid = actual.uuid)
    } finally {
        icons.awaitImages(); icons.close()
    }
    val logs = db.metricLogDao().getAllLogsForMetric(metric.id)
    val saved = logs.filter { it.id !in previousLogs }.single()
    assertEquals(previousLogs.size + 1, logs.size)
    assertEquals(inserted, saved.id); assertEquals(21.125, saved.value, 0.0)
    assertEquals(note, saved.note); assertEquals(millis, saved.date)
    val queue = db.syncOutboxDao().getAll()
    val source = queue.filter { it.operationId !in previousQueue }.single()
    assertEquals(previousQueue.size + 1, queue.size)
    assertEquals("metric_log", source.recordType); assertEquals(saved.uuid, source.entityUuid)
    val origin = requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION, source.operationId))
    assertEquals(source.operationId, origin.requestId); assertEquals(source.id, origin.queueId)
    assertEquals(5, origin.protocol)
    assertEquals(expectedRound, roundOperationIntent(origin.intentJson) != null)
    val operation = decodeNextOperationIntent(origin.intentJson)
    assertEquals("metric_observation", operation.entityType); assertEquals(saved.uuid, operation.entityUuid)
    assertEquals(SyncV2Mapper.metricObservation(saved, metric.uuid), operation.payload)
    assertEquals(previousOrigins + 1L, db.openHelper.writableDatabase.query("SELECT count(*) FROM main.next_request_origins").use {
        assertTrue(it.moveToFirst()); it.getLong(0).also { _ -> assertFalse(it.moveToNext()) }
    })
    assertEquals(previousNetwork, servers.map { it.requests.size })
    return source
}
