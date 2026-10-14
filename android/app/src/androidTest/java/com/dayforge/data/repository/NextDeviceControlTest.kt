package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.datastore.preferences.core.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.*
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.domain.service.SyncManager
import io.mockk.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual discovery/HTTP, conditional permissions, physical challenge DB and real creation consumer. */
@RunWith(AndroidJUnit4::class)
class NextDeviceControlTest : NextObjectEditorFixture() {
    private val json = Json { encodeDefaults = true }
    private val legacy = mockk<IncrementalSyncRepository>(relaxed = true)
    private var primary = false
    private var editing = false
    private var revision = 2
    private lateinit var installation: String

    private fun driver(http: NextSyncHttp): BusinessSyncRepository {
        val monitor = mockk<NetworkMonitor>()
        coEvery { monitor.localNetworks() } returns emptyList()
        val resolver = EndpointResolver(monitor, preferences, tokens, json, SelectedNetworkTransport())
        return BusinessSyncRepository(legacy, resolver, db, tokens, sessions, http,
            NextSyncRuntime(db, tokens, sessions, http, preferences))
    }

    private fun device() = DeviceResponse(id(4), installation, "android", isPrimaryEditor = primary,
        structuralEditEnabled = editing, capabilityRevision = revision,
        capabilities = (setOf("sync.read", "facts.append", "timer.control", "devices.manage_self") +
            if (primary || editing) setOf("structure.write") else emptySet()).toList(), lastSeenAt = time)

    private fun response(input: MaterialSocketServer.Input): MaterialSocketServer.Reply {
        if (input.path.endsWith("/identity")) return reply(input)
        assertEquals("5", input.headers["x-dayforge-protocol"])
        assertEquals(id(2), input.headers["x-dayforge-server-instance"])
        assertEquals(id(3), input.headers["x-dayforge-sync-epoch"])
        return when (input.path) {
            "/api/v2/devices/register" -> {
                assertEquals("POST", input.method)
                val request = json.decodeFromString<DeviceRegisterRequest>(input.body.toString(Charsets.UTF_8))
                assertEquals(5, request.protocolVersion)
                installation = request.installationId
                MaterialSocketServer.Reply(json.encodeToString(device()).toByteArray())
            }
            "/api/v2/sync/rounds/bootstrap" -> MaterialSocketServer.Reply(json.encodeToString(
                RoundSyncBootstrapResponse(emptyList(), 20, time, emptyList(), 1, emptyList(), emptyList())).toByteArray())
            "/api/v2/sync/rounds/changes" -> MaterialSocketServer.Reply(json.encodeToString(
                RoundSyncPullResponse(emptyList(), 20, false, time, 1, emptyList(), emptyList())).toByteArray())
            "/api/v2/devices/${id(4)}/make-primary" -> {
                assertEquals("POST", input.method); assertTrue(input.body.isEmpty())
                if (!primary) { primary = true; revision++ }
                MaterialSocketServer.Reply(json.encodeToString(device()).toByteArray())
            }
            "/api/v2/devices/${id(4)}/editing" -> {
                assertEquals("PATCH", input.method)
                val body = Json.parseToJsonElement(input.body.toString(Charsets.UTF_8)).jsonObject
                assertEquals(setOf("structural_edit_enabled"), body.keys)
                val enabled = json.decodeFromString<DeviceEditingUpdate>(body.toString()).structuralEditEnabled
                if (editing != enabled) { editing = enabled; revision++ }
                MaterialSocketServer.Reply(json.encodeToString(device()).toByteArray())
            }
            else -> error("Unexpected device consumer route: ${input.target}")
        }
    }

    private suspend fun active(http: NextSyncHttp): BusinessSyncRepository {
        db.clearAllData()
        return driver(http).also { it.sync() }
    }

