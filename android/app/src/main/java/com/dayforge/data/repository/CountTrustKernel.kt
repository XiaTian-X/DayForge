package com.dayforge.data.repository

import android.database.Cursor
import androidx.room.Room
import androidx.room.withTransaction
import com.dayforge.data.api.dto.SyncV2Change
import com.dayforge.data.api.dto.ChallengeMetadata
import com.dayforge.data.history.CountTrustIndex
import com.dayforge.data.local.*
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.local.entity.MetricLogEntity
import com.dayforge.data.local.entity.NextSyncStateEntity
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.*
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.domain.service.ActivityRateCalculator
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** A transaction-scoped consumer of the device-bound root, never a retained access grant.
 * All uncontrolled/unsupported mutations leave the permanent dirty marker set. Issuance and
 * incremental advancement use the private SQL capability, and commit with the business rows.
 */
internal class CountTrustKernel(private val database: HabitDatabase, private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator) {
    private val runtime get() = CountTrustRuntime.forDatabase(database)
    private val sql get() = database.openHelper.writableDatabase

    internal enum class Mutation { LOCAL_COUNT, LOCAL_OBSERVATIONS, SEND, ACK }
    internal class Capture internal constructor(internal val context: Context,
        internal val boundary: CountTrustJournal.Boundary) {
        internal val beganAt = System.currentTimeMillis()
        internal val rates = linkedMapOf<String, RateCalculation>()
        internal var metricPreparation: MetricPreparation? = null
        internal var metricProfileInvalidated = false
        internal var metricEffects: MetricObservationLocalEffects? = null
        internal var independentMetricPreparation = false
        internal var countSourcePreparation: CountSourcePreparation? = null
        internal var countSourceInvalidated = false
        internal var timerPreparation: TimerTransition.Prepared? = null
        internal var timerPreparationAttempted = false
        internal var timerDiagnostic: TimerTrustMaintenanceDiagnostic.Attempt? = null
        // Only the private issuer can construct/consume these exact-capture read states.
        internal var metricSourceReadState: Any? = null
    }
    internal class MetricInsertTicket internal constructor()
    internal class CountSourceTicket internal constructor()
    internal class CountSourcePreparation(val ticket: CountSourceTicket, val plan: CountLocalSourcePlan) {
        var insertedId: Long? = null
    }
    internal class MetricReadSelection(val table: String, val where: String, args: Array<out Any?>,
        rows: List<CountTrustRows.Row>) {
        val args: List<Any?> = java.util.Collections.unmodifiableList(args.toList())
        val rows: List<CountTrustRows.Row> = java.util.Collections.unmodifiableList(rows.toList())
        fun verify(native: CountTrustRows) {
            check(native.read(table, where, args.toTypedArray()).map { it.value } == rows.map { it.value }) {
                "METRIC_LOCAL_DEPENDENCY_CHANGED"
            }
        }
    }
    internal class MetricPreparation(val ticket: MetricInsertTicket,
        val plan: MetricObservationLocalEnvelopePlan, val uuids: List<String>,
        val reads: List<MetricReadSelection>, val input: MetricT1Input? = null) {
        var insertedIds: List<Long>? = null
    }
    internal data class RateCalculation(val rate: Int, val instant: Long, val zone: ZoneId)
    private class LocalPreparation(val kernel: CountTrustKernel, val capture: Capture) :
        AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<LocalPreparation>
        var boundary: CountTrustJournal.Boundary? = null
        var caller: Job? = null
        var active = true
        var metricInput: MetricT1Input.Builder? = null
    }

    internal class Context internal constructor(val index: CountTrustIndex, val rows: CountTrustRows,
        val header: CountTrustSeal.Header, val access: LocalCoreWriteAccess,
        val environment: CountTrustValidationEnvironment.Snapshot) {
        fun members(key: String): List<String> = index.get(key)?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        fun row(name: String): CountTrustRows.Row {
            require(name.startsWith("row:"))
            val table = name.substringAfter("row:").substringBefore(':')
            val row = rows.decode(table, requireNotNull(index.get(name)).jsonObject)
            check(row.name == name)
            return row
        }
        fun certified(key: String): List<CountTrustRows.Row> = members(key).map(::row)
        fun checked(key: String, table: String, where: String, args: Array<out Any?>): List<CountTrustRows.Row> {
            val actual = rows.read(table, where, args)
            check(actual.map { it.name }.sorted() == members(key).sorted()) { "COUNT_TRUST_MEMBERS_CHANGED" }
            actual.forEach { check(index.get(it.name) == it.value) { "COUNT_TRUST_CONTENT_CHANGED" } }
            return actual
        }
        fun eligible(habit: HabitEntity): Boolean = when (habit.habitType) {
            HabitType.COUNTING -> index.get(eligibleKey(habit.uuid)) == JsonPrimitive(true)
            HabitType.CHECK_IN -> index.get(CHECK_COVERAGE_KEY) == JsonPrimitive(CHECK_COVERAGE) &&
                index.get(checkEligibleKey(habit.uuid)) == JsonPrimitive(CHECK_COVERAGE)
            else -> false
        }
        val once: Boolean get() {
            val coverage = index.get(OnceTrustEvidence.COVERAGE_KEY)
            val habits = index.get(OnceTrustEvidence.HABITS_KEY)
            if (coverage == null && habits == null) return false
            check(coverage == JsonPrimitive(OnceTrustEvidence.COVERAGE) && habits is JsonArray) {
                "ONCE_TRUST_COVERAGE_CHANGED"
            }
            val values = habits.map { it.jsonPrimitive.also { value -> check(value.isString) }.content }
            check(values == values.distinct().sorted()) { "ONCE_TRUST_HABITS_CHANGED" }
            return true
        }
        fun onceEligible(habit: HabitEntity): Boolean {
            val covered = once
            val eligible = index.get(OnceTrustEvidence.eligibleKey(habit.uuid))
            val projection = index.get(OnceTrustEvidence.projectionKey(habit.uuid))
            if (!covered) {
                check(eligible == null && projection == null) { "ONCE_TRUST_ORPHAN_QUALIFICATION" }
                return false
            }
            val declared = index.get(OnceTrustEvidence.HABITS_KEY)!!.jsonArray.any { it == JsonPrimitive(habit.uuid) }
            check(if (declared) eligible == JsonPrimitive(OnceTrustEvidence.COVERAGE) && projection is JsonObject
                else eligible == null && projection == null) { "ONCE_TRUST_QUALIFICATION_CHANGED" }
            return declared && habit.completionPolicy == "one_and_done"
        }
        val metricOnly: Boolean get() = when (index.get(MetricTrustDirectory.METRIC_ONLY_KEY)) {
            JsonPrimitive(true) -> true
            JsonPrimitive(false) -> false
            else -> error("METRIC_DIRECTORY_QUALIFICATION_PROFILE")
        }
        val metricRanges: Boolean get() {
            metricOnly
            check(index.get(MetricCommandAbsenceEvidence.KEY) in setOf(JsonPrimitive(true), JsonPrimitive(false))) { "METRIC_ABSENCE_COVERAGE" }
            check(index.get(MetricTrustDirectory.COVERAGE_KEY) == JsonPrimitive(MetricTrustDirectory.COVERAGE) &&
                index.get(MetricTrustDirectory.METRICS_KEY) is JsonArray && index.get(MetricTrustDirectory.ROSTER_KEY) is JsonArray) {
                "METRIC_DIRECTORY_COVERAGE"
            }
            return true
        }
        val observations: Boolean get() = when (index.get(OBSERVATION_COVERAGE_KEY)) {
            null -> false
            JsonPrimitive(OBSERVATION_COVERAGE) -> true
            else -> error("COUNT_OBSERVATION_COVERAGE_CHANGED")
        }
        private fun claimedTimerHabits(): List<String> {
            val claimed = requireNotNull(index.get(TimerTrustEvidence.HABITS_KEY)) { "TIMER_TRUST_HABITS_MISSING" }
                .jsonArray.map { item -> item.jsonPrimitive.also { check(it.isString) { "TIMER_TRUST_HABITS_CHANGED" } }.content }
            check(claimed == claimed.sorted() && claimed.distinct().size == claimed.size && claimed.all(::isContractUuid)) {
                "TIMER_TRUST_HABITS_CHANGED"
            }
            return claimed
        }
        val timers: Boolean get() {
            val source = index.get(TimerTrustEvidence.COVERAGE_KEY)
            val display = index.get(TimerDisplayBlocks.COVERAGE_KEY)
            val habits = index.get(TimerTrustEvidence.HABITS_KEY)
            if (source == null) {
                check(display == null && habits == null) { "TIMER_TRUST_COVERAGE_CHANGED" }
                return false
            }
            check(source == JsonPrimitive(TimerTrustEvidence.COVERAGE) &&
                display == JsonPrimitive(TimerDisplayBlocks.COVERAGE) && habits != null) { "TIMER_TRUST_COVERAGE_CHANGED" }
            claimedTimerHabits()
            return true
        }
        fun timerEligible(habit: HabitEntity): Boolean {
            if (habit.habitType != HabitType.TIMER) return false
            val eligible = index.get(TimerTrustEvidence.eligibleKey(habit.uuid))
            if (!timers) {
                check(eligible == null) { "TIMER_TRUST_ELIGIBILITY_CHANGED" }
                return false
            }
            val issued = habit.uuid in claimedTimerHabits()
            check(if (issued) eligible == JsonPrimitive(TimerTrustEvidence.COVERAGE) else eligible == null) {
                "TIMER_TRUST_ELIGIBILITY_CHANGED"
            }
            return issued
        }
        val formal: Boolean get() = index.get(CountTrustRoundEvidence.COVERAGE_KEY) == JsonPrimitive(CountTrustRoundEvidence.COVERAGE)
        fun head(activity: String): ChallengeRoundHead? = if (formal)
            CountTrustRoundEvidence.decode(requireNotNull(index.get(CountTrustRoundEvidence.headKey(activity)))) else null
        fun known(activity: String): List<ChallengeRoundHead?> = if (formal)
            requireNotNull(index.get(CountTrustRoundEvidence.knownKey(activity))).jsonArray.map(CountTrustRoundEvidence::decode)
            else listOf(null)
        fun factHead(uuid: String): ChallengeRoundHead? = if (formal)
            CountTrustRoundEvidence.decode(requireNotNull(index.get(CountTrustRoundEvidence.factKey(uuid)))) else null
    }

    private data class AuthenticatedOpen(val control: Control,
        val header: CountTrustSeal.Header, val index: CountTrustIndex)

    /** Compatibility observation: any returned Context has no live decoded-operation session.
     * Production reads/writers use withOpen/withCapture so opening flags share their exact proof.
     */
    suspend fun open(access: LocalCoreWriteAccess): Context? = withOpen(access) { it }

    /** Lexical decoded evidence only. Authentication and the real transaction remain fresh;
     * no maintenance capability is held around a caller's DAO/business callback.
     */
    internal suspend fun <T> withOpen(access: LocalCoreWriteAccess, block: suspend (Context?) -> T): T {
        val owner = runtime ?: return block(null)
        val caller = currentCoroutineContext()
        // The bridge and COUNT seal share one frozen warm schema inside this short handoff.
        // Mandatory inspection still precedes optional retirement/dirty exits; no caller DAO
        // or business operation runs with this maintenance capability.
        val started = CountCommandSchemaBridge.withOpenSchema(database, access) { pending, currentSchema ->
            if (pending || owner.isRetired) return@withOpenSchema null
            check(database.inTransaction())
            owner.maintenance(database) {
                owner.requireUsable()
                val control = control()
                if (owner.recoveryPreviewSuppressed(control.storage)) return@maintenance null
                if (control.dirty) return@maintenance null
                val header = CountTrustSeal.read(sql) ?: return@maintenance null
                check(header.generation == control.generation) { "COUNT_TRUST_GENERATION_CHANGED" }
                val binding = CountTrustSeal.binding(access, control.storage, header.validator)
                if (header.binding != binding) return@maintenance null
                check(CountTrustSeal().matches(header, binding, currentSchema() ?: schemaHash(owner))) { "COUNT_TRUST_SEAL_INVALID" }
                val readToken = owner.captureReadToken(database)
                // Cancellation stays cheap in codec byte loops. The actual transaction guard runs
                // at logical lookups/session boundaries, not once for every decoded byte.
                val index = CountTrustIndex(sql, binding, header.root, { caller.ensureActive() },
                    { owner.requireReadToken(database, readToken) })
                AuthenticatedOpen(control, header, index)
            }
        } ?: return block(null)
        return started.index.withOperationReadScope {
            val context = owner.maintenance(database) {
                owner.requireUsable()
                // The two maintenance scopes belong to the same real transaction. Detect any
                // unexpected interleaving before using the previously authenticated tuple.
                check(control() == started.control && CountTrustSeal.read(sql) == started.header) {
                    "COUNT_TRUST_OPEN_BOUNDARY_CHANGED"
                }
                if (started.header.validator != CountTrustSeal.VALIDATOR) {
                    started.index.verifyContents()
                    return@maintenance null
                }
                val environment = CountTrustValidationEnvironment.Snapshot.decode(
                    requireNotNull(started.index.get(CountTrustValidationEnvironment.KEY)) { "COUNT_TRUST_ENVIRONMENT_MISSING" })
                if (environment != CountTrustValidationEnvironment.current()) {
                    started.index.verifyContents()
                    verifyMetricDirectoryContents(started.index)
                    return@maintenance null
                }
                Context(started.index, CountTrustRows(sql), started.header, access, environment).also { it.metricRanges }
            }
            block(context)
        }
    }

    private fun verifyMetricDirectoryContents(index: CountTrustIndex) {
        check(index.get(MetricTrustDirectory.METRIC_ONLY_KEY) in setOf(JsonPrimitive(true), JsonPrimitive(false)))
        check(index.get(MetricCommandAbsenceEvidence.KEY) in setOf(JsonPrimitive(true), JsonPrimitive(false)))
        check(index.get(MetricTrustDirectory.ROSTER_KEY) is JsonArray)
        check(index.get(MetricTrustDirectory.COVERAGE_KEY) == JsonPrimitive(MetricTrustDirectory.COVERAGE))
        val ids = requireNotNull(index.get(MetricTrustDirectory.METRICS_KEY)).jsonArray.map { it.jsonPrimitive.content }
        check(ids == ids.distinct().sorted()) { "METRIC_DIRECTORY_MEMBERS" }
        val reader = MetricTrustDirectory.Reader(index::get)
        ids.forEach(reader::verifyAll)
    }

    /** Opaque read participants only. No Context, SQL handle or write/capture capability escapes.
     * The implementation belongs to one existing SELECT-only phase and its exact coroutine Job.
     */
    internal sealed interface DisplayRead {
        suspend fun metricSnapshot(database: HabitDatabase, tokens: TokenManager, metric: MetricEntity,
            start: Long, end: Long): MetricWindowSnapshot?
        suspend fun metricHistory(database: HabitDatabase, tokens: TokenManager, metric: MetricEntity): List<MetricLogEntity>?
        suspend fun metricPage(database: HabitDatabase, tokens: TokenManager, metric: MetricEntity,
            upper: Long?, target: Int, expectedRevision: Long?): MetricTrustProjection.Page
        suspend fun metricRoster(database: HabitDatabase, tokens: TokenManager)
        suspend fun onceSnapshot(database: HabitDatabase, tokens: TokenManager, activityUuid: String,
            session: com.dayforge.data.local.LocalDataSession): OnceTrustProjection.Read?
        suspend fun summary(database: HabitDatabase, tokens: TokenManager, habit: HabitEntity,
            today: LocalDate): CountSummary?
        suspend fun projection(database: HabitDatabase, tokens: TokenManager, habit: HabitEntity,
            today: LocalDate, zone: ZoneId, cardAtMillis: Long?): CountDisplayProjection?
        suspend fun checkProjection(database: HabitDatabase, tokens: TokenManager, habit: HabitEntity,
            today: LocalDate, zone: ZoneId, cardAtMillis: Long?): CheckDisplayProjection?
        suspend fun timerProjection(database: HabitDatabase, tokens: TokenManager, habit: HabitEntity,
            today: LocalDate, zone: ZoneId, now: Long): TimerDisplayProjection?
        suspend fun exactFact(database: HabitDatabase, tokens: TokenManager, habit: HabitEntity,
            id: Long?, uuid: String?): CompletionEntity?
        suspend fun details(database: HabitDatabase, tokens: TokenManager, habit: HabitEntity): List<CompletionEntity>?
        suspend fun displayedRounds(database: HabitDatabase, tokens: TokenManager,
            access: LocalSyncAccess): NextRoundWriteScope?
    }

    private inner class DisplayReadOwner(private val context: Context,
        private val phase: NextEvidenceReadSession, private val job: Job) : DisplayRead {
        var active = true
        private suspend fun requireOwner(actualDatabase: HabitDatabase, actualTokens: TokenManager) {
            currentCoroutineContext().ensureActive()
            check(active && actualDatabase === database && actualTokens === tokens &&
                currentCoroutineContext()[Job] === job && database.inTransaction()) {
                "COUNT_DISPLAY_READ_EXPIRED"
            }
            check(NextEvidenceReadSession.current(database) === phase) { "COUNT_DISPLAY_READ_EXPIRED" }
        }
        override suspend fun metricSnapshot(database: HabitDatabase, tokens: TokenManager, metric: MetricEntity,
            start: Long, end: Long): MetricWindowSnapshot? {
            requireOwner(database, tokens)
            val result = MetricTrustProjection(database).read(context, metric, start, end)
            requireOwner(database, tokens)
            return result
        }
        override suspend fun metricHistory(database: HabitDatabase, tokens: TokenManager, metric: MetricEntity): List<MetricLogEntity>? {
            requireOwner(database, tokens)
            val result = MetricTrustProjection(database).history(context, metric)
            requireOwner(database, tokens)
            return result
        }
        override suspend fun metricPage(database: HabitDatabase, tokens: TokenManager, metric: MetricEntity,
            upper: Long?, target: Int, expectedRevision: Long?): MetricTrustProjection.Page {
            requireOwner(database, tokens)
            val result = context.index.withReadScope { MetricTrustProjection(database).page(context, metric, upper, target, expectedRevision) }
            requireOwner(database, tokens)
            return result
        }
        override suspend fun metricRoster(database: HabitDatabase, tokens: TokenManager) {
            requireOwner(database, tokens)
            context.index.withReadScope { MetricTrustProjection(database).verifyRoster(context) }
            requireOwner(database, tokens)
        }
        override suspend fun onceSnapshot(database: HabitDatabase, tokens: TokenManager, activityUuid: String,
            session: com.dayforge.data.local.LocalDataSession): OnceTrustProjection.Read? {
            requireOwner(database, tokens)
            val result = OnceTrustProjection(database, tokens, sessions).readInContext(context, activityUuid, session)
            requireOwner(database, tokens)
            return result
        }
        override suspend fun summary(database: HabitDatabase, tokens: TokenManager, habit: HabitEntity,
            today: LocalDate): CountSummary? {
            requireOwner(database, tokens)
            return summaryInContext(context, habit, today)
        }
        override suspend fun projection(database: HabitDatabase, tokens: TokenManager, habit: HabitEntity,
            today: LocalDate, zone: ZoneId, cardAtMillis: Long?): CountDisplayProjection? {
            requireOwner(database, tokens)
            return projectionInContext(context, habit, today, zone, cardAtMillis)
        }
        override suspend fun exactFact(database: HabitDatabase, tokens: TokenManager, habit: HabitEntity,
            id: Long?, uuid: String?): CompletionEntity? {
            requireOwner(database, tokens)
            return exactFactInContext(context, habit, id, uuid)
        }
        override suspend fun checkProjection(database: HabitDatabase, tokens: TokenManager, habit: HabitEntity,
            today: LocalDate, zone: ZoneId, cardAtMillis: Long?): CheckDisplayProjection? {
            requireOwner(database, tokens)
            return checkProjectionInContext(context, habit, today, zone, cardAtMillis)
        }
        override suspend fun timerProjection(database: HabitDatabase, tokens: TokenManager, habit: HabitEntity,
            today: LocalDate, zone: ZoneId, now: Long): TimerDisplayProjection? {
            requireOwner(database, tokens)
            return timerProjectionInContext(context, habit, today, zone, now)
        }
        override suspend fun details(database: HabitDatabase, tokens: TokenManager, habit: HabitEntity): List<CompletionEntity>? {
            requireOwner(database, tokens)
            return detailsInContext(context, habit)
        }
        override suspend fun displayedRounds(database: HabitDatabase, tokens: TokenManager,
            access: LocalSyncAccess): NextRoundWriteScope? {
            requireOwner(database, tokens)
            // Preserve the original logical participant's fresh authority read. Reuse proof,
            // never entry credentials; the display owner still performs its final fresh check.
            val core = tokens.localCoreWriteAccess() ?: return null
            if (core.session != access.session || core.capturedDeviceId != access.deviceId ||
                core.capabilities != access.capabilities || core != context.access) return null
            requireOwner(database, tokens)
            return displayedRoundsInContext(context, access)
        }
    }

    /** Optional batch proof; failed/unsupported opening falls back to the original participants.
     * Only opening is caught. Participant failures still abort the actual outer transaction.
     * Existing read-session probes and final fresh authority/actual END remain mandatory.
     */
    internal suspend fun <T> withDisplayRead(access: LocalCoreWriteAccess,
        block: suspend (DisplayRead?) -> T): T {
        NextEvidenceReadSession.current(database)
        val context = try { open(access) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (migration: CountCommandSchemaBridge.Invalid) { throw migration }
        catch (_: Exception) { null }
        return if (context == null) block(null) else useDisplayRead(context, block)
    }

    private suspend fun <T> useDisplayRead(context: Context, block: suspend (DisplayRead?) -> T): T {
        val phase = NextEvidenceReadSession.current(database)
        val participant = DisplayReadOwner(context, phase, requireNotNull(currentCoroutineContext()[Job]))
        return try { context.index.withOperationReadScope { block(participant) } }
        finally { participant.active = false }
    }

    /** The only production batch entry. Qualification may write before the SELECT-only phase;
     * no participant callback can interleave its warm authentication and this handoff. The
     * actual Room transaction remains unchanged. Cold/unsupported cases retain original proof.
     */
    internal suspend fun <T> withPreparedDisplayRead(access: LocalCoreWriteAccess?, prepare: Boolean,
        prepareTimerFor: HabitEntity? = null, openExistingProof: Boolean = false, prepareMetrics: Boolean = false,
        block: suspend (DisplayRead?) -> T): T {
        val prepareTimers = prepareTimerFor != null
        check(!openExistingProof || !prepare && !prepareTimers && !prepareMetrics) { "ONCE_DISPLAY_PREPARATION_FORBIDDEN" }
        if (prepare) NextEvidenceReadSession.requireWritePhase()
        // The witness starts BEFORE authentication. An intervening schema edit must not
        // become the new read phase's initial baseline and silently adopt the old root MAC.
        val before = if ((prepare || openExistingProof) && access != null && runtime?.isRetired == false) displayBoundary() else null
        val prepared = when {
            prepare && access != null -> prepareDisplayContext(access, prepareTimerFor, prepareMetrics)
            openExistingProof && access != null -> open(access) // Authenticate only; no optional cold write.
            else -> null
        }
        return NextEvidenceReadSession.read(database) {
            if ((!prepare && !openExistingProof) || access == null) block(null)
            else if (prepared == null) {
                if (openExistingProof) block(null)
                else if (prepareTimers || prepareMetrics) {
                    // Cold issuance returns no prepared Context: authenticate in this read
                    // phase, close that operation scope, then enter the participant scope.
                    // Keep TIMER authentication failures outside the optional COUNT fallback.
                    val context = open(access)
                    if (context == null) block(null) else useDisplayRead(context, block)
                } else withDisplayRead(access, block)
            }
            else {
                check(before != null && displayBoundary() == before) { "COUNT_DISPLAY_HANDOFF_CHANGED" }
                useDisplayRead(rebindDisplayContext(prepared), block)
            }
        }
    }

    private data class DisplaySchema(val sequence: Long, val name: String, val file: String, val version: Long)
    private data class DisplayBoundary(val changes: Long, val main: Long, val temp: Long, val schemas: List<DisplaySchema>)
    private fun displayBoundary(): DisplayBoundary {
        fun version(name: String): Long = sql.query("PRAGMA \"${name.replace("\"", "\"\"")}\".schema_version").use {
            check(it.moveToFirst()); it.getLong(0)
        }
        val main = version("main"); val temp = version("temp")
        val schemas = sql.query("PRAGMA database_list").use { cursor -> buildList {
            while (cursor.moveToNext()) {
                val name = cursor.getString(1)
                add(DisplaySchema(cursor.getLong(0), name, cursor.getString(2), when (name) {
                    "main" -> main; "temp" -> temp; else -> version(name)
                }))
            }
        } }
        val changes = sql.query("SELECT total_changes()").use { check(it.moveToFirst()); it.getLong(0) }
        return DisplayBoundary(changes, main, temp, schemas)
    }

    private suspend fun rebindDisplayContext(prepared: Context): Context {
        NextEvidenceReadSession.current(database)
        // EvidenceReadSession establishes a new Job. Bind byte-loop cancellation to this Job,
        // while first checking the old token still belongs to this exact native transaction.
        prepared.index.withReadScope { }
        val caller = currentCoroutineContext()
        val owner = requireNotNull(runtime)
        return owner.maintenance(database) {
            owner.requireUsable()
            val current = control()
            check(!current.dirty && current.generation == prepared.header.generation &&
                CountTrustSeal.binding(prepared.access, current.storage, prepared.header.validator) == prepared.header.binding &&
                CountTrustSeal.read(sql) == prepared.header) { "COUNT_DISPLAY_HANDOFF_CHANGED" }
            val token = owner.captureReadToken(database)
            Context(CountTrustIndex(sql, prepared.header.binding, prepared.header.root, { caller.ensureActive() },
                { owner.requireReadToken(database, token) }), CountTrustRows(sql), prepared.header,
                prepared.access, prepared.environment)
        }
    }

    /** Only the first qualification or an explicit invalidation performs the old complete proof.
     * Call outside NextEvidenceReadSession, in the same account-owned outer transaction.
     */
    suspend fun prepareInTransaction(access: LocalCoreWriteAccess) {
        prepareDisplayContext(access)
    }

    private suspend fun prepareDisplayContext(access: LocalCoreWriteAccess, timerHabit: HabitEntity? = null, prepareMetrics: Boolean = false): Context? {
        val prepareTimers = timerHabit != null
        if (prepareMetrics) open(access)?.let { if (it.metricRanges) return it }
        // A clean authenticated TIMER claim is not a disposable cache. Authenticate outside
        // the optional cold-qualification catch; corrupt MAC/old index/coverage must fail.
        // Dirty or absent roots still return null and take the original complete cold proof.
        if (timerHabit != null) {
            check(timerHabit.habitType == HabitType.TIMER) { "TIMER_ACTIVITY_CHANGED" }
            open(access)?.let { context ->
                // Check the selected subject BEFORE an unissued domain can be cold-issued.
                // In particular, three absent declarations do not excuse an orphan eligible
                // leaf; a true COUNT-only root has neither the declarations nor this leaf.
                context.timerEligible(timerHabit)
                if (context.timers) return context
            }
        }
        return try { qualify(access, prepareTimers, prepareMetrics = prepareMetrics) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (migration: CountCommandSchemaBridge.Invalid) { throw migration }
        catch (error: Exception) {
            // Optional qualification cannot broaden the original reader's rejection scope.
            // No root is issued on failure; the caller still runs its complete original proof.
            lastQualificationFailure = error.javaClass.simpleName + ":" + error.message
            null
        }
    }

    private suspend fun qualify(access: LocalCoreWriteAccess, prepareTimers: Boolean, prepareOnce: Boolean = false, prepareMetrics: Boolean = false): Context? {
        val owner = runtime ?: return null
        if (owner.isRetired) { CountCommandSchemaBridge.pending(database, access); return null }
        if (sql.version == com.dayforge.data.local.CountCommandV20Schema.VERSION) {
            if (!ReplicaPresentationRepository(database, tokens, sessions).isOpenWhileAccountLocked()) {
                CountCommandSchemaBridge.pending(database, access) // Validate only; frozen bytes are not rewritten.
                return null
            }
            // One-time exact migration envelope conversion, inside the original fresh-auth,
            // write-capable qualification transaction. No legacy traversal on subsequent opens.
            CountCommandSchemaBridge.advance(database, access)
        }
        val metricOnly = prepareMetrics && !prepareTimers && !prepareOnce
        open(access)?.let { if ((metricOnly || !it.metricOnly) && (!prepareTimers || it.timers) && (!prepareOnce || it.once)) return it }
        if (owner.isRetired) return null // An active preview may retire this new physical owner.
        val environment = CountTrustValidationEnvironment.current() ?: run {
            lastQualificationFailure = "ENVIRONMENT_UNAVAILABLE:" + CountTrustValidationEnvironment.failure
            return null
        }
        check(database.inTransaction())
        if (!ReplicaPresentationRepository(database, tokens, sessions).isOpenWhileAccountLocked()) return null
        val allHabits = database.habitDao().getAllHabitsOnce()
        val visibleTypes = allHabits.filter { it.appearance != null && it.completionPolicy == "recurring" }
        val onceHabits = if (prepareOnce) allHabits.filter { it.completionPolicy == "one_and_done" } else emptyList()
        // An explicitly requested metric range must not first audit unrelated per-habit
        // histories. Other domains retain their original explicit preparation and can
        // replace this native-range-only root with their full qualification later.
        val habits = if (metricOnly) emptyList() else visibleTypes.filter { it.habitType in setOf(HabitType.COUNTING, HabitType.CHECK_IN) }
        val timerHabits = if (prepareTimers) visibleTypes.filter { it.habitType == HabitType.TIMER } else emptyList()
        if (habits.isEmpty() && timerHabits.isEmpty() && onceHabits.isEmpty() && !prepareMetrics) return null
        // Verify this build's complete schema before any root can authenticate native changes.
        if (!approvedSchema(owner)) return null
        val validatedControl = control()
        val roundScope = NextCoreLocalIntentStore(database, tokens, sessions).captureDisplayedRoundsInTransaction()
        // Pending creation/restart needs its own maintained source certificate. Existing business
        // remains available through the original reader until that complete coverage is installed.
        if (roundScope != null && (roundScope.pendingInitials.isNotEmpty() || roundScope.pendingRestarts.isNotEmpty())) return null
        val metadata = roundScope?.let { Json.decodeFromString(ChallengeMetadata.serializer(), it.metadataJson) }
        val today = com.dayforge.util.DateTimeUtils.today()
        val evidence = LinkedHashMap<String, RecurringCompletionEvidence>()
        val original = RecurringCompletionEvidenceReader(database, tokens, sessions)
        for (habit in habits) {
            currentCoroutineContext().ensureActive()
            evidence[habit.uuid] = original.readInTransaction(habit, today)
        }
        // TIMER is explicitly requested only by its finite display entry. Old full readers
        // and ordinary COUNT/CHECK qualification do not silently acquire this extra work.
        val timerEvidence = linkedMapOf<String, TimerTrustEvidence>()
        val timerReader = TimerHistoryReader(database, tokens, sessions)
        for (habit in timerHabits) {
            currentCoroutineContext().ensureActive()
            timerEvidence[habit.uuid] = timerReader.trustInTransaction(habit, today)
        }
        val onceEvidence = linkedMapOf<String, OnceTrustEvidence>()
        for (habit in onceHabits) {
            currentCoroutineContext().ensureActive()
            onceEvidence[habit.uuid] = OnceTrustEvidence.audit(database, habit.uuid, access.session)
        }
        val commonHealthy = try { CountTrustAck.globalCommonHealth(database) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { lastQualificationFailure = "ACK_GLOBAL:" + error.message; false }
        check(control() == validatedControl) { "COUNT_TRUST_QUALIFICATION_CHANGED" }
        owner.maintenance(database) {
            owner.requireUsable()
            val native = CountTrustRows(sql)
            val all = (snapshotTables + MetricCommandAbsenceEvidence.tables + (if (prepareTimers) timerSnapshotTables else emptyList()) +
                (if (prepareOnce) onceSnapshotTables else emptyList())).distinct().flatMap { native.read(it) }
            check(control() == validatedControl) { "COUNT_TRUST_QUALIFICATION_CHANGED" }
            val values = linkedMapOf<String, JsonElement>()
            val members = linkedMapOf<String, MutableSet<String>>()
            for (row in all) {
                currentCoroutineContext().ensureActive()
                values[row.name] = row.value
                (CountTrustMembership.memberships(row) + MetricCommandAbsenceEvidence.memberships(row)).forEach { (key, member) -> members.getOrPut(key) { sortedSetOf() }.add(member) }
                if (prepareOnce) OnceTrustEvidence.memberships(row).forEach { (key, member) ->
                    members.getOrPut(key) { sortedSetOf() }.add(member)
                }
                if (prepareTimers) TimerTrustEvidence.memberships(row).forEach { (key, member) ->
                    members.getOrPut(key) { sortedSetOf() }.add(member)
                }
            }
            members.forEach { (key, set) -> values[key] = JsonArray(set.map(::JsonPrimitive)) }
            val countIds = habits.mapTo(hashSetOf()) { it.id }
            val factHeads = if (metadata == null) emptyMap() else CountTrustRoundEvidence.bootstrap(metadata,
                all.filter { it.table.name == "completions" && it.long("habitId") in countIds },
                all.filter { it.table.name == "next_request_origins" })
            if (metadata != null) {
                values[CountTrustRoundEvidence.COVERAGE_KEY] = JsonPrimitive(CountTrustRoundEvidence.COVERAGE)
                values[CountTrustRoundEvidence.METADATA] = Json.encodeToJsonElement(ChallengeMetadata.serializer(), metadata)
                factHeads.forEach { (uuid, head) -> values[CountTrustRoundEvidence.factKey(uuid)] = CountTrustRoundEvidence.encode(head) }
            }
            for (habit in habits) {
                val source = evidence.getValue(habit.uuid)
                val factRows = all.filter { it.table.name == "completions" && it.long("habitId") == habit.id }
                val dayRows = all.filter { it.table.name == "count_days" && it.long("habitId") == habit.id }
                val months = (source.completions.map { YearMonth.from(it.businessDate) } +
                    source.policies.keys.map(YearMonth::from) + source.unknownDates.map(YearMonth::from)).toSortedSet()
                if (habit.habitType == HabitType.CHECK_IN)
                    values[checkEligibleKey(habit.uuid)] = JsonPrimitive(CHECK_COVERAGE)
                else values[eligibleKey(habit.uuid)] = JsonPrimitive(true)
                val heads: List<ChallengeRoundHead?> = if (metadata == null) listOf(null) else {
                    val checkpoint = metadata.checkpoints.single { it.head.activityUuid == habit.uuid }
                    check(source.roundHead == checkpoint.head) { "COUNT_ROUND_INVALID" }
                    values[CountTrustRoundEvidence.headKey(habit.uuid)] = CountTrustRoundEvidence.encode(checkpoint.head)
                    checkpoint.records.map { it.head }.also { known ->
                        values[CountTrustRoundEvidence.knownKey(habit.uuid)] = JsonArray(known.map(CountTrustRoundEvidence::encode))
                    }
                }
                // Every retained round has its own quantity/latest result. Day policy stays shared.
                for (head in heads) {
                    val partitionFacts = factRows.filter { head == null || factHeads[it.text("uuid")] == head }
                    val partitionMonths = (months + partitionFacts.map { YearMonth.from(LocalDate.parse(it.text("recordedLocalDate"))) }).toSortedSet()
                    values[monthsKey(habit.uuid, head)] = JsonArray(partitionMonths.map { JsonPrimitive(it.toString()) })
                    for (month in partitionMonths) values[summaryKey(habit.uuid, month, head)] = CountTrustMembership.summary(habit.uuid, month,
                        partitionFacts.filter { YearMonth.from(LocalDate.parse(it.text("recordedLocalDate"))) == month },
                        dayRows.filter { YearMonth.from(LocalDate.parse(it.text("localDate"))) == month },
                        if (head == null || head == source.roundHead) source.unknownDates.filterTo(linkedSetOf()) { YearMonth.from(it) == month }
                            else null)
                    if (head != null || habit.habitType == HabitType.CHECK_IN) {
                        val completeMonths = partitionMonths.associateWith { month ->
                            requireNotNull(values[summaryKey(habit.uuid, month, head)]).jsonObject }
                        values.putAll(if (habit.habitType == HabitType.CHECK_IN)
                            CheckDisplayBlocks.issue(habit, head, completeMonths, ZoneId.systemDefault())
                            else CountDisplayBlocks.issue(habit, requireNotNull(head), completeMonths, ZoneId.systemDefault()))
                    }
                }
            }
            if (prepareTimers) {
                check(all.count { it.table.name == "timelogs" && it.nullableLong("endTime") == null } <= 1) {
                    "TIMER_FAILURE_MULTIPLE_ACTIVE_SESSIONS"
                }
                for (habit in timerHabits) {
                    val audited = timerEvidence.getValue(habit.uuid)
                    check(audited.habit == habit) { "TIMER_TRUST_ACTIVITY_CHANGED" }
                    val heads: List<ChallengeRoundHead?> = if (metadata == null) listOf(null) else {
                        val checkpoint = metadata.checkpoints.single { it.head.activityUuid == habit.uuid }
                        check(audited.displayedHead == checkpoint.head) { "TIMER_ROUND_INVALID" }
                        values[CountTrustRoundEvidence.headKey(habit.uuid)] = CountTrustRoundEvidence.encode(checkpoint.head)
                        checkpoint.records.map { it.head }.also { known ->
                            values[CountTrustRoundEvidence.knownKey(habit.uuid)] = JsonArray(known.map(CountTrustRoundEvidence::encode))
                        }
                    }
                    for (session in audited.sessions) {
                        check(session.head in heads) { "TIMER_ROUND_INVALID" }
                        values[TimerTrustEvidence.birthKey(session.log.uuid)] = TimerTrustEvidence.encodeBirth(session.head)
                    }
                    heads.forEach { head -> values.putAll(TimerDisplayBlocks.issue(habit, head,
                        audited.completedMillis(head), ZoneId.systemDefault())) }
                    values[TimerTrustEvidence.eligibleKey(habit.uuid)] = JsonPrimitive(TimerTrustEvidence.COVERAGE)
                }
                values[TimerTrustEvidence.HABITS_KEY] = JsonArray(timerHabits.map { JsonPrimitive(it.uuid) }.sortedBy { it.content })
                values[TimerTrustEvidence.COVERAGE_KEY] = JsonPrimitive(TimerTrustEvidence.COVERAGE)
                values[TimerDisplayBlocks.COVERAGE_KEY] = JsonPrimitive(TimerDisplayBlocks.COVERAGE)
            }
            if (prepareOnce) {
                for (habit in onceHabits) {
                    val audited = onceEvidence.getValue(habit.uuid)
                    check(audited.habit == habit) { "ONCE_TRUST_ACTIVITY_CHANGED" }
                    values[OnceTrustEvidence.eligibleKey(habit.uuid)] = JsonPrimitive(OnceTrustEvidence.COVERAGE)
                    values[OnceTrustEvidence.projectionKey(habit.uuid)] = OnceTrustEvidence.encode(audited.snapshot)
                }
                values[OnceTrustEvidence.HABITS_KEY] = JsonArray(onceHabits.map { JsonPrimitive(it.uuid) }.sortedBy { it.content })
                values[OnceTrustEvidence.COVERAGE_KEY] = JsonPrimitive(OnceTrustEvidence.COVERAGE)
            }
            // The same frozen native rows also build metric range evidence: no second history query.
            val metricCaller = currentCoroutineContext()
            values[MetricTrustDirectory.METRIC_ONLY_KEY] = JsonPrimitive(metricOnly)
            values[MetricCommandAbsenceEvidence.KEY] = MetricCommandAbsenceEvidence.issue(all) { metricCaller.ensureActive() }
            values.putAll(MetricTrustDirectory.issue(all.filter { it.table.name == "metrics" },
                all.filter { it.table.name == "metric_logs" }, Math.addExact(validatedControl.generation, 1)) { metricCaller.ensureActive() })
            values[CHECK_COVERAGE_KEY] = JsonPrimitive(CHECK_COVERAGE)
            values[CheckDisplayBlocks.COVERAGE_KEY] = JsonPrimitive(CheckDisplayBlocks.COVERAGE)
            if (metadata != null) values[CountDisplayBlocks.COVERAGE_KEY] = JsonPrimitive(CountDisplayBlocks.COVERAGE)
            // Empty tables and no-history habits still have a root with authenticated absence.
            values["format"] = JsonPrimitive(CountTrustSeal.VALIDATOR)
            values[CountTrustValidationEnvironment.KEY] = environment.toJson()
            values["global_common"] = JsonPrimitive(commonHealthy)
            values[OBSERVATION_COVERAGE_KEY] = JsonPrimitive(OBSERVATION_COVERAGE)
            values["activity_shadow_count"] = JsonPrimitive(all.count { it.table.name == "sync_entity_state" && it.text("entityType") == "activity_event" })
            check(control() == validatedControl) { "COUNT_TRUST_QUALIFICATION_CHANGED" }
            val binding = CountTrustSeal.binding(access, validatedControl.storage)
            val caller = currentCoroutineContext()
            val root = CountTrustIndex.build(sql, binding, values) { caller.ensureActive() }
            check(control() == validatedControl) { "COUNT_TRUST_QUALIFICATION_CHANGED" }
            val old = CountTrustSeal.read(sql)
            val revision = Math.addExact(old?.revision ?: 0, 1)
            check(environment == CountTrustValidationEnvironment.current()) { "COUNT_TRUST_ENVIRONMENT_CHANGED" }
            check(tokens.localCoreWriteAccess() == access) { "COUNT_SESSION_CHANGED" }
            CountTrustSeal.store(sql, CountTrustSeal().prepare(binding, schemaHash(owner), root, revision, validatedControl.generation))
        }
        return null // Cold issuance is authenticated in the new read phase, once.
    }

    /** Only original write transactions opt into cold ONCE issuance. The returned capture
     * has no active decoded-index operation scope, so original inner readers keep their own
     * scopes. No authenticated capability crosses an actual Room transaction boundary.
     */
    internal suspend fun <T> withOnceCapture(access: LocalCoreWriteAccess?, prepare: Boolean = false,
        block: suspend (Capture?) -> T): T {
        if (access == null) return block(null)
        check(database.inTransaction())
        var context = open(access)
        if (prepare && context?.once != true && runtime?.isRetired == false) {
            // Authenticate claimed leaves before optional issuance: a missing coverage marker
            // cannot launder a signed orphan eligible/projection for an existing subject.
            if (context != null) for (habit in database.habitDao().getAllHabitsOnce().filter {
                    it.completionPolicy == "one_and_done" }) context.onceEligible(habit)
            try { qualify(access, prepareTimers = true, prepareOnce = true) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (migration: CountCommandSchemaBridge.Invalid) { throw migration }
            catch (error: Exception) { lastQualificationFailure = "ONCE_QUALIFY:" + error.javaClass.simpleName }
            context = open(access)
        }
        val captured = context?.takeIf { it.once }?.let { value -> requireNotNull(runtime).maintenance(database) {
            Capture(value, requireNotNull(runtime).captureChangeBoundary())
        } }
        return block(captured)
    }

    suspend fun capture(access: LocalCoreWriteAccess): Capture? = withCapture(access) { it }

    /** One actual producer/send/ACK operation. Keeps only immutable decoded old-root evidence
     * through preparation and final effects; putAll disables reuse before changing that root.
     */
    internal suspend fun <T> withCapture(access: LocalCoreWriteAccess?, prepareMetricSourceRead: Boolean = false,
        block: suspend (Capture?) -> T): T {
        if (access == null) return block(null)
        val before = if (prepareMetricSourceRead && runtime?.isRetired == false) {
            check(database.inTransaction()); displayBoundary()
        } else null
        return withOpen(access) { context ->
            val captured = context?.let { value -> requireNotNull(runtime).maintenance(database) {
                Capture(value, requireNotNull(runtime).captureChangeBoundary())
            } }
            // First journal installation legitimately changes TEMP/schema/total_changes.
            // It keeps the original path for this operation; never rebase an old proof onto
            // an unknown mutation. Normal warm source reads can reuse only identical input.
            if (captured != null && before != null && displayBoundary() == before)
                captured.metricSourceReadState = MetricSourceAnchor(this, captured, before)
            block(captured)
        }
    }

    /** Complete accepted metadata, not a truncated COUNT-only ticket. Its cold certificate also
     * proved the absence of pending creation/restart. Any unsupported writer dirties that proof.
     * Callers keep their original fresh sync authentication and final publication checks.
     */
    suspend fun displayedRounds(access: com.dayforge.data.local.LocalSyncAccess): NextRoundWriteScope? {
        val core = tokens.localCoreWriteAccess() ?: return null
        if (core.session != access.session || core.capturedDeviceId != access.deviceId || core.capabilities != access.capabilities) return null
        return withOpen(core) { context -> context?.let { displayedRoundsInContext(it, access) } }
    }

    private fun displayedRoundsInContext(context: Context, access: LocalSyncAccess): NextRoundWriteScope? {
        if (!context.formal) return null
        val metadata = requireNotNull(context.index.get(CountTrustRoundEvidence.METADATA)).jsonObject
        return NextRoundWriteScope(access.copy(capabilities = access.capabilities.toSet()), metadata.toString())
    }

    private class MetricSourceAnchor(val kernel: CountTrustKernel, val capture: Capture,
        val boundary: DisplayBoundary)
    private class MetricSourceSeed(val kernel: CountTrustKernel, val capture: Capture,
        val anchor: MetricSourceAnchor, val rounds: NextRoundWriteScope,
        val state: NextSyncStateEntity, val metadata: ChallengeMetadata)

    /** Takes only the actual original same-phase metadata proof. The original producer
     * privately supplies its validated pending maps; any pending profile keeps the old reader.
     */
    internal suspend fun captureMetricSourceSeed(capture: Capture?, access: LocalSyncAccess,
        snapshot: NextChallengeReadSnapshot, initials: Map<String, NextPendingInitial>,
        restarts: Map<String, NextRestartReference>) {
        val actual = capture ?: return
        val anchor = actual.metricSourceReadState as? MetricSourceAnchor ?: return
        check(anchor.kernel === this && anchor.capture === actual) { "METRIC_SOURCE_CAPTURE_CHANGED" }
        if (initials.isNotEmpty() || restarts.isNotEmpty()) return
        val (state, metadata) = snapshot.borrow(database, access)
        check(actual.context.access.session == access.session && actual.context.access.capturedDeviceId == access.deviceId &&
            actual.context.access.capabilities == access.capabilities) { "SYNC_CHALLENGE_CONTEXT_CHANGED" }
        val frozen = NextRoundWriteScope(access.copy(capabilities = java.util.Collections.unmodifiableSet(access.capabilities.toSet())),
            Json.encodeToString(ChallengeMetadata.serializer(), metadata))
        snapshot.borrow(database, access)
        actual.metricSourceReadState = MetricSourceSeed(this, actual, anchor, frozen, state, metadata)
    }

    /** A scoped revalidation result, never arbitrary metadata or a transferable permission.
     * This module's only implementation is private and bound to a fresh final read phase.
     */
    internal sealed interface FinalChallengeProof {
        suspend fun borrow(database: HabitDatabase, access: LocalSyncAccess): Pair<NextSyncStateEntity, ChallengeMetadata>
    }

    private inner class IssuedFinalChallengeProof(val seed: MetricSourceSeed,
        val owner: CountTrustRuntime, val lifetime: Any, val boundary: DisplayBoundary) : FinalChallengeProof {
        var active = true
        var consumed = false
            private set
        var phase: NextEvidenceReadSession? = null
        var job: Job? = null

        override suspend fun borrow(database: HabitDatabase, access: LocalSyncAccess): Pair<NextSyncStateEntity, ChallengeMetadata> {
            currentCoroutineContext().ensureActive()
            check(active && !consumed && database === this@CountTrustKernel.database && runtime === owner &&
                seed.kernel === this@CountTrustKernel && seed.capture.metricSourceReadState === seed &&
                currentCoroutineContext()[Job] === job && job?.isActive == true &&
                NextEvidenceReadSession.current(database) === phase) { "CHALLENGE_FINAL_PROOF_EXPIRED" }
            check(access == seed.rounds.access) { "SYNC_CHALLENGE_CONTEXT_CHANGED" }
            owner.requireReadToken(database, lifetime)
            check(seed.capture.context.environment == CountTrustValidationEnvironment.current()) { "COUNT_TRUST_ENVIRONMENT_CHANGED" }
            check(displayBoundary() == boundary) { "CHALLENGE_FINAL_HANDOFF_CHANGED" }
            consumed = true
            return seed.state to seed.metadata
        }
    }

    /** Only the real typed metric producer may reuse its original complete challenge audit.
     * Journal checks run in the original caller, outside both SELECT-only child Jobs. The
     * closure is consumed at the original final-capture position, after source/origin checks.
     */
    internal suspend fun <T> withFinalMetricChallengeRead(capture: Capture?,
        effects: MetricObservationLocalEffects?, access: LocalSyncAccess?,
        block: suspend (suspend () -> NextChallengeReadSnapshot?) -> T): T {
        NextEvidenceReadSession.requireWritePhase()
        val seed = capture?.metricSourceReadState as? MetricSourceSeed
        if (seed == null || effects == null || access == null) return NextEvidenceReadSession.read(database) { block { null } }
        val actual = requireNotNull(capture)
        check(seed.kernel === this && seed.capture === actual && actual.metricEffects === effects) { "METRIC_LOCAL_PLAN_REQUIRED" }
        check(actual.context.formal && actual.context.observations && access == seed.rounds.access) { "SYNC_CHALLENGE_CONTEXT_CHANGED" }
        check(actual.context.environment == CountTrustValidationEnvironment.current()) { "COUNT_TRUST_ENVIRONMENT_CHANGED" }
        val owner = requireNotNull(runtime)
        // Capture BEFORE the journal query; a late DML must not become the handoff baseline.
        val before = displayBoundary()
        val lifetime = owner.maintenance(database) {
            val window = owner.unchangedChallengeWindow(actual.boundary) ?: return@maintenance null
            val current = control()
            check(current.dirty && current.generation == Math.addExact(actual.context.header.generation, window.totalChanges)) {
                "COUNT_TRUST_JOURNAL_INCOMPLETE"
            }
            check(CountTrustSeal.binding(actual.context.access, current.storage, actual.context.header.validator) ==
                actual.context.header.binding) { "CHALLENGE_FINAL_BINDING_CHANGED" }
            owner.captureReadToken(database)
        } ?: return NextEvidenceReadSession.read(database) { block { null } }
        check(displayBoundary() == before) { "CHALLENGE_FINAL_HANDOFF_CHANGED" }
        val proof = IssuedFinalChallengeProof(seed, owner, lifetime, before)
        return try {
            NextEvidenceReadSession.read(database) {
                proof.phase = NextEvidenceReadSession.current(database)
                proof.job = requireNotNull(currentCoroutineContext()[Job])
                val result = block { NextChallengeReadSnapshot.revalidated(database, access, proof) }
                check(proof.consumed) { "CHALLENGE_FINAL_PROOF_NOT_CONSUMED" }
                result
            }
        } finally {
            proof.active = false
            proof.phase = null
            proof.job = null
        }
    }

    /** Proof only, never credentials, publication or mutation. Existing logical reader entry
     * and final authority calls continue to run and supply their freshly obtained access.
     */
    internal sealed interface MetricSourceRead {
        suspend fun rounds(database: HabitDatabase, tokens: TokenManager, access: LocalSyncAccess): NextRoundWriteScope
        suspend fun summary(database: HabitDatabase, tokens: TokenManager, access: LocalCoreWriteAccess,
            habit: HabitEntity, today: LocalDate): CountSummary?
        suspend fun projection(database: HabitDatabase, tokens: TokenManager, access: LocalCoreWriteAccess,
            habit: HabitEntity, today: LocalDate, zone: ZoneId): CountDisplayProjection?
        suspend fun exactFact(database: HabitDatabase, tokens: TokenManager, access: LocalCoreWriteAccess,
            habit: HabitEntity, id: Long?, uuid: String?): CompletionEntity?
    }

    private inner class MetricSourceReadOwner(private val preparation: LocalPreparation,
        private val seed: MetricSourceSeed, private val context: Context,
        private val phase: NextEvidenceReadSession, private val job: Job) : MetricSourceRead {
        var active = true
        private suspend fun requireOwner(actualDatabase: HabitDatabase, actualTokens: TokenManager) {
            currentCoroutineContext().ensureActive()
            check(active && preparation.active && currentCoroutineContext()[LocalPreparation] === preparation &&
                currentCoroutineContext()[Job] === job && actualDatabase === database && actualTokens === tokens &&
                database.inTransaction() && preparation.capture === seed.capture && seed.kernel === this@CountTrustKernel &&
                preparation.capture.metricSourceReadState === seed) { "METRIC_SOURCE_READ_EXPIRED" }
            check(NextEvidenceReadSession.current(database) === phase) { "METRIC_SOURCE_READ_EXPIRED" }
        }
        override suspend fun rounds(database: HabitDatabase, tokens: TokenManager,
            access: LocalSyncAccess): NextRoundWriteScope {
            requireOwner(database, tokens)
            check(access == seed.rounds.access) { "SYNC_CHALLENGE_CONTEXT_CHANGED" }
            return NextRoundWriteScope(seed.rounds.access, seed.rounds.metadataJson)
        }
        override suspend fun summary(database: HabitDatabase, tokens: TokenManager, access: LocalCoreWriteAccess,
            habit: HabitEntity, today: LocalDate): CountSummary? {
            requireOwner(database, tokens)
            check(access == context.access) { "COUNT_SESSION_CHANGED" }
            return summaryInContext(context, habit, today)
        }
        override suspend fun projection(database: HabitDatabase, tokens: TokenManager, access: LocalCoreWriteAccess,
            habit: HabitEntity, today: LocalDate, zone: ZoneId): CountDisplayProjection? {
            requireOwner(database, tokens)
            check(access == context.access) { "COUNT_SESSION_CHANGED" }
            return projectionInContext(context, habit, today, zone, null)
        }
        override suspend fun exactFact(database: HabitDatabase, tokens: TokenManager, access: LocalCoreWriteAccess,
            habit: HabitEntity, id: Long?, uuid: String?): CompletionEntity? {
            requireOwner(database, tokens)
            check(access == context.access) { "COUNT_SESSION_CHANGED" }
            return exactFactInContext(context, habit, id, uuid)
        }
    }

    private suspend fun <T> readMetricSource(preparation: LocalPreparation,
        block: suspend (MetricSourceRead?) -> T): T {
        val seed = preparation.capture.metricSourceReadState as? MetricSourceSeed ?: return block(null)
        // No optional proof is granted to a delegated/late callback; its original guards
        // remain available. An issued handle itself rejects every escaped/foreign use.
        if (!preparation.active || preparation.caller !== currentCoroutineContext()[Job] ||
            !database.inTransaction() || runtime?.isRetired != false) return block(null)
        check(seed.kernel === this && seed.capture === preparation.capture) { "METRIC_SOURCE_CAPTURE_CHANGED" }
        val boundary = preparation.boundary ?: return block(null)
        val owner = requireNotNull(runtime)
        // This boundary belongs to LocalPreparation's Job, BEFORE EvidenceReadSession creates
        // its legal read child. Corrupt/expired native journal evidence propagates, never miss.
        if (owner.maintenance(database) { owner.hasPendingChanges(boundary) }) return block(null)
        return NextEvidenceReadSession.read(database) {
            check(displayBoundary() == seed.anchor.boundary) { "METRIC_SOURCE_READ_CHANGED" }
            val context = rebindDisplayContext(preparation.capture.context)
            val read = MetricSourceReadOwner(preparation, seed, context,
                NextEvidenceReadSession.current(database), requireNotNull(currentCoroutineContext()[Job]))
            try { context.index.withOperationReadScope { block(read) } }
            finally { read.active = false }
        }
    }

    /** A read-only preparation scope, not signing or login authority. Its own journal boundary
     * belongs to the callback Job; the outer finish retains its separate original boundary.
     */
    suspend fun <T> withLocalPreparation(capture: Capture?, block: suspend () -> T): T {
        if (capture == null || runtime?.isRetired != false) return block()
        val preparation = LocalPreparation(this, capture)
        return withContext(preparation) {
            preparation.caller = currentCoroutineContext()[Job]
            try {
                val owner = requireNotNull(runtime)
                owner.maintenance(database) {
                    val current = control()
                    if (!current.dirty && current.generation == capture.context.header.generation)
                        preparation.boundary = owner.captureChangeBoundary()
                }
                block()
            } finally { preparation.active = false; preparation.metricInput?.close(); preparation.metricInput = null }
        }
    }

    private suspend fun prepareMetricBatch(preparation: LocalPreparation, logs: List<MetricLogEntity>,
        metrics: Map<Long, MetricEntity>, habit: HabitEntity, fact: WidgetFactClaim): MetricInsertTicket? {
        val owner = runtime ?: return null
        val capture = preparation.capture
        val context = capture.context
        if (owner.isRetired || !preparation.active || preparation.caller !== currentCoroutineContext()[Job] ||
            !database.inTransaction() || !context.formal || !context.observations ||
            context.index.get("global_common") != JsonPrimitive(true) || habit.habitType != HabitType.COUNTING ||
            !context.eligible(habit) || logs.isEmpty()) return null
        // This intentionally small profile owns one complete original prompt submission.
        if (capture.metricProfileInvalidated) return null
        if (capture.metricPreparation != null) {
            // Multiple original batches are legal but outside this single-group profile.
            capture.metricPreparation = null; capture.metricProfileInvalidated = true
            return null
        }
        // This callback owns its own Job-bound read boundary. The final advance still
        // verifies the producer's complete outer journal after this scope has returned.
        val preparationBoundary = preparation.boundary ?: return null
        if (owner.maintenance(database) { owner.pendingChanges(preparationBoundary) }.isNotEmpty()) return null
        val plan = MetricObservationLocalEnvelopePlan.freezeBeforeInsert(logs, metrics, context.access,
            NextRequestSql.watermark(sql, "sync_outbox")) ?: return null
        return context.index.withReadScope {
            val reads = arrayListOf<MetricReadSelection>()
            fun retain(kind: String, table: String, parts: List<String>, where: String,
                args: Array<out Any?>): List<CountTrustRows.Row> {
                val rows = context.checked(CountTrustMembership.key(kind, *parts.toTypedArray()), table, where, args)
                reads += MetricReadSelection(table, where, args, rows)
                return rows
            }
            val nativeHabit = retain("habit.uuid", "habits", listOf(habit.uuid), "uuid=?", arrayOf(habit.uuid)).singleOrNull()
                ?: return@withReadScope null
            check(nativeHabit.long("id") == habit.id && database.habitDao().getHabitById(habit.id) == habit)
            check(fact.session() == context.access.session && fact.deviceId == context.access.capturedDeviceId &&
                fact.habitId == habit.id && fact.habitUuid == habit.uuid && fact.challengeContract == 1 &&
                fact.roundHead == context.head(habit.uuid)) { "FACT_PROMPT_STALE_ACCOUNT" }
            val source = requireNotNull(fact.completionUuid)
            check(retain("completion.uuid", "completions", listOf(source), "uuid=?", arrayOf(source)).single().long("habitId") == habit.id)
            retain("outbox.record", "sync_outbox", listOf("habit", habit.uuid),
                "recordType=? AND entityUuid=?", arrayOf("habit", habit.uuid))
            val observedInput = preparation.metricInput?.takeIf { it.hasCompletedSource(fact) }
            for (metric in metrics.values) {
                val parent = retain("metric.uuid", "metrics", listOf(metric.uuid), "uuid=?", arrayOf(metric.uuid)).singleOrNull()
                    ?: return@withReadScope null
                check(parent.long("id") == metric.id && database.metricDao().getMetricById(metric.id) == metric)
                val link = retain("link.pair", "habit_metric_links", listOf(habit.id.toString(), metric.id.toString()),
                    "habitId=? AND metricId=?", arrayOf(habit.id, metric.id)).single()
                check(link.text("habitUuid") == habit.uuid && link.text("metricUuid") == metric.uuid &&
                    link.bool("isActive") && link.bool("promptOnComplete")) { "METRIC_PROMPT_LINK_CHANGED" }
                retain("outbox.record", "sync_outbox", listOf("metric", metric.uuid),
                    "recordType=? AND entityUuid=?", arrayOf("metric", metric.uuid))
                retain("outbox.record", "sync_outbox", listOf("link", link.text("uuid")),
                    "recordType=? AND entityUuid=?", arrayOf("link", link.text("uuid")))
            }
            retain("cursor.id", "next_sync_state", listOf("1"), "id=?", arrayOf(1))
            retain("challenge.id", "next_challenge_state", listOf("1"), "id=?", arrayOf(1))
            val absentRanges = observedInput?.let { linkedSetOf<String>() }
            val absentOrigins = observedInput?.let { linkedSetOf<String>() }
            fun selectedAbsent(key: String, table: String, where: String, args: Array<out Any?>): Boolean {
                val rows = context.checked(key, table, where, args)
                if (rows.isEmpty()) absentRanges?.add(key)
                return rows.isEmpty()
            }
            for (log in logs) {
                if (!selectedAbsent(CountTrustMembership.key("metricLog.uuid", log.uuid), "metric_logs", "uuid=?", arrayOf(log.uuid)) ||
                    !selectedAbsent(CountTrustMembership.key("shadow.entity", "metric_observation", log.uuid), "sync_entity_state",
                        "entityType=? AND entityUuid=?", arrayOf("metric_observation", log.uuid)) ||
                    !selectedAbsent(CountTrustMembership.key("outbox.entity", "metric_observation", log.uuid), "sync_outbox",
                        "recordType=? AND entityUuid=?", arrayOf("metric_log", log.uuid)) ||
                    !selectedAbsent(CountTrustMembership.key("outbox.wire", "metric_observation", log.uuid), "sync_outbox",
                        "recordType=? AND wireEntityUuid=?", arrayOf("metric_log", log.uuid)) ||
                    context.members(CountTrustMembership.key("origin.entity", "metric_observation", log.uuid)).isNotEmpty())
                    return@withReadScope null // Same short-circuit order and original nullable fallback.
                absentOrigins?.add(log.uuid)
            }
            val input = observedInput?.seal(logs, metrics, fact, requireNotNull(absentRanges), requireNotNull(absentOrigins))
            val ticket = MetricInsertTicket()
            capture.metricPreparation = MetricPreparation(ticket, if (input == null) plan else plan.withInput(input),
                java.util.Collections.unmodifiableList(logs.map { it.uuid }), java.util.Collections.unmodifiableList(reads), input)
            ticket
        }
    }

    /** Independent typed observation: no fabricated COUNT source, habit or link authority.
     * The original producer still owns plain/formal permission and final complete proofs. */
    private suspend fun prepareIndependentMetricBatch(preparation: LocalPreparation,
        logs: List<MetricLogEntity>, metrics: Map<Long, MetricEntity>): MetricInsertTicket? {
        val owner = runtime ?: return null
        val capture = preparation.capture; val context = capture.context
        if (owner.isRetired || !preparation.active || preparation.caller !== currentCoroutineContext()[Job] ||
            !database.inTransaction() || !context.observations || !context.metricRanges ||
            context.index.get("global_common") != JsonPrimitive(true) ||
            context.index.get(MetricCommandAbsenceEvidence.KEY) != JsonPrimitive(true) || logs.isEmpty()) return null
        if (capture.metricProfileInvalidated) return null
        if (capture.metricPreparation != null) {
            capture.metricPreparation = null; capture.metricProfileInvalidated = true; return null
        }
        val boundary = preparation.boundary ?: return null
        if (owner.maintenance(database) { owner.hasPendingChanges(boundary) }) return null
        val plan = MetricObservationLocalEnvelopePlan.freezeBeforeInsert(logs, metrics, context.access,
            NextRequestSql.watermark(sql, "sync_outbox"), formal = context.formal) ?: return null
        return context.index.withReadScope {
            val reads = arrayListOf<MetricReadSelection>()
            fun retain(kind: String, table: String, parts: List<String>, where: String, args: Array<out Any?>): List<CountTrustRows.Row> {
                val rows = context.checked(CountTrustMembership.key(kind, *parts.toTypedArray()), table, where, args)
                reads += MetricReadSelection(table, where, args, rows); return rows
            }
            for (metric in metrics.values) {
                val parent = retain("metric.uuid", "metrics", listOf(metric.uuid), "uuid=?", arrayOf(metric.uuid)).singleOrNull()
                    ?: return@withReadScope null
                check(parent.long("id") == metric.id && database.metricDao().getMetricById(metric.id) == metric)
                if (MetricTrustDirectory.Reader(context.index::get).manifest(parent) == null) return@withReadScope null
                retain("outbox.record", "sync_outbox", listOf("metric", metric.uuid),
                    "recordType=? AND entityUuid=?", arrayOf("metric", metric.uuid))
            }
            retain("cursor.id", "next_sync_state", listOf("1"), "id=?", arrayOf(1))
            retain("challenge.id", "next_challenge_state", listOf("1"), "id=?", arrayOf(1))
            for (log in logs) {
                if (context.checked(CountTrustMembership.key("birth.entity", "metric_observation", log.uuid), "next_challenge_births",
                        "entityType=? AND entityUuid=?", arrayOf("metric_observation", log.uuid)).isNotEmpty() ||
                    context.checked(CountTrustMembership.key("metricSubmission.entity", "metric_observation", log.uuid), "local_fact_submissions",
                        "entityType=? AND entityUuid=?", arrayOf("metric_observation", log.uuid)).isNotEmpty() ||
                    context.checked(CountTrustMembership.key("metricLog.uuid", log.uuid), "metric_logs", "uuid=?", arrayOf(log.uuid)).isNotEmpty() ||
                    context.checked(CountTrustMembership.key("shadow.entity", "metric_observation", log.uuid), "sync_entity_state",
                        "entityType=? AND entityUuid=?", arrayOf("metric_observation", log.uuid)).isNotEmpty() ||
                    context.checked(CountTrustMembership.key("outbox.entity", "metric_observation", log.uuid), "sync_outbox",
                        "recordType=? AND entityUuid=?", arrayOf("metric_log", log.uuid)).isNotEmpty() ||
                    context.checked(CountTrustMembership.key("outbox.wire", "metric_observation", log.uuid), "sync_outbox",
                        "recordType=? AND wireEntityUuid=?", arrayOf("metric_log", log.uuid)).isNotEmpty() ||
                    context.members(CountTrustMembership.key("origin.entity", "metric_observation", log.uuid)).isNotEmpty()) return@withReadScope null
            }
            val ticket = MetricInsertTicket()
            capture.metricPreparation = MetricPreparation(ticket, plan,
                java.util.Collections.unmodifiableList(logs.map { it.uuid }), java.util.Collections.unmodifiableList(reads))
            capture.independentMetricPreparation = true
            ticket
        }
    }

    /** Binds trigger allocations before the original origin loop; never reads expected values
     * from journal NEW cells. Original source/reuse/authentication audits remain unchanged.
     */
    suspend fun bindPreparedMetricSources(capture: Capture?): MetricObservationLocalEffects? {
        val preparation = capture?.metricPreparation ?: return null
        check(database.inTransaction() && capture.metricEffects == null)
        val ids = requireNotNull(preparation.insertedIds) { "METRIC_LOCAL_BATCH_NOT_BOUND" }
        val native = capture.context.rows
        val sources = preparation.uuids.map { uuid ->
            val raw = native.read("sync_outbox", "recordType=? AND entityUuid=?", arrayOf("metric_log", uuid)).single()
            requireNotNull(database.syncOutboxDao().getById(raw.long("id")))
        }
        val bound = preparation.plan.bind(ids, sources)
        val effects = MetricObservationLocalEffects.from(bound, native)
        effects.verifyPreparedContents(native)
        preparation.reads.forEach { it.verify(native) }
        capture.metricEffects = effects
        return effects
    }

    private suspend fun prepareCountSource(preparation: LocalPreparation, habit: HabitEntity,
        fact: com.dayforge.data.local.entity.CompletionEntity): CountSourceTicket? {
        val owner = runtime ?: return null
        val capture = preparation.capture
        val context = capture.context
        if (owner.isRetired || !preparation.active || preparation.caller !== currentCoroutineContext()[Job] ||
            !database.inTransaction() || habit.habitType != HabitType.COUNTING || !context.formal || !context.eligible(habit) || capture.countSourceInvalidated) return null
        if (capture.countSourcePreparation != null) {
            capture.countSourcePreparation = null; capture.countSourceInvalidated = true
            return null // Several legal writes in one callback retain the complete original path.
        }
        val boundary = preparation.boundary ?: return null
        if (owner.maintenance(database) { owner.hasPendingChanges(boundary) }) return null
        val plan = CountLocalSourcePlan.freeze(habit, fact, NextRequestSql.watermark(sql, "sync_outbox")) ?: return null
        // The original producer still validates every source. Optional UUID eligibility needs
        // only bounded identities; never read arbitrary existing fact bodies to decide a miss.
        if (sql.query("SELECT id FROM main.completions WHERE uuid=? LIMIT 1", arrayOf(fact.uuid)).use { it.moveToFirst() }) return null
        val ticket = CountSourceTicket()
        capture.countSourcePreparation = CountSourcePreparation(ticket, plan)
        return ticket
    }

    /** Original complete before maps have already passed sources(). This proof replaces only
     * subsequent source scans, not any domain validator. Always read the ORIGINAL outer boundary.
     */
    suspend fun prepareSourceDelta(capture: Capture?, before: Map<String, Map<Long, String>>,
        watermarks: Map<String, Long>, metrics: MetricObservationLocalEffects?): NextSourceDelta? {
        if (capture == null || runtime?.isRetired != false) return null
        val prepared = if (metrics == null) capture.countSourcePreparation ?: return null else null
        val insertedId = if (prepared != null) prepared.insertedId ?: return null else null
        val independentMetricSources = metrics?.sources()
        val expectedCount = independentMetricSources?.size ?: 1
        val owner = requireNotNull(runtime)
        val window = owner.maintenance(database) {
            val value = owner.sourceWindow(capture.boundary, expectedCount) ?: return@maintenance null
            val current = control()
            check(current.dirty && current.generation == Math.addExact(capture.context.header.generation, value.totalChanges)) {
                "COUNT_TRUST_JOURNAL_INCOMPLETE"
            }
            value
        } ?: return null
        // The count formula is valid only AFTER the complete metadata confirms INSERT-only
        // ordinary sources and no timer/old-row changes. Unsupported shapes use original sources.
        if (before.getValue("sync_outbox").size.toLong() + expectedCount > 10000L)
            rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
        val expected = if (independentMetricSources != null) {
            check(capture.metricEffects === metrics) { "METRIC_LOCAL_PLAN_REQUIRED" }
            independentMetricSources
        } else {
            val plan = requireNotNull(prepared).plan
            val ids = sql.query("SELECT id FROM main.sync_outbox WHERE recordType=? AND entityUuid=? ORDER BY id LIMIT 2",
                arrayOf("completion", plan.uuid)).use { cursor -> buildList {
                while (cursor.moveToNext()) {
                    if (cursor.getType(0) != Cursor.FIELD_TYPE_INTEGER) return null
                    add(cursor.getLong(0))
                }
            } }
            val queueId = ids.singleOrNull() ?: return null
            if (queueId <= 0L || queueId <= watermarks.getValue("sync_outbox")) return null
            // This is the unchanged original source byte/type/range preflight, BEFORE source
            // DAO/native materialization. Fact matching itself also returns only SQL scalars.
            if (NextRequestSql.rowHash(sql, "sync_outbox", "id=?", arrayOf(queueId)) == null) return null
            val countId = requireNotNull(insertedId)
            val fact = plan.verifiedStoredFact(sql, countId) ?: return null
            val source = database.syncOutboxDao().getById(queueId)
            listOf(plan.bind(countId, fact, source) ?: return null)
        }
        return owner.maintenance(database) {
            NextSourceDelta.prepare(capture, before, watermarks, expected, window, owner, sql, capture.context.rows)
        }
    }

    /** The source-only scalar window never loads unrelated OLD/NEW cells. Other domain
     * effects and the original full metric 3N journal are still checked at their original footer.
     */
    suspend fun verifySourceDelta(capture: Capture, delta: NextSourceDelta): Boolean {
        delta.requireCapture(capture)
        val owner = requireNotNull(runtime)
        return owner.maintenance(database) {
            val window = owner.sourceWindow(capture.boundary, delta.sourceCount) ?: return@maintenance false
            val current = control()
            check(current.dirty && current.generation == Math.addExact(capture.context.header.generation, window.totalChanges)) {
                "COUNT_TRUST_JOURNAL_INCOMPLETE"
            }
            delta.verifyCurrent(sql, capture.context.rows)
            delta.verifyWindow(owner, window)
        }
    }

    private suspend fun calculatePreparedRate(preparation: LocalPreparation, habit: HabitEntity): RateCalculation? {
        val owner = runtime ?: return null
        if (owner.isRetired || !preparation.active || preparation.caller !== currentCoroutineContext()[Job] ||
            !preparation.capture.context.eligible(habit)) return null
        val boundary = preparation.boundary ?: return null
        val changes = owner.maintenance(database) { owner.pendingChanges(boundary) }
        // All retained dates were parsed by the original qualification. Newly touched rows
        // must preserve the old parser's rejection even when outside the rate's small window.
        for (change in changes) if (change.table == "completions") {
            val fields = CountTrustJournal.COLUMNS.getValue("completions")
            val cells = change.new ?: continue
            val id = cells[fields.indexOf("habitId")]
            if (id.type != Cursor.FIELD_TYPE_INTEGER) return null
            if (id.value == habit.id) {
                val date = cells[fields.indexOf("recordedLocalDate")]
                if (date.type != Cursor.FIELD_TYPE_STRING) return null
                LocalDate.parse(date.value as String)
            }
        }
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val completed = rateWindow(habit, now, zone).filter { date ->
            nativeCompletionExists(habit.id, date)
        }
        return RateCalculation(ActivityRateCalculator.calculate(habit.schedule, habit.createdAt, completed, now, zone), now, zone)
            .also { preparation.capture.rates[habit.uuid] = it }
    }

    private fun nativeCompletionExists(habitId: Long, date: LocalDate): Boolean = sql.query(
        "SELECT EXISTS(SELECT 1 FROM completions WHERE habitId=? AND recordedLocalDate=?)", arrayOf<Any>(habitId, date.toString())).use {
        check(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_INTEGER && it.getLong(0) in 0..1)
        (it.getLong(0) == 1L).also { _ -> check(!it.moveToNext()) }
    }

    private fun rateWindow(habit: HabitEntity, now: Long, zone: ZoneId): List<LocalDate> =
        ActivityRateCalculator.generateWindowCheckInDays(habit.schedule, habit.createdAt, now, zone)
            .map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }

    /** Original producer/send/ACK checks run outside the maintenance capability. Unknown effects
     * deliberately retain dirty=1; they are never signed just because the callback returned.
     */
    /** TIMER uses its own frozen native command/ACK description. A mismatch is a whole
     * maintenance miss, preserving the dirty marker; original business guards still run.
     * Raw events are compared before coalescing so same-value/repeated writes cannot vanish.
     */
    internal suspend fun finishTimer(capture: Capture?, proof: TimerTrustUpdate?) {
        try { advanceTimer(capture, proof) }
        catch (error: com.dayforge.data.history.HistoryBlockCodec.Rejected) {
            if (error.code != com.dayforge.data.history.HistoryBlockCodec.Code.BUDGET) throw error
            capture?.timerDiagnostic?.mark(TimerTrustMaintenanceDiagnostic.Reason.BLOCK_BUDGET)
            lastQualificationFailure = "TIMER_ADVANCE_BUDGET:" + error.message
        } catch (error: IllegalArgumentException) {
            if (error.message != "COUNT_TRUST_VALUE_BUDGET") throw error
            capture?.timerDiagnostic?.mark(TimerTrustMaintenanceDiagnostic.Reason.VALUE_BUDGET)
            lastQualificationFailure = "TIMER_ADVANCE_VALUE_BUDGET"
        }
    }

    private suspend fun advanceTimer(capture: Capture?, proof: TimerTrustUpdate?) {
        if (capture == null || proof == null) return // Preparation retains its more precise diagnostic.
        if (!capture.context.timers) {
            capture.timerDiagnostic?.mark(TimerTrustMaintenanceDiagnostic.Reason.NO_TIMER_COVERAGE)
            return
        }
        check(proof.context === capture.context) { "TIMER_TRUST_CAPTURE_CHANGED" }
        val owner = requireNotNull(runtime)
        val context = capture.context
        check(context.environment == CountTrustValidationEnvironment.current()) { "COUNT_TRUST_ENVIRONMENT_CHANGED" }
        val changes = owner.maintenance(database) { owner.pendingChanges(capture.boundary) }
        val native = context.rows
        fun row(change: CountTrustJournal.Change, cells: List<CountTrustJournal.Cell>?): CountTrustRows.Row? = cells?.let {
            val table = native.table(change.table)
            val fields = CountTrustJournal.COLUMNS.getValue(change.table)
            CountTrustRows.Row(table, table.columns.map { name -> it[fields.indexOf(name).also { index -> check(index >= 0) }] })
        }
        val supported = setOf("timelogs", "timer_segments", "timelog_day_allocations", "timer_command_outbox",
            "next_request_origins", "next_transmissions", "next_acceptances", "next_challenge_rounds",
            "next_challenge_births", "next_challenge_state")
        if (changes.any { it.table !in supported }) {
            capture.timerDiagnostic?.mark(TimerTrustMaintenanceDiagnostic.Reason.UNSUPPORTED_TABLE,
                changes.first { it.table !in supported }.table)
            return
        }
        if (changes.size != proof.effects.size) {
            capture.timerDiagnostic?.mark(TimerTrustMaintenanceDiagnostic.Reason.EVENT_COUNT,
                actual = changes.size, expected = proof.effects.size)
            return
        }
        val expected = proof.effects.toMutableList()
        val events = changes.map { change -> Triple(change.table, row(change, change.old), row(change, change.new)) }
        for ((table, before, after) in events) {
            val found = expected.indexOfFirst { it.table == table && it.before?.value == before?.value && it.after?.value == after?.value }
            if (found < 0) {
                capture.timerDiagnostic?.mark(TimerTrustMaintenanceDiagnostic.Reason.EFFECT_TABLE, table)
                return
            }
            expected.removeAt(found)
        }
        // Every current-session validator remains outside the maintenance capability. It
        // cannot sign and cannot move this capture to another transaction/Job.
        proof.verifyFinal(database)
        if (changes.isEmpty()) {
            capture.timerDiagnostic?.mark(TimerTrustMaintenanceDiagnostic.Reason.EMPTY_EFFECTS)
            return
        }
        owner.maintenance(database) {
            check(owner.pendingChanges(capture.boundary) == changes) { "TIMER_TRUST_FINAL_WRITES_CHANGED" }
            context.index.withReadScope {
                val final = linkedMapOf<String, Pair<CountTrustRows.Row?, CountTrustRows.Row?>>()
                for ((_, old, fresh) in events) {
                    if (old != null && fresh != null) check(old.name == fresh.name) { "COUNT_TRUST_PRIMARY_CHANGED" }
                    val name = (old ?: requireNotNull(fresh)).name
                    val expectedOld = if (name in final) final.getValue(name).second?.value else context.index.get(name)
                    check(expectedOld == old?.value) { "COUNT_TRUST_OLD_CONTENT_CHANGED" }
                    final[name] = (if (name in final) final.getValue(name).first else old) to fresh
                }
                val control = control()
                check(control.dirty && control.generation == Math.addExact(context.header.generation, changes.size.toLong())) {
                    "COUNT_TRUST_JOURNAL_INCOMPLETE"
                }
                val edits = linkedMapOf<String, JsonElement?>()
                proof.metadata?.let { metadata ->
                    check(context.formal)
                    // New unrelated metadata is retained in full. Changing a covered display
                    // partition needs its own issuance; no existing block is silently rebound.
                    val retained = Json.decodeFromJsonElement(ChallengeMetadata.serializer(),
                        requireNotNull(context.index.get(CountTrustRoundEvidence.METADATA)))
                    for (checkpoint in retained.checkpoints) {
                        val key = CountTrustRoundEvidence.headKey(checkpoint.head.activityUuid)
                        if (context.index.get(key) != null && metadata.checkpoints.singleOrNull {
                            it.head.activityUuid == checkpoint.head.activityUuid } != checkpoint) return@withReadScope
                    }
                    edits[CountTrustRoundEvidence.METADATA] = Json.encodeToJsonElement(ChallengeMetadata.serializer(), metadata)
                }
                val memberships = linkedMapOf<String, MutableSet<String>>()
                suspend fun revise(row: CountTrustRows.Row, add: Boolean) {
                    val keys = CountTrustMembership.memberships(row) + TimerTrustEvidence.memberships(row) + MetricCommandAbsenceEvidence.memberships(row)
                    keys.forEach { (key, member) ->
                        val set = memberships.getOrPut(key) { context.members(key).toSortedSet() }
                        if (add) check(set.add(member)) else check(set.remove(member))
                    }
                }
                final.forEach { (name, pair) ->
                    pair.first?.let { revise(it, false) }; pair.second?.let { revise(it, true) }
                    edits[name] = pair.second?.value
                    val identity = pair.first ?: requireNotNull(pair.second)
                    check(native.byIdentity(identity.table.name, identity.identity)?.value == pair.second?.value) {
                        "COUNT_TRUST_FINAL_CONTENT_CHANGED"
                    }
                }
                memberships.forEach { (key, values) -> edits[key] = JsonArray(values.map(::JsonPrimitive)) }
                check(proof.displayEdits.keys.none { it in edits || it.startsWith("row:") }) { "TIMER_TRUST_DISPLAY_OVERLAP" }
                edits.putAll(proof.displayEdits)
                edits[MetricCommandAbsenceEvidence.KEY] = MetricCommandAbsenceEvidence.after(context.index.get(MetricCommandAbsenceEvidence.KEY), final.values.toList(), native)
                edits.putAll(metricDirectoryEdits(context, final.values.toList()))
                context.index.putAll(edits)
                check(context.environment == CountTrustValidationEnvironment.current()) { "COUNT_TRUST_ENVIRONMENT_CHANGED" }
                check(tokens.localCoreWriteAccess() == context.access) { "COUNT_SESSION_CHANGED" }
                CountTrustSeal.store(sql, CountTrustSeal().prepare(context.header.binding, context.header.schemaHash,
                    context.index.root, Math.addExact(context.header.revision, 1), control.generation))
                capture.timerDiagnostic?.mark(TimerTrustMaintenanceDiagnostic.Reason.SEALED_BEFORE_END)
            }
        }
    }

    /** Full original ONCE guards remain at their existing boundary. This only maintains
     * exact predicted raw events after those guards; extra or repeated events leave dirty.
     */
    internal suspend fun finishOnce(capture: Capture?, proof: OnceTrustUpdate?) {
        if (capture == null || proof == null || !capture.context.once) return
        check(proof.context === capture.context) { "ONCE_TRUST_CAPTURE_CHANGED" }
        val owner = requireNotNull(runtime)
        val context = capture.context
        check(context.environment == CountTrustValidationEnvironment.current()) { "COUNT_TRUST_ENVIRONMENT_CHANGED" }
        val changes = owner.maintenance(database) { owner.pendingChanges(capture.boundary) }
        val native = context.rows
        fun row(change: CountTrustJournal.Change, cells: List<CountTrustJournal.Cell>?): CountTrustRows.Row? = cells?.let {
            val layout = native.table(change.table)
            val fields = CountTrustJournal.COLUMNS.getValue(change.table)
            CountTrustRows.Row(layout, layout.columns.map { name -> it[fields.indexOf(name).also { index -> check(index >= 0) }] })
        }
        val supported = setOf("habits", "completions", "sync_outbox", "sync_entity_state", "sync_control",
            "local_fact_submissions", "one_time_transmissions", "completion_metric_prompts", "metric_logs",
            "next_request_origins", "next_transmissions", "next_acceptances", "next_challenge_state", "next_sync_state")
        if (changes.any { it.table !in supported } || changes.size != proof.effects.size) return
        val events = changes.map { Triple(it.table, row(it, it.old), row(it, it.new)) }
        val expected = proof.effects.toMutableList()
        for ((table, before, after) in events) {
            val found = expected.indexOfFirst { it.table == table && it.before?.value == before?.value && it.after?.value == after?.value }
            if (found < 0) return
            expected.removeAt(found)
            if (table == "completions" && listOfNotNull(before, after).any { it.nullableText("oneTimeAction") == null }) return
            if (table == "habits" && listOfNotNull(before, after).any { it.nullableText("completionPolicy") != "one_and_done" }) return
        }
        val changedSubjects = events.flatMap { (table, before, after) -> listOfNotNull(before, after).mapNotNull { value ->
            when (table) {
                "habits" -> value.text("uuid")
                "completions" -> value.nullableText("habitUuid")
                "sync_outbox" -> if (value.text("recordType") == "one_time_completion") value.nullableText("referenceUuid") else null
                else -> null
            }
        } }.toSet()
        if (!proof.affectedHabitUuids.containsAll(changedSubjects)) return
        proof.verifyFinal(database)
        val audits = proof.affectedHabitUuids.sorted().associateWith { uuid ->
            val habit = requireNotNull(database.habitDao().getHabitByUuid(uuid)) { "ONCE_TRUST_ACTIVITY_CHANGED" }
            check(context.onceEligible(habit)) { "ONCE_TRUST_ACTIVITY_NOT_QUALIFIED" }
            OnceTrustEvidence.audit(database, uuid, context.access.session)
        }
        if (changes.isEmpty()) return
        // ONCE's subject audit cannot certify the common merger's cross-domain state.
        // Retain that full read-only validator before maintenance when its inputs changed;
        // this intentionally pays global H work so a following metric ACK may stay warm.
        val commonHealthy = if (events.any { it.first in setOf("completions", "metric_logs", "sync_entity_state") }) {
            try { CountTrustAck.globalCommonHealth(database) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { false }
        } else null
        try {
            owner.maintenance(database) {
                check(owner.pendingChanges(capture.boundary) == changes) { "ONCE_TRUST_FINAL_WRITES_CHANGED" }
                context.index.withReadScope {
                    val final = linkedMapOf<String, Pair<CountTrustRows.Row?, CountTrustRows.Row?>>()
                    for ((_, old, fresh) in events) {
                        if (old != null && fresh != null) check(old.name == fresh.name) { "COUNT_TRUST_PRIMARY_CHANGED" }
                        val name = (old ?: requireNotNull(fresh)).name
                        val expectedOld = if (name in final) final.getValue(name).second?.value else context.index.get(name)
                        check(expectedOld == old?.value) { "COUNT_TRUST_OLD_CONTENT_CHANGED" }
                        final[name] = (if (name in final) final.getValue(name).first else old) to fresh
                    }
                    val control = control()
                    check(control.dirty && control.generation == Math.addExact(context.header.generation, changes.size.toLong())) {
                        "COUNT_TRUST_JOURNAL_INCOMPLETE"
                    }
                    val edits = linkedMapOf<String, JsonElement?>()
                    proof.metadata?.let { metadata ->
                        check(context.formal)
                        val retained = Json.decodeFromJsonElement(ChallengeMetadata.serializer(),
                            requireNotNull(context.index.get(CountTrustRoundEvidence.METADATA)))
                        // ONCE has no recurring birth/head; legitimate extra metadata remains
                        // on the original merger/full-dirty path, never borrowed from COUNT.
                        if (metadata != retained) return@withReadScope
                    }
                    val memberships = linkedMapOf<String, MutableSet<String>>()
                    suspend fun revise(value: CountTrustRows.Row, add: Boolean) {
                        val keys = CountTrustMembership.memberships(value) + OnceTrustEvidence.memberships(value) + MetricCommandAbsenceEvidence.memberships(value)
                        keys.forEach { (key, member) ->
                            val set = memberships.getOrPut(key) { context.members(key).toSortedSet() }
                            if (add) check(set.add(member)) else check(set.remove(member))
                        }
                    }
                    final.forEach { (name, pair) ->
                        pair.first?.let { revise(it, false) }; pair.second?.let { revise(it, true) }
                        edits[name] = pair.second?.value
                        val identity = pair.first ?: requireNotNull(pair.second)
                        check(native.byIdentity(identity.table.name, identity.identity)?.value == pair.second?.value) {
                            "COUNT_TRUST_FINAL_CONTENT_CHANGED"
                        }
                    }
                    memberships.forEach { (key, values) -> edits[key] = JsonArray(values.map(::JsonPrimitive)) }
                    audits.forEach { (uuid, audit) -> edits[OnceTrustEvidence.projectionKey(uuid)] = OnceTrustEvidence.encode(audit.snapshot) }
                    commonHealthy?.let { edits["global_common"] = JsonPrimitive(it) }
                    val shadowDelta = final.values.sumOf { (old, fresh) ->
                        (if (fresh?.table?.name == "sync_entity_state" && fresh.text("entityType") == "activity_event") 1 else 0) -
                            (if (old?.table?.name == "sync_entity_state" && old.text("entityType") == "activity_event") 1 else 0)
                    }
                    if (shadowDelta != 0) edits["activity_shadow_count"] = JsonPrimitive(Math.addExact(
                        requireNotNull(context.index.get("activity_shadow_count")).jsonPrimitive.int, shadowDelta))
                    edits[MetricCommandAbsenceEvidence.KEY] = MetricCommandAbsenceEvidence.after(context.index.get(MetricCommandAbsenceEvidence.KEY), final.values.toList(), native)
                    edits.putAll(metricDirectoryEdits(context, final.values.toList()))
                    context.index.putAll(edits)
                    check(context.environment == CountTrustValidationEnvironment.current()) { "COUNT_TRUST_ENVIRONMENT_CHANGED" }
                    check(tokens.localCoreWriteAccess() == context.access) { "COUNT_SESSION_CHANGED" }
                    CountTrustSeal.store(sql, CountTrustSeal().prepare(context.header.binding, context.header.schemaHash,
                        context.index.root, Math.addExact(context.header.revision, 1), control.generation))
                }
            }
        } catch (error: com.dayforge.data.history.HistoryBlockCodec.Rejected) {
            if (error.code != com.dayforge.data.history.HistoryBlockCodec.Code.BUDGET) throw error
            lastQualificationFailure = "ONCE_ADVANCE_BUDGET:" + error.message
        } catch (error: IllegalArgumentException) {
            if (error.message != "COUNT_TRUST_VALUE_BUDGET") throw error
            lastQualificationFailure = "ONCE_ADVANCE_VALUE_BUDGET"
        }
    }

    /** Runs inside the ORIGINAL owner's final index scope, after that owner verified the
     * complete journal/effects. It returns pure derived edits; it never reads/consumes another
     * journal or seals a second root. Definitions remain unsupported/dirty in those owners. */
    private fun metricDirectoryEdits(context: Context,
        changes: List<Pair<CountTrustRows.Row?, CountTrustRows.Row?>>): Map<String, JsonElement?> {
        val ids = changes.flatMap { listOfNotNull(it.first, it.second) }
            .filter { it.table.name == "metric_logs" }.map { it.long("metricId") }.distinct()
        if (ids.isEmpty()) return emptyMap()
        val metrics = ids.map { id -> context.checked(CountTrustMembership.key("metric.id", id.toString()),
            "metrics", "id=?", arrayOf(id)).single() }
        return MetricTrustDirectory.update(context.index::get, changes, metrics)
    }

    internal suspend fun freezeTimerOrigin(capture: Capture?, origin: com.dayforge.data.local.entity.NextRequestOriginEntity) {
        capture?.timerPreparation?.freezeOrigin(origin)
    }

    suspend fun finish(capture: Capture?, mode: Mutation, expected: List<CountTrustAck.Effect>? = null,
        roundAck: CountTrustRoundAck.Prepared? = null, localObservations: MetricObservationLocalEffects? = null) {
        try { advance(capture, mode, expected, roundAck, localObservations) }
        catch (error: com.dayforge.data.history.HistoryBlockCodec.Rejected) {
            if (error.code != com.dayforge.data.history.HistoryBlockCodec.Code.BUDGET) throw error
            // Resource limits belong to the optional proof representation, not the product.
            // The old root remains dirty; original validated business writes may still commit.
            lastQualificationFailure = "ADVANCE_BUDGET:" + error.message
        }
        catch (error: IllegalArgumentException) {
            if (error.message != "COUNT_TRUST_VALUE_BUDGET") throw error
            lastQualificationFailure = "ADVANCE_VALUE_BUDGET"
        }
    }

    private suspend fun advance(capture: Capture?, mode: Mutation, expected: List<CountTrustAck.Effect>?,
        roundAck: CountTrustRoundAck.Prepared?, localObservations: MetricObservationLocalEffects?) {
        if (capture == null) return
        check(capture.context.environment == CountTrustValidationEnvironment.current()) { "COUNT_TRUST_ENVIRONMENT_CHANGED" }
        val owner = requireNotNull(runtime)
        val changes = owner.maintenance(database) { owner.pendingChanges(capture.boundary) }
        if (changes.isEmpty()) return
        val changedHabits = linkedSetOf<String>()
        for (change in changes) if (change.table in setOf("completions", "count_days", "habits")) {
            val fields = CountTrustJournal.COLUMNS.getValue(change.table)
            for (cells in listOfNotNull(change.old, change.new)) {
                val cell = cells[fields.indexOf(if (change.table == "habits") "uuid" else "habitUuid")]
                if (cell.type == Cursor.FIELD_TYPE_STRING) changedHabits.add(cell.value as String)
            }
        }
        // Room may switch Job inside its DAO adapter. Freeze DTOs before the non-delegable
        // maintenance capability; callback/child Jobs never inherit proof-write authority.
        val habits = changedHabits.associateWith { database.habitDao().getHabitByUuid(it) }
        owner.maintenance(database) {
            val context = capture.context
            context.index.withReadScope {
                val native = context.rows
                if (mode == Mutation.LOCAL_OBSERVATIONS) {
                    check((context.formal || capture.independentMetricPreparation) && context.observations && context.index.get("global_common") == JsonPrimitive(true))
                    check(localObservations != null && capture.metricEffects === localObservations) { "METRIC_LOCAL_PLAN_REQUIRED" }
                    localObservations.verifyActual(changes, native)
                    requireNotNull(capture.metricPreparation).reads.forEach { it.verify(native) }
                    localObservations.verifyFinalContents(native)
                } else check(localObservations == null)
                if (mode == Mutation.ACK) {
                    val effects = expected ?: return@withReadScope
                    check(changes.size == effects.size) { "COUNT_ACK_EXTRA_EFFECT" }
                    changes.zip(effects).forEach { (change, effect) ->
                        val fields = CountTrustJournal.COLUMNS.getValue(change.table)
                        fun value(cells: List<CountTrustJournal.Cell>?): JsonObject? = cells?.let {
                            val table = native.table(change.table)
                            CountTrustRows.Row(table, table.columns.map { name -> it[fields.indexOf(name)] }).value
                        }
                        check(change.table == effect.table && value(change.old) == effect.before?.value &&
                            value(change.new) == effect.after?.value) { "COUNT_ACK_UNEXPECTED_EFFECT" }
                    }
                }
                // All 36 business tables invalidate permanently, but unrelated recovery/archive
                // payloads are not duplicated into this count directory. An unhandled table cannot
                // be promoted merely because an old fingerprint was never retained for it.
                if (changes.any { it.table !in mutationTables(mode) }) return@withReadScope
                val changesByName = linkedMapOf<String, Pair<CountTrustRows.Row?, CountTrustRows.Row?>>()
                val checkUpdatedHabitIds = hashSetOf<Long>()
                for (change in changes) {
                    val table = native.table(change.table)
                    val fields = CountTrustJournal.COLUMNS.getValue(change.table)
                    fun native(values: List<CountTrustJournal.Cell>) = CountTrustRows.Row(table,
                        table.columns.map { values[fields.indexOf(it).also { index -> check(index >= 0) }] })
                    val old = change.old?.let(::native)
                    val new = change.new?.let(::native)
                    if (old != null && new != null) check(old.name == new.name) { "COUNT_TRUST_PRIMARY_CHANGED" }
                    val name = (old ?: requireNotNull(new)).name
                    val expected = changesByName[name]?.second?.value ?: if (name in changesByName) null else context.index.get(name)
                    check(expected == old?.value) { "COUNT_TRUST_OLD_CONTENT_CHANGED" }
                    if (mode == Mutation.LOCAL_COUNT && table.name == "completions" &&
                        (old != null && new != null || name in changesByName)) {
                        old?.let { checkUpdatedHabitIds.add(it.long("habitId")) }
                        new?.let { checkUpdatedHabitIds.add(it.long("habitId")) }
                    }
                    val first = if (name in changesByName) changesByName.getValue(name).first else old
                    changesByName[name] = first to new
                }
                // Original OLD/type/primary continuity above remains mandatory. Ordinary
                // CHECK only qualifies INSERT/DELETE once per fact identity. Unsupported
                // UPDATE/repeated events retain the trigger's dirty marker for the WHOLE
                // advancement, including mixed COUNT work; no partial root is sealed.
                if (checkUpdatedHabitIds.isNotEmpty() && habits.values.any {
                        it != null && it.habitType == HabitType.CHECK_IN && it.id in checkUpdatedHabitIds
                    }) return@withReadScope
                val habitEdits = changesByName.values.filter { (old, new) -> (old ?: requireNotNull(new)).table.name == "habits" }
                val activations = linkedSetOf<String>()
                for ((old, new) in habitEdits) {
                    if (mode != Mutation.LOCAL_COUNT || old == null || new == null) return@withReadScope
                    val habit = habits[new.text("uuid")] ?: return@withReadScope
                    val rate = capture.rates[habit.uuid] ?: return@withReadScope
                    val factChanges = changesByName.values.filter { (before, after) ->
                        listOfNotNull(before, after).any { it.table.name == "completions" &&
                            it.long("habitId") == habit.id && it.nullableText("habitUuid") == habit.uuid }
                    }
                    if (factChanges.isEmpty() || !context.eligible(habit) || habit.habitType !in setOf(HabitType.COUNTING, HabitType.CHECK_IN) ||
                        habit.appearance == null || habit.completionPolicy != "recurring" ||
                        new.long("activityRateUpdatedAt") != rate.instant || new.int("activityRate") != rate.rate ||
                        rate.zone != ZoneId.systemDefault()) return@withReadScope
                    val activated = !old.bool("isActive") && new.bool("isActive") &&
                        factChanges.any { (before, after) -> before == null && after != null }
                    val allowed = if (activated) setOf("activityRate", "activityRateUpdatedAt", "isActive", "updatedAt")
                        else setOf("activityRate", "activityRateUpdatedAt")
                    if (old.table.columns.any { it !in allowed && old.cell(it) != new.cell(it) }) return@withReadScope
                    if (activated) {
                        if (new.long("updatedAt") !in capture.beganAt..rate.instant) return@withReadScope
                        activations.add(habit.uuid)
                    }
                }
                if (!supported(mode, changesByName.values.toList(), activations, habits, context)) return@withReadScope
                val control = control()
                check(control.generation == Math.addExact(context.header.generation, changes.size.toLong()) && control.dirty) {
                    "COUNT_TRUST_JOURNAL_INCOMPLETE"
                }
                val edits = linkedMapOf<String, JsonElement?>()
                if (context.formal && mode == Mutation.ACK) {
                    check(roundAck != null && roundAck.context === context) { "COUNT_ROUND_ACK_PROOF_REQUIRED" }
                    edits[CountTrustRoundEvidence.METADATA] = Json.encodeToJsonElement(ChallengeMetadata.serializer(), roundAck.metadata)
                }
                val memberships = linkedMapOf<String, MutableSet<String>>()
                suspend fun removeOrAdd(row: CountTrustRows.Row, add: Boolean) {
                    CountTrustMembership.memberships(row).forEach { (key, member) ->
                        val set = memberships.getOrPut(key) { context.members(key).toSortedSet() }
                        if (add) check(set.add(member)) else check(set.remove(member))
                    }
                }
                changesByName.forEach { (name, pair) ->
                    pair.first?.let { removeOrAdd(it, false) }
                    pair.second?.let { removeOrAdd(it, true) }
                    edits[name] = pair.second?.value
                }
                memberships.forEach { (key, set) -> edits[key] = JsonArray(set.map(::JsonPrimitive)) }
                val shadowDelta = changesByName.values.sumOf { (old, new) ->
                    (if (new?.table?.name == "sync_entity_state" && new.text("entityType") == "activity_event") 1 else 0) -
                        (if (old?.table?.name == "sync_entity_state" && old.text("entityType") == "activity_event") 1 else 0)
                }
                if (shadowDelta != 0) {
                    val count = Math.addExact(requireNotNull(context.index.get("activity_shadow_count")).jsonPrimitive.int, shadowDelta)
                    check(count >= 0)
                    edits["activity_shadow_count"] = JsonPrimitive(count)
                    if (count > 10000) edits["global_common"] = JsonPrimitive(false)
                }
                val displayCovered = when (context.index.get(CountDisplayBlocks.COVERAGE_KEY)) {
                    null -> false
                    JsonPrimitive(CountDisplayBlocks.COVERAGE) -> true
                    else -> error("COUNT_DISPLAY_COVERAGE_CHANGED")
                }
                val checkDisplayCovered = when (context.index.get(CheckDisplayBlocks.COVERAGE_KEY)) {
                    null -> false
                    JsonPrimitive(CheckDisplayBlocks.COVERAGE) -> true
                    else -> error("CHECK_DISPLAY_COVERAGE_CHANGED")
                }
                val displayChanges = arrayListOf<CountDisplayBlocks.Change>()
                val checkDisplayChanges = arrayListOf<CheckDisplayBlocks.Change>()
                fun current(key: String) = if (key in edits) edits[key] else context.index.get(key)
                fun certified(key: String) = current(key)?.jsonArray?.map { item ->
                    val name = item.jsonPrimitive.content
                    val table = name.substringAfter("row:").substringBefore(':')
                    native.decode(table, requireNotNull(current(name)).jsonObject)
                }.orEmpty()
                if (context.formal) for ((old, fresh) in changesByName.values) {
                    val fact = fresh ?: requireNotNull(old)
                    if (fact.table.name != "completions") continue
                    val habit = habits[fact.text("habitUuid")] ?: return@withReadScope
                    if (!context.eligible(habit)) return@withReadScope
                    val key = CountTrustRoundEvidence.factKey(fact.text("uuid"))
                    if (fresh == null) { edits[key] = null; continue }
                    val birth = certified(CountTrustMembership.key("birth.entity", "activity_event", fresh.text("uuid"))).singleOrNull()
                    val accepted = birth?.let {
                        val record = certified(CountTrustMembership.key("round.uuid", it.text("roundUuid"))).single()
                        Json.decodeFromString(ChallengeRoundRecord.serializer(), record.text("recordJson")).head
                    }
                    val head = CountTrustRoundEvidence.bindFact(fresh,
                        certified(CountTrustMembership.key("origin.entity", "activity_event", fresh.text("uuid"))),
                        accepted, context.known(habit.uuid).filterNotNull().toSet(), context.access) { origin, source ->
                        check(NextRequestSql.rowHash(sql, "next_acceptances", "kind=? AND requestId=?",
                            arrayOf(NEXT_OPERATION, origin.text("requestId"))) == null &&
                            NextRequestSql.rowHash(sql, "sync_outbox", "id=?", arrayOf(origin.long("queueId"))) == origin.text("sourceHash")) {
                            "COUNT_ROUND_SOURCE_CHANGED"
                        }
                        val sent = native.read("next_transmissions", "kind=? AND requestId=?",
                            arrayOf(NEXT_OPERATION, origin.text("requestId"))).singleOrNull()
                        sent?.let {
                            check(it.text("accountId") == origin.text("accountId") &&
                                it.text("serverInstanceId") == origin.text("serverInstanceId") &&
                                it.text("syncEpoch") == origin.text("syncEpoch") && it.text("deviceId") == context.access.capturedDeviceId &&
                                it.long("queueId") == origin.long("queueId") && it.long("protocol") == 5L)
                            val wire = (it.cell("wireBytes").also { cell -> check(cell.type == Cursor.FIELD_TYPE_BLOB) }.value as List<*>)
                                .map { byte -> byte as Byte }.toByteArray()
                            check(nextRequestHash(wire) == it.text("wireHash") &&
                                validateNextOperationEnvelope(origin.text("intentJson"), wire, it.text("deviceId")) == source.operation) {
                                "COUNT_ROUND_SOURCE_CHANGED"
                            }
                        }
                    }
                    if (old != null) check(context.factHead(old.text("uuid")) == head) { "COUNT_ROUND_INVALID" }
                    if (mode == Mutation.LOCAL_COUNT && old == null) check(head == context.head(habit.uuid)) { "COUNT_ROUND_INVALID" }
                    edits[key] = CountTrustRoundEvidence.encode(head)
                }
                for ((_, new) in habitEdits) {
                    val habit = requireNotNull(habits[requireNotNull(new).text("uuid")])
                    val rate = capture.rates.getValue(habit.uuid)
                    val completed = rateWindow(habit, rate.instant, rate.zone).filter { date ->
                        val present = current(CountTrustMembership.key("completion.date", habit.uuid, date.toString()))
                            ?.jsonArray?.isNotEmpty() == true
                        check(nativeCompletionExists(habit.id, date) == present) { "COUNT_TRUST_RATE_DEPENDENCY_CHANGED" }
                        present
                    }
                    check(ActivityRateCalculator.calculate(habit.schedule, habit.createdAt, completed, rate.instant, rate.zone) == rate.rate) {
                        "COUNT_TRUST_RATE_CHANGED"
                    }
                }
                val affected = changesByName.values.flatMap { listOfNotNull(it.first, it.second) }
                    .filter { it.table.name in setOf("completions", "count_days") }
                    .map { row -> (if (row.table.name == "completions") requireNotNull(row.nullableText("habitUuid")) else row.text("habitUuid")) to
                        YearMonth.from(LocalDate.parse(row.text(if (row.table.name == "completions") "recordedLocalDate" else "localDate"))) }.toSet()
                for ((uuid, month) in affected) {
                    val habit = requireNotNull(habits[uuid])
                    if (!context.eligible(habit)) return@withReadScope
                    val facts = certified(CountTrustMembership.key("completion.month", uuid, month.toString()))
                    val days = certified(CountTrustMembership.key("day.month", uuid, month.toString()))
                    // Only changed/new effective facts execute business rules. Unchanged members
                    // retain the root's validation result, including sealed historical months.
                    for ((_, new) in changesByName.values) if (new?.table?.name == "completions" && new.nullableText("habitUuid") == uuid) {
                        val fact = new.completion()
                        val originals = certified(CountTrustMembership.key("origin.entity", "activity_event", fact.uuid))
                            .map { row ->
                                check(row.text("accountId") == context.access.session.authentication.userId)
                                check(row.long("protocol") == 5L && row.long("queueId") > 0)
                                decodeNextOperationIntent(row.text("intentJson")).also {
                                    check(it.operationId == row.text("requestId"))
                                    com.dayforge.data.api.dto.validateNextSyncOperation(it)
                                }
                            }.filter { it.action == "upsert" && it.payload["event_type"] in quantityTypes }
                        check(originals.size <= 1) { "COUNT_FACT_INVALID" }
                        val states = certified(CountTrustMembership.key("shadow.entity", "activity_event", fact.uuid))
                        check(states.size <= 1)
                        val accepted = states.singleOrNull()?.shadow()?.let { state ->
                            check(!state.deleted && state.revision > 0 && state.payloadJson != null && state.payloadHash == syncPayloadHash(state.payloadJson))
                            val payload = Json.parseToJsonElement(requireNotNull(state.payloadJson)).jsonObject
                            SyncV2Change(0, "activity_event", fact.uuid, "upsert", state.revision, payload,
                                payload.getValue("updated_at").jsonPrimitive.content)
                        }
                        val policy = certified(CountTrustMembership.key("day.date", uuid, fact.businessDate.toString())).singleOrNull()?.day()?.policy
                        check(certified(CountTrustMembership.key("completion.uuid", fact.uuid)).size == 1)
                        check(certified(CountTrustMembership.key("timer.uuid", fact.uuid)).isEmpty())
                        CountFactEvidenceRules.verify(habit, fact, policy, originals.singleOrNull()?.payload, accepted,
                            certified(CountTrustMembership.key("shadow.revertTarget", fact.uuid)).isNotEmpty())
                    }
                    val removed = changesByName.values.mapNotNull { it.first }.filter { it.table.name == "completions" &&
                        it.nullableText("habitUuid") == uuid && YearMonth.from(LocalDate.parse(it.text("recordedLocalDate"))) == month }
                    val added = changesByName.values.mapNotNull { it.second }.filter { it.table.name == "completions" &&
                        it.nullableText("habitUuid") == uuid && YearMonth.from(LocalDate.parse(it.text("recordedLocalDate"))) == month }
                    fun newHead(row: CountTrustRows.Row): ChallengeRoundHead? = if (context.formal)
                        CountTrustRoundEvidence.decode(requireNotNull(current(CountTrustRoundEvidence.factKey(row.text("uuid"))))) else null
                    val policyChanged = changesByName.values.any { (old, new) -> listOfNotNull(old, new).any {
                        it.table.name == "count_days" && it.text("habitUuid") == uuid && YearMonth.from(LocalDate.parse(it.text("localDate"))) == month
                    } }
                    val touchedHeads = if (policyChanged) context.known(uuid) else
                        (removed.map { context.factHead(it.text("uuid")) } + added.map(::newHead)).distinct()
                    for (head in touchedHeads) {
                        val previous = context.index.get(summaryKey(uuid, month, head))?.jsonObject
                        val partitionFacts = facts.filter { newHead(it) == head }
                        val partitionRemoved = removed.filter { context.factHead(it.text("uuid")) == head }
                        val partitionAdded = added.filter { newHead(it) == head }
                        val known = days.mapTo(hashSetOf()) { LocalDate.parse(it.text("localDate")) }
                        val unknown = (previous?.let { CountTrustMembership.values(it).unknownDates }.orEmpty() +
                            partitionFacts.map { LocalDate.parse(it.text("recordedLocalDate")) }).filterTo(linkedSetOf()) { it !in known }
                        val removedDates = partitionRemoved.mapTo(hashSetOf()) { it.text("recordedLocalDate") }
                        edits[summaryKey(uuid, month, head)] = if (previous == null) CountTrustMembership.summary(uuid, month, partitionFacts, days, unknown)
                            else CountTrustMembership.update(previous, partitionRemoved, partitionAdded, days,
                                partitionFacts.filter { it.text("recordedLocalDate") in removedDates }, unknown)
                        val months = current(monthsKey(uuid, head))?.jsonArray?.mapTo(sortedSetOf()) { it.jsonPrimitive.content } ?: sortedSetOf()
                        months.add(month.toString())
                        edits[monthsKey(uuid, head)] = JsonArray(months.map(::JsonPrimitive))
                        val body = requireNotNull(edits[summaryKey(uuid, month, head)]).jsonObject
                        if (habit.habitType == HabitType.CHECK_IN) {
                            if (checkDisplayCovered) checkDisplayChanges += CheckDisplayBlocks.Change(habit, head, month, body)
                        } else if (displayCovered && head != null) displayChanges += CountDisplayBlocks.Change(habit, head, month, body)
                    }
                }
                if (displayCovered) edits.putAll(CountDisplayBlocks.advance(displayChanges, context.index::get))
                if (checkDisplayCovered) edits.putAll(CheckDisplayBlocks.advance(checkDisplayChanges, context.index::get))
                // Actual SQL content is checked after every producer and trigger has run.
                changesByName.forEach { (_, pair) ->
                    val row = pair.second ?: requireNotNull(pair.first)
                    check(native.byIdentity(row.table.name, row.identity)?.value == pair.second?.value) { "COUNT_TRUST_FINAL_CONTENT_CHANGED" }
                }
                edits[MetricCommandAbsenceEvidence.KEY] = MetricCommandAbsenceEvidence.after(context.index.get(MetricCommandAbsenceEvidence.KEY), changesByName.values.toList(), native)
                edits.putAll(metricDirectoryEdits(context, changesByName.values.toList()))
                context.index.putAll(edits)
                check(context.environment == CountTrustValidationEnvironment.current()) { "COUNT_TRUST_ENVIRONMENT_CHANGED" }
                check(tokens.localCoreWriteAccess() == context.access) { "COUNT_SESSION_CHANGED" }
                CountTrustSeal.store(sql, CountTrustSeal().prepare(context.header.binding, context.header.schemaHash,
                    context.index.root, Math.addExact(context.header.revision, 1), control.generation))
            }
        }
    }


    private suspend fun supported(mode: Mutation, changes: List<Pair<CountTrustRows.Row?, CountTrustRows.Row?>>,
        activations: Set<String>, habits: Map<String, HabitEntity?>, context: Context): Boolean {
        if (changes.any { (old, new) -> (old ?: requireNotNull(new)).table.name !in mutationTables(mode) }) return false
        // This mode already matched the privately prepared complete 3N INSERT plan, before
        // coalescing. None of these rows affects a COUNT arithmetic or global-health input.
        if (mode == Mutation.LOCAL_OBSERVATIONS) return context.observations && changes.all { (old, fresh) ->
            old == null && fresh != null && fresh.table.name in setOf("metric_logs", "sync_outbox", "next_request_origins")
        }
        val createdOrigins = changes.mapNotNull { (old, new) -> new?.takeIf { old == null && it.table.name == "next_request_origins" } }
        val createdQueues = changes.mapNotNull { (old, new) -> new?.takeIf { old == null && it.table.name == "sync_outbox" } }
        val newFacts = changes.mapNotNull { (old, new) -> new?.takeIf { old == null && it.table.name == "completions" } }
        for ((old, new) in changes) {
            val row = new ?: requireNotNull(old)
            if (mode == Mutation.LOCAL_COUNT) {
                if (row.table.name == "count_days" && (old != null || new == null ||
                        newFacts.none { it.text("uuid") == row.text("firstEventUuid") })) return false
                if (row.table.name == "next_structural_dependencies" && (old != null || new == null ||
                        createdOrigins.none { it.text("requestId") == row.text("operationId") })) return false
                if (row.table.name == "sync_outbox" && (old != null || new == null)) return false
                if (row.table.name == "next_request_origins" && createdQueues.none {
                        it.text("operationId") == row.text("requestId") && it.long("id") == row.long("queueId") }) return false
            }
            if (mode == Mutation.SEND && row.table.name == "next_transmissions" && (old != null || new == null || row.text("kind") != NEXT_OPERATION)) return false
            if (row.table.name == "next_request_origins") {
                if (old != null || new == null || row.text("kind") != NEXT_OPERATION) return false
                val round = roundOperationIntent(row.text("intentJson"))
                if ((round != null) != context.formal) return false
                if (round != null && (round.initialCreation || round.restartFrontier != null || round.goalChildFrontier != null)) return false
                val operation = decodeNextOperationIntent(row.text("intentJson"))
                if (operation.entityType == "plan_node") {
                    if (mode != Mutation.LOCAL_COUNT || operation.entityUuid !in activations || operation.action != "upsert" ||
                        operation.payload != NextStructureMapper.writePlan(requireNotNull(habits[operation.entityUuid]))) return false
                    continue
                }
                if (operation.entityType != "activity_event" || operation.action != "upsert" ||
                    operation.payload["event_type"] !in quantityTypes + JsonPrimitive("revert")) return false
                // A second source for an unchanged retained fact is not a new count command.
                if (operation.payload["event_type"] in quantityTypes && newFacts.none { it.text("uuid") == operation.entityUuid }) return false
                if (operation.payload["event_type"] == JsonPrimitive("revert") && changes.none { (before, after) ->
                        before?.table?.name == "completions" && after == null &&
                            operation.payload["reverts_event_uuid"] == JsonPrimitive(before.text("uuid")) }) return false
            }
            if (row.table.name == "sync_outbox" && row.text("recordType") != "completion" &&
                !(mode == Mutation.LOCAL_COUNT && row.text("recordType") == "habit" && row.text("entityUuid") in activations) &&
                !(mode == Mutation.ACK && context.observations && row.text("recordType") == "metric_log")) return false
            if (row.table.name == "sync_entity_state" && row.text("entityType") != "activity_event" &&
                !(mode == Mutation.ACK && context.observations && row.text("entityType") == "metric_observation")) return false
            if (row.table.name == "metric_logs" && (mode != Mutation.ACK || !context.observations)) return false
            if (row.table.name == "completions" && (row.nullableText("oneTimeAction") != null || row.nullableText("habitUuid") == null)) return false
        }
        return true
    }

    suspend fun summary(habit: HabitEntity, today: LocalDate, access: LocalCoreWriteAccess): CountSummary? =
        withOpen(access) { context -> context?.let { summaryInContext(it, habit, today) } }

    internal suspend fun projection(habit: HabitEntity, today: LocalDate, zone: ZoneId,
        access: LocalCoreWriteAccess, cardAtMillis: Long? = null): CountDisplayProjection? =
        withOpen(access) { context -> context?.let { projectionInContext(it, habit, today, zone, cardAtMillis) } }

    private suspend fun projectionInContext(context: Context, habit: HabitEntity, today: LocalDate,
        zone: ZoneId, cardAtMillis: Long?): CountDisplayProjection? {
        if (habit.habitType != HabitType.COUNTING || !context.eligible(habit) || !context.formal) return null
        return context.index.withReadScope {
            when (context.index.get(CountDisplayBlocks.COVERAGE_KEY)) {
                null -> return@withReadScope null
                JsonPrimitive(CountDisplayBlocks.COVERAGE) -> Unit
                else -> error("COUNT_DISPLAY_COVERAGE_CHANGED")
            }
            val nativeHabit = context.checked(CountTrustMembership.key("habit.uuid", habit.uuid),
                "habits", "uuid=?", arrayOf(habit.uuid)).singleOrNull()
            check(nativeHabit != null && database.habitDao().getHabitById(habit.id) == habit) { "COUNT_ACTIVITY_CHANGED" }
            val head = requireNotNull(context.head(habit.uuid))
            val scope = CountDisplayBlocks.Scope(habit.uuid, head)
            val global = CountDisplayBlocks.global(requireNotNull(context.index.get(scope.globalKey())) {
                "COUNT_DISPLAY_GLOBAL_MISSING"
            }.jsonObject, scope, habit)
            // Calendar basis differs legitimately after timezone travel. Original full reader
            // remains available; stale strict-failure answers never become display authority.
            if (global.zone != zone) return@withReadScope null
            val years = linkedMapOf<Int, CountDisplayRollup.YearBlock?>()
            fun year(number: Int): CountDisplayRollup.YearBlock? = if (number in years) years[number]
                else CountDisplayBlocks.year(global, number, habit, context.index::get).also { years[number] = it }
            val expectedMonth = year(today.year)?.months?.get(today.monthValue)
            val body = context.index.get(summaryKey(habit.uuid, YearMonth.from(today), head))?.jsonObject
            check((body == null) == (expectedMonth == null)) { "COUNT_DISPLAY_MONTH_CHANGED" }
            val values = body?.let {
                check(it["habit_uuid"] == JsonPrimitive(habit.uuid) && it["month"] == JsonPrimitive(YearMonth.from(today).toString()))
                CountTrustMembership.values(it).also { decoded ->
                    val actual = CountDisplayRollup.Month.from(YearMonth.from(today), decoded)
                    val expected = requireNotNull(expectedMonth)
                    check(actual.qualified == expected.qualified && actual.quantity == expected.quantity &&
                        actual.participation == expected.participation && actual.unknown == expected.unknown) {
                        "COUNT_DISPLAY_MONTH_CHANGED"
                    }
                }
            }
            val unknown = today !in values?.policies.orEmpty() && unbound(context, habit, today)
            val policy = values?.policies?.get(today) ?: if (unknown) null else CountDayPolicy(habit.targetValue, habit.isCountdown)
            val card = cardAtMillis?.let { instant ->
                check(Instant.ofEpochMilli(instant).atZone(zone).toLocalDate() == today) { "DISPLAY_DATE_CHANGED" }
                if (today > LocalDate.MIN) year(today.minusDays(1).year)
                val current = global.fold.currentStreak(today, years.filterValues { it != null }.mapValues { requireNotNull(it.value) })
                val dates = ActivityRateCalculator.generateWindowCheckInDays(habit.schedule, habit.createdAt, instant, zone)
                    .map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
                val participating = dates.filter { date -> year(date.year)?.participated(date) == true }
                val missed = dates.size - participating.size
                val activity = (100 - missed * ActivityRateCalculator.getDeductionPerMiss(habit.schedule)).coerceAtLeast(0)
                CountDisplayProjection.Card(current, global.fold.bestStreak, activity)
            }
            CountDisplayProjection(today, zone.id, values?.quantities?.get(today) ?: 0L, policy,
                values?.latestIds?.get(today), global.fold.qualifiedCount, global.fold.hasFailed(habit, today, zone), head,
                CountActionIdentity(context.access.session, habit.id, habit.uuid, context.access.capturedDeviceId,
                    NextStructureMapper.writePlan(habit), head), card)
        }
    }

    internal suspend fun checkProjection(habit: HabitEntity, today: LocalDate, zone: ZoneId,
        access: LocalCoreWriteAccess, cardAtMillis: Long? = null): CheckDisplayProjection? =
        withOpen(access) { context -> context?.let { checkProjectionInContext(it, habit, today, zone, cardAtMillis) } }

    private suspend fun checkProjectionInContext(context: Context, habit: HabitEntity, today: LocalDate,
        zone: ZoneId, cardAtMillis: Long?): CheckDisplayProjection? {
        if (habit.habitType != HabitType.CHECK_IN || !context.eligible(habit)) return null
        return context.index.withReadScope {
            when (context.index.get(CheckDisplayBlocks.COVERAGE_KEY)) {
                null -> return@withReadScope null
                JsonPrimitive(CheckDisplayBlocks.COVERAGE) -> Unit
                else -> error("CHECK_DISPLAY_COVERAGE_CHANGED")
            }
            val nativeHabit = context.checked(CountTrustMembership.key("habit.uuid", habit.uuid),
                "habits", "uuid=?", arrayOf(habit.uuid)).singleOrNull()
            val actualHabit = database.habitDao().getHabitById(habit.id)
            if (actualHabit?.uuid == habit.uuid && actualHabit != habit)
                throw HabitReadSnapshotChanged("COUNT_ACTIVITY_CHANGED")
            check(nativeHabit != null && actualHabit == habit) { "COUNT_ACTIVITY_CHANGED" }
            val ownedHabit = requireNotNull(actualHabit) // Original DAO value; never retain caller-owned schedule containers.
            val head = context.head(ownedHabit.uuid)
            val scope = CheckDisplayBlocks.Scope(ownedHabit.uuid, head)
            val global = CheckDisplayBlocks.global(requireNotNull(context.index.get(scope.globalKey())) {
                "CHECK_DISPLAY_GLOBAL_MISSING"
            }.jsonObject, scope, ownedHabit)
            // Calendar basis differs legitimately after timezone travel. Original full reader
            // remains available; stale strict-failure answers never become display authority.
            if (global.zone != zone) return@withReadScope null
            val years = linkedMapOf<Int, CountDisplayRollup.YearBlock?>()
            fun year(number: Int): CountDisplayRollup.YearBlock? = if (number in years) years[number]
                else CheckDisplayBlocks.year(global, number, ownedHabit, context.index::get).also { years[number] = it }
            val expectedMonth = year(today.year)?.months?.get(today.monthValue)
            val body = context.index.get(summaryKey(ownedHabit.uuid, YearMonth.from(today), head))?.jsonObject
            check((body == null) == (expectedMonth == null)) { "CHECK_DISPLAY_MONTH_CHANGED" }
            val values = body?.let {
                check(it["habit_uuid"] == JsonPrimitive(ownedHabit.uuid) && it["month"] == JsonPrimitive(YearMonth.from(today).toString()))
                CountTrustMembership.values(it).also { decoded ->
                    val actual = CheckDisplayBlocks.month(YearMonth.from(today), decoded)
                    val expected = requireNotNull(expectedMonth)
                    check(actual.qualified == expected.qualified && actual.quantity == expected.quantity &&
                        actual.participation == expected.participation && actual.unknown == expected.unknown) {
                        "CHECK_DISPLAY_MONTH_CHANGED"
                    }
                }
            }
            val card = cardAtMillis?.let { instant ->
                check(Instant.ofEpochMilli(instant).atZone(zone).toLocalDate() == today) { "DISPLAY_DATE_CHANGED" }
                if (today > LocalDate.MIN) year(today.minusDays(1).year)
                val current = global.fold.currentStreak(today, years.filterValues { it != null }.mapValues { requireNotNull(it.value) })
                val dates = ActivityRateCalculator.generateWindowCheckInDays(ownedHabit.schedule, ownedHabit.createdAt, instant, zone)
                    .map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
                val participating = dates.filter { date -> year(date.year)?.participated(date) == true }
                val missed = dates.size - participating.size
                val activity = (100 - missed * ActivityRateCalculator.getDeductionPerMiss(ownedHabit.schedule)).coerceAtLeast(0)
                CheckDisplayProjection.Card(current, global.fold.bestStreak, activity)
            }
            CheckDisplayProjection(today, zone.id, values?.quantities?.get(today) ?: 0L,
                values?.latestIds?.get(today), global.fold.qualifiedCount, global.fold.hasFailed(ownedHabit, today, zone), head,
                CheckActionIdentity(context.access.session, ownedHabit.id, ownedHabit.uuid, context.access.capturedDeviceId,
                    NextStructureMapper.writePlan(ownedHabit), head), card)
        }
    }


    internal suspend fun timerProjection(habit: HabitEntity, today: LocalDate, zone: ZoneId,
        access: LocalCoreWriteAccess, now: Long): TimerDisplayProjection? =
        withOpen(access) { context -> context?.let { timerProjectionInContext(it, habit, today, zone, now) } }

    private suspend fun timerProjectionInContext(context: Context, habit: HabitEntity, today: LocalDate,
        zone: ZoneId, now: Long): TimerDisplayProjection? {
        if (!context.timerEligible(habit)) return null
        return context.index.withReadScope {
            check(context.index.get(TimerDisplayBlocks.COVERAGE_KEY) == JsonPrimitive(TimerDisplayBlocks.COVERAGE)) {
                "TIMER_DISPLAY_COVERAGE_CHANGED"
            }
            val nativeHabit = context.checked(CountTrustMembership.key("habit.uuid", habit.uuid),
                "habits", "uuid=?", arrayOf(habit.uuid)).singleOrNull()
            val actualHabit = database.habitDao().getHabitById(habit.id)
            if (actualHabit?.uuid == habit.uuid && actualHabit != habit)
                throw HabitReadSnapshotChanged("TIMER_ACTIVITY_CHANGED")
            check(nativeHabit != null && actualHabit == habit) { "TIMER_ACTIVITY_CHANGED" }
            val ownedHabit = requireNotNull(actualHabit)
            val head = context.head(ownedHabit.uuid)
            val scope = TimerDisplayBlocks.Scope(ownedHabit.uuid, head)
            val global = TimerDisplayBlocks.global(requireNotNull(context.index.get(scope.globalKey())) {
                "TIMER_DISPLAY_GLOBAL_MISSING"
            }.jsonObject, scope, ownedHabit)
            if (global.zone != zone) return@withReadScope null
            val activeRows = context.checked(CountTrustMembership.key("timer.active", ownedHabit.id.toString()),
                "timelogs", "habitId=? AND endTime IS NULL", arrayOf(ownedHabit.id))
            check(activeRows.size <= 1) { "TIMER_FAILURE_MULTIPLE_ACTIVE_SESSIONS" }
            val retainedActive = activeRows.singleOrNull()?.let(TimerTrustEvidence::log)
            val visibleActive = retainedActive?.takeIf { log ->
                val encoded = requireNotNull(context.index.get(TimerTrustEvidence.birthKey(log.uuid))) { "TIMER_TRUST_BIRTH_MISSING" }
                val birth = TimerTrustEvidence.decodeBirth(encoded)
                check(birth in context.known(ownedHabit.uuid)) { "TIMER_ROUND_INVALID" }
                birth == head
            }
            val segments = visibleActive?.let { log ->
                context.checked(CountTrustMembership.key("timer.segments", log.uuid), "timer_segments",
                    "sessionUuid=?", arrayOf(log.uuid)).map(TimerTrustEvidence::segment).sortedBy { it.sequence }
            }.orEmpty()
            TimerDisplayBlocks.project(global, ownedHabit, today, zone, now, visibleActive, segments, context.index::get)
        }
    }

    private suspend fun summaryInContext(context: Context, habit: HabitEntity, today: LocalDate): CountSummary? {
        if (habit.habitType != HabitType.COUNTING || !context.eligible(habit)) return null
        return context.index.withReadScope {
            val nativeHabit = context.checked(CountTrustMembership.key("habit.uuid", habit.uuid), "habits", "uuid=?", arrayOf(habit.uuid)).singleOrNull()
            check(nativeHabit != null && database.habitDao().getHabitById(habit.id) == habit) { "COUNT_ACTIVITY_CHANGED" }
            val head = context.head(habit.uuid)
            val parts = context.members(monthsKey(habit.uuid, head)).map { month ->
                val body = requireNotNull(context.index.get(summaryKey(habit.uuid, YearMonth.parse(month), head))).jsonObject
                check(body["habit_uuid"] == JsonPrimitive(habit.uuid) && body["month"] == JsonPrimitive(month)) { "COUNT_TRUST_SUMMARY_SCOPE_CHANGED" }
                CountTrustMembership.values(body)
            }
            val values = CountSummary.Values.combine(parts)
            // An empty current date may still have retained unknown sources. This exact original
            // probe only runs for that date; no previous month's proof is recalculated.
            val unknownToday = today !in values.policies && unbound(context, habit, today)
            // A prior unknown day is no longer effective when its final fact was removed.
            // Today's retained origins are decided by the exact current probe above, not by
            // the previous calculation block's historical unknown flag.
            val unknown = values.unknownDates.filterTo(linkedSetOf()) { it in values.quantities }
            if (unknownToday) unknown.add(today)
            val adjusted = if (unknown != values.unknownDates) CountSummary.Values.create(values.policies,
                values.quantities, unknown, values.latestIds) else values
            val policy = adjusted.policies[today] ?: if (today in adjusted.unknownDates) null else CountDayPolicy(habit.targetValue, habit.isCountdown)
            CountSummary.fromValues(today, adjusted, policy, head,
                CountActionIdentity(context.access.session, habit.id, habit.uuid, context.access.capturedDeviceId, NextStructureMapper.writePlan(habit), head))
        }
    }

    /** Exact source for a button/necessary prompt. Never fabricates an empty detail history. */
    suspend fun exactFact(habit: HabitEntity, access: LocalCoreWriteAccess, id: Long? = null,
        uuid: String? = null): CompletionEntity? {
        require((id == null) != (uuid == null))
        return withOpen(access) { context -> context?.let { exactFactInContext(it, habit, id, uuid) } }
    }

    private suspend fun exactFactInContext(context: Context, habit: HabitEntity, id: Long?, uuid: String?): CompletionEntity? {
        require((id == null) != (uuid == null))
        if (!context.eligible(habit)) return null
        return context.index.withReadScope {
            val row = if (uuid != null) context.checked(CountTrustMembership.key("completion.uuid", uuid),
                "completions", "uuid=?", arrayOf(uuid)).singleOrNull()
            else {
                val identity = JsonArray(listOf(CountTrustRows.encode(CountTrustJournal.Cell(Cursor.FIELD_TYPE_INTEGER, requireNotNull(id))))).toString()
                val actual = context.rows.byIdentity("completions", identity)
                check(context.index.get(CountTrustRows.rowName("completions", identity)) == actual?.value) { "COUNT_TRUST_CONTENT_CHANGED" }
                actual?.also { found ->
                    check(context.members(CountTrustMembership.key("completion.uuid", found.text("uuid"))) == listOf(found.name)) {
                        "COUNT_TRUST_MEMBERS_CHANGED"
                    }
                }
            }
            val fact = row?.completion()?.also { check(it.habitId == habit.id && it.habitUuid == habit.uuid) { "COUNT_FACT_INVALID" } }
            fact?.takeIf { context.factHead(it.uuid) == context.head(habit.uuid) }
        }
    }

    private suspend fun unbound(context: Context, habit: HabitEntity, date: LocalDate): Boolean {
        fun rows(kind: String): List<CountTrustRows.Row> = context.certified(CountTrustMembership.key(kind, habit.uuid, date.toString())).onEach {
            check(context.rows.byIdentity(it.table.name, it.identity)?.value == it.value) { "COUNT_TRUST_CONTENT_CHANGED" }
        }
        val origins = rows("origin.date")
        val shadows = rows("shadow.date")
        require(origins.size <= 10000 && shadows.size <= 10000)
        var unknown = false
        for (row in origins) {
            require(row.long("protocol") == 5L && isContractUuid(row.text("accountId")) && isContractUuid(row.text("requestId")))
            require(row.text("accountId") == context.access.session.authentication.userId) { "COUNT_SESSION_CHANGED" }
            val operation = decodeNextOperationIntent(row.text("intentJson"))
            require(operation.operationId == row.text("requestId"))
            if (operation.payload["event_type"] in countTypes) {
                require(NextCommonFactMapper.countPolicy(operation.payload) == null) { "COUNT_DAY_INVALID" }
                unknown = true
            }
        }
        for (row in shadows) {
            val state = row.shadow(); val payload = Json.parseToJsonElement(requireNotNull(state.payloadJson)).jsonObject
            require(state.payloadHash == syncPayloadHash(state.payloadJson))
            if (payload["event_type"] in countTypes) {
                require(NextCommonFactMapper.countPolicy(payload) == null) { "COUNT_DAY_INVALID" }
                unknown = true
            }
        }
        return unknown || context.members(CountTrustMembership.key("completion.date", habit.uuid, date.toString())).isNotEmpty()
    }

    suspend fun details(habit: HabitEntity, access: LocalCoreWriteAccess): List<CompletionEntity>? =
        withOpen(access) { context -> context?.let { detailsInContext(it, habit) } }

    private suspend fun detailsInContext(context: Context, habit: HabitEntity): List<CompletionEntity>? {
        if (!context.eligible(habit)) return null
        return context.index.withReadScope {
            val result = arrayListOf<com.dayforge.data.local.entity.CompletionEntity>()
            val head = context.head(habit.uuid)
            for (month in context.members(monthsKey(habit.uuid, head))) {
                val first = YearMonth.parse(month).atDay(1).toString()
                val after = YearMonth.parse(month).plusMonths(1).atDay(1).toString()
                result += context.checked(CountTrustMembership.key("completion.month", habit.uuid, month), "completions",
                    "habitId=? AND recordedLocalDate>=? AND recordedLocalDate<?", arrayOf<Any>(habit.id, first, after)).map { it.completion() }
                    .filter { context.factHead(it.uuid) == head }
            }
            result.sortedWith(compareByDescending<com.dayforge.data.local.entity.CompletionEntity> { it.recordedLocalDate }
                .thenByDescending { it.actualCompletedAt ?: it.date }.thenByDescending { it.id })
        }
    }

    private data class Control(val storage: String, val generation: Long, val dirty: Boolean)
    private fun control(): Control = sql.query("SELECT storageId,generation,dirty FROM count_trust_control WHERE id=1").use {
        check(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_STRING && it.getType(1) == Cursor.FIELD_TYPE_INTEGER &&
            it.getType(2) == Cursor.FIELD_TYPE_INTEGER && it.getLong(1) >= 0 && it.getLong(2) in 0..1)
        Control(it.getString(0), it.getLong(1), it.getLong(2) == 1L).also { _ -> check(!it.moveToNext()) }
    }

    private suspend fun approvedSchema(owner: CountTrustRuntime): Boolean {
        val expected = expectedSchema ?: run {
            val factory = CountTrustOpenHelperFactory(owner.context)
            val reference = Room.inMemoryDatabaseBuilder(owner.context, HabitDatabase::class.java)
                .openHelperFactory(factory).addCallback(SyncSchemaCallback).build().also { CountTrustRuntime.attach(it, factory) }
            try { reference.withTransaction { ReplicaSqlSchema.read(reference.openHelper.writableDatabase) } }
            finally { reference.close() }
        }.also { expectedSchema = it }
        val actual = ReplicaSqlSchema.read(sql)
        val optional = setOf("table.sqlite_stat1", "table.sqlite_stat4", "table.android_metadata")
        return actual.keys - optional == expected.keys - optional && actual.filterKeys { it !in optional }.all { (key, value) -> expected[key] == value }
    }

    private fun schemaHash(owner: CountTrustRuntime) = CountTrustIndex.hash(JsonArray(owner.schemaSnapshot().map { row ->
        JsonArray(row.map { it?.let(::JsonPrimitive) ?: JsonNull }) }).toString().toByteArray(Charsets.UTF_8))

    companion object {
        // Retain only the domain's dependency inputs, not large opaque recovery archives.
        // Existing transmission/receipt bytes are still freshly checked by their original
        // request validators; the fast paths only insert new immutable transmission/receipts.
        internal const val OBSERVATION_COVERAGE_KEY = "coverage:metric-observation"
        private const val OBSERVATION_COVERAGE = "metric-observation/native-members-v1/2026-10-24"
        private val snapshotTables = listOf("habits", "completions", "count_days", "timelogs", "metrics", "metric_logs", "habit_metric_links",
            "sync_outbox", "sync_entity_state", "sync_conflicts", "sync_control", "timer_command_outbox",
            "next_request_origins", "next_structural_dependencies", "next_sync_state",
            "next_challenge_state", "next_challenge_rounds", "next_challenge_births")
        private val onceSnapshotTables = listOf("local_fact_submissions", "one_time_transmissions", "completion_metric_prompts",
            "next_transmissions", "next_acceptances")
        private val timerSnapshotTables = listOf("timer_segments", "timelog_day_allocations", "next_transmissions", "next_acceptances")
        private fun mutationTables(mode: Mutation): Set<String> = when (mode) {
            Mutation.LOCAL_COUNT -> setOf("habits", "completions", "count_days", "sync_outbox", "next_request_origins", "next_structural_dependencies")
            Mutation.LOCAL_OBSERVATIONS -> setOf("metric_logs", "sync_outbox", "next_request_origins")
            Mutation.SEND -> setOf("next_transmissions")
            Mutation.ACK -> setOf("completions", "count_days", "metric_logs", "sync_entity_state", "sync_control", "sync_outbox", "next_acceptances",
                "next_challenge_state", "next_challenge_births")
        }
        @Volatile internal var lastQualificationFailure: String? = null; private set
        @Volatile private var expectedSchema: Map<String, ReplicaSqlSchema.SchemaObject>? = null
        /** Only an active producer callback may select this bounded preparation. Ordinary
         * callers and unqualified/retired connections retain the complete original parser.
         */
        internal suspend fun prepareTimerTransition(database: HabitDatabase, transition: TimerTransition) {
            val diagnostic = TimerTrustMaintenanceDiagnostic.begin(transition)
            val preparation = currentCoroutineContext()[LocalPreparation] ?: run {
                diagnostic?.mark(TimerTrustMaintenanceDiagnostic.Reason.NO_LOCAL_PREPARATION)
                return
            }
            if (!preparation.active || preparation.kernel.database !== database ||
                preparation.caller !== currentCoroutineContext()[Job] || preparation.boundary == null) {
                diagnostic?.mark(TimerTrustMaintenanceDiagnostic.Reason.OWNER_OR_BOUNDARY_MISS)
                return
            }
            val capture = preparation.capture
            capture.timerDiagnostic = diagnostic
            if (capture.timerPreparationAttempted) {
                capture.timerPreparation = null
                diagnostic?.mark(TimerTrustMaintenanceDiagnostic.Reason.DUPLICATE_PREPARATION)
                return
            }
            capture.timerPreparationAttempted = true
            diagnostic?.mark(TimerTrustMaintenanceDiagnostic.Reason.PREPARE_NULL)
            capture.timerPreparation = TimerTransition.Prepared.prepare(capture.context, database,
                preparation.kernel.tokens, preparation.kernel.sessions, transition, diagnostic)
            if (capture.timerPreparation != null) diagnostic?.mark(TimerTrustMaintenanceDiagnostic.Reason.PREPARED)
        }

        internal suspend fun preparedActivityRate(database: HabitDatabase, habit: HabitEntity): RateCalculation? {
            val preparation = currentCoroutineContext()[LocalPreparation] ?: return null
            if (preparation.kernel.database !== database) return null
            return preparation.kernel.calculatePreparedRate(preparation, habit)
        }
        internal suspend fun beginCountSource(database: HabitDatabase, habit: HabitEntity,
            fact: com.dayforge.data.local.entity.CompletionEntity): CountSourceTicket? {
            val preparation = currentCoroutineContext()[LocalPreparation] ?: return null
            if (preparation.kernel.database !== database) return null
            return preparation.kernel.prepareCountSource(preparation, habit, fact)
        }
        internal suspend fun bindCountSource(database: HabitDatabase, ticket: CountSourceTicket?, id: Long) {
            if (ticket == null) return
            val preparation = requireNotNull(currentCoroutineContext()[LocalPreparation]) { "COUNT_SOURCE_SCOPE_EXPIRED" }
            check(preparation.kernel.database === database && preparation.active &&
                preparation.caller === currentCoroutineContext()[Job] && database.inTransaction()) { "COUNT_SOURCE_SCOPE_EXPIRED" }
            val count = requireNotNull(preparation.capture.countSourcePreparation)
            check(count.ticket === ticket && count.insertedId == null) { "COUNT_SOURCE_TICKET_CHANGED" }
            check(id > 0)
            count.insertedId = id
        }
        /** Used only around the original COUNT completion-source read, ending before INSERT. */
        internal suspend fun <T> withMetricSourceRead(database: HabitDatabase,
            block: suspend (MetricSourceRead?) -> T): T {
            val preparation = currentCoroutineContext()[LocalPreparation] ?: return block(null)
            if (preparation.kernel.database !== database) return block(null)
            return preparation.kernel.readMetricSource(preparation, block)
        }

        /** Original Editor DAO loading point. Only this function observes that actual DAO
         * result; ordinary/foreign/child callers get exactly the original DAO without a proof.
         */
        internal suspend fun loadMetricForT1(database: HabitDatabase, id: Long): MetricEntity? {
            val value = database.metricDao().getMetricById(id) ?: return null
            val preparation = metricInputPreparation(database) ?: return value
            if (preparation.capture.metricSourceReadState !is MetricSourceSeed) return value
            val intent = NextCoreLocalIntentStore.commandInput(database) ?: return value
            if (intent.action != CountCommandRecords.Action.LINKED_METRICS) return value
            val builder = preparation.metricInput ?: MetricT1Input.Builder(intent).also { preparation.metricInput = it }
            builder.observeActualDao(value)
            return value
        }
        private suspend fun metricInputPreparation(database: HabitDatabase): LocalPreparation? {
            val preparation = currentCoroutineContext()[LocalPreparation] ?: return null
            return preparation.takeIf { it.kernel.database === database && it.active &&
                it.caller === currentCoroutineContext()[Job] && it.caller?.isActive != false && database.inTransaction() }
        }
        internal suspend fun metricDefinitionForT1(database: HabitDatabase, value: MetricEntity): JsonObject =
            metricInputPreparation(database)?.metricInput?.definition(value) ?: NextStructureMapper.writeMetric(value)
        internal suspend fun metricCommandInput(database: HabitDatabase, intent: CountCommandRecords.Intent): JsonObject =
            metricInputPreparation(database)?.metricInput?.commandInput(intent) ?: intent.body().getValue("input").jsonObject

        /** Closed actual source consumer, not an arbitrary callback returning "verified".
         * Original source SQL, fresh checks and read footer all execute. Null/foreign readers
         * retain the original behavior but cannot register a reusable source in this frame.
         */
        internal suspend fun requireMetricCompletion(database: HabitDatabase, reader: WidgetFactReader,
            claim: WidgetFactClaim): WidgetFactSnapshot {
            val preparation = metricInputPreparation(database)
            var issued = false
            val result = withMetricSourceRead(database) { source ->
                issued = source != null
                reader.requireCompletionInTransaction(claim, metricSourceRead = source)
            }
            if (issued && preparation != null && metricInputPreparation(database) === preparation &&
                reader.isMetricInputOwner(database, preparation.kernel.tokens, preparation.kernel.sessions))
                preparation.metricInput?.sourceCompleted(result.claim)
            return result
        }
        /** Returns only the exact privately stored input from this live original preparation.
         * There is no caller-supplied proof parameter or cross-Job/transaction registration.
         */
        internal suspend fun frozenMetricInput(database: HabitDatabase, intent: CountCommandRecords.Intent,
            logs: List<MetricLogEntity>, metrics: Map<Long,MetricEntity>): MetricT1Input? {
            val preparation = metricInputPreparation(database) ?: return null
            val input = preparation.capture.metricPreparation?.input ?: return null
            return input.takeIf { it.matches(intent, logs, metrics) }
        }

        internal suspend fun beginIndependentMetricBatch(database: HabitDatabase, logs: List<MetricLogEntity>,
            metrics: Map<Long, MetricEntity>): MetricInsertTicket? {
            val preparation = currentCoroutineContext()[LocalPreparation] ?: return null
            if (preparation.kernel.database !== database) return null
            return preparation.kernel.prepareIndependentMetricBatch(preparation, logs, metrics)
        }
        internal suspend fun beginMetricBatch(database: HabitDatabase, logs: List<MetricLogEntity>,
            metrics: Map<Long, MetricEntity>, habit: HabitEntity, fact: WidgetFactClaim): MetricInsertTicket? {
            val preparation = currentCoroutineContext()[LocalPreparation] ?: return null
            if (preparation.kernel.database !== database) return null
            return preparation.kernel.prepareMetricBatch(preparation, logs, metrics, habit, fact)
        }
        internal suspend fun bindMetricBatch(database: HabitDatabase, ticket: MetricInsertTicket?, ids: List<Long>) {
            if (ticket == null) return
            val preparation = requireNotNull(currentCoroutineContext()[LocalPreparation]) { "METRIC_LOCAL_SCOPE_EXPIRED" }
            check(preparation.kernel.database === database && preparation.active &&
                preparation.caller === currentCoroutineContext()[Job] && database.inTransaction()) { "METRIC_LOCAL_SCOPE_EXPIRED" }
            val metric = requireNotNull(preparation.capture.metricPreparation)
            check(metric.ticket === ticket && metric.insertedIds == null) { "METRIC_LOCAL_TICKET_CHANGED" }
            check(ids.size == metric.uuids.size && ids.all { it > 0 } && ids.distinct().size == ids.size)
            metric.insertedIds = java.util.Collections.unmodifiableList(ids.toList())
        }
        private val countTypes = setOf(JsonPrimitive("count_delta"), JsonPrimitive("count_snapshot"))
        private val quantityTypes = setOf(JsonPrimitive("check_in"), JsonPrimitive("count_delta"), JsonPrimitive("count_snapshot"))
        private const val CHECK_COVERAGE_KEY = "check:source-coverage"
        private const val CHECK_COVERAGE = "all-retained-recurring-check/v1"
        private fun checkEligibleKey(uuid: String) = "check:eligible:$uuid"
        private fun eligibleKey(uuid: String) = "eligible:$uuid"
        private fun monthsKey(uuid: String, head: ChallengeRoundHead? = null) = CountTrustRoundEvidence.monthsKey(uuid, head)
        private fun summaryKey(uuid: String, month: YearMonth, head: ChallengeRoundHead? = null) = CountTrustRoundEvidence.summaryKey(uuid, month, head)
    }
}
