@file:kotlinx.serialization.UseSerializers(com.dayforge.domain.model.ContractStringSerializer::class)

package com.dayforge.data.export

import com.dayforge.data.appearance.ValidatedConfigBundle
import com.dayforge.data.appearance.AccountIconContext
import com.dayforge.data.appearance.strictAppearanceJson
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.model.FailMode
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.data.model.PlanStructureMetadata
import com.dayforge.data.repository.NextStructureMapper
import com.dayforge.domain.model.ConfigSchedule
import com.dayforge.domain.model.IconReference
import com.dayforge.domain.model.ObjectAppearance
import com.dayforge.domain.model.isContractUuid
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Persist this exact allocation at confirmation, before files or business writes. Never regenerate on retry. */
@Serializable
internal data class ConfigImportIdentities(val target: ConfigImportTarget, val archiveHash: String,
    val creationTimestamp: String, val ids: Map<String, String>) {
    fun encode(): String = Json.encodeToString(this).also { require(it.toByteArray(Charsets.UTF_8).size <= IDENTITY_LIMIT) }

    companion object {
        // At most 7,000 64-character object keys plus their original create-operation IDs.
        private const val IDENTITY_LIMIT = 2_097_152
        fun decode(bytes: ByteArray): ConfigImportIdentities = Json.decodeFromString(strictAppearanceJson(
            bytes, IDENTITY_LIMIT, {}) { error("CONFIG_IMPORT_IDENTITIES_$it") })
    }
}

/** Stored binding, not authorization. Every installation/commit still requires a fresh authenticated context. */
@Serializable
internal data class ConfigImportTarget(val accountId: String, val serverInstanceId: String,
    val syncEpoch: String, val deviceId: String) {
    init { require(listOf(accountId, serverInstanceId, syncEpoch, deviceId).all(::isContractUuid)) }
    companion object {
        fun from(context: AccountIconContext) = ConfigImportTarget(context.namespace.accountId,
            context.namespace.serverInstanceId, context.namespace.syncEpoch, context.access.deviceId)
    }
}

/**
 * Inverse of the file mapper, not an import receipt. Takes validated frozen input and one saved
 * allocation. No owner, revision, facts, timer session or source business identity is invented.
 * Callers must durably journal identities and atomically write rows/outbox; this cannot clear Room.
 */
internal class NextConfigImportPlan(val source: ValidatedConfigBundle, identities: ConfigImportIdentities) {
    private val manifest = source.manifest
    val identities = identities.copy(ids = Collections.unmodifiableMap(identities.ids.toMap()))
    private val instant = Instant.parse(identities.creationTimestamp)
    private val time = instant.toEpochMilli()

    init {
        require(identities.archiveHash == archiveHash(source)) { "CONFIG_IMPORT_SOURCE_CHANGED" }
        require(instant.toString() == identities.creationTimestamp && instant.atOffset(ZoneOffset.UTC).year in 1..9999)
        require(identities.ids.keys == keys(source) && identities.ids.values.all(::isContractUuid) &&
            identities.ids.values.distinct().size == identities.ids.size &&
            identities.ids.values.none { it in sourceIds(source) }) { "CONFIG_IMPORT_IDENTITIES_INVALID" }
        this.identities.encode()
    }

    private fun id(key: String) = identities.ids.getValue(key)
    fun requireTarget(context: AccountIconContext) {
        check(identities.target == ConfigImportTarget.from(context)) { "CONFIG_IMPORT_TARGET_CHANGED" }
    }
    val importId: String get() = id("import")
    val creationOperationIds = Collections.unmodifiableMap(buildMap {
        manifest.nodes.forEach { put(id("node:${it.key}"), id("operation:node:${it.key}")) }
        manifest.metrics.forEach { put(id("metric:${it.key}"), id("operation:metric:${it.key}")) }
        manifest.links.forEach { put(id("link:${it.key}"), id("operation:link:${it.key}")) }
    })
    private fun appearance(value: ObjectAppearance) = value.copy(icon = when (val icon = value.icon) {
        is IconReference.Role -> icon
        is IconReference.Asset -> IconReference.Asset(id("asset:${icon.assetId}"))
    })

    val iconPack = manifest.iconPack?.let { pack -> pack.copy(packId = id("pack"), revision = 1,
        assets = Collections.unmodifiableList(pack.assets.map { it.copy(assetId = id("asset:${it.assetId}")) }),
        roles = Collections.unmodifiableMap(pack.roles.mapValues { id("asset:${it.value}") }),
        placeholderAssetId = pack.placeholderAssetId?.let { id("asset:$it") }) }
    val themes = Collections.unmodifiableList(manifest.themes.map { theme ->
        theme.copy(themeId = id("theme:${theme.themeId}:${theme.revision}"), revision = 1)
    })

