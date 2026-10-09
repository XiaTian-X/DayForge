package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.appearance.AccountConfigExportPreview
import com.dayforge.data.appearance.AccountIconContext
import com.dayforge.data.appearance.ConfigIconUse
import com.dayforge.data.appearance.ValidatedConfigBundle
import com.dayforge.data.export.NextConfigMapper
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.domain.appearance.ThemeVersionRef
import com.dayforge.domain.model.OneTimeState
import com.dayforge.domain.model.isContractUuid
import com.dayforge.domain.model.ThemeDefinition
import com.dayforge.data.appearance.ThemeCatalogContent
import com.dayforge.domain.service.AccountIconController
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.domain.service.DeviceThemeController
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.debounce

/** Real account-bound template read. No business, outbox, preference, import or selection writes. */
@Singleton
internal class NextConfigExportRepository @Inject constructor(
    private val database: HabitDatabase,
    private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator,
    private val preferences: PreferencesManager,
    private val icons: AccountIconController,
    private val themes: DeviceThemeController
) {
    /** Evidence selects a transport, never grants authority or activates the next protocol. */
    suspend fun usesNextReplica(): Boolean = withContext(Dispatchers.IO) { sessions.exclusive {
        database.withTransaction {
            database.syncOutboxDao().hasProtocolNextRecovery() || database.syncOutboxDao().hasProtocolNextRequests() ||
                database.openHelper.readableDatabase.query("SELECT 1 FROM habits WHERE appearance IS NOT NULL " +
                    "UNION ALL SELECT 1 FROM metrics WHERE appearance IS NOT NULL LIMIT 1").use { it.moveToFirst() }
        }
    } }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    fun changes() = combine(tokens.iconAccessChanges, database.invalidationTracker.createFlow(
        "next_config_imports", "next_acceptances", "next_sync_state", "next_request_origins", "habits", "metrics").debounce(250)) {
        access, _ -> access
    }.map { Unit }

    internal data class Snapshot(
        val habits: List<HabitEntity>, val metrics: List<MetricEntity>, val links: List<HabitMetricLinkEntity>,
        val states: Map<String, OneTimeState>
    )

    internal class Options internal constructor(val context: AccountIconContext,
        internal val snapshot: Snapshot, val themes: List<ThemeDefinition>) {
        val completedItems = Collections.unmodifiableList(snapshot.habits.filter {
            snapshot.states[it.uuid]?.completionEventUuid != null
        }.map { it.uuid to it.name })
    }

    /** Frozen business/definition choices; no identities, history or preference writes. */
    suspend fun options(): Options = withContext(Dispatchers.IO) {
        val context = icons.capture()
        val snapshot = sessions.exclusive { database.withTransaction {
            check(tokens.localIconAccess() == context.access) { "CONFIG_SESSION_CHANGED" }
            capture(context, requireNotNull(tokens.localSyncAccess()), emptySet(), includeCompleted = true)
        } }
        val definitions = themes.library().items.mapNotNull { (it.content as? ThemeCatalogContent.Available)?.definition }
        check(tokens.localIconAccess() == context.access) { "CONFIG_SESSION_CHANGED" }
        Options(context, snapshot, Collections.unmodifiableList(definitions))
    }

    /** Selection only narrows the original capture. It never rereads later business or themes. */
    suspend fun prepare(options: Options, completedItems: Set<String>, themeRefs: Set<ThemeVersionRef>): AccountConfigExportPreview =
        withContext(Dispatchers.IO) {
            val selected = completedItems.toSet(); val selectedThemes = themeRefs.toSet()
            require(selected.all { id -> options.completedItems.any { it.first == id } }) { "CONFIG_TEMPLATE_NOT_AVAILABLE" }
            require(selectedThemes.size <= 16 && selectedThemes.all { ref ->
                options.themes.any { it.themeId == ref.themeId && it.revision == ref.revision }
            }) { "CONFIG_THEME_NOT_AVAILABLE" }
            val original = options.snapshot
            val nodes = original.habits.filter { it.uuid in selected || original.states[it.uuid]?.completionEventUuid == null }
            val ids = nodes.map { it.uuid }.toSet()
            val filtered = Snapshot(nodes, original.metrics, original.links.filter { it.habitUuid in ids }, original.states.filterKeys { it in ids })
            freeze(options.context, filtered, selected, options.themes.filter { ThemeVersionRef(it.themeId, it.revision) in selectedThemes })
        }

    suspend fun prepare(completedItemTemplateIds: Set<String> = emptySet(),
        themeRefs: List<ThemeVersionRef> = emptyList()): AccountConfigExportPreview = withContext(Dispatchers.IO) {
        require(completedItemTemplateIds.size <= 1000 && completedItemTemplateIds.all(::isContractUuid))
        require(themeRefs.size <= 16 && themeRefs.distinct().size == themeRefs.size)
        val selected = completedItemTemplateIds.toSet()
        val requestedThemes = themeRefs.toList()
        val context = icons.capture()
        val snapshot = sessions.exclusive {
            check(tokens.localIconAccess() == context.access) { "CONFIG_SESSION_CHANGED" }
            val access = requireNotNull(tokens.localSyncAccess()) { "CONFIG_ACCESS_DENIED" }
            database.withTransaction {
                val captured = capture(context, access, selected)
                check(tokens.localIconAccess() == context.access) { "CONFIG_SESSION_CHANGED" }
                captured
            }
        }
        // Neither account lock nor business Room transaction spans theme/image/provider I/O.
        val definitions = requestedThemes.map { themes.export(it).definition }
        freeze(context, snapshot, selected, definitions)
    }

    private suspend fun freeze(context: AccountIconContext, snapshot: Snapshot, selected: Set<String>,
        definitions: List<ThemeDefinition>): AccountConfigExportPreview {
        require(snapshot.habits.size <= 1000 && snapshot.metrics.size <= 1000 && snapshot.links.size <= 5000) { "CONFIG_OBJECT_LIMIT" }
        val uses = snapshot.habits.map { ConfigIconUse(requireNotNull(it.appearance).icon,
            it.completionPolicy == "one_and_done") } +
            snapshot.metrics.map { ConfigIconUse(requireNotNull(it.appearance).icon, false) }
        return icons.prepareConfigExport(context, uses) { pack, missing ->
            NextConfigMapper.bundle(snapshot.habits, snapshot.metrics, snapshot.links, pack, missing,
                definitions, selected, snapshot.states)
        }
    }

    /** Frozen capture-point result, not a new live DB read; old-account publication is rejected. */
    suspend fun <T> publish(preview: AccountConfigExportPreview, block: (ValidatedConfigBundle) -> T): T =
        icons.publishConfigExport(preview, block)

    private suspend fun capture(context: AccountIconContext, access: LocalSyncAccess,
        selected: Set<String>, includeCompleted: Boolean = false): Snapshot {
        check(database.inTransaction() && access.session == context.access.session && access.deviceId == context.access.deviceId &&
            access.capabilityRevision == context.access.capabilityRevision) { "CONFIG_SESSION_CHANGED" }
        val sql = database.openHelper.writableDatabase
        NextRequestSql.rowHash(sql, "next_sync_state", "1=1", emptyArray())
        val cursor = database.nextSyncStateDao().rows().let { require(it.size <= 1); it.singleOrNull() }
        if (cursor != null) check(cursor.accountId == context.namespace.accountId &&
            cursor.serverInstanceId == context.namespace.serverInstanceId && cursor.syncEpoch == context.namespace.syncEpoch &&
            cursor.deviceId == context.access.deviceId) { "CONFIG_REPLICA_CHANGED" }
        NextChallengeStore(database).readInTransaction(access, cursor)
        val deleted = pendingDeletes(access)
        val allHabits = database.habitDao().getAllHabitsOnce()
        val allMetrics = database.metricDao().getAllMetricsOnce()
        require(allHabits.map { it.uuid }.distinct().size == allHabits.size &&
            allMetrics.map { it.uuid }.distinct().size == allMetrics.size)
        val visible = allHabits.filter { it.uuid !in deleted.getValue("habit") }
        val onceIds = visible.filter { it.completionPolicy == "one_and_done" }.map { it.uuid }.toSet()
        require(selected.all { it in onceIds }) { "CONFIG_TEMPLATE_NOT_AVAILABLE" }
        val intents = OneTimeLocalIntentStore(database, tokens, sessions, preferences)
        val states = visible.filter { it.completionPolicy == "one_and_done" }.associate { row ->
            currentCoroutineContext().ensureActive()
            row.uuid to intents.readInTransaction(row.uuid, access.session).queue.optimisticState
        }
        val habits = visible.filter { includeCompleted || it.uuid in selected || states[it.uuid]?.completionEventUuid == null }.map { row ->
            val schedule = row.schedule
            if (schedule is HabitSchedule.Weekly) row.copy(schedule = schedule.copy(
                daysOfWeek = Collections.unmodifiableList(schedule.daysOfWeek.toList()))) else row
        }
        val metrics = allMetrics.filter { it.uuid !in deleted.getValue("metric") }
        val sourceHabits = allHabits.associateBy { it.uuid }
        val sourceMetrics = allMetrics.associateBy { it.uuid }
        val habitIds = habits.map { it.uuid }.toSet()
        val metricIds = metrics.map { it.uuid }.toSet()
        val links = database.habitMetricLinkDao().getAllLinksOnce().filter { it.uuid !in deleted.getValue("link") }.filter { row ->
            val habit = requireNotNull(sourceHabits[row.habitUuid]) { "CONFIG_LINK_ACTIVITY_MISSING" }
            val metric = requireNotNull(sourceMetrics[row.metricUuid]) { "CONFIG_LINK_METRIC_MISSING" }
            require(row.habitId == habit.id && row.metricId == metric.id && habit.habitType != HabitType.GOAL) {
                "CONFIG_LINK_IDENTITY_CHANGED"
            }
            row.habitUuid in habitIds && row.metricUuid in metricIds
        }
        if (!includeCompleted) require(habits.size <= 1000 && metrics.size <= 1000 && links.size <= 5000) { "CONFIG_OBJECT_LIMIT" }
        return Snapshot(Collections.unmodifiableList(habits), Collections.unmodifiableList(metrics),
            Collections.unmodifiableList(links), Collections.unmodifiableMap(states.filterKeys { it in habitIds }))
    }

    /** Retained v5 delete rows are invisible before ACK; legacy work is never relabelled or consumed. */
    private suspend fun pendingDeletes(access: LocalSyncAccess): Map<String, Set<String>> {
        val sql = database.openHelper.writableDatabase
        val ids = sql.query("SELECT q.id,o.requestId FROM sync_outbox q JOIN next_request_origins o ON " +
            "o.kind='sync_operation' AND o.requestId=q.operationId AND o.queueId=q.id AND o.protocol=5 " +
            "WHERE q.action='delete' AND q.recordType IN ('habit','metric','link') ORDER BY q.id").use { raw ->
            buildList { while (raw.moveToNext()) {
                require(raw.getType(0) == android.database.Cursor.FIELD_TYPE_INTEGER && raw.getLong(0) > 0 &&
                    raw.getType(1) == android.database.Cursor.FIELD_TYPE_STRING)
                add(raw.getLong(0) to raw.getString(1))
            } }
        }
        val deleted = mapOf<String, MutableSet<String>>("habit" to mutableSetOf(), "metric" to mutableSetOf(), "link" to mutableSetOf())
        for ((id, request) in ids) {
            requireNotNull(NextRequestSql.rowHash(sql, "next_request_origins", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, request)))
            requireNotNull(NextRequestSql.rowHash(sql, "sync_outbox", "id=?", arrayOf(id)))
            val origin = requireNotNull(database.nextRequestDao().origin(NEXT_OPERATION, request))
            check(origin.accountId == access.session.authentication.userId &&
                (origin.serverInstanceId == null && origin.syncEpoch == null ||
                    origin.serverInstanceId == access.session.serverInstanceId && origin.syncEpoch == access.session.syncEpoch)) {
                "CONFIG_DELETE_CONTEXT_CHANGED"
            }
            val row = requireNotNull(database.syncOutboxDao().getById(id))
            val operation = decodeNextOperationIntent(origin.intentJson)
            val type = when (row.recordType) { "habit" -> "plan_node"; "metric" -> "metric"; "link" -> "activity_metric_link"
                else -> error("Invalid delete marker") }
            require(operation.operationId == request && operation.entityType == type && operation.entityUuid == row.entityUuid &&
                operation.action == "delete" && NextRequestSql.sourceHash(row.copy(
                    deadLetteredAt = null, errorCode = null, lastError = null)) == origin.sourceHash) { "CONFIG_DELETE_SOURCE_CHANGED" }
            deleted.getValue(row.recordType).add(row.entityUuid)
        }
        return deleted
    }
}
