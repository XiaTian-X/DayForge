package com.dayforge.data.repository

import android.content.Intent
import android.net.Uri
import androidx.room.withTransaction
import com.dayforge.data.api.decodeSyncReply
import com.dayforge.data.local.AuthenticationSession
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalDataSession
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.CountDayPolicy
import com.dayforge.domain.model.CountHistory
import com.dayforge.domain.model.OneTimeState
import com.dayforge.domain.model.isContractUuid
import com.dayforge.domain.service.AccountSessionCoordinator
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** Credential-free display claim. This is NOT ObjectEditAuthority or OneTimeActionAuthority. */
@Serializable
@ConsistentCopyVisibility
data class WidgetFactClaim internal constructor(
    internal val accountId: String,
    internal val generation: String,
    internal val serverInstanceId: String?,
    internal val syncEpoch: String?,
    internal val deviceId: String?,
    val habitId: Long,
    val habitUuid: String,
    internal val plan: JsonObject,
    internal val date: String,
    internal val timezone: String,
    internal val completionUuid: String?,
    internal val oneTimeState: OneTimeState?,
    internal val countPolicy: JsonObject?
) {
    internal fun session() = LocalDataSession(AuthenticationSession(accountId, generation), serverInstanceId, syncEpoch)
    fun encode(): String = Json.encodeToString(this)
    fun attach(intent: Intent) {
        val payload = encode()
        intent.putExtra(EXTRA, payload)
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).joinToString("") { "%02x".format(it) }
        intent.data = Uri.Builder().scheme("dayforge-widget-fact").authority(digest).appendPath(intent.action ?: "prompt").build()
    }

    companion object {
        private const val EXTRA = "com.dayforge.widget.fact.claim"
        fun read(intent: Intent): WidgetFactClaim? = intent.getStringExtra(EXTRA)?.let(::decode)
        fun decode(payload: String): WidgetFactClaim {
            require(payload.length <= 65_536)
            return decodeSyncReply(payload.toByteArray(Charsets.UTF_8), 65_536, serializer(), {}).also { claim ->
                require(claim.habitId > 0)
                require(listOf(claim.accountId, claim.generation, claim.habitUuid).all(::isContractUuid))
                require((claim.serverInstanceId == null) == (claim.syncEpoch == null))
                listOfNotNull(claim.serverInstanceId, claim.syncEpoch, claim.deviceId, claim.completionUuid).forEach {
                    require(isContractUuid(it))
                }
                require(claim.timezone in ZoneId.getAvailableZoneIds())
                require(LocalDate.parse(claim.date).toString() == claim.date)
                claim.countPolicy?.let(CountDayPolicy::fromJson)
                require(claim.oneTimeState == null || claim.countPolicy == null)
            }
        }
    }
}

@ConsistentCopyVisibility
data class WidgetFactSnapshot internal constructor(
    val habit: HabitEntity,
    val claim: WidgetFactClaim,
    val count: CountHistory?,
    val completed: Boolean,
    val targetProgress: Int
)

class WidgetGoalDisplay internal constructor(val habit: HabitEntity, val progress: Int, val target: Int)

