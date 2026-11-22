package com.dayforge.data.repository

import android.content.Intent
import android.net.Uri
import androidx.room.withTransaction
import com.dayforge.data.api.decodeSyncReply
import com.dayforge.data.local.AuthenticationSession
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalDataSession
import com.dayforge.data.local.LocalCoreWriteAccess
import com.dayforge.data.local.PersonalDataDisplayMemory
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.CountDayPolicy
import com.dayforge.domain.model.CountHistory
import com.dayforge.domain.model.CountSummary
import com.dayforge.domain.model.CheckHistory
import com.dayforge.domain.model.ChallengeRoundHead
import com.dayforge.domain.model.ContractIntegerSerializer
import com.dayforge.domain.model.OneTimeState
import com.dayforge.domain.model.isContractUuid
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.domain.service.CompletionActionTiming
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

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
    internal val countPolicy: JsonObject?,
    @Serializable(with = ContractIntegerSerializer::class)
    internal val challengeContract: Int = 0,
    internal val roundHead: ChallengeRoundHead? = null,
    internal val storageId: String? = null
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
                listOfNotNull(claim.serverInstanceId, claim.syncEpoch, claim.deviceId, claim.completionUuid, claim.storageId).forEach {
                    require(isContractUuid(it))
                }
                require(claim.timezone in ZoneId.getAvailableZoneIds())
                require(LocalDate.parse(claim.date).toString() == claim.date)
                claim.countPolicy?.let(CountDayPolicy::fromJson)
                require(claim.oneTimeState == null || claim.countPolicy == null)
                require(claim.challengeContract in 0..1 && (claim.challengeContract != 0 || claim.roundHead == null))
                require(claim.roundHead == null || claim.roundHead.activityUuid == claim.habitUuid && claim.oneTimeState == null)
            }
        }
    }
}

@ConsistentCopyVisibility
data class WidgetFactSnapshot internal constructor(
    val habit: HabitEntity,
    val claim: WidgetFactClaim,
    val count: CountSummary?,
    val completed: Boolean,
    val targetProgress: Int,
    val hasFailed: Boolean = false,
    val countDisplay: com.dayforge.domain.model.CountDisplayProjection? = null,
    val checkDisplay: com.dayforge.domain.model.CheckDisplayProjection? = null
)

/** Current-day result and this operation's historical source are distinct read products.
 * The current side retains the original post-write date/default-zone sampling point.
 */
internal data class WidgetCountReadResult(val current: WidgetFactSnapshot, val completionSource: WidgetFactSnapshot?)

class WidgetGoalDisplay internal constructor(val habit: HabitEntity, val progress: Int, val target: Int)

/** Pure projection of history already audited in the same read transaction; never a new authority. */
internal fun recurringFactSnapshot(habit: HabitEntity, access: LocalCoreWriteAccess,
    rounds: NextRoundWriteScope?, history: CountHistory?, checks: CheckHistory?, date: LocalDate,
    zone: ZoneId, source: CompletionEntity? = null, storageId: String? = null): WidgetFactSnapshot {
    check(habit.appearance != null && habit.completionPolicy == "recurring")
    check((habit.habitType == HabitType.COUNTING && history != null && checks == null) ||
        (habit.habitType == HabitType.CHECK_IN && checks != null && history == null))
    check(history == null || history.today == date && history.todayPolicy != null) { "COUNT_RULE_UNKNOWN" }
    check(checks == null || checks.today == date)
    val summary = history?.summary
    val facts = checks?.todayCompletions ?: requireNotNull(history).completions.filter { it.recordedLocalDate == date.toString() }
    check(facts.all { it.habitUuid == habit.uuid && it.oneTimeAction == null }) { "FACT_WIDGET_FACT_INVALID" }
    if (source != null) check(source in facts && source.recordedTimezone == zone.id) { "COMPLETION_SOURCE_CHANGED" }
    val claim = WidgetFactClaim(access.session.authentication.userId, access.session.authentication.generation,
        access.session.serverInstanceId, access.session.syncEpoch, access.capturedDeviceId, habit.id, habit.uuid,
        NextStructureMapper.writePlan(habit), date.toString(), zone.id, source?.uuid ?: facts.maxByOrNull { it.id }?.uuid,
        null, history?.todayPolicy?.toJson(), if (rounds == null) 0 else 1, history?.roundHead ?: checks?.roundHead, storageId)
    return WidgetFactSnapshot(habit, claim, summary, summary?.completedToday ?: requireNotNull(checks).completedToday,
        summary?.qualifiedDates?.size ?: requireNotNull(checks).qualifiedDates.size,
        summary?.let { com.dayforge.domain.service.FailureCheckerUtils.countHasFailed(habit, it) }
            ?: com.dayforge.domain.service.FailureCheckerUtils.checkHasFailed(habit, requireNotNull(checks)))
}

