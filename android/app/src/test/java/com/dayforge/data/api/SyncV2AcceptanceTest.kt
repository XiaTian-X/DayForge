package com.dayforge.data.api

import com.dayforge.data.api.dto.DeviceRegisterRequest
import com.dayforge.data.api.dto.SyncV2BootstrapResponse
import com.dayforge.data.api.dto.SyncV2Operation
import com.dayforge.data.api.dto.SyncV2PushRequest
import com.dayforge.data.api.dto.SyncV2PushResponse
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks Android serialization to the backend's Sync V2 wire format. */
class SyncV2AcceptanceTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `android device registration always includes required platform`() {
        val request = DeviceRegisterRequest(
            installationId = "android-installation",
            protocolVersion = 4,
            platform = "android",
            deviceClass = "interactive",
            appVersion = "2.0",
            displayName = "Acceptance phone"
        )

        val encoded = json.parseToJsonElement(json.encodeToString(request)).jsonObject

        assertEquals("android-installation", encoded["installation_id"]?.jsonPrimitive?.content)
        assertEquals("4", encoded["protocol_version"]?.jsonPrimitive?.content)
        assertEquals("android", encoded["platform"]?.jsonPrimitive?.content)
        assertEquals("interactive", encoded["device_class"]?.jsonPrimitive?.content)
        assertEquals("2.0", encoded["app_version"]?.jsonPrimitive?.content)
    }

    @Test
    fun `android decodes a canonical backend bootstrap snapshot`() {
        val body = """
            {
              "changes": [{
                "sequence": 0,
                "entity_type": "plan_node",
                "entity_uuid": "11111111-1111-4111-8111-111111111111",
                "operation": "upsert",
                "revision": 3,
                "payload": {
                  "node_kind": "activity",
                  "title": "Drink water",
                  "parent_uuid": "22222222-2222-4222-8222-222222222222",
                  "activity": {
                    "tracking_mode": "check",
                    "recurrence_rule": {"schema_version": 1, "type": "daily", "interval": 1}
                  }
                },
                "changed_at": "2026-08-10T00:00:00Z",
                "origin_device_id": null
              }],
              "next_cursor": 27,
              "server_time": "2026-08-10T00:00:01Z"
            }
        """.trimIndent()

        val snapshot = json.decodeFromString<SyncV2BootstrapResponse>(body)

        assertEquals(27, snapshot.nextCursor)
        assertEquals("plan_node", snapshot.changes.single().entityType)
        assertEquals(3, snapshot.changes.single().revision)
        assertEquals(
            "check",
            snapshot.changes.single().payload["activity"]?.jsonObject
                ?.get("tracking_mode")?.jsonPrimitive?.content
        )
    }

    @Test
    fun `android encodes offline operations with backend snake case fields`() {
        val request = SyncV2PushRequest(
            deviceId = "33333333-3333-4333-8333-333333333333",
            operations = listOf(
                SyncV2Operation(
                    operationId = "44444444-4444-4444-8444-444444444444",
                    entityType = "plan_node",
                    entityUuid = "55555555-5555-4555-8555-555555555555",
                    action = "upsert",
                    baseRevision = null,
                    payload = buildJsonObject {
                        put("node_kind", "goal")
                        put("title", "Health")
                    }
                )
            )
        )

        val encoded = json.parseToJsonElement(json.encodeToString(request)).jsonObject
        val operation = encoded["operations"]!!.jsonObjectOrFirstArrayElement()

        assertTrue("device_id" in encoded)
        assertFalse("deviceId" in encoded)
        assertEquals("plan_node", operation["entity_type"]?.jsonPrimitive?.content)
        assertTrue("operation_id" in operation)
        // Null base_revision may be omitted; the backend schema defaults it to null.
        assertFalse("baseRevision" in operation)
    }

    @Test
    fun `android decodes missing base snapshot as a recoverable conflict`() {
        val body = """
            {
              "results": [{
                "operation_id": "44444444-4444-4444-8444-444444444444",
                "entity_type": "plan_node",
                "entity_uuid": "55555555-5555-4555-8555-555555555555",
                "status": "conflict",
                "revision": 3,
                "error_code": "BASE_SNAPSHOT_UNAVAILABLE",
                "message": "The edit base is no longer available for a safe merge",
                "entity": {"node_kind": "goal", "title": "Server title"},
                "local_entity": {"node_kind": "goal", "title": "Local title"},
                "conflicting_fields": [],
                "conflict_kind": "base_snapshot_unavailable"
              }]
            }
        """.trimIndent()

        val result = json.decodeFromString<SyncV2PushResponse>(body).results.single()

        assertEquals("conflict", result.status)
        assertEquals("BASE_SNAPSHOT_UNAVAILABLE", result.errorCode)
        assertEquals("base_snapshot_unavailable", result.conflictKind)
        assertEquals("Server title", result.entity?.get("title")?.jsonPrimitive?.content)
        assertEquals("Local title", result.localEntity?.get("title")?.jsonPrimitive?.content)
    }

    private fun kotlinx.serialization.json.JsonElement.jsonObjectOrFirstArrayElement() =
        (this as kotlinx.serialization.json.JsonArray).first().jsonObject
}
