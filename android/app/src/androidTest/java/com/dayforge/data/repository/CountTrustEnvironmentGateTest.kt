package com.dayforge.data.repository

import android.database.sqlite.SQLiteDatabase
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.history.CountTrustIndex
import com.dayforge.data.local.*
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.util.DateTimeUtils
import java.security.KeyStore
import javax.crypto.Mac
import javax.crypto.SecretKey
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real builder/kernel under the normal static platform environment. Old-environment certificates
 * below are explicitly synthetic authenticated fixtures, not claims of a real OS/process update.
 * Run separately from CountTrustValidationEnvironmentTest's isolated custom-provider process.
 */
@RunWith(AndroidJUnit4::class)
class CountTrustEnvironmentGateTest : NextCoreRequestFixture() {
    private val completeProofReads = AtomicInteger()
    private val cancelIssuance = AtomicBoolean()
    private val cancellationReached = AtomicBoolean()
    private val cancelTarget = AtomicReference<Job?>()
    private val oldValueReads = AtomicInteger()
    private val watchedOldValue = AtomicReference<String?>()
    private val callback = object : RoomDatabase.QueryCallback {
        override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
            val sql = sqlQuery.lowercase().replace("`", "").replace(Regex("\\s+"), " ")
            if (sql.startsWith("select value,habituuid,onetimeaction,recordedlocaldate,")) completeProofReads.incrementAndGet()
            if (sql.startsWith("select typeof(body),length(body),body from count_trust_nodes") &&
                watchedOldValue.get() != null && bindArgs.firstOrNull() == watchedOldValue.get()) oldValueReads.incrementAndGet()
            // Root storage is mandatory even when all rebuilt content-addressed nodes already
            // exist. This observes the root-write request, not a physical COMMIT completion.
            if (sql.startsWith("insert or replace into count_trust_root(") && cancelIssuance.compareAndSet(true, false)) {
                cancellationReached.set(true)
                requireNotNull(cancelTarget.get()).cancel(CancellationException("COUNT_TRUST_TEST_ENVIRONMENT_CANCEL"))
            }
        }
    }
    init { storage.isolatedBuilder = { context -> HabitDatabaseProvider.build(context, callback) } }
    private val sql get() = db.openHelper.writableDatabase
    private val runtime get() = requireNotNull(CountTrustRuntime.forDatabase(db))
    private fun reader() = CountHistoryReader(db, tokens, sessions)
    private fun root() = requireNotNull(CountTrustSeal.read(sql))
    private fun dirty(): Long = sql.query("SELECT dirty FROM count_trust_control WHERE id=1").use {
        assertTrue(it.moveToFirst()); it.getLong(0)
    }
    private suspend fun currentHabit() = requireNotNull(db.habitDao().getHabitById(habit.id))

    private suspend fun append(number: Int, date: LocalDate = DateTimeUtils.today()): SyncOutboxEntity {
        val habit = currentHabit()
        val zone = ZoneId.of("Asia/Shanghai")
        val instant = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val fact = CompletionEntity(habitId=habit.id, habitUuid=habit.uuid, uuid=id(80_000 + number),
            date=date.atStartOfDay(zone).toInstant().toEpochMilli(), actualCompletedAt=instant,
            recordedTimezone=zone.id, recordedLocalDate=date.toString(), createdAt=instant)
        producer().write(local()) {
            NextCountDayStore(db).capture(habit, fact); db.completionDao().insertForSync(fact)
        }
        return db.syncOutboxDao().getAll().single { it.entityUuid == fact.uuid }
    }

    private suspend fun ready() {
        register(); append(1, DateTimeUtils.today().minusMonths(13))
        reader().readSummary(currentHabit())
        assertEquals("The real kernel must qualify: " + CountTrustKernel.lastQualificationFailure, 0L, dirty())
        assertEquals(CountTrustSeal.VALIDATOR, root().validator)
        assertEquals(requireNotNull(CountTrustValidationEnvironment.current()), environment())
    }

    private suspend fun environment(): CountTrustValidationEnvironment.Snapshot = db.withTransaction {
        runtime.maintenance(db) {
            val header = root()
            val index = CountTrustIndex(sql, header.binding, header.root) {}
            CountTrustValidationEnvironment.Snapshot.decode(requireNotNull(index.get(CountTrustValidationEnvironment.KEY)))
        }
    }

    /** Uses existing test maintenance/seal helpers. No production environment setter is added. */
    private suspend fun replaceEnvironment(value: JsonElement?): CountTrustSeal.Header = db.withTransaction {
        runtime.maintenance(db) {
            val old = root()
            val index = CountTrustIndex(sql, old.binding, old.root) {}
            index.putAll(mapOf(CountTrustValidationEnvironment.KEY to value))
            CountTrustSeal().prepare(old.binding, old.schemaHash, index.root, old.revision + 1, old.generation)
                .also { CountTrustSeal.store(sql, it) }
        }
    }

    private suspend fun installExpiredEnvironment(): CountTrustSeal.Header {
        val body = requireNotNull(CountTrustValidationEnvironment.current()).toJson()
        val expired = JsonObject(body + ("buildFingerprint" to JsonPrimitive(body.getValue("buildFingerprint")
            .jsonPrimitive.content + "/synthetic-prior-environment")))
        assertNotEquals(CountTrustValidationEnvironment.current(), CountTrustValidationEnvironment.Snapshot.decode(expired))
        return replaceEnvironment(expired)
    }

    private suspend fun historicalValueDigest(): String = db.withTransaction {
        runtime.maintenance(db) {
            val row = CountTrustRows(sql).read("completions").single()
            val bytes = row.value.toString().toByteArray(Charsets.UTF_8)
            check(bytes.size <= CountTrustIndex.CHUNK_BYTES)
            CountTrustIndex.hash(bytes).also { digest ->
                sql.query("SELECT body FROM count_trust_nodes WHERE digest=?", arrayOf(digest)).use {
                    assertTrue(it.moveToFirst()); assertArrayEquals(bytes, it.getBlob(0))
                }
            }
        }
    }

    private suspend fun installLegacy(validator: String): CountTrustSeal.Header = db.withTransaction {
        val access = requireNotNull(tokens.localCoreWriteAccess())
        runtime.maintenance(db) {
            val old = root()
            val storageId = sql.query("SELECT storageId FROM count_trust_control WHERE id=1").use {
                assertTrue(it.moveToFirst()); it.getString(0)
            }
            val binding = CountTrustSeal.binding(access, storageId, validator)
            val contents = mapOf("format" to JsonPrimitive(validator), "eligible:${habit.uuid}" to JsonPrimitive(true),
                "retired/environment-test-content" to JsonPrimitive("authenticated old-version content"))
            val oldRoot = CountTrustIndex.build(sql, binding, contents) {}
            CountTrustSeal().prepare(binding, old.schemaHash, oldRoot, old.revision + 1, old.generation, validator)
                .also { CountTrustSeal.store(sql, it) }
        }
    }

    /** Frozen v1/v2 root framing from baseline Seal 7a28baaf..., independent of candidate
     * prepare/matches. The device-local key is real, but no production encoder is reused here.
     */
    private fun frozenOldMac(header: CountTrustSeal.Header): ByteArray {
        require(header.validator in setOf(
            "count-trust/plain-recurring-v1/native-members-and-rules/2026-10-22",
            "count-trust/round-recurring-v2/native-members-and-rules/2026-10-23"))
        val body = buildJsonObject {
            put("domain", "DayForge/count-trust-root"); put("format", 1); put("validator", header.validator)
            put("binding", header.binding); put("schema", header.schemaHash); put("index", "count-trust-v1")
            put("scope", "directory/1970-01/null-round/open-bounds")
            put("height", header.root.height); put("count", header.root.count); put("digest", header.root.digest)
            put("revision", header.revision); put("generation", header.generation)
        }.toString().toByteArray(Charsets.UTF_8)
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .getKey("dayforge.count.trust.root.v1", null) as SecretKey
        return Mac.getInstance("HmacSHA256").run { init(key); doFinal(body) }
    }

    private fun editClosed(block: (SQLiteDatabase) -> Unit) {
        db.close()
        SQLiteDatabase.openDatabase(app.getDatabasePath("habit_database").path, null, SQLiteDatabase.OPEN_READWRITE).use(block)
        storage.reopen().openHelper.writableDatabase
        assertFalse(runtime.isRetired)
    }

    private fun corruptValue(digest: String) = editClosed { raw ->
        val bytes = raw.rawQuery("SELECT body FROM count_trust_nodes WHERE digest=?", arrayOf(digest)).use {
            assertTrue(it.moveToFirst()); it.getBlob(0)
        }
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        raw.execSQL("UPDATE count_trust_nodes SET body=? WHERE digest=?", arrayOf(bytes, digest))
    }

    private suspend fun assertRejectedWithoutReissue(): Throwable {
        val before = root()
        var published = false
        val failure = runCatching { reader().readSummary(currentHabit()); published = true }.exceptionOrNull()
        assertNotNull(failure); assertFalse(published)
        assertEquals(before, root()); assertEquals(0L, dirty())
        return requireNotNull(failure)
    }

    @Test fun sameEnvironmentReopenAndWarmWriteSendAckRetainIncrementalQualification() = runBlocking<Unit> {
        ready()
        val expectedEnvironment = requireNotNull(CountTrustValidationEnvironment.current())
        val before = root()
        storage.reopen().openHelper.writableDatabase
        completeProofReads.set(0)
        assertEquals(1L, reader().readSummary(currentHabit()).totalQuantity)
        assertEquals(before, root()); assertEquals(0, completeProofReads.get())
        val request = append(2)
        val written = root(); assertTrue(written.revision > before.revision); assertEquals(0L, dirty())
        val (http, _) = channel { successReply(it) }
        val store = sender(http)
        val delivery = requireNotNull(store.sendOperation(access(), request.operationId))
        val sent = root(); assertTrue(sent.revision > written.revision)
        assertEquals(NextOperationAcceptance.COMMITTED, store.acceptOperation(delivery))
        val accepted = root(); assertTrue(accepted.revision > sent.revision); assertEquals(0L, dirty())
        assertEquals(NextOperationAcceptance.REPLAYED, store.acceptOperation(delivery)); assertEquals(accepted, root())
        assertEquals(0, completeProofReads.get()); assertEquals(expectedEnvironment, environment())
        assertSame(expectedEnvironment, CountTrustValidationEnvironment.current())
        val actual = reader().read(currentHabit())
        val original = RecurringCompletionEvidenceReader(db, tokens, sessions).read(currentHabit())
        assertEquals(original.completions, actual.completions)
    }

    @Test fun authenticatedExpiredEnvironmentVerifiesOldContentsThenRequalifiesExactlyOnce() = runBlocking<Unit> {
        ready()
        val oldValue = historicalValueDigest()
        val expired = installExpiredEnvironment()
        storage.reopen().openHelper.writableDatabase
        watchedOldValue.set(oldValue); oldValueReads.set(0); completeProofReads.set(0)
        assertEquals(1L, reader().readSummary(currentHabit()).totalQuantity)
        assertTrue("Every old reachable value must be checked", oldValueReads.get() > 0)
        assertTrue("Old environment must execute original business proof", completeProofReads.get() > 0)
        val rebuilt = root(); assertEquals(expired.revision + 1, rebuilt.revision)
        assertEquals(requireNotNull(CountTrustValidationEnvironment.current()), environment())
        storage.reopen().openHelper.writableDatabase; completeProofReads.set(0)
        reader().readSummary(currentHabit())
        assertEquals(rebuilt, root()); assertEquals(0, completeProofReads.get())
    }

    @Test fun knownV1AndV2RootsRemainMacCompatibleButRequireCompleteRequalification() = runBlocking<Unit> {
        ready()
        for (validator in listOf(CountTrustSeal.LEGACY_VALIDATOR, CountTrustSeal.LEGACY_ROUND_VALIDATOR)) {
            val old = installLegacy(validator)
            assertArrayEquals("Old MAC bytes must match independent frozen framing", frozenOldMac(old), old.mac())
            assertTrue(CountTrustSeal().matches(old, old.binding, old.schemaHash))
            storage.reopen().openHelper.writableDatabase; completeProofReads.set(0)
            assertEquals(1L, reader().readSummary(currentHabit()).totalQuantity)
            assertTrue(completeProofReads.get() > 0)
            assertEquals(CountTrustSeal.VALIDATOR, root().validator)
            assertEquals(old.revision + 1, root().revision)
            assertEquals(requireNotNull(CountTrustValidationEnvironment.current()), environment())
        }
    }

    @Test fun currentValidatorMissingOrMalformedEnvironmentIsNotAnOldVersionMiss() = runBlocking<Unit> {
        ready()
        val good = requireNotNull(CountTrustValidationEnvironment.current()).toJson()
        replaceEnvironment(null)
        assertRejectedWithoutReissue()
        replaceEnvironment(JsonObject(good + ("zoneCount" to JsonPrimitive("1"))))
        assertRejectedWithoutReissue()
        replaceEnvironment(good)
        assertEquals(1L, reader().readSummary(currentHabit()).totalQuantity)
    }

    @Test fun expiredEnvironmentCannotHideBadMac() = runBlocking<Unit> {
        ready(); installExpiredEnvironment()
        editClosed { it.execSQL("UPDATE count_trust_root SET mac=? WHERE id=1", arrayOf(ByteArray(32))) }
        completeProofReads.set(0)
        val failure = assertRejectedWithoutReissue()
        assertTrue(failure.toString().contains("COUNT_TRUST_SEAL_INVALID"))
        assertEquals(0, completeProofReads.get())
    }

    @Test fun expiredEnvironmentCannotHideUnselectedHistoricalValueCorruption() = runBlocking<Unit> {
        ready(); val oldValue = historicalValueDigest(); installExpiredEnvironment()
        corruptValue(oldValue); completeProofReads.set(0)
        val failure = assertRejectedWithoutReissue()
        assertTrue(failure.toString().contains("COUNT_TRUST_NODE_CHANGED"))
        assertEquals(0, completeProofReads.get())
    }

    @Test fun knownOldValidatorCannotHideUnselectedValueCorruption() = runBlocking<Unit> {
        ready(); installLegacy(CountTrustSeal.LEGACY_ROUND_VALIDATOR)
        val value = JsonPrimitive("authenticated old-version content").toString().toByteArray(Charsets.UTF_8)
        corruptValue(CountTrustIndex.hash(value)); completeProofReads.set(0)
        assertRejectedWithoutReissue()
        assertEquals(0, completeProofReads.get())
    }

    @Test fun cancellationDuringExpiredEnvironmentRequalificationPreservesTheOldRoot() = runBlocking<Unit> {
        ready(); val old = installExpiredEnvironment()
        val nodes = count("count_trust_nodes")
        completeProofReads.set(0)
        val published = AtomicBoolean()
        supervisorScope {
            val job = async(Dispatchers.IO, start=CoroutineStart.LAZY) {
                reader().readSummary(currentHabit()); published.set(true)
            }
            cancelTarget.set(job); cancelIssuance.set(true)
            assertNotNull(runCatching { job.await() }.exceptionOrNull())
        }
        assertTrue("Cancellation must reach the root-store SQL request", cancellationReached.get())
        assertTrue("Original full proof must precede the replacement root", completeProofReads.get() > 0)
        assertFalse(published.get())
        assertEquals(old, root()); assertEquals(nodes, count("count_trust_nodes")); assertEquals(0L, dirty())
        cancelTarget.set(null); cancelIssuance.set(false)
        reader().readSummary(currentHabit())
        assertEquals(old.revision + 1, root().revision)
        assertEquals(requireNotNull(CountTrustValidationEnvironment.current()), environment())
    }
}