/** Summary and exact selected/latest fact from ONE verified snapshot; no fabricated history list. */
internal fun recurringCountFactSnapshot(habit: HabitEntity, access: LocalCoreWriteAccess,
    rounds: NextRoundWriteScope?, summary: CountSummary, date: LocalDate, zone: ZoneId,
    fact: CompletionEntity?, explicitSource: Boolean, storageId: String? = null): WidgetFactSnapshot {
    check(habit.appearance != null && habit.completionPolicy == "recurring" && habit.habitType == HabitType.COUNTING)
    check(summary.today == date && summary.todayPolicy != null) { "COUNT_RULE_UNKNOWN" }
    fact?.let {
        check(it.habitId == habit.id && it.habitUuid == habit.uuid && it.oneTimeAction == null &&
            it.recordedLocalDate == date.toString() && (!explicitSource || it.recordedTimezone == zone.id)) {
            "COMPLETION_SOURCE_CHANGED"
        }
    }
    if (!explicitSource) check(fact?.id == summary.todayLatestCompletionId) { "COMPLETION_SOURCE_CHANGED" }
    val claim = WidgetFactClaim(access.session.authentication.userId, access.session.authentication.generation,
        access.session.serverInstanceId, access.session.syncEpoch, access.capturedDeviceId, habit.id, habit.uuid,
        NextStructureMapper.writePlan(habit), date.toString(), zone.id, fact?.uuid, null,
        summary.todayPolicy.toJson(), if (rounds == null) 0 else 1, summary.roundHead, storageId)
    return WidgetFactSnapshot(habit, claim, summary, summary.completedToday, summary.qualifiedDates.size,
        com.dayforge.domain.service.FailureCheckerUtils.countHasFailed(habit, summary))
}

/** Finite COUNT follow-up under the same exact-source contract. Standalone full widgets
 * retain their original count-summary path; null history is never used to skip their checks. */
internal fun recurringProjectedCountFactSnapshot(habit: HabitEntity, access: LocalCoreWriteAccess,
    rounds: NextRoundWriteScope?, projection: com.dayforge.domain.model.CountDisplayProjection,
    date: LocalDate, zone: ZoneId, fact: CompletionEntity?, explicitSource: Boolean, storageId: String? = null): WidgetFactSnapshot? {
    check(habit.appearance != null && habit.completionPolicy == "recurring" && habit.habitType == HabitType.COUNTING)
    check(projection.today == date && projection.todayPolicy != null) { "COUNT_RULE_UNKNOWN" }
    fact?.let {
        check(it.habitId == habit.id && it.habitUuid == habit.uuid && it.oneTimeAction == null &&
            it.recordedLocalDate == date.toString() && (!explicitSource || it.recordedTimezone == zone.id)) {
            "COMPLETION_SOURCE_CHANGED"
        }
    }
    if (!explicitSource) check(fact?.id == projection.todayLatestCompletionId) { "COMPLETION_SOURCE_CHANGED" }
    val claim = WidgetFactClaim(access.session.authentication.userId, access.session.authentication.generation,
        access.session.serverInstanceId, access.session.syncEpoch, access.capturedDeviceId, habit.id, habit.uuid,
        NextStructureMapper.writePlan(habit), date.toString(), zone.id, fact?.uuid, null,
        projection.todayPolicy.toJson(), if (rounds == null) 0 else 1, projection.roundHead, storageId)
    // Exactly where the original helper samples its default failure-calculation zone.
    // A legitimate concurrent clock change selects the full original reader, never a stale flag.
    if (projection.zoneId != ZoneId.systemDefault().id) return null
    return WidgetFactSnapshot(habit, claim, null, projection.completedToday, projection.qualifiedDayCount,
        projection.hasFailed, projection)
}

