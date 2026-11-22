package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.dto.SyncV2Change
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.CountDayPolicy
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.zone.ZoneOffsetTransition
import java.time.zone.ZoneRules
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Public Android SDK only. A/B rule values exercise the pure codec; actual original validators
 * use two real public zone IDs. Changing both fact/source zone IDs is an INPUT difference, not
 * unchanged bytes under an OS/TZDB update. No hidden provider, reflection or current reset exists.
 */
@RunWith(AndroidJUnit4::class)
class CountTrustValidationEnvironmentTest {
    @Before fun requireActualPlatformZones() {
        assertTrue(ZoneId.getAvailableZoneIds().containsAll(listOf("Etc/UTC", "Etc/GMT-1", "Europe/Paris")))
    }

    @Test fun publicFullRuleValuesWithTheSameLogicalIdProduceDifferentDigests() = runBlocking<Unit> {
        suspend fun digest(rules: ZoneRules) = CountTrustValidationEnvironment.digestRules(
            listOf(CountTrustValidationEnvironment.RuleEntry("Logical/SameId", rules)))
        val utc = ZoneRules.of(ZoneOffset.UTC)
        val plusOne = ZoneRules.of(ZoneOffset.ofHours(1))
        assertNotEquals(digest(utc), digest(plusOne))
        assertEquals(digest(utc), digest(ZoneRules.of(ZoneOffset.UTC)))
        // Same present offset is insufficient: retained historical transitions remain encoded.
        val changedIn2000 = ZoneRules.of(ZoneOffset.UTC, ZoneOffset.UTC, emptyList(),
            listOf(ZoneOffsetTransition.of(LocalDateTime.of(2000, 1, 1, 0, 0), ZoneOffset.UTC, ZoneOffset.ofHours(1))),
            emptyList())
        val now = Instant.parse("2026-10-06T12:00:00Z")
        assertEquals(plusOne.getOffset(now), changedIn2000.getOffset(now))
        assertNotEquals(digest(plusOne), digest(changedIn2000))
    }

    @Test fun actualPlatformCaptureIsStableAcrossRuleCacheWarming() = runBlocking<Unit> {
        val before = CountTrustValidationEnvironment.readPlatform()
        val paris = ZoneId.of("Europe/Paris")
        for (year in listOf(1900, 2026, 2099, 2400)) {
            LocalDate.of(year, 3, 31).atStartOfDay(paris)
            LocalDate.of(year, 10, 31).atTime(2, 30).atZone(paris)
        }
        assertEquals(before, CountTrustValidationEnvironment.readPlatform())
        assertEquals(before, CountTrustValidationEnvironment.Snapshot.decode(before.toJson()))
    }

    @Test fun pendingInputZoneDifferenceRejectsUnchangedDayOrMidnightProjection() {
        for (time in listOf("2026-10-06T23:30:00Z", "2026-10-06T12:00:00Z")) {
            val before = fact(time, "Etc/UTC")
            val changed = before.copy(recordedTimezone="Etc/GMT-1")
            val original = pending(before)
            val changedOriginal = pending(changed)
            assertEquals(before, changed.copy(recordedTimezone=before.recordedTimezone))
            assertEquals(JsonObject(original - "timezone"), JsonObject(changedOriginal - "timezone"))
            CountFactEvidenceRules.verify(habit, before, policy, original, null, false)
            assertNotNull("A consistent new input zone must not excuse a stale day/midnight: $time",
                runCatching { CountFactEvidenceRules.verify(habit, changed, policy, changedOriginal, null, false) }.exceptionOrNull())
        }
    }

    @Test fun acceptedInputZoneDifferenceRejectsUnchangedDayOrMidnightProjection() {
        for (time in listOf("2026-10-06T23:30:00Z", "2026-10-06T12:00:00Z")) {
            val before = fact(time, "Etc/UTC")
            val changed = before.copy(recordedTimezone="Etc/GMT-1")
            val original = accepted(before, time)
            val changedOriginal = accepted(changed, time)
            assertEquals(before, changed.copy(recordedTimezone=before.recordedTimezone))
            assertEquals(JsonObject(original.payload - "timezone"), JsonObject(changedOriginal.payload - "timezone"))
            assertEquals(before, NextCommonFactMapper.completion(original, habit, before))
            CountFactEvidenceRules.verify(habit, before, policy, null, original, false)
            assertNotNull("A consistent new accepted input zone must not excuse a stale day/midnight: $time",
                runCatching { CountFactEvidenceRules.verify(habit, changed, policy, null, changedOriginal, false) }.exceptionOrNull())
        }
    }

