package com.dayforge.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class DeviceRegisterRequest(
    @SerialName("installation_id") val installationId: String,
    @SerialName("protocol_version") val protocolVersion: Int,
    val platform: String,
    @SerialName("device_class") val deviceClass: String,
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("display_name") val displayName: String? = null
)

@Serializable
data class DeviceResponse(
    @SerialName("device_id") val deviceId: String,
    @SerialName("installation_id") val installationId: String,
    val platform: String,
    @SerialName("device_class") val deviceClass: String = "interactive",
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("is_primary_editor") val isPrimaryEditor: Boolean = false,
    @SerialName("structural_edit_enabled") val structuralEditEnabled: Boolean = false,
    @SerialName("capability_revision") val capabilityRevision: Int = 1,
    val capabilities: List<String> = emptyList(),
    @SerialName("last_seen_at") val lastSeenAt: String? = null
)

@Serializable
data class DeviceEditingUpdate(
    @SerialName("structural_edit_enabled") val structuralEditEnabled: Boolean
)

@Serializable
data class ServerIdentityResponse(
    @SerialName("server_instance_id") val serverInstanceId: String,
    @SerialName("sync_epoch") val syncEpoch: String,
    @SerialName("protocol_version") val protocolVersion: Int,
    val capabilities: List<String>,
    @SerialName("server_time") val serverTime: String
)

@Serializable
data class TimerCommandRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("session_id") val sessionId: String,
    val sequence: Int,
    @SerialName("command_type") val commandType: String,
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("expected_control_generation") val expectedControlGeneration: Int,
    @SerialName("expected_revision") val expectedRevision: Int? = null,
    @SerialName("activity_uuid") val activityUuid: String? = null,
    val timezone: String? = null,
    @SerialName("active_elapsed_ms") val activeElapsedMs: Long? = null
)

@Serializable
data class TimerCommandBatchRequest(
    @SerialName("device_id") val deviceId: String,
    val commands: List<TimerCommandRequest>
)

@Serializable
data class TimerSessionResponse(
    @SerialName("session_id") val sessionId: String,
    @SerialName("activity_uuid") val activityUuid: String,
    val state: String,
    @SerialName("controller_device_id") val controllerDeviceId: String,
    @SerialName("control_generation") val controlGeneration: Int,
    val revision: Int,
    @SerialName("next_command_sequence") val nextCommandSequence: Int,
    @SerialName("started_at") val startedAt: String,
    @SerialName("state_changed_at") val stateChangedAt: String,
    @SerialName("ended_at") val endedAt: String? = null,
    val timezone: String,
    @SerialName("is_countdown") val isCountdown: Boolean,
    @SerialName("target_seconds") val targetSeconds: Int,
    @SerialName("max_duration_seconds") val maxDurationSeconds: Int,
    @SerialName("active_elapsed_ms") val activeElapsedMs: Long,
    @SerialName("last_heartbeat_at") val lastHeartbeatAt: String? = null,
    @SerialName("completed_event_id") val completedEventId: String? = null
)

@Serializable
data class TimerCommandResult(
    @SerialName("command_id") val commandId: String,
    @SerialName("session_id") val sessionId: String,
    val status: String,
    @SerialName("error_code") val errorCode: String? = null,
    val message: String? = null,
    val session: TimerSessionResponse? = null
)

@Serializable
data class TimerCommandBatchResponse(
    val results: List<TimerCommandResult>,
    @SerialName("server_time") val serverTime: String
)

@Serializable
data class TimerStatusResponse(
    val session: TimerSessionResponse? = null,
    @SerialName("server_time") val serverTime: String
)

@Serializable
data class TimerHeartbeatRequest(
    @SerialName("device_id") val deviceId: String,
    @SerialName("control_generation") val controlGeneration: Int
)

@Serializable
data class TimerHeartbeatResponse(
    val accepted: Boolean,
    val session: TimerSessionResponse,
    @SerialName("server_time") val serverTime: String
)

@Serializable
data class SyncV2Operation(
    @SerialName("operation_id") val operationId: String,
    @SerialName("entity_type") val entityType: String,
    @SerialName("entity_uuid") val entityUuid: String,
    val action: String,
    @SerialName("base_revision") val baseRevision: Long? = null,
    val payload: JsonObject
)

@Serializable
data class SyncV2PushRequest(
    @SerialName("device_id") val deviceId: String,
    val operations: List<SyncV2Operation>
)

@Serializable
data class SyncV2OperationResult(
    @SerialName("operation_id") val operationId: String,
    @SerialName("entity_type") val entityType: String,
    @SerialName("entity_uuid") val entityUuid: String,
    val status: String,
    val revision: Long? = null,
    @SerialName("error_code") val errorCode: String? = null,
    val message: String? = null,
    val entity: JsonObject? = null,
    @SerialName("base_entity") val baseEntity: JsonObject? = null,
    @SerialName("local_entity") val localEntity: JsonObject? = null,
    @SerialName("conflicting_fields") val conflictingFields: List<String> = emptyList(),
    @SerialName("conflict_kind") val conflictKind: String? = null
)

@Serializable
data class SyncV2PushResponse(val results: List<SyncV2OperationResult>)

@Serializable
data class SyncV2Change(
    val sequence: Long,
    @SerialName("entity_type") val entityType: String,
    @SerialName("entity_uuid") val entityUuid: String,
    val operation: String,
    val revision: Long,
    val payload: JsonObject,
    @SerialName("changed_at") val changedAt: String,
    @SerialName("origin_device_id") val originDeviceId: String? = null
)

@Serializable
data class SyncV2PullResponse(
    val changes: List<SyncV2Change>,
    @SerialName("next_cursor") val nextCursor: Long,
    @SerialName("has_more") val hasMore: Boolean,
    @SerialName("server_time") val serverTime: String
)

@Serializable
data class SyncV2BootstrapResponse(
    val changes: List<SyncV2Change>,
    @SerialName("next_cursor") val nextCursor: Long,
    @SerialName("server_time") val serverTime: String
)