/** Finite CHECK source/current result with the original presence and strict-failure semantics. */
internal fun recurringProjectedCheckFactSnapshot(habit: HabitEntity, access: LocalCoreWriteAccess,
    rounds: NextRoundWriteScope?, projection: com.dayforge.domain.model.CheckDisplayProjection,
    date: LocalDate, zone: ZoneId, fact: CompletionEntity?, explicitSource: Boolean, storageId: String? = null): WidgetFactSnapshot? {
    check(habit.appearance != null && habit.completionPolicy == "recurring" && habit.habitType == HabitType.CHECK_IN)
    check(projection.today == date) { "CHECK_DISPLAY_DATE_CHANGED" }
    fact?.let {
        check(it.habitId == habit.id && it.habitUuid == habit.uuid && it.oneTimeAction == null &&
            it.recordedLocalDate == date.toString() && (!explicitSource || it.recordedTimezone == zone.id)) {
            "COMPLETION_SOURCE_CHANGED"
        }
    }
    if (!explicitSource) check(fact?.id == projection.todayLatestCompletionId) { "COMPLETION_SOURCE_CHANGED" }
    val claim = WidgetFactClaim(access.session.authentication.userId, access.session.authentication.generation,
        access.session.serverInstanceId, access.session.syncEpoch, access.capturedDeviceId, habit.id, habit.uuid,
        NextStructureMapper.writePlan(habit), date.toString(), zone.id, fact?.uuid, null, null,
        if (rounds == null) 0 else 1, projection.roundHead, storageId)
    // Preserve the original helper's last default-zone sample. A moved clock selects the full reader.
    if (projection.zoneId != ZoneId.systemDefault().id) return null
    return WidgetFactSnapshot(habit, claim, null, projection.completedToday, projection.qualifiedDayCount,
        projection.hasFailed, checkDisplay = projection)
}