    @Test fun settingsServicePromotesReadOnlyDeviceAndRealCreationUsesPublishedPermissionsAcrossColdReopen() = runBlocking<Unit> {
        val (http, server) = channel { response(it) }; val business = active(http)
        assertFalse(tokens.canEditStructure.first()); rejected { creator.captureForNavigation() }
        val original = access(); val state = db.nextSyncStateDao().rows().single()
        val lastSync = preferences.lastSyncTimestamp.first()
        SyncManager(legacy, business).makeCurrentDevicePrimary()
        assertTrue(tokens.isPrimaryEditor.first()); assertTrue(tokens.canEditStructure.first())
        assertEquals(3, access().capabilityRevision); assertEquals(original.session, access().session)
        assertEquals(installation, tokens.registeredInstallationId()); assertEquals(state, db.nextSyncStateDao().rows().single())
        assertEquals(lastSync, preferences.lastSyncTimestamp.first())
        val ticket = requireNotNull(creator.captureForNavigation())
        val saved = creatingMetrics().createMetric(metric.copy(id = 0), creationAuthority = ticket)
        val origin = db.nextRequestDao().origin(NEXT_OPERATION, db.syncOutboxDao().getAll().single().operationId)!!
        assertEquals(id(4), roundOperationIntent(origin.intentJson)!!.capturedDeviceId)
        storage.reopen(); assertEquals(metric.appearance, db.metricDao().getMetricById(saved)!!.appearance)
        creator = NextObjectCreator(db, tokens, sessions, icons)
        assertNotNull(creator.captureForNavigation())
        assertEquals(1, server.requests.count { it.path.endsWith("/register") })
        assertEquals(1, server.requests.count { it.path.endsWith("/make-primary") })
        coVerify(exactly = 0) { legacy.changeDeviceForAuthentication(any(), any()) }
    }

    @Test fun explicitEditingTrueFalsePublishesExactServerProofWithoutPrimaryOrQueueCursorChanges() = runBlocking<Unit> {
        val (http, server) = channel { response(it) }; val business = active(http)
        val state = db.nextSyncStateDao().rows().single(); val original = access().session
        val lastSync = preferences.lastSyncTimestamp.first()
        val service = SyncManager(legacy, business)
        service.setCurrentDeviceStructuralEditing(true)
        assertTrue(tokens.canEditStructure.first()); assertFalse(tokens.isPrimaryEditor.first())
        assertNotNull(creator.captureForNavigation()); assertEquals(3, access().capabilityRevision)
        service.setCurrentDeviceStructuralEditing(false)
        assertFalse(tokens.canEditStructure.first()); assertFalse(tokens.isPrimaryEditor.first())
        rejected { creator.captureForNavigation() }; assertEquals(4, access().capabilityRevision)
        assertEquals(original, access().session); assertEquals(state, db.nextSyncStateDao().rows().single())
        assertTrue(db.syncOutboxDao().getAll().isEmpty()); assertEquals(lastSync, preferences.lastSyncTimestamp.first())
        storage.reopen(); assertFalse(tokens.canEditStructure.first())
        assertEquals(2, server.requests.count { it.path.endsWith("/editing") })
        assertEquals(1, server.requests.count { it.path.endsWith("/register") })
    }

    @Test fun invalidDeviceProofAndMissingDefaultedFieldsNeverPublishPermissions() = runBlocking<Unit> {
        var override: JsonObject? = null
        val (http, _) = channel { input ->
            if (input.path.endsWith("/make-primary") && override != null)
                MaterialSocketServer.Reply(override.toString().toByteArray()) else response(input)
        }
        val business = active(http); val before = dataStore.data.first()
        val good = json.encodeToJsonElement(device().copy(isPrimaryEditor = true, capabilityRevision = 3,
            capabilities = device().capabilities + "structure.write")).jsonObject
        val bad = listOf(
            JsonObject(good + ("device_id" to JsonPrimitive(id(99)))),
            JsonObject(good + ("installation_id" to JsonPrimitive(id(99)))),
            JsonObject(good + ("platform" to JsonPrimitive("other"))),
            JsonObject(good + ("device_class" to JsonPrimitive("hardware"))),
            JsonObject(good + ("capability_revision" to JsonPrimitive(1))),
            JsonObject(good + ("capability_revision" to JsonPrimitive(2))),
            JsonObject(good + ("is_primary_editor" to JsonPrimitive(false))),
            JsonObject(good + ("capabilities" to JsonArray(listOf(JsonPrimitive("sync.read"))))),
            JsonObject(good + ("last_seen_at" to JsonPrimitive("not-a-time"))),
            JsonObject(good - "structural_edit_enabled"), JsonObject(good - "capability_revision"))
        for (value in bad) {
            override = value
            assertTrue(rejected { business.makeCurrentDevicePrimary() } is NextSyncReplyInvalid)
            assertEquals(before, dataStore.data.first())
        }
        storage.reopen(); assertFalse(tokens.canEditStructure.first()); assertFalse(tokens.isPrimaryEditor.first())
    }

