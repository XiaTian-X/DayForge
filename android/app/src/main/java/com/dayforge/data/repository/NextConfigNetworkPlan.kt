@file:kotlinx.serialization.UseSerializers(com.dayforge.domain.model.ContractStringSerializer::class,
    com.dayforge.domain.model.ContractLongSerializer::class, com.dayforge.domain.model.ContractIntegerSerializer::class)

package com.dayforge.data.repository

import com.dayforge.data.appearance.strictAppearanceJson
import com.dayforge.data.export.ConfigImportTarget
import com.dayforge.data.export.NextConfigImportPlan
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.model.HabitType
import com.dayforge.data.api.dto.SyncV2Operation
import com.dayforge.domain.model.isContractUuid
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Frozen NEW original work only. Small field names keep the full graph in the existing 2MiB budget. */
@Serializable
internal data class ConfigNetworkStep(@SerialName("id") val operationId: String,
    @SerialName("u") val entityUuid: String, @SerialName("t") val entityType: String,
    @SerialName("a") val action: String, @SerialName("s") val phase: Int,
    @SerialName("r") val baseRevision: Long?, @SerialName("h") val payloadHash: String) {
    init {
        require(isContractUuid(operationId) && isContractUuid(entityUuid) && operationId != entityUuid)
        require(payloadHash.matches(Regex("[0-9a-f]{64}")))
        require(if (action == "delete") baseRevision != null && baseRevision > 0 && when (entityType) {
            "activity_metric_link" -> phase == 0; "metric" -> phase == 1; "plan_node" -> phase in 1..2; else -> false
        } else action == "upsert" && baseRevision == null && when (entityType) {
            "activity_metric_link" -> phase == 5; "metric" -> phase == 3; "plan_node" -> phase in 3..4; else -> false
        })
    }
    fun requireOperation(operation: SyncV2Operation) {
        require(operation.operationId == operationId && operation.entityUuid == entityUuid &&
            operation.entityType == entityType && operation.action == action && operation.baseRevision == baseRevision &&
            hashPayload(operation.payload) == payloadHash) { "CONFIG_IMPORT_OPERATION_CHANGED" }
    }
    companion object {
        fun hashPayload(payload: JsonObject) = nextRequestHash(payload.toString().toByteArray(Charsets.UTF_8))
    }
}

@Serializable
internal data class ConfigNetworkPlan(val importId: String, val target: ConfigImportTarget,
    val fingerprint: String, val steps: List<ConfigNetworkStep>) {
    init {
        require(isContractUuid(importId) && fingerprint.matches(Regex("[0-9a-f]{64}")))
        require(steps.size <= 10_000 && steps.map { it.operationId }.distinct().size == steps.size &&
            steps.map { it.entityUuid }.distinct().size == steps.size &&
            steps.map { it.operationId }.toSet().intersect(steps.map { it.entityUuid }.toSet()).isEmpty() &&
            steps.none { it.operationId == importId || it.entityUuid == importId })
        require(steps.map { it.phase } == steps.map { it.phase }.sorted())
    }
    fun encode(): ByteArray = Json.encodeToString(this).toByteArray(Charsets.UTF_8).also {
        require(it.size <= LIMIT) { "CONFIG_IMPORT_NETWORK_CAPACITY" }
    }
    fun requirePlan(plan: NextConfigImportPlan) {
        require(importId == plan.importId && target == plan.identities.target)
        require(steps.filter { it.action == "upsert" } == creations(plan)) { "CONFIG_IMPORT_NETWORK_PLAN_CHANGED" }
        require(steps.filter { it.action == "delete" }.all {
            it.entityUuid !in plan.identities.ids.values && it.operationId !in plan.identities.ids.values
        })
    }
    companion object {
        const val LIMIT = 2_097_152
        fun decode(bytes: ByteArray): ConfigNetworkPlan = Json.decodeFromString(strictAppearanceJson(bytes, LIMIT, {}) {
            error("CONFIG_IMPORT_NETWORK_$it")
        })
        private fun creations(plan: NextConfigImportPlan): List<ConfigNetworkStep> {
            val habits = plan.habits.map { it.uuid to (it.habitType == HabitType.GOAL) }.toMap()
            val bodies = plan.metrics.map { it.uuid to ("metric" to NextStructureMapper.writeMetric(it)) } +
                plan.habits.map { it.uuid to ("plan_node" to NextStructureMapper.writePlan(it)) } +
                plan.links(plan.habits.mapIndexed { i, row -> row.uuid to i.toLong() + 1 }.toMap(),
                    plan.metrics.mapIndexed { i, row -> row.uuid to i.toLong() + 1 }.toMap())
                    .map { it.uuid to ("activity_metric_link" to SyncV2Mapper.link(it)) }
            return bodies.map { (uuid, value) -> ConfigNetworkStep(plan.creationOperationIds.getValue(uuid), uuid,
                value.first, "upsert", if (value.first == "activity_metric_link") 5 else
                if (value.first == "plan_node" && habits[uuid] != true) 4 else 3, null,
                ConfigNetworkStep.hashPayload(value.second)) }.sortedBy { it.phase }
        }
        suspend fun capture(database: HabitDatabase, plan: NextConfigImportPlan, fingerprint: String): ConfigNetworkPlan {
            check(database.inTransaction())
            val old = database.habitDao().getAllHabitsOnce().associateBy { it.uuid }
            val values = database.habitMetricLinkDao().getAllLinksOnce().map {
                Triple(it.uuid, "activity_metric_link", 0)
            } + database.metricDao().getAllMetricsOnce().map { Triple(it.uuid, "metric", 1) } +
                old.values.sortedBy { it.uuid }.map { Triple(it.uuid, "plan_node", if (it.habitType == HabitType.GOAL) 2 else 1) }
            val deletes = values.map { (uuid, type, phase) ->
                requireNotNull(NextRequestSql.rowHash(database.openHelper.writableDatabase, "sync_entity_state",
                    "entityType=? AND entityUuid=?", arrayOf(type, uuid)))
                val state = requireNotNull(database.syncOutboxDao().getState(type, uuid)); require(!state.deleted && state.revision > 0)
                val payload = buildJsonObject { if (phase == 2) put("child_policy", "cascade_children") }
                ConfigNetworkStep(UUID.randomUUID().toString(), uuid, type, "delete", phase, state.revision,
                    ConfigNetworkStep.hashPayload(payload))
            }.sortedBy { it.phase }
            return ConfigNetworkPlan(plan.importId, plan.identities.target, fingerprint, deletes + creations(plan)).also {
                it.requirePlan(plan); it.encode()
            }
        }
    }
}

@Serializable
internal data class ConfigNetworkReceipt(val networkHash: String, val sources: Map<String, ConfigImportedSource>,
    val acceptanceHash: String? = null) {
    init { require(networkHash.matches(Regex("[0-9a-f]{64}")) &&
        (acceptanceHash == null || acceptanceHash.matches(Regex("[0-9a-f]{64}")))) }
}
