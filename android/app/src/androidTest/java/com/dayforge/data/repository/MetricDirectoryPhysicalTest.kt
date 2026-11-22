package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.ReplicaBusinessReferenceSchema
import com.dayforge.data.local.entity.MetricLogEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Physical native rows/SQL are the oracle. This suite tests directory/GC boundaries,
 * not command legitimacy, HTTP provenance or release-device latency. */
@RunWith(AndroidJUnit4::class)
class MetricDirectoryPhysicalTest : CountNodeGcFixture() {
    private suspend fun seed(dates: List<Long>) = db.withTransaction {
        sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
        db.metricLogDao().insertAll(dates.mapIndexed { n, at -> MetricLogEntity(metricId = metric.id,
            uuid = id(1000 + n), date = at, value = n.toDouble(), unit = metric.unit, recordedTimezone = "UTC") })
        sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
    }
    private suspend fun metricReady() {
        register()
        val editor = NextObjectEditor(db, tokens, sessions, io.mockk.mockk<com.dayforge.domain.service.AccountIconController>())
        MetricDisplayReader(db, tokens, sessions, editor).overview()
        db.withTransaction { CountTrustKernel(db, tokens, sessions).withOpen(requireNotNull(tokens.localCoreWriteAccess())) {
            assertTrue(requireNotNull(it).metricRanges)
        } }
        ReplicaBusinessReferenceSchema.read(app)
    }
    private suspend fun <T> current(block: suspend (CountTrustKernel.Context) -> T): T = db.withTransaction {
        CountTrustKernel(db, tokens, sessions).withOpen(requireNotNull(tokens.localCoreWriteAccess())) { context ->
            assertNotNull(context); block(requireNotNull(context))
        }
    }

    @Test(timeout = 150_000) fun sameDateSpansLeavesButLatestIsOriginalDaoAndPageIncludesWholeCohort() = runBlocking<Unit> {
        seed(listOf(millis - 1) + List(129) { millis } + listOf(millis + 1)); metricReady()
        current { context -> context.index.withReadScope {
            val native = context.rows.read("metrics", "id=?", arrayOf(metric.id)).single()
            val reader = MetricTrustDirectory.Reader(context.index::get)
            val manifest = requireNotNull(reader.manifest(native)); assertEquals(131L, manifest.root.count)
            val selected = requireNotNull(db.metricLogDao().getLatestLog(metric.id))
            reader.latest(manifest, context.rows.read("metric_logs", "id=?", arrayOf(selected.id)).single())
            val window = context.rows.read("metric_logs", "metricId=? AND date>=? AND date<?", arrayOf(metric.id, millis, millis + 1))
            reader.range(manifest, millis, millis + 1, window); assertEquals(129, window.size)
            val first = MetricTrustProjection(db).page(context, metric, target = 10)
            assertEquals(130, first.logs.size); assertFalse(first.complete); assertEquals(millis, first.nextExclusiveDate)
            assertEquals(first.logs.size, first.logs.map { it.uuid }.distinct().size)
            val second = MetricTrustProjection(db).page(context, metric, first.nextExclusiveDate, 10, first.directoryRevision)
            assertEquals(1, second.logs.size); assertTrue(second.complete)
            assertEquals(db.metricLogDao().getAllLogsForMetric(metric.id).map { it.uuid }.toSet(),
                (first.logs + second.logs).map { it.uuid }.toSet())
        } }
    }