/** Capture and validate under the existing account/Room boundary; never invent an owner on click. */
@Singleton
class WidgetFactReader @Inject constructor(
    private val database: HabitDatabase,
    private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator,
    private val preferences: PreferencesManager,
    private val counts: CountHistoryReader,
    private val checks: CheckHistoryReader = CheckHistoryReader(database, tokens, sessions)
) {
    private val once = OneTimeLocalIntentStore(database, tokens, sessions, preferences)
    private val replica = ReplicaPresentationRepository(database, tokens, sessions)
    internal fun isMetricInputOwner(database: HabitDatabase, tokens: TokenManager,
        sessions: AccountSessionCoordinator): Boolean =
        this.database === database && this.tokens === tokens && this.sessions === sessions
    internal val accessChanges = tokens.coreAccessChanges
    internal fun registerCompletionConsumer(listener: PersonalDataDisplayMemory.Listener) = tokens.registerPersonalDisplay(listener)

    suspend fun read(expected: HabitEntity): WidgetFactSnapshot {
        if (expected.habitType == HabitType.CHECK_IN && expected.appearance != null && expected.completionPolicy == "recurring")
            return RecurringDisplayReadScope.withSnapshot(database, tokens, sessions) { scope ->
                check(replica.isOpenWhileAccountLocked()) { com.dayforge.data.local.ReplicaSwitchGuard.PENDING }
                val current = requireNotNull(database.habitDao().getVisibleHabitById(expected.id))
                check(current == expected) { "FACT_WIDGET_STALE_DISPLAY" }
                snapshot(current, displayScope = scope, preferCompact = true)
            }
        return readOriginal(expected)
    }

    private suspend fun readOriginal(expected: HabitEntity): WidgetFactSnapshot = sessions.exclusive {
        check(replica.isOpenWhileAccountLocked()) { com.dayforge.data.local.ReplicaSwitchGuard.PENDING }
        database.withTransaction {
            val current = requireNotNull(database.habitDao().getVisibleHabitById(expected.id))
            check(current == expected) { "FACT_WIDGET_STALE_DISPLAY" }
            snapshot(current)
        }
    }

    internal suspend fun authorize(claim: WidgetFactClaim, exactFact: Boolean = true): ObjectEditSnapshot<HabitEntity> =
        authorizeFact(claim, exactFact, completionSource = false)

    internal suspend fun authorizeCompletion(claim: WidgetFactClaim): ObjectEditSnapshot<HabitEntity> =
        authorizeFact(claim, exactFact = true, completionSource = true)

    private suspend fun authorizeFact(claim: WidgetFactClaim, exactFact: Boolean,
        completionSource: Boolean): ObjectEditSnapshot<HabitEntity> =
        sessions.exclusive {
            check(replica.isOpenWhileAccountLocked()) { com.dayforge.data.local.ReplicaSwitchGuard.PENDING }
            database.withTransaction {
                val view = if (completionSource) requireCompletionInTransaction(claim) else requireInTransaction(claim, exactFact)
                // Mint the actual in-memory ticket from the trusted current row, not decoded plan/owner fields.
                val access = requireNotNull(tokens.localCoreWriteAccess())
                ObjectEditSnapshot(view.habit, ObjectEditAuthority(access.session,
                    view.habit.uuid, "plan_node", NextStructureMapper.writePlan(view.habit),
                    NextCoreLocalIntentStore(database, tokens, sessions).captureDisplayedRoundsInTransaction(),
                    capturedDeviceId = access.capturedDeviceId, completionDeviceCaptured = true))
            }
        }

    /** Caller already holds the non-reentrant account lock and producer transaction. */
    internal suspend fun requireInTransaction(claim: WidgetFactClaim, exactFact: Boolean = true,
        requireDay: Boolean = true): WidgetFactSnapshot =
        requireBoundFactInTransaction(claim, exactFact, requireDay, completionSource = false)

    /** Original app completion, not the widget's exact-current/latest toggle semantics. */
    internal suspend fun requireCompletionInTransaction(claim: WidgetFactClaim, requireDay: Boolean = false,
        displayScope: RecurringDisplayReadScope? = null,
        metricSourceRead: CountTrustKernel.MetricSourceRead? = null, preferCompact: Boolean = false): WidgetFactSnapshot =
        requireBoundFactInTransaction(claim, exactFact = true, requireDay, completionSource = true,
            displayScope, metricSourceRead, preferCompact)

    private suspend fun requireBoundFactInTransaction(claim: WidgetFactClaim, exactFact: Boolean,
        requireDay: Boolean, completionSource: Boolean, displayScope: RecurringDisplayReadScope? = null,
        metricSourceRead: CountTrustKernel.MetricSourceRead? = null, preferCompact: Boolean = false): WidgetFactSnapshot {
        check(displayScope == null || metricSourceRead == null)
        check(database.inTransaction())
        check(replica.isOpenWhileAccountLocked()) { com.dayforge.data.local.ReplicaSwitchGuard.PENDING }
        val access = displayScope?.borrow(database, tokens)?.first
            ?: requireNotNull(tokens.localCoreWriteAccess()) { "FACT_WIDGET_STALE_ACCOUNT" }
        check(access.session == claim.session() && access.capturedDeviceId == claim.deviceId) { "FACT_WIDGET_STALE_ACCOUNT" }
        val habit = requireNotNull(database.habitDao().getVisibleHabitById(claim.habitId)) { "FACT_WIDGET_NOT_FOUND" }
        check(habit.uuid == claim.habitUuid && NextStructureMapper.writePlan(habit) == claim.plan) { "FACT_WIDGET_PLAN_CHANGED" }
        if (requireDay) check(ZoneId.systemDefault().id == claim.timezone &&
            Instant.now().atZone(ZoneId.of(claim.timezone)).toLocalDate().toString() == claim.date) { "FACT_WIDGET_DAY_CHANGED" }
        if (completionSource) check(habit.completionPolicy == "recurring" && claim.completionUuid != null) {
            "COMPLETION_SOURCE_CHANGED"
        }
        val current = snapshot(habit, LocalDate.parse(claim.date), ZoneId.of(claim.timezone),
            displayScope, sourceUuid = claim.completionUuid.takeIf { completionSource }, metricSourceRead = metricSourceRead,
            preferCompact = preferCompact || metricSourceRead != null)
        check(current.claim.storageId == claim.storageId) { "FACT_WIDGET_REPLICA_CHANGED" }
        check(current.claim.challengeContract == claim.challengeContract && current.claim.roundHead == claim.roundHead) {
            "FACT_WIDGET_ROUND_CHANGED"
        }
        check(current.claim.oneTimeState == claim.oneTimeState && current.claim.countPolicy == claim.countPolicy) {
            "FACT_WIDGET_STATE_CHANGED"
        }
        if (exactFact) check(current.claim.completionUuid == claim.completionUuid) { "FACT_WIDGET_FACT_CHANGED" }
        if (displayScope != null) displayScope.borrow(database, tokens)
        else check(tokens.localCoreWriteAccess() == access) { "FACT_WIDGET_STALE_ACCOUNT" }
        return current
    }

    suspend fun requireCurrent(claim: WidgetFactClaim) = authorize(claim).value!!

    /** Exact original completion for a restored required prompt. This does not substitute
     * a latest/current-day fact and does not issue a write ticket from a historical receipt. */
    internal suspend fun completionSource(claim: WidgetFactClaim): WidgetFactSnapshot = sessions.exclusive {
        database.withTransaction { requireCompletionInTransaction(claim, requireDay = false) }
    }


    suspend fun goalDisplay(claim: WidgetFactClaim): WidgetGoalDisplay = goalDisplayInternal(claim, null)

    internal suspend fun goalDisplayForCommand(claim: WidgetFactClaim, command: CountCommandReceipt): WidgetGoalDisplay =
        goalDisplayInternal(claim, command)

    private suspend fun goalDisplayInternal(claim: WidgetFactClaim, command: CountCommandReceipt?): WidgetGoalDisplay = sessions.exclusive {
        database.withTransaction {
            command?.let { CountCommandStore(database, tokens, sessions).requirePendingGoalInTransaction(
                it.committed.reference, claim.habitUuid, requireNotNull(it.factUuid)) }
            val view = requireInTransaction(claim)
            val progress = view.targetProgress
            val target = requireNotNull(view.habit.targetCycles)
            check(view.habit.completionPolicy == "recurring" && progress >= target) { "FACT_WIDGET_GOAL_CHANGED" }
            WidgetGoalDisplay(view.habit, progress, target)
        }
    }

    internal suspend fun publishGoalForCommand(claim: WidgetFactClaim, command: CountCommandReceipt,
        block: suspend () -> Unit) = sessions.exclusive {
        database.withTransaction {
            CountCommandStore(database, tokens, sessions).requirePendingGoalInTransaction(command.committed.reference,
                claim.habitUuid, requireNotNull(command.factUuid))
            val current = requireInTransaction(claim)
            require(current.habit.targetCycles != null && current.targetProgress >= current.habit.targetCycles) { "FACT_WIDGET_GOAL_CHANGED" }
        }
        block() // The original account/source publication scope still owns this assignment.
    }

    /** The same committed object's follow-up, never a fresh local-ID lookup in another account. */
    suspend fun afterWrite(claim: WidgetFactClaim): WidgetFactSnapshot = sessions.exclusive {
        check(replica.isOpenWhileAccountLocked()) { com.dayforge.data.local.ReplicaSwitchGuard.PENDING }
        database.withTransaction {
            val access = requireNotNull(tokens.localCoreWriteAccess())
            check(access.session == claim.session() && access.capturedDeviceId == claim.deviceId) { "FACT_WIDGET_STALE_ACCOUNT" }
            val habit = requireNotNull(database.habitDao().getVisibleHabitById(claim.habitId))
            check(habit.uuid == claim.habitUuid) { "FACT_WIDGET_ACTIVITY_CHANGED" }
            snapshot(habit)
        }
    }

    /** The receipt supplies only a fact locator, never current math/display permission.
     * First execute the original snapshot(habit) sampling point, including its NOW/default
     * zone. Only the independent historical source product uses the original claim's date
     * and zone. Normal same-day/latest results reuse the exact already verified snapshot.
     */
    internal suspend fun afterCountCommand(claim: WidgetFactClaim, committedFactUuid: String?): WidgetCountReadResult = sessions.exclusive {
        check(replica.isOpenWhileAccountLocked()) { com.dayforge.data.local.ReplicaSwitchGuard.PENDING }
        database.withTransaction {
            val access = requireNotNull(tokens.localCoreWriteAccess()) { "FACT_WIDGET_STALE_ACCOUNT" }
            check(access.session == claim.session() && access.capturedDeviceId == claim.deviceId) { "FACT_WIDGET_STALE_ACCOUNT" }
            val habit = requireNotNull(database.habitDao().getVisibleHabitById(claim.habitId)) { "FACT_WIDGET_NOT_FOUND" }
            check(habit.uuid == claim.habitUuid && habit.habitType == HabitType.COUNTING && habit.completionPolicy == "recurring") {
                "FACT_WIDGET_ACTIVITY_CHANGED"
            }
            val current = snapshot(habit) // Same original post-write current date/default-zone sample.
            check(claim.storageId != null && current.claim.storageId == claim.storageId) { "FACT_WIDGET_REPLICA_CHANGED" }
            check(current.claim.challengeContract == claim.challengeContract && current.claim.roundHead == claim.roundHead) {
                "FACT_WIDGET_ROUND_CHANGED"
            }
            val source = if (committedFactUuid == null) null else {
                val sameSource = current.claim.completionUuid == committedFactUuid && current.claim.date == claim.date &&
                    current.claim.timezone == claim.timezone
                (if (sameSource) current else snapshot(habit, LocalDate.parse(claim.date), ZoneId.of(claim.timezone),
                    sourceUuid = committedFactUuid)).also {
                    check(it.claim.storageId == claim.storageId) { "FACT_WIDGET_REPLICA_CHANGED" }
                    check(it.claim.challengeContract == claim.challengeContract && it.claim.roundHead == claim.roundHead) { "FACT_WIDGET_ROUND_CHANGED" }
                    check(it.claim.completionUuid == committedFactUuid) { "FACT_WIDGET_FACT_CHANGED" }
                }
            }
            check(tokens.localCoreWriteAccess() == access) { "FACT_WIDGET_STALE_ACCOUNT" }
            WidgetCountReadResult(current, source)
        }
    }

    /** Read-only reconstruction after authenticating an exact current primary command.
     * Namespace and fact UUID are locators, not grants; snapshot() still proves the original
     * current domain and the historical source independently. No mutation input is rewritten.
     */
    internal suspend fun afterRecoveredCount(namespace: CountCommandRecords.Namespace, habitId: Long,
        habitUuid: String, committedFactUuid: String): WidgetCountReadResult = sessions.exclusive {
        check(replica.isOpenWhileAccountLocked()) { com.dayforge.data.local.ReplicaSwitchGuard.PENDING }
        database.withTransaction {
            val access = requireNotNull(tokens.localCoreWriteAccess()) { "FACT_WIDGET_STALE_ACCOUNT" }
            require(access.session.authentication.userId == namespace.account &&
                access.session.serverInstanceId == namespace.server && access.session.syncEpoch == namespace.epoch &&
                access.capturedDeviceId == namespace.device &&
                com.dayforge.data.local.LocalReplicaIdentity.readInTransaction(database) == namespace.storage) {
                "COMMAND_CURRENT_NAMESPACE_ISOLATED"
            }
            val habit = requireNotNull(database.habitDao().getVisibleHabitById(habitId)) { "FACT_WIDGET_NOT_FOUND" }
            require(habit.uuid == habitUuid && habit.appearance != null && habit.habitType == HabitType.COUNTING &&
                habit.completionPolicy == "recurring") { "FACT_WIDGET_ACTIVITY_CHANGED" }
            val current = snapshot(habit)
            val fact = requireNotNull(database.completionDao().getCompletionByUuid(committedFactUuid)) { "FACT_WIDGET_FACT_CHANGED" }
            require(fact.habitId == habit.id && fact.habitUuid == habit.uuid && fact.oneTimeAction == null) { "FACT_WIDGET_FACT_CHANGED" }
            val source = if (current.claim.completionUuid == fact.uuid && current.claim.date == fact.recordedLocalDate &&
                current.claim.timezone == fact.recordedTimezone) current else
                snapshot(habit, LocalDate.parse(fact.recordedLocalDate), ZoneId.of(fact.recordedTimezone), sourceUuid = fact.uuid)
            require(source.claim.completionUuid == committedFactUuid && source.claim.storageId == namespace.storage) { "FACT_WIDGET_FACT_CHANGED" }
            check(tokens.localCoreWriteAccess() == access) { "FACT_WIDGET_STALE_ACCOUNT" }
            WidgetCountReadResult(current, source)
        }
    }

    /** No UI/network/files or another account-coordinated repository may run inside this callback. */
    internal suspend fun publish(claim: WidgetFactClaim, block: suspend () -> Unit) = sessions.exclusive {
        check(replica.isOpenWhileAccountLocked()) { com.dayforge.data.local.ReplicaSwitchGuard.PENDING }
        val access = requireNotNull(tokens.localCoreWriteAccess()) { "FACT_WIDGET_STALE_ACCOUNT" }
        check(access.session == claim.session() && access.capturedDeviceId == claim.deviceId) { "FACT_WIDGET_STALE_ACCOUNT" }
        database.withTransaction {
            check(database.habitDao().getVisibleHabitById(claim.habitId)?.uuid == claim.habitUuid) { "FACT_WIDGET_ACTIVITY_CHANGED" }
        }
        block()
    }

    /** Exact completion follow-up fence; the callback may update bound preferences, never reenter a repository. */
    internal suspend fun publishCompletion(snapshot: WidgetFactSnapshot, requireDay: Boolean = false,
        requireUnchangedProgress: Boolean = false, block: suspend () -> Unit) = sessions.exclusive {
        requireCompletionPublication(snapshot, requireDay, requireUnchangedProgress)
        block()
    }

    /** Only the private formal delivery sequence may call this bridge. No proof or account
     * capability is passed to the UI consumer; it can only report whether its prompt survives.
     * The original read really ends before the original awaited DataStore edit begins. */
    internal suspend fun publishFormalMetricPreference(scope: CountCommandStore.FormalMetricDeliveryScope,
        snapshot: WidgetFactSnapshot, isConsumerCurrent: () -> Boolean) {
        CountCommandStore.requireFormalMetricSource(scope, database, tokens, sessions)
        preferences.requireTokenOwner(tokens)
        CompletionActionTiming.measure(CompletionActionTiming.Stage.METRIC_DELIVERY_SOURCE_READ) {
            requireCompletionPublication(snapshot, requireDay = false, requireUnchangedProgress = false,
                diagnosticCaller = if (CompletionActionTiming.enabled) CompletionActionTiming.ReadCaller.FORMAL_METRIC_SOURCE else null)
        }
        CountCommandStore.requireFormalMetricSource(scope, database, tokens, sessions)
        check(isConsumerCurrent()) { "FACT_PROMPT_EXPIRED" }
        if (CompletionActionTiming.enabled) CompletionActionTiming.mark(CompletionActionTiming.Stage.METRIC_DELIVERY_PREFERENCES_START)
        preferences.removePendingMetricHabit(snapshot.claim.session(), snapshot.habit.uuid)
        if (CompletionActionTiming.enabled) CompletionActionTiming.mark(CompletionActionTiming.Stage.METRIC_DELIVERY_PREFERENCES_RETURNED)
        CountCommandStore.requireFormalMetricSource(scope, database, tokens, sessions)
    }

    private suspend fun requireCompletionPublication(snapshot: WidgetFactSnapshot, requireDay: Boolean,
        requireUnchangedProgress: Boolean,
        diagnosticCaller: CompletionActionTiming.ReadCaller? = null) {
        RecurringDisplayReadScope.withSnapshotWhileAccountLocked(database, tokens, sessions,
            prepareCountProof = false, diagnosticCaller = diagnosticCaller) { scope ->
            val current = requireCompletionInTransaction(snapshot.claim, requireDay, scope, preferCompact = snapshot.countDisplay != null || snapshot.checkDisplay != null)
            if (requireUnchangedProgress) check(current.completed == snapshot.completed &&
                current.targetProgress == snapshot.targetProgress && current.hasFailed == snapshot.hasFailed) {
                "COMPLETION_DISPLAY_CHANGED"
            }
        }
    }

    /** One bounded follow-up read plan. Participants only read; publication is memory-only and
     * happens after the read transaction and its final authority check have actually returned.
     */
    internal suspend fun <T> readCompletionPlanAndPublish(snapshot: WidgetFactSnapshot,
        read: suspend (WidgetFactSnapshot, RecurringDisplayReadScope) -> T,
        publish: (T) -> Unit) = withContext(Dispatchers.IO) { sessions.exclusive(AccountSessionCoordinator.Work.COMPLETION_PROMPT_READ) {
        // Publication is read-only even when its source has a compact display. A dirty
        // proof falls back to the original read; this boundary must not issue a new root.
        val prepared = RecurringDisplayReadScope.withSnapshotWhileAccountLocked(database, tokens, sessions,
            prepareCountProof = false) { scope ->
            val current = requireCompletionInTransaction(snapshot.claim, requireDay = false, displayScope = scope,
                preferCompact = snapshot.countDisplay != null || snapshot.checkDisplay != null)
            read(current, scope)
        }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        publish(prepared)
    } }

    private suspend fun snapshot(habit: HabitEntity,
        capturedDate: LocalDate? = null,
        zone: ZoneId = ZoneId.systemDefault(), displayScope: RecurringDisplayReadScope? = null,
        sourceUuid: String? = null, metricSourceRead: CountTrustKernel.MetricSourceRead? = null,
        preferCompact: Boolean = false): WidgetFactSnapshot {
        val storageId = com.dayforge.data.local.LocalReplicaIdentity.readInTransaction(database)
        val date = capturedDate ?: Instant.now().atZone(zone).toLocalDate()
        check(habit.appearance != null && habit.habitType in setOf(HabitType.CHECK_IN, HabitType.COUNTING))
        val borrowed = displayScope?.borrow(database, tokens)
        val access = borrowed?.first ?: requireNotNull(tokens.localCoreWriteAccess()) { "FACT_WIDGET_STALE_ACCOUNT" }
        val rounds = if (borrowed != null) borrowed.second
            else NextCoreLocalIntentStore(database, tokens, sessions).captureDisplayedRoundsInTransaction(metricSourceRead = metricSourceRead)
        check(displayScope == null || habit.completionPolicy == "recurring") { "COMPLETION_RECURRING_REQUIRED" }
        val oneTime = if (habit.completionPolicy == "one_and_done") {
            val view = once.readInTransaction(habit.uuid, access.session)
            view.queue.optimisticState
        } else null
        if (habit.habitType == HabitType.COUNTING && preferCompact && (displayScope != null || metricSourceRead != null)) {
            // Claims use the captured source zone; the old failure helper samples the current
            // default zone. Keep those two meanings separate and fall back if that sample moves.
            val failureZone = ZoneId.systemDefault()
            val selected = counts.selectProjectionInTransaction(habit, date, failureZone, displayScope,
                sourceUuid = sourceUuid, metricSourceRead = metricSourceRead)
            val value = recurringProjectedCountFactSnapshot(habit, access, rounds, selected.projection, date, zone,
                selected.fact, explicitSource = sourceUuid != null, storageId = storageId) ?: return snapshot(habit, date, zone, displayScope,
                    sourceUuid, metricSourceRead, preferCompact = false)
            if (displayScope != null) displayScope.borrow(database, tokens)
            else check(tokens.localCoreWriteAccess() == access) { "FACT_WIDGET_STALE_ACCOUNT" }
            return value
        }
        if (habit.habitType == HabitType.COUNTING) {
            val selected = counts.selectInTransaction(habit, date, displayScope, sourceUuid = sourceUuid, metricSourceRead = metricSourceRead)
            val value = recurringCountFactSnapshot(habit, access, rounds, selected.summary, date, zone,
                selected.fact, explicitSource = sourceUuid != null, storageId = storageId)
            if (displayScope != null) displayScope.borrow(database, tokens)
            else check(tokens.localCoreWriteAccess() == access) { "FACT_WIDGET_STALE_ACCOUNT" }
            return value
        }
        if (oneTime == null && habit.habitType == HabitType.CHECK_IN && preferCompact && displayScope != null) {
            val failureZone = ZoneId.systemDefault()
            val selected = checks.selectProjectionInTransaction(habit, date, failureZone, displayScope, sourceUuid = sourceUuid)
            val value = recurringProjectedCheckFactSnapshot(habit, access, rounds, selected.projection, date, zone,
                selected.fact, explicitSource = sourceUuid != null, storageId = storageId) ?: return snapshot(habit, date, zone,
                    displayScope, sourceUuid, metricSourceRead, preferCompact = false)
            displayScope.borrow(database, tokens)
            return value
        }
        val checkHistory = if (oneTime == null && habit.habitType == HabitType.CHECK_IN) {
            if (displayScope != null) checks.readInTransaction(habit, date, displayScope) else checks.readInTransaction(habit, date)
        } else null
        val facts = if (oneTime != null) emptyList() else checkHistory?.todayCompletions
            ?: database.completionDao().getCompletionsInRange(habit.id, date, date.plusDays(1))
        check(facts.all { it.habitUuid == habit.uuid && it.oneTimeAction == null }) { "FACT_WIDGET_FACT_INVALID" }
        val source = sourceUuid?.let { uuid ->
            requireNotNull(facts.singleOrNull { it.uuid == uuid && it.recordedTimezone == zone.id }) { "COMPLETION_SOURCE_CHANGED" }
        }
        val latest = source?.uuid ?: facts.maxByOrNull { it.id }?.uuid
        val claim = WidgetFactClaim(access.session.authentication.userId, access.session.authentication.generation,
            access.session.serverInstanceId, access.session.syncEpoch, access.capturedDeviceId, habit.id, habit.uuid,
            NextStructureMapper.writePlan(habit), date.toString(), zone.id, oneTime?.completionEventUuid ?: latest,
            oneTime, null, if (rounds == null) 0 else 1, checkHistory?.roundHead, storageId)
        if (displayScope != null) displayScope.borrow(database, tokens)
        else check(tokens.localCoreWriteAccess() == access) { "FACT_WIDGET_STALE_ACCOUNT" }
        val progress = if (oneTime != null) 0 else checkHistory?.qualifiedDates?.size
            ?: database.completionDao().getDistinctDayCount(habit.id)
        return WidgetFactSnapshot(habit, claim, null, oneTime?.let { it.completionEventUuid != null }
            ?: checkHistory?.completedToday ?: facts.isNotEmpty(), progress,
            checkHistory?.let { com.dayforge.domain.service.FailureCheckerUtils.checkHasFailed(habit, it) } ?: false)
    }
}
