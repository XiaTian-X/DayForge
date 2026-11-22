package com.dayforge.data.local

import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.repository.*
import com.dayforge.domain.model.*
import java.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*

/** A new-epoch read-only peer. The donor itself remains the unchanged real bootstrap peer.
 * No database handle, root, current-mode seed, acceptance or production admission override. */
internal class RetainedReplicaPreviewPeer(donor: RetainedJourneyServer, private val installation: String) : AutoCloseable {
    private val json = Json { encodeDefaults = true }
    private val now = Instant.now().toString()
    private fun id(n: Int) = legacyGenerationId(n)
    private fun canonical(kind: String, uuid: String, payload: JsonObject, created: String) = SyncV2Change(0, kind, uuid,
        "upsert", 1, JsonObject(payload + mapOf("public_id" to JsonPrimitive(uuid), "revision" to JsonPrimitive(1),
            "created_at" to JsonPrimitive(created), "updated_at" to JsonPrimitive(now), "deleted_at" to JsonNull)), now, id(4))
    // A changed, fully validated destination is legitimate. Its contents are not old-source authority.
    private val snapshot = RoundSyncBootstrapResponse(
        donor.counts.map { canonical("plan_node", it.uuid, NextStructureMapper.writePlan(it), Instant.ofEpochMilli(it.createdAt).toString()) } +
            donor.metrics.map { canonical("metric", it.uuid, NextStructureMapper.writeMetric(it), Instant.ofEpochMilli(it.createdAt).toString()) },
        20, now, emptyList(), 1,
        donor.counts.map { value ->
            val record = ChallengeRoundRecord(initialChallengeRoundHead(value.uuid), null, null, null)
            ChallengeCheckpoint(record.head, listOf(record))
        }, emptyList())
    val server = MaterialSocketServer { input, _ ->
        assertEquals("GET", input.method); assertTrue(input.body.isEmpty())
        if (input.path == "/api/v2/system/identity") {
            assertNull(input.headers["authorization"])
            MaterialSocketServer.Reply(json.encodeToString(ServerIdentityResponse(id(2), id(99), 5,
                listOf("sync.read"), now)).toByteArray())
        } else {
            assertEquals("/api/v2/sync/rounds/recovery-preview", input.path)
            assertEquals("Bearer synthetic-legacy-access", input.headers["authorization"])
            assertEquals("5", input.headers["x-dayforge-protocol"])
            assertEquals(id(2), input.headers["x-dayforge-server-instance"])
            assertEquals(id(99), input.headers["x-dayforge-sync-epoch"])
            val url = ("http://localhost" + input.target).toHttpUrl()
            assertEquals(id(4), url.queryParameter("device_id"))
            assertEquals(installation, url.queryParameter("installation_id"))
            assertEquals("1", url.queryParameter("challenge_contract"))
            val response = ReplicaRecoveryPreviewResponse(1, 5, id(1), id(2), id(99),
                RecoveryDeviceProof(id(4), installation, "android", "interactive", null, null,
                    true, true, 3, listOf("sync.read", "structure.write", "facts.append", "timer.control"), now), snapshot, null)
            MaterialSocketServer.Reply(json.encodeToString(recoveryMaterialReply(response, input, emptyList(), emptyList())).toByteArray())
        }
    }
    override fun close() = server.close()
}