/** Capture and validate under the existing account/Room boundary; never invent an owner on click. */
@Singleton
class WidgetFactReader @Inject constructor(
    private val database: HabitDatabase,
    private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator,
    private val preferences: PreferencesManager,
    private val counts: CountHistoryReader
) {
    private val once = OneTimeLocalIntentStore(database, tokens, sessions, preferences)
    internal val accessChanges = combine(tokens.factAccessChanges, tokens.iconAccessChanges) { _, _ ->
        tokens.localCoreWriteAccess()
    }.distinctUntilChanged()

    suspend fun read(expected: HabitEntity): WidgetFactSnapshot = sessions.exclusive {
        database.withTransaction {
            val current = requireNotNull(database.habitDao().getVisibleHabitById(expected.id))
            check(current == expected) { "FACT_WIDGET_STALE_DISPLAY" }
            snapshot(current)
        }
    }

    internal suspend fun authorize(claim: WidgetFactClaim, exactFact: Boolean = true): ObjectEditSnapshot<HabitEntity> =
        sessions.exclusive {
            database.withTransaction {
                val view = requireInTransaction(claim, exactFact)
                // Mint the actual in-memory ticket from the trusted current row, not decoded plan/owner fields.
                ObjectEditSnapshot(view.habit, ObjectEditAuthority(requireNotNull(tokens.localCoreWriteAccess()).session,
                    view.habit.uuid, "plan_node", NextStructureMapper.writePlan(view.habit)))
            }
        }

    /** Caller already holds the non-reentrant account lock and producer transaction. */
    internal suspend fun requireInTransaction(claim: WidgetFactClaim, exactFact: Boolean = true,
        requireDay: Boolean = true): WidgetFactSnapshot {
        check(database.inTransaction())
        val access = requireNotNull(tokens.localCoreWriteAccess()) { "FACT_WIDGET_STALE_ACCOUNT" }
        check(access.session == claim.session() && access.capturedDeviceId == claim.deviceId) { "FACT_WIDGET_STALE_ACCOUNT" }
        val habit = requireNotNull(database.habitDao().getVisibleHabitById(claim.habitId)) { "FACT_WIDGET_NOT_FOUND" }
        check(habit.uuid == claim.habitUuid && NextStructureMapper.writePlan(habit) == claim.plan) { "FACT_WIDGET_PLAN_CHANGED" }
        if (requireDay) check(ZoneId.systemDefault().id == claim.timezone &&
            Instant.now().atZone(ZoneId.of(claim.timezone)).toLocalDate().toString() == claim.date) { "FACT_WIDGET_DAY_CHANGED" }
        val current = snapshot(habit, LocalDate.parse(claim.date), ZoneId.of(claim.timezone))
        check(current.claim.oneTimeState == claim.oneTimeState && current.claim.countPolicy == claim.countPolicy) {
            "FACT_WIDGET_STATE_CHANGED"
        }
        if (exactFact) check(current.claim.completionUuid == claim.completionUuid) { "FACT_WIDGET_FACT_CHANGED" }
        check(tokens.localCoreWriteAccess() == access) { "FACT_WIDGET_STALE_ACCOUNT" }
        return current
    }

    suspend fun requireCurrent(claim: WidgetFactClaim) = authorize(claim).value!!

    suspend fun goalDisplay(claim: WidgetFactClaim): WidgetGoalDisplay = sessions.exclusive {
        database.withTransaction {
            val view = requireInTransaction(claim)
            val progress = view.count?.qualifiedDates?.size ?: database.completionDao().getDistinctDayCount(view.habit.id)
            val target = requireNotNull(view.habit.targetCycles)
            check(view.habit.completionPolicy == "recurring" && progress >= target) { "FACT_WIDGET_GOAL_CHANGED" }
            WidgetGoalDisplay(view.habit, progress, target)
        }
    }

    /** The same committed object's follow-up, never a fresh local-ID lookup in another account. */
    suspend fun afterWrite(claim: WidgetFactClaim): WidgetFactSnapshot = sessions.exclusive {
        database.withTransaction {
            val access = requireNotNull(tokens.localCoreWriteAccess())
            check(access.session == claim.session() && access.capturedDeviceId == claim.deviceId) { "FACT_WIDGET_STALE_ACCOUNT" }
            val habit = requireNotNull(database.habitDao().getVisibleHabitById(claim.habitId))
            check(habit.uuid == claim.habitUuid) { "FACT_WIDGET_ACTIVITY_CHANGED" }
            snapshot(habit)
        }
    }

    /** No UI/network/files or another account-coordinated repository may run inside this callback. */
    internal suspend fun publish(claim: WidgetFactClaim, block: suspend () -> Unit) = sessions.exclusive {
        val access = requireNotNull(tokens.localCoreWriteAccess()) { "FACT_WIDGET_STALE_ACCOUNT" }
        check(access.session == claim.session() && access.capturedDeviceId == claim.deviceId) { "FACT_WIDGET_STALE_ACCOUNT" }
        database.withTransaction {
            check(database.habitDao().getVisibleHabitById(claim.habitId)?.uuid == claim.habitUuid) { "FACT_WIDGET_ACTIVITY_CHANGED" }
        }
        block()
    }

    private suspend fun snapshot(habit: HabitEntity,
        capturedDate: LocalDate? = null,
        zone: ZoneId = ZoneId.systemDefault()): WidgetFactSnapshot {
        val date = capturedDate ?: Instant.now().atZone(zone).toLocalDate()
        check(habit.appearance != null && habit.habitType in setOf(HabitType.CHECK_IN, HabitType.COUNTING))
        val access = requireNotNull(tokens.localCoreWriteAccess()) { "FACT_WIDGET_STALE_ACCOUNT" }
        val oneTime = if (habit.completionPolicy == "one_and_done") {
            val view = once.readInTransaction(habit.uuid, access.session)
            view.queue.optimisticState
        } else null
        val history = if (habit.habitType == HabitType.COUNTING) counts.readInTransaction(habit, date) else null
        val facts = if (oneTime != null) emptyList() else database.completionDao().getCompletionsInRange(habit.id, date, date.plusDays(1))
        check(facts.all { it.habitUuid == habit.uuid && it.oneTimeAction == null }) { "FACT_WIDGET_FACT_INVALID" }
        val latest = facts.maxByOrNull { it.id }?.uuid
        val claim = WidgetFactClaim(access.session.authentication.userId, access.session.authentication.generation,
            access.session.serverInstanceId, access.session.syncEpoch, access.capturedDeviceId, habit.id, habit.uuid,
            NextStructureMapper.writePlan(habit), date.toString(), zone.id, oneTime?.completionEventUuid ?: latest,
            oneTime, history?.todayPolicy?.toJson())
        check(history == null || history.todayPolicy != null) { "COUNT_RULE_UNKNOWN" }
        check(tokens.localCoreWriteAccess() == access) { "FACT_WIDGET_STALE_ACCOUNT" }
        val progress = if (oneTime != null) 0 else history?.qualifiedDates?.size
            ?: database.completionDao().getDistinctDayCount(habit.id)
        return WidgetFactSnapshot(habit, claim, history, oneTime?.let { it.completionEventUuid != null }
            ?: history?.completedToday ?: facts.isNotEmpty(), progress)
    }
}