    @Test fun editingReplyMustConfirmRequestedFlagAndHttpFailurePreservesOriginalProof() = runBlocking<Unit> {
        var fault = false
        val (http, _) = channel { input ->
            when {
                fault && input.path.endsWith("/editing") -> MaterialSocketServer.Reply(
                    json.encodeToString(device()).toByteArray()) // Valid no-op response is not acknowledgment of true.
                fault && input.path.endsWith("/make-primary") -> MaterialSocketServer.Reply(
                    """{"detail":{"code":"DEVICE_REVOKED"}}""".toByteArray(), 403)
                else -> response(input)
            }
        }
        val business = active(http); val before = dataStore.data.first(); fault = true
        assertTrue(rejected { business.setCurrentDeviceStructuralEditing(true) } is NextSyncReplyInvalid)
        val error = rejected { business.makeCurrentDevicePrimary() } as NextSyncHttpFailure
        assertEquals(403, error.status); assertEquals("DEVICE_REVOKED", error.code)
        assertEquals(before, dataStore.data.first())
    }

    @Test fun reauthenticationWhileControlHttpRunsDoesNotBlockAccountOrPublishLatePermissions() = runBlocking<Unit> {
        val (http, _) = channel { input ->
            if (input.path.endsWith("/make-primary")) runBlocking {
                withTimeout(3000) { sessions.exclusive { tokens.saveLoginSession("new", "new-refresh", "again", id(1), false) } }
            }
            response(input)
        }
        val business = active(http); val state = db.nextSyncStateDao().rows().single(); val original = access()
        rejected { business.makeCurrentDevicePrimary() }
        assertEquals("new", tokens.accessToken.first())
        val retained = access() // A same-account login retains the existing device, but changes authentication generation.
        assertNotEquals(original.session.authentication, retained.session.authentication)
        assertEquals(original.deviceId, retained.deviceId); assertEquals(original.capabilities, retained.capabilities)
        assertEquals(original.capabilityRevision, retained.capabilityRevision)
        assertEquals(state, db.nextSyncStateDao().rows().single())
    }

    @Test fun capabilityChangeWhileHttpRunsCannotBeOverwrittenByOldAuthority() = runBlocking<Unit> {
        val (http, _) = channel { input ->
            if (input.path.endsWith("/make-primary")) runBlocking {
                tokens.saveDeviceRegistration(id(4), setOf("sync.read"), false, 50)
            }
            response(input)
        }
        val business = active(http)
        rejected { business.makeCurrentDevicePrimary() }
        assertEquals(50, access().capabilityRevision); assertEquals(setOf("sync.read"), access().capabilities)
        assertFalse(tokens.isPrimaryEditor.first())
    }