    @Test(timeout = 150_000) fun signedLongKeyOrderMatchesActualSqlWithoutOverflowBoundaryArithmetic() = runBlocking<Unit> {
        val dates = listOf(Long.MAX_VALUE, 1L, 0L, -1L, Long.MIN_VALUE)
        val actual = db.withTransaction { sql.query(
            "SELECT ? AS value UNION ALL SELECT ? UNION ALL SELECT ? UNION ALL SELECT ? UNION ALL SELECT ? ORDER BY value",
            dates.toTypedArray()).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } } }
        assertEquals(actual, dates.sortedBy { MetricTrustDirectory.orderedKey(it, id(99)) })
        seed(listOf(millis)); metricReady()
        current { context -> context.index.withReadScope {
            val parent = context.rows.read("metrics", "id=?", arrayOf(metric.id)).single()
            val reader = MetricTrustDirectory.Reader(context.index::get)
            val manifest = requireNotNull(reader.manifest(parent))
            reader.range(manifest, Long.MIN_VALUE, Long.MIN_VALUE, emptyList())
            reader.range(manifest, millis + 1, Long.MAX_VALUE, emptyList())
        } }
    }

    @Test(timeout = 150_000) fun declaredMissingChildIsRejectedInsteadOfOptionalFallback() = runBlocking<Unit> {
        seed(List(129) { millis }); metricReady()
        current { context -> context.index.withReadScope {
            val native = context.rows.read("metrics", "id=?", arrayOf(metric.id)).single()
            val manifest = requireNotNull(MetricTrustDirectory.Reader(context.index::get).manifest(native))
            var missed = false
            val faulty = MetricTrustDirectory.Reader { name ->
                if (name.startsWith("metric-display/v1/${metric.uuid}/node/") && !name.endsWith("/root")) { missed = true; null }
                else context.index.get(name)
            }
            val failure = runCatching { faulty.range(manifest, millis, millis + 1, context.rows.read("metric_logs")) }.exceptionOrNull()
            assertNotNull(failure); assertTrue(missed)
            assertEquals("METRIC_DIRECTORY_CHILD_MISSING", failure!!.message)
        } }
    }

    @Test(timeout = 150_000) fun wrongMetricChildAndTombstoneCannotRepresentAValidEmptyLeaf() = runBlocking<Unit> {
        metricReady()
        current { context -> context.index.withReadScope {
            val native = context.rows.read("metrics", "id=?", arrayOf(metric.id)).single()
            val key = "metric-display/v1/${metric.uuid}/node/root"
            val original = requireNotNull(context.index.get(key)).jsonObject
            val wrong = JsonObject(original + ("metric" to JsonPrimitive(id(990))))
            assertNotNull(runCatching { MetricTrustDirectory.Reader { if (it == key) wrong else context.index.get(it) }.manifest(native) }.exceptionOrNull())
            assertNotNull(runCatching { MetricTrustDirectory.Reader { if (it == key) null else context.index.get(it) }.manifest(native) }.exceptionOrNull())
            val reader = MetricTrustDirectory.Reader(context.index::get)
            val manifest = requireNotNull(reader.manifest(native)); assertEquals(0L, manifest.root.count)
            reader.latest(manifest, null); reader.range(manifest, Long.MIN_VALUE, Long.MAX_VALUE, emptyList())
        } }
    }

    @Test(timeout = 150_000) fun unsupportedLegacyDuplicateIdentityKeepsOriginalRowsRatherThanCollapsingAKey() = runBlocking<Unit> {
        seed(listOf(millis, millis));
        db.withTransaction {
            val rows = db.metricLogDao().getAllLogsForMetric(metric.id)
            db.metricLogDao().update(rows.last().copy(uuid = rows.first().uuid))
            val native = CountTrustRows(sql)
            val original = db.metricLogDao().getAllLogsForMetric(metric.id)
            assertEquals(2, original.size)
            val values = MetricTrustDirectory.issue(native.read("metrics"), native.read("metric_logs"))
            assertFalse(MetricTrustDirectory.Reader(values::get).declared(metric.uuid))
            assertEquals(original, db.metricLogDao().getAllLogsForMetric(metric.id))
        }
    }

    @Test(timeout = 150_000) fun actualCollectorRetainsNamedValueChunksAndColdReopenCanReadCurrentRanges() = runBlocking<Unit> {
        seed(List(129) { millis }); metricReady(); orphan(552)
        val live = reachable(); val before = nativeAssertions().capture()
        val reports = finish(); assertTrue(reports.sumOf { it.deletedNodes } >= 1)
        assertOnlyUnreachableRemoved(before, live)
        storage.reopen()
        current { context -> context.index.withReadScope {
            val parent = context.rows.read("metrics", "id=?", arrayOf(metric.id)).single()
            val reader = MetricTrustDirectory.Reader(context.index::get)
            val manifest = requireNotNull(reader.manifest(parent)); reader.verifyAll(metric.uuid)
            reader.range(manifest, millis, millis + 1, context.rows.read("metric_logs"))
            val selected = requireNotNull(db.metricLogDao().getLatestLog(metric.id))
            reader.latest(manifest, context.rows.read("metric_logs", "id=?", arrayOf(selected.id)).single())
        } }
    }
    /** Storage-transform fixture only: exercise real native dates/deletes and the pure
     * updater, then install its named values under the original physical root. This does
     * NOT claim that arbitrary log edits are a production business profile. Source-health
     * reuse is explicitly removed; production profile legality has separate ACK tests. */
    private suspend fun updateNative(block: suspend () -> Unit) = db.withTransaction {
        CountTrustKernel(db, tokens, sessions).withOpen(requireNotNull(tokens.localCoreWriteAccess())) { opened ->
            val context = requireNotNull(opened)
            val before = context.rows.read("metric_logs").associateBy { it.name }
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            block()
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
            val after = context.rows.read("metric_logs").associateBy { it.name }
            val changes = (before.keys + after.keys).mapNotNull { name ->
                val old = before[name]; val fresh = after[name]
                if (old?.value == fresh?.value) null else old to fresh
            }
            assertTrue("The physical DAO mutation must actually change native input", changes.isNotEmpty())
            context.index.withReadScope {
                val edits = linkedMapOf<String, JsonElement?>()
                for ((old, fresh) in changes) edits[(old ?: requireNotNull(fresh)).name] = fresh?.value
                val keys = changes.flatMap { (old, fresh) -> listOfNotNull(old, fresh).flatMap { CountTrustMembership.memberships(it).keys } }.toSet()
                for (key in keys) {
                    val members = context.members(key).toMutableSet()
                    for ((old, fresh) in changes) {
                        old?.let { if (key in CountTrustMembership.memberships(it)) members.remove(it.name) }
                        fresh?.let { if (key in CountTrustMembership.memberships(it)) members.add(it.name) }
                    }
                    edits[key] = JsonArray(members.sorted().map(::JsonPrimitive))
                }
                edits.putAll(MetricTrustDirectory.update(context.index::get, changes, context.rows.read("metrics")))
                edits[MetricCommandAbsenceEvidence.KEY] = JsonPrimitive(false)
                edits["global_common"] = JsonPrimitive(false)
                owner.maintenance(db) {
                    context.index.putAll(edits)
                    val generation = sql.query("SELECT generation FROM count_trust_control WHERE id=1").use {
                        check(it.moveToFirst()); it.getLong(0)
                    }
                    CountTrustSeal.store(sql, CountTrustSeal().prepare(context.header.binding, context.header.schemaHash,
                        context.index.root, context.header.revision + 1, generation))
                }
            }
        }
    }
    private suspend fun matchesOriginalDao() = current { context ->
        val original = db.metricLogDao().getAllLogsForMetric(metric.id)
        assertEquals(original, MetricTrustProjection(db).history(context, metric))
        val selected = db.metricLogDao().getLatestLog(metric.id)
        assertEquals(selected, MetricTrustProjection(db).read(context, metric, 0, 0)?.latest)
        context.index.withReadScope {
            val manifest = requireNotNull(MetricTrustDirectory.Reader(context.index::get).manifest(
                context.rows.read("metrics", "id=?", arrayOf(metric.id)).single()))
            assertEquals(original.size.toLong(), manifest.root.count)
            MetricTrustDirectory.Reader(context.index::get).verifyAll(metric.uuid)
        }
    }
    @Test(timeout = 150_000) fun incrementalNativeInsertCrossesLeafLimitAndKeepsEveryDateAndIdentity() = runBlocking<Unit> {
        seed(List(128) { millis }); metricReady()
        updateNative { db.metricLogDao().insert(MetricLogEntity(metricId = metric.id, uuid = id(2559),
            date = millis + 86_400_000L, value = 19.5, unit = metric.unit, recordedTimezone = "UTC")) }
        current { context -> context.index.withReadScope {
            val root = requireNotNull(context.index.get("metric-display/v1/${metric.uuid}/node/root")).jsonObject
            assertEquals("branch", root.getValue("kind").jsonPrimitive.content)
            assertEquals(129L, root.getValue("summary").jsonObject.getValue("count").jsonPrimitive.long)
        } }
        matchesOriginalDao()
    }
    @Test(timeout = 150_000) fun nativeDateMoveThenDeleteMaxAndDeleteEmptyUseCurrentCompleteBounds() = runBlocking<Unit> {
        seed(listOf(millis, millis + 86_400_000L, millis + 172_800_000L)); metricReady()
        val oldest = db.metricLogDao().getAllLogsForMetric(metric.id).last()
        updateNative { db.metricLogDao().update(oldest.copy(date = millis + 259_200_000L)) }
        matchesOriginalDao(); assertEquals(oldest.id, db.metricLogDao().getLatestLog(metric.id)?.id)
        updateNative { db.metricLogDao().delete(requireNotNull(db.metricLogDao().getLatestLog(metric.id))) }
        matchesOriginalDao(); assertEquals(2, db.metricLogDao().getAllLogsForMetric(metric.id).size)
        updateNative { db.metricLogDao().getAllLogsForMetric(metric.id).forEach { db.metricLogDao().delete(it) } }
        matchesOriginalDao(); assertNull(db.metricLogDao().getLatestLog(metric.id))
        current { context -> context.index.withReadScope {
            val page = MetricTrustProjection(db).page(context, metric)
            assertTrue(page.logs.isEmpty()); assertEquals(0L, page.totalCount); assertTrue(page.complete)
        } }
    }
    @Test(timeout = 150_000) fun splitAndNativeDateChangesSurviveActualGcThenColdReopen() = runBlocking<Unit> {
        seed(List(128) { millis }); metricReady()
        updateNative { db.metricLogDao().insert(MetricLogEntity(metricId = metric.id, uuid = id(3559),
            date = millis + 86_400_000L, value = 4.5, unit = metric.unit, recordedTimezone = "UTC")) }
        updateNative {
            val max = requireNotNull(db.metricLogDao().getLatestLog(metric.id))
            db.metricLogDao().update(max.copy(date = millis - 86_400_000L))
        }
        matchesOriginalDao(); orphan(559)
        val live = reachable(); val before = nativeAssertions().capture()
        val reports = finish(); assertTrue(reports.sumOf { it.deletedNodes } >= 1)
        assertOnlyUnreachableRemoved(before, live)
        storage.reopen(); matchesOriginalDao()
    }

}