    @Test fun preCancelledCurrentAndMidEncodingCancellationDoNotPublishResults() = runBlocking<Unit> {
        val currentPublished = AtomicBoolean()
        supervisorScope {
            val job = async(start=CoroutineStart.LAZY) {
                requireNotNull(currentCoroutineContext()[Job]).cancel(CancellationException("COUNT_TRUST_TEST_PRE_CANCEL"))
                CountTrustValidationEnvironment.current()
                currentPublished.set(true)
            }
            assertTrue(runCatching { job.await() }.exceptionOrNull() is CancellationException)
        }
        assertFalse(currentPublished.get())
        val active = requireNotNull(CountTrustValidationEnvironment.current())
        assertEquals(active, CountTrustValidationEnvironment.readPlatform())

        val values = listOf("Logical/A", "Logical/B", "Logical/C").map {
            CountTrustValidationEnvironment.RuleEntry(it, ZoneRules.of(ZoneOffset.UTC))
        }
        val expectedDigest = CountTrustValidationEnvironment.digestRules(values)
        val accessed = AtomicInteger()
        val encodedPublished = AtomicBoolean()
        supervisorScope {
            val job = async(Dispatchers.Default, start=CoroutineStart.LAZY) {
                val caller = requireNotNull(currentCoroutineContext()[Job])
                // Ordinary codec input with a deterministic second-element cancellation. This
                // tests real partial encoding, not actual platform lookup/current mid-capture.
                val cancellingInput = object : AbstractList<CountTrustValidationEnvironment.RuleEntry>() {
                    override val size: Int get() = values.size
                    override fun get(index: Int): CountTrustValidationEnvironment.RuleEntry {
                        accessed.incrementAndGet()
                        if (index == 1) caller.cancel(CancellationException("COUNT_TRUST_TEST_ENCODING_CANCEL"))
                        return values[index]
                    }
                }
                CountTrustValidationEnvironment.digestRules(cancellingInput)
                encodedPublished.set(true)
            }
            assertTrue(runCatching { job.await() }.exceptionOrNull() is CancellationException)
        }
        assertEquals("The first entry was encoded; cancellation stopped the second and remaining work", 2, accessed.get())
        assertFalse(encodedPublished.get())
        assertEquals(expectedDigest, CountTrustValidationEnvironment.digestRules(values))
        assertSame(active, CountTrustValidationEnvironment.current())
    }

    private fun fact(time: String, zone: String): CompletionEntity {
        val instant = Instant.parse(time).toEpochMilli()
        return CompletionEntity(id=11, habitId=habit.id, habitUuid=habit.uuid, uuid=FACT,
            date=Instant.parse("2026-10-06T00:00:00Z").toEpochMilli(), value=1,
            actualCompletedAt=instant, createdAt=instant, recordedTimezone=zone,
            recordedLocalDate="2026-10-06", timeMetadataSource="captured")
    }

    private fun pending(fact: CompletionEntity): JsonObject = buildJsonObject {
        SyncV2Mapper.completion(fact, habit).forEach { (key, value) -> put(key, value) }
        put("count_policy", policy.toJson())
    }

    private fun accepted(fact: CompletionEntity, time: String): SyncV2Change = SyncV2Change(
        1, "activity_event", fact.uuid, "upsert", 1, buildJsonObject {
            SyncV2Mapper.completion(fact, habit).forEach { (key, value) -> put(key, value) }
            put("public_id", fact.uuid); put("revision", 1)
            put("created_at", time); put("updated_at", time); put("deleted_at", JsonNull)
            put("note", ""); put("source_device_id", DEVICE)
            put("external_event_id", JsonNull); put("metadata", buildJsonObject {})
            put("received_at", time); put("count_policy", policy.toJson())
            for (key in listOf("duration_seconds", "duration_milliseconds", "started_at", "ended_at", "reverts_event_uuid"))
                put(key, JsonNull)
        }, time)

    companion object {
        private const val FACT = "aaf00000-0000-4000-8000-000000000001"
        private const val DEVICE = "aaf00000-0000-4000-8000-000000000002"
        private val policy = CountDayPolicy(10, false)
        private val habit = HabitEntity(id=7, uuid="aaf00000-0000-4000-8000-000000000003",
            name="Environment count", habitType=HabitType.COUNTING, iconResId=0, colorHex="#123456",
            schedule=HabitSchedule.Daily, targetValue=10, completionPolicy="recurring",
            createdAt=Instant.parse("2026-10-01T00:00:00Z").toEpochMilli())
    }
}