    @Test fun lostControlResponseRetriesSameDeviceActionWithoutRegistrationOrLocalGrant() = runBlocking<Unit> {
        var drop = false
        val (http, server) = channel { input ->
            val result = response(input)
            if (drop && input.path.endsWith("/make-primary")) null else result
        }
        val business = active(http); val before = dataStore.data.first(); drop = true
        assertTrue(rejected { business.makeCurrentDevicePrimary() } is java.io.IOException)
        assertEquals(before, dataStore.data.first()); drop = false
        business.makeCurrentDevicePrimary()
        assertEquals(3, access().capabilityRevision); assertTrue(tokens.isPrimaryEditor.first())
        assertEquals(2, server.requests.count { it.path.endsWith("/make-primary") })
        assertEquals(1, server.requests.count { it.path.endsWith("/register") })
    }

    @Test fun cancelledControlReleasesMutexAndColdRetryUsesOriginalInstallation() = runBlocking<Unit> {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); var block = false
        val (http, _) = channel { input ->
            if (block && input.path.endsWith("/make-primary")) {
                entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS))
            }
            response(input).copy(allowClientClose = input.path.endsWith("/make-primary"))
        }
        val business = active(http); val before = dataStore.data.first(); block = true
        val work = async { business.makeCurrentDevicePrimary() }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            withTimeout(3000) { sessions.exclusive { assertEquals(before, dataStore.data.first()) } }
            work.cancelAndJoin(); assertEquals(before, dataStore.data.first())
        } finally { release.countDown(); work.cancelAndJoin() }
        block = false; storage.reopen()
        driver(http).makeCurrentDevicePrimary(); assertEquals(installation, tokens.registeredInstallationId())
        assertTrue(tokens.isPrimaryEditor.first())
    }

    @Test fun partialPlainAndMissingInstallationCannotBeAdoptedByDeviceControl() = runBlocking<Unit> {
        val (http, server) = channel { response(it) }
        val business = driver(http)
        rejected { business.makeCurrentDevicePrimary() }
        assertFalse(server.requests.any { !it.path.endsWith("/identity") })
        active(http)
        db.withTransaction { db.openHelper.writableDatabase.execSQL("UPDATE next_sync_state SET challengeContract=0") }
        rejected { business.makeCurrentDevicePrimary() }
        db.withTransaction { db.openHelper.writableDatabase.execSQL("UPDATE next_sync_state SET challengeContract=1") }
        dataStore.edit { it.remove(
            androidx.datastore.preferences.core.stringPreferencesKey("sync_installation_id")) }
        rejected { business.makeCurrentDevicePrimary() }
        assertNull(tokens.registeredInstallationId())
        assertFalse(server.requests.any { it.path.endsWith("/make-primary") })
    }

    @Test fun replicaAndUnknownVersionFailClosedAndV4DispatchRetainsOriginalAuthentication() = runBlocking<Unit> {
        val (http, _) = channel { response(it) }; active(http)
        val original = access()
        for (changed in listOf(""""server_instance_id":"${id(99)}"""", """"protocol_version":6""")) {
            val (other, server) = channel { input ->
                assertTrue(input.path.endsWith("/identity"))
                val identity = reply(input).bytes.toString(Charsets.UTF_8)
                val from = if (changed.contains("server_instance_id")) """"server_instance_id":"${id(2)}"""" else """"protocol_version":5"""
                MaterialSocketServer.Reply(identity.replace(from, changed).toByteArray())
            }
            val before = dataStore.data.first()
            rejected { driver(other).makeCurrentDevicePrimary() }
            assertTrue(server.requests.all { it.path.endsWith("/identity") })
            // Discovery may select the configured address; it must not grant or rewrite device authority.
            assertEquals(before.asMap().filterKeys { it.name != "active_server_url" },
                dataStore.data.first().asMap().filterKeys { it.name != "active_server_url" })
            assertEquals(original, access())
        }
        // Legacy dispatch is checked separately; the mocked engine does not masquerade as real v4 HTTP.
        val (old, _) = channel { reply(it, 4) }
        coEvery { legacy.changeDeviceForAuthentication(original.session.authentication, null) } returns device()
        driver(old).makeCurrentDevicePrimary()
        coVerify(exactly = 1) { legacy.changeDeviceForAuthentication(original.session.authentication, null) }
    }
}
