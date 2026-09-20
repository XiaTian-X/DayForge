package com.dayforge.data.api

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.runner.RunWith
import com.dayforge.data.api.dto.ServerIdentityResponse
import com.dayforge.data.api.dto.SyncV2BootstrapResponse
import com.dayforge.data.api.dto.SyncV2PullResponse
import com.dayforge.data.api.dto.SyncV2PushRequest
import com.dayforge.data.api.dto.SyncV2PushResponse
import com.dayforge.data.api.dto.TimerCommandBatchRequest
import com.dayforge.data.api.dto.TimerCommandBatchResponse
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keeps Android's wire DTOs executable against the repository-level Sync V2 fixtures. */
@RunWith(AndroidJUnit4::class)
class SyncV2ContractFixtureTest {
    private val json = Json { ignoreUnknownKeys = false }

    @Test
    fun all_valid_client_push_fixtures_round_trip_without_wire_drift() {
        listOf(
            "client/push-all-entities.json",
            "client/delete-goal.json",
            "client/conflict-create.json",
            "client/conflict-remote.json",
            "client/conflict-stale.json",
            "client/account-a.json",
            "client/account-b.json"
        ).forEach { path -> assertExactRoundTrip<SyncV2PushRequest>(path) }
    }

    @Test
    fun ordered_timer_commands_round_trip_without_losing_command_preconditions() {
        val request = assertExactRoundTrip<TimerCommandBatchRequest>(
            "client/timer-commands.json"
        )

        assertEquals(listOf("start", "pause", "resume", "stop"), request.commands.map { it.commandType })
        assertEquals(listOf(1, 2, 3, 4), request.commands.map { it.sequence })
        assertEquals(60_000L, request.commands.last().activeElapsedMs)
    }

    @Test
    fun all_canonical_server_fixtures_decode_and_round_trip() {
        assertExactRoundTrip<SyncV2BootstrapResponse>("server/bootstrap-response.json")
        val pull = assertExactRoundTrip<SyncV2PullResponse>(
            "server/pull-with-tombstone-response.json"
        )
        assertExactRoundTrip<SyncV2PushResponse>("server/push-applied-response.json")
        assertExactRoundTrip<SyncV2PushResponse>("server/conflict-response.json")
        assertExactRoundTrip<TimerCommandBatchResponse>("server/timer-commands-response.json")

        assertTrue(pull.changes.any { it.operation == "delete" })
        assertEquals("2025-12-31", pull.changes.first().payload["local_date"]?.toString()?.trim('"'))
    }

    @Test
    fun epoch_transition_fixtures_keep_server_identity_but_change_epoch() {
        val before = assertExactRoundTrip<ServerIdentityResponse>(
            "server/identity-before-response.json"
        )
        val after = assertExactRoundTrip<ServerIdentityResponse>(
            "server/identity-after-epoch-reset-response.json"
        )

        assertEquals(before.serverInstanceId, after.serverInstanceId)
        assertNotEquals(before.syncEpoch, after.syncEpoch)
        assertEquals(4, after.protocolVersion)
    }

    @Test
    fun malformed_client_fixture_is_rejected_before_it_can_be_sent() {
        val failure = runCatching {
            json.decodeFromString<SyncV2PushRequest>(
                fixture("invalid/push-missing-operation-id.json")
            )
        }.exceptionOrNull()

        assertTrue(failure is SerializationException)
    }

    private inline fun <reified T> assertExactRoundTrip(path: String): T {
        val source = fixture(path)
        val decoded = json.decodeFromString<T>(source)
        val encoded = json.encodeToString(decoded)
        assertEquals(
            "Fixture changed during Android serialization: $path",
            json.parseToJsonElement(source),
            json.parseToJsonElement(encoded)
        )
        assertEquals(decoded, json.decodeFromString<T>(encoded))
        return decoded
    }

    private fun fixture(path: String): String =
        InstrumentationRegistry.getInstrumentation()
            .context.assets.open("sync-v2/$path").bufferedReader().use { it.readText() }
}