    /** Parent-first insert order, with original file order retained in sort metadata. */
    val habits = Collections.unmodifiableList(manifest.nodes.mapIndexed { index, node ->
        val goal = node.goal
        val activity = node.activity
        val schedule = when (val value = activity?.schedule) {
            null, is ConfigSchedule.Daily -> HabitSchedule.Daily
            is ConfigSchedule.Weekly -> HabitSchedule.Weekly(Collections.unmodifiableList(value.weekdays.toList()))
            is ConfigSchedule.Monthly -> HabitSchedule.Monthly(value.dayOfMonth)
            is ConfigSchedule.Interval -> HabitSchedule.Custom(value.everyDays)
            is ConfigSchedule.Once -> HabitSchedule.Once(value.dueDate)
        }
        val start = when (val value = activity?.schedule) {
            is ConfigSchedule.Daily -> value.startDate
            is ConfigSchedule.Weekly -> value.startDate
            is ConfigSchedule.Monthly -> value.startDate
            is ConfigSchedule.Interval -> value.startDate
            is ConfigSchedule.Once, null -> goal?.startDate
        }
        val type = when (activity?.trackingMode) {
            null -> HabitType.GOAL; "check" -> HabitType.CHECK_IN; "count" -> HabitType.COUNTING
            "duration" -> HabitType.TIMER; else -> error("CONFIG_IMPORT_TRACKING_INVALID")
        }
        val once = activity?.completionPolicy == "one_and_done"
        val visual = appearance(node.appearance)
        HabitEntity(uuid = id("node:${node.key}"), name = node.name, description = node.description,
            habitType = type, iconResId = 0, colorHex = visual.accentColor, schedule = schedule,
            targetValue = activity?.targetValue?.let { if (type == HabitType.TIMER) it / 60 else it } ?: 1,
            isCountdown = activity?.isCountdown ?: false, isActive = node.isActive,
            parentHabitId = node.parentKey?.let { id("node:$it") },
            targetCycles = activity?.targetCycles ?: goal?.targetCycles,
            failMode = if ((activity?.failMode ?: goal!!.failMode) == "strict") FailMode.STRICT else FailMode.LOOSE,
            bestTime = activity?.preferredMinute?.toLong(), createdAt = time, updatedAt = time,
            activityRateUpdatedAt = time, completionPolicy = activity?.completionPolicy,
            oneTimeConfirmedVersion = if (once) 0 else null, appearance = visual,
            planMetadata = PlanStructureMetadata(identities.creationTimestamp, index.toLong(), start, goal?.dueDate,
                activity?.timezone, if (type == HabitType.TIMER) "second" else null,
                activity?.preferredMinute?.let { LocalTime.of(it / 60, it % 60).toString() }, null))
            .also { NextStructureMapper.writePlan(it) }
    }.sortedBy { it.habitType != HabitType.GOAL })

    val metrics = Collections.unmodifiableList(manifest.metrics.map { row ->
        val visual = appearance(row.appearance)
        MetricEntity(name = row.name, description = row.description, unit = row.unit, decimalPlaces = row.decimalPlaces,
            aggregationType = row.aggregationType, targetDirection = row.targetDirection, targetValue = row.targetValue,
            targetValueUpper = row.targetValueUpper, iconResId = 0, colorHex = visual.accentColor, isActive = row.isActive,
            uuid = id("metric:${row.key}"), createdAt = time, updatedAt = time, appearance = visual)
            .also { NextStructureMapper.writeMetric(it) }
    })

    /** Actual AUTOINCREMENT results only; no reuse of source/local IDs or guessed missing endpoints. */
    fun links(habitIds: Map<String, Long>, metricIds: Map<String, Long>): List<HabitMetricLinkEntity> {
        require(habitIds.keys == habits.map { it.uuid }.toSet() && metricIds.keys == metrics.map { it.uuid }.toSet())
        for (values in listOf(habitIds.values, metricIds.values)) require(values.all { it > 0 } && values.distinct().size == values.size)
        return Collections.unmodifiableList(manifest.links.map { row ->
            val habit = id("node:${row.activityKey}"); val metric = id("metric:${row.metricKey}")
            HabitMetricLinkEntity(uuid = id("link:${row.key}"), habitId = habitIds.getValue(habit), habitUuid = habit,
                metricId = metricIds.getValue(metric), metricUuid = metric, coefficient = row.coefficient,
                showInHabitDetail = row.showInDetail, promptOnComplete = row.promptOnComplete, isActive = row.isActive,
                createdAt = time, updatedAt = time)
        })
    }

    companion object {
        /** Explicit confirmation only. A new import uses a new allocation; exact retry restores the saved one. */
        fun allocate(source: ValidatedConfigBundle, target: ConfigImportTarget, instant: Instant,
            newId: () -> String = { UUID.randomUUID().toString() }): ConfigImportIdentities =
            ConfigImportIdentities(target, archiveHash(source), instant.toString(), keys(source).associateWith { newId() }).also {
                NextConfigImportPlan(source, it)
            }

        private fun archiveHash(source: ValidatedConfigBundle) = MessageDigest.getInstance("SHA-256")
            .digest(source.exportBytes()).joinToString("") { "%02x".format(it) }

        private fun keys(source: ValidatedConfigBundle): Set<String> = with(source.manifest) { buildSet {
            add("import")
            nodes.forEach { add("node:${it.key}"); add("operation:node:${it.key}") }
            metrics.forEach { add("metric:${it.key}"); add("operation:metric:${it.key}") }
            links.forEach { add("link:${it.key}"); add("operation:link:${it.key}") }
            iconPack?.let { pack -> add("pack"); pack.assets.forEach { add("asset:${it.assetId}") } }
            themes.forEach { add("theme:${it.themeId}:${it.revision}") }
        } }
        private fun sourceIds(source: ValidatedConfigBundle): Set<String> = with(source.manifest) { buildSet {
            addAll(nodes.map { it.key }); addAll(metrics.map { it.key }); addAll(links.map { it.key })
            iconPack?.let { pack -> add(pack.packId); addAll(pack.assets.map { it.assetId }) }
            addAll(themes.map { it.themeId })
        } }
    }
}
