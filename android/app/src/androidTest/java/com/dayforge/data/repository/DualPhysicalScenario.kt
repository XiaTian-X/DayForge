package com.dayforge.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.api.AuthApi
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.LoginRequest
import com.dayforge.data.api.dto.RefreshRequest
import com.dayforge.data.api.dto.RoundSyncPushRequest
import com.dayforge.data.history.CountTrustIndex
import com.dayforge.data.local.*
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.di.DatabaseModule
import com.dayforge.domain.model.IconReference
import com.dayforge.domain.model.ObjectAppearance
import com.dayforge.domain.service.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import dagger.hilt.components.SingletonComponent
import com.dayforge.widget.BusinessWidgetIsolation
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Fixed real FastAPI/two-installation phases. No JUnit lifecycle clears retained state.
 * Files carry original references and diagnostics ONLY: all facts, authority and display
 * qualification are re-read through the actual Host and original production validators. */
@HiltAndroidTest @UninstallModules(DatabaseModule::class)
class DualPhysicalScenario {
    private val args = InstrumentationRegistry.getArguments()
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val run = requireNotNull(args.getString("df_dual_run")).also { check(UUID.fromString(it).toString() == it) }
    private val nonce = requireNotNull(args.getString("df_dual_nonce")).also { check(it.matches(Regex("[a-f0-9]{32}"))) }
    private val phase = requireNotNull(args.getString("df_dual_phase")).also { check(it in PHASES) }
    private val role = phase.take(1)
    private val previous = requireNotNull(args.getString("df_dual_previous")).also { check(it == "none" || it.matches(Regex("[a-f0-9]{64}"))) }
    private val port = requireNotNull(args.getString("dayforgeJointPort")).toInt().also { check(it in 1024..65535) }
    private val endpoint = "http://127.0.0.1:$port/"
    private val peer = CountCommandRecords.parse(Base64.decode(requireNotNull(args.getString("df_dual_shared")), Base64.NO_WRAP), 16_384, {})
        .also { value -> check(value.keys.all { it in SHARED_KEYS }); value.values.forEach { check(UUID.fromString(it.jsonPrimitive.content).toString() == it.jsonPrimitive.content) } }
    private val directory get() = File(app.filesDir, "dayforge-dual-physical/$run/$role")
    private val stateFile get() = File(directory, "state.json")
    private val authFile get() = File(directory, "auth.preferences_pb")
    private val alias get() = "dayforge.test.dual.$run.$role"
    private lateinit var data: DataStore<Preferences>
    private lateinit var cipher: AndroidKeystoreTokenCipher
    private lateinit var scope: CoroutineScope
    private lateinit var rooms: DatabaseEpochTestRooms.Configuration
    private val native = RetainedJourneyNative()
    private val hilt = HiltAndroidRule(this)
    @Inject internal lateinit var host: DatabaseRuntimeHost
    @Inject internal lateinit var factory: DatabaseEpochGraphFactory
    @Inject internal lateinit var tokens: TokenManager
    @Inject internal lateinit var sessions: AccountSessionCoordinator
    @Inject internal lateinit var settings: PreferencesManager
    @Inject internal lateinit var auth: AuthApi
    @Inject internal lateinit var http: NextSyncHttp
    @Inject internal lateinit var icons: AccountIconController
    @Inject internal lateinit var themes: DeviceThemeController
    private lateinit var selectedHost: DatabaseRuntimeHost
    private var auxiliary: DatabaseRuntimeHost? = null
    private lateinit var widgets: BusinessWidgetIsolation
    private var closed = false
    private var closeFailure: Throwable? = null
    private var nativeBefore = 0
    private var offlineTransport: String? = null
    private val requestWitnesses = mutableListOf<JsonObject>()
    private val graph get() = selectedHost.boundGraph()
    private val db get() = graph.database
    private val habits get() = graph.habitRepository
    private val metrics get() = graph.metricRepository
    private val editor get() = graph.nextObjectEditor
    private val creator get() = graph.nextObjectCreator
    private suspend fun <T> current(body: suspend () -> T): T = selectedHost.withGraph { body() }
    private fun get(key: String) = state().getValue(key).jsonPrimitive.content
    private fun shared() = state().getValue("shared").jsonObject
    private fun uuid(key: String) = shared().getValue(key).jsonPrimitive.content
    private fun share(vararg values: Pair<String, String>) {
        check(values.all { it.first in SHARED_KEYS })
        save("shared" to JsonObject(shared() + values.map { it.first to JsonPrimitive(it.second) }))
    }
    private fun state() = CountCommandRecords.parse(stateFile.readBytes(), 1_048_576, {}).also {
        check(it["run"] == JsonPrimitive(run) && it["nonce"] == JsonPrimitive(nonce) && it["role"] == JsonPrimitive(role))
    }
    private fun write(file: File, body: JsonObject) {
        val bytes = body.toString().toByteArray(); check(bytes.size <= 1_048_576)
        val atomic = AtomicFile(file); val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) } catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }
    private fun save(vararg entries: Pair<String, JsonElement>) = write(stateFile, JsonObject(state() + entries))
    private fun reference(key: String): CountCommandReference = state().getValue(key).jsonObject.let {
        CountCommandReference(it.getValue("command").jsonPrimitive.content, it.getValue("input").jsonPrimitive.content,
            it.getValue("namespace").jsonPrimitive.content, it.getValue("dataset").jsonPrimitive.content)
    }
    private fun retainReference(key: String, value: CountCommandReference) = save(key to buildJsonObject {
        put("command", value.commandId); put("input", value.inputHash); put("namespace", value.namespaceHash); put("dataset", value.datasetId)
    })
    private suspend fun command(ref: CountCommandReference) = CountCommandStore(db, tokens, sessions).resolve(ref.native()).historical
    private suspend fun image() = current { db.withTransaction { NextReplicaStorageProof(db).readInTransaction().contents } }
    private suspend fun sync() = current { graph.businessSyncRepository.sync() }
    private fun appearance(kind: String) = ObjectAppearance(IconReference.Role(kind), "#123456", "theme")

    fun runScenarioPhase(deadline: Long): String {
        check(app.packageName == "com.dayforge.testbed" && active == null)
        PhysicalDatabaseRule.requireProcessCleanupClear()
        check(DatabaseRuntimeHosts.snapshot().all { it.lifecycleEnded })
        var error: Throwable? = null
        widgets = BusinessWidgetIsolation.open(app)
        try {
            runBlocking { openFiles() }
            rooms = DatabaseEpochTestRooms.install(null, native)
            active = this
            val statement = object : Statement() {
                override fun evaluate() = runBlocking<Unit> {
                    var primary: Throwable? = null
                    try {
                        hilt.inject(); selectedHost = host
                        val remaining = deadline - SystemClock.elapsedRealtime(); check(remaining > 0)
                        withTimeout(remaining) {
                            themes.current()
                            if (phase in setOf("A1", "B1")) login() else checkAuthority()
                            native.arm(); nativeBefore = native.ends.size
                            when (phase) {
                                "A1" -> preparePrimary()
                                "B1" -> joinSecondary()
                                "B2" -> offlineCount()
                                "B3" -> offlineMetric()
                                "A2" -> absentWhileOffline()
                                "B4" -> reconnect()
                                "A3" -> pullPeer()
                                "B5" -> takeover()
                                "A4" -> formerPrimary()
                            }
                            checkAuthority()
                        }
                    } catch (failure: Throwable) {
                        primary = failure
                        throw failure
                    } finally {
                        try { withContext(NonCancellable) { closeOwners() } }
                        catch (cleanup: Throwable) {
                            val retained = primary
                            if (retained == null) throw cleanup
                            else if (cleanup !== retained && retained.suppressed.none { it === cleanup }) retained.addSuppressed(cleanup)
                        }
                    }
                }
            }
            hilt.apply(statement, Description.createTestDescription(javaClass, "dualPhysical$phase")).evaluate()
            check(closed && closeFailure == null && SystemClock.elapsedRealtime() < deadline) { "DUAL_PHASE_NOT_CLOSED_WITHIN_BUDGET" }
            check(native.observationFailures.isEmpty())
            val report = buildJsonObject {
                put("format", "dual-physical-phase/v1"); put("run", run); put("nonce", nonce); put("role", role); put("phase", phase)
                put("previous", previous); put("pid", Process.myPid()); put("status", "PASS"); put("owners_closed", true)
                put("shared", shared()); put("request_witnesses", JsonArray(requestWitnesses.toList()))
                put("generation", get("generation")); put("installation", get("installation")); put("device", get("device"))
                put("date", get("date")); put("zone", get("zone")); put("widget_scope", "isolated")
                offlineTransport?.let { put("offline_transport", it) }
                put("native", buildJsonArray { native.ends.drop(nativeBefore).forEach { end -> add(buildJsonObject {
                    put("kind", end.kind.name); put("sequence", end.sequence); put("begin", end.beginAt); put("end", end.endAt)
                    put("accepted", JsonArray(end.acceptedRequests.map(::JsonPrimitive)))
                }) } })
                put("checks", buildJsonObject { for (key in listOf("fresh_authority", "same_installation", "original_domain",
                    "actual_end", "exact_effects", "known_close")) put(key, true) })
            }
            val file = File(directory, "$phase.json"); check(!file.exists()); write(file, report)
            val digest = CountTrustIndex.hash(file.readBytes())
            save("last_phase" to JsonPrimitive(phase), "last_digest" to JsonPrimitive(digest))
            return digest
        } catch (failure: Throwable) {
            error = failure
            PhysicalDatabaseRule.preserveProcessCleanupFailure(failure)
            throw failure
        } finally {
            if (!closed) try { runBlocking { closeOwners() } } catch (cleanup: Throwable) {
                if (error == null) throw cleanup else if (cleanup !== error && error.suppressed.none { it === cleanup }) error.addSuppressed(cleanup)
            }
            // An unknown close deliberately retains isolation/state. No raw preference reset,
            // key deletion, database removal, fake cancellation, or next phase is attempted.
        }
    }

    private suspend fun openFiles() {
        val first = phase == "A1" || phase == "B1"
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (first) {
            check(previous == "none" && !directory.exists() && !keyStore.containsAlias(alias))
            check(!app.getDatabasePath("habit_database").exists())
            check(directory.mkdirs())
            write(stateFile, buildJsonObject {
                put("run", run); put("nonce", nonce); put("role", role); put("shared", peer)
                put("date", LocalDate.now().toString()); put("zone", ZoneId.systemDefault().id); put("endpoint", endpoint)
            })
        } else {
            check(authFile.isFile && stateFile.isFile && app.getDatabasePath("habit_database").isFile && keyStore.containsAlias(alias))
            val prior = PER_ROLE.getValue(role).let { it[it.indexOf(phase) - 1] }
            check(get("last_phase") == prior && get("last_digest") == previous)
            check(CountTrustIndex.hash(File(directory, "$prior.json").readBytes()) == previous)
            check(get("endpoint") == endpoint && get("date") == LocalDate.now().toString() && get("zone") == ZoneId.systemDefault().id)
            val old = shared(); check(old.all { (key, value) -> peer[key] == value })
            save("shared" to peer)
        }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        data = PreferenceDataStoreFactory.create(scope = scope, produceFile = { authFile })
        cipher = AndroidKeystoreTokenCipher(alias)
        if (!first) check(keyStore.getKey(alias, null).encoded == null)
    }
    private suspend fun login() {
        check(tokens.authenticationSnapshot() == null)
        settings.setRemoteServerUrl(null); settings.setActiveServerUrl(null); settings.setServerUrl(endpoint)
        val signed = auth.login(LoginRequest("joint-count-command", "TestPassword123!"))
        val refreshed = auth.refreshToken(RefreshRequest(signed.refreshToken)); assertEquals(signed.userId, refreshed.userId)
        sessions.exclusive { tokens.saveLoginSession(refreshed.accessToken, refreshed.refreshToken, "joint-count-command", signed.userId, false) }
        sync()
        val access = requireNotNull(tokens.localSyncAccess()); val installation = requireNotNull(tokens.registeredInstallationId())
        val values = data.data.first()
        for (name in listOf("access_token", "refresh_token")) check(requireNotNull(values[stringPreferencesKey(name)]).startsWith("enc:v1:"))
        check(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null).encoded == null)
        save("generation" to JsonPrimitive(access.session.authentication.generation), "installation" to JsonPrimitive(installation), "device" to JsonPrimitive(requireNotNull(access.deviceId)))
        if (phase == "A1") share("account" to access.session.authentication.userId, "server" to requireNotNull(access.session.serverInstanceId),
            "epoch" to requireNotNull(access.session.syncEpoch), "installation_a" to installation, "device_a" to requireNotNull(access.deviceId))
        else {
            check(access.session.authentication.userId == uuid("account") && access.session.serverInstanceId == uuid("server") && access.session.syncEpoch == uuid("epoch"))
            check(installation != uuid("installation_a") && access.deviceId != uuid("device_a"))
            share("installation_b" to installation, "device_b" to requireNotNull(access.deviceId))
        }
        checkAuthority()
    }
    private suspend fun checkAuthority() {
        val access = requireNotNull(tokens.localSyncAccess())
        check(access.session.authentication.userId == uuid("account") && access.session.serverInstanceId == uuid("server") && access.session.syncEpoch == uuid("epoch"))
        check(access.session.authentication.generation == get("generation") && access.deviceId == get("device"))
        check(tokens.registeredInstallationId() == get("installation") && tokens.isCurrentSyncAuthority(access))
        check(settings.serverUrl.first() == endpoint && settings.remoteServerUrl.first() == null && settings.activeServerUrl.first() == endpoint)
        check(get("date") == LocalDate.now().toString() && get("zone") == ZoneId.systemDefault().id)
    }
    private suspend fun newMetric(name: String): Long = metrics.createMetric(MetricEntity(name = name, unit = "kg", decimalPlaces = 3,
        iconResId = 0, colorHex = "#123456", appearance = appearance("metric.weight")), creationAuthority = creator.capture())
    private suspend fun preparePrimary() = current {
        assertTrue(tokens.isPrimaryEditor.first()); assertTrue("structure.write" in requireNotNull(tokens.localSyncAccess()).capabilities)
        assertTrue(db.habitDao().getAllHabitsOnce().isEmpty()); assertTrue(db.metricDao().getAllMetricsOnce().isEmpty())
        val metric = newMetric("dual required metric")
        val habit = habits.createHabit("dual count", "", HabitType.COUNTING, 0, "#123456", HabitSchedule.Daily,
            targetValue = 100, targetCycles = 3, completionPolicy = "recurring", selectedMetricIds = setOf(metric),
            appearance = appearance("habit.exercise"), creationAuthority = creator.capture())
        val h = requireNotNull(habits.getHabitById(habit)); val m = requireNotNull(db.metricDao().getMetricById(metric))
        val link = db.habitMetricLinkDao().getAllLinksForHabit(habit).single(); assertTrue(link.promptOnComplete)
        share("habit" to h.uuid, "metric" to m.uuid, "link" to link.uuid)
        val sources = db.syncOutboxDao().getAll(); assertEquals(3, sources.size)
        sync(); assertTrue(db.syncOutboxDao().getAll().isEmpty())
        sources.forEach { source ->
            val kind = when (source.recordType) { "habit" -> "plan_node"; "metric" -> "metric"; "link" -> "activity_metric_link"; else -> error("DUAL_UNEXPECTED_DEFINITION_SOURCE") }
            requestWitnesses += replay(source.operationId, source.entityUuid, kind, NextRequestSql.sourceHash(source))
        }
        phases(0, 0, 0, 0, sources.map { it.operationId }.toSet())
    }
    private suspend fun ids(): Pair<Long, Long> = requireNotNull(db.habitDao().getHabitByUuid(uuid("habit"))).id to
        requireNotNull(db.metricDao().getMetricByUuid(uuid("metric"))).id
    private suspend fun denyStructure() {
        val before = image()
        val failure = runCatching { current { newMetric("must not create") } }.exceptionOrNull()
        check(failure is IllegalStateException && failure.message == "OBJECT_CREATE_ACCESS_DENIED")
        assertEquals(before, image())
    }
    private suspend fun joinSecondary() {
        assertFalse(tokens.isPrimaryEditor.first()); assertFalse("structure.write" in requireNotNull(tokens.localSyncAccess()).capabilities)
        assertTrue("facts.append" in requireNotNull(tokens.localSyncAccess()).capabilities)
        current { ids(); assertTrue(db.syncOutboxDao().getAll().isEmpty()) }
        denyStructure()
    }
    private fun phases(t0: Int, countT1: Int, metric: Int, t2: Int, accepted: Set<String> = emptySet()) {
        check(native.observationFailures.isEmpty())
        fun count(kind: RetainedJourneyNative.Kind) = native.ends.count { it.kind == kind }
        assertEquals(t0, count(RetainedJourneyNative.Kind.T0)); assertEquals(countT1, count(RetainedJourneyNative.Kind.COUNT_T1))
        assertEquals(metric, count(RetainedJourneyNative.Kind.METRIC_T1)); assertEquals(t2, count(RetainedJourneyNative.Kind.T2))
        val actual = native.ends.filter { it.kind == RetainedJourneyNative.Kind.ACK }.flatMap { it.acceptedRequests }
        assertEquals(accepted, actual.toSet()); assertEquals(accepted.size, actual.size)
    }
    private suspend fun prompt(parent: CountCommandReceipt): CompletionMetricRead {
        val (habit, _) = ids()
        val source = requireNotNull(graph.widgetFactReader.afterRecoveredCount(command(parent.reference).intent.namespace,
            habit, uuid("habit"), requireNotNull(parent.factUuid)).completionSource)
        val page = graph.dashboardReadRepository.readProjection(ZonedDateTime.now())
        assertEquals(1L, page.counts.getValue(habit).todayQuantity)
        var published = false; assertTrue(graph.dashboardReadRepository.publish(page) { published = true }); assertTrue(published)
        var required: CompletionMetricRead? = null; metrics.publishCompletionPrompt(source) { required = it }
        return requireNotNull(required).also { assertEquals(listOf(uuid("metric")), it.metrics.map { value -> requireNotNull(value.value).uuid }); assertTrue(it.links.single().promptOnComplete) }
    }
    private suspend fun offlineCount() {
        val original = requireNotNull(tokens.localSyncAccess())
        val before = image()
        val failure = runCatching { sync() }.exceptionOrNull()
        check(failure is IOException) { "DUAL_OFFLINE_NOT_IO_FAILURE" }
        val transport = generateSequence<Throwable>(failure) { it.cause }.take(8).firstOrNull {
            it is java.net.ConnectException || it is java.net.SocketTimeoutException || it is java.net.NoRouteToHostException
        }
        check(transport != null) { "DUAL_OFFLINE_NOT_TRANSPORT_FAILURE" }
        offlineTransport = transport.javaClass.name
        assertEquals(original, tokens.localSyncAccess()); assertEquals(endpoint, settings.serverUrl.first())
        assertEquals(before, image())
        current {
            val (habitId, _) = ids(); val selected = habits.getHabitForEditing(habitId); val h = requireNotNull(selected.value)
            val attempt = editor.beginCountAdd(h, requireNotNull(selected.authority), Instant.now(), ZoneId.systemDefault(), 1)
            val ref = CountCommandReference(attempt.input.commandId, attempt.input.inputHash, attempt.input.namespace.hash, attempt.input.namespace.dataset)
            try {
                assertEquals(CountCommandRecords.Execution.PREPARED, command(ref).status.execution); assertNull(command(ref).outcome)
                assertTrue(db.completionDao().getByHabitOnce(habitId).isEmpty()); assertTrue(db.syncOutboxDao().getAll().isEmpty())
                phases(1, 0, 0, 0)
                val parent = requireNotNull(habits.logCompletionWithReceipt(app, habitId, authority = selected.authority,
                    expectedHabitUuid = h.uuid, preparedCommand = attempt).command)
                assertEquals(ref, parent.reference); assertTrue(parent.recorded && parent.requiresMetricFollowUp)
                assertEquals(CountCommandRecords.Delivery.FOLLOW_UP_PENDING, command(ref).status.delivery)
                prompt(parent)
                val source = db.syncOutboxDao().getAll().single(); assertEquals(parent.factUuid, source.entityUuid)
                assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, source.operationId))
                retainReference("parent", ref); share("count_fact" to requireNotNull(parent.factUuid), "count_operation" to source.operationId)
                save("parent_intent" to JsonPrimitive(command(ref).intent.text()), "parent_outcome" to JsonPrimitive(requireNotNull(command(ref).outcome).text()),
                    "count_source_hash" to JsonPrimitive(NextRequestSql.sourceHash(source)))
                phases(1, 1, 0, 0)
            } finally { editor.releaseUnexecuted(attempt) }
        }
    }
    private suspend fun offlineMetric() = current {
        val parent = (habits.resumeCountCommandAfterCompleteProof(app, reference("parent")) as CountCommandRecovery.HistoricalCommitted).receipt
        assertEquals(uuid("count_fact"), parent.factUuid)
        assertEquals(get("parent_intent"), command(parent.reference).intent.text()); assertEquals(get("parent_outcome"), requireNotNull(command(parent.reference).outcome).text())
        val required = prompt(parent); val target = required.metrics.single(); val metric = requireNotNull(target.value)
        val (habitId, _) = ids(); val values = listOf(MetricValueDraft(metric.id, 23.625, "dual required original input"))
        val at = System.currentTimeMillis()
        val attempt = editor.beginMetricChild(parent, requireNotNull(target.authority), required.source.habit, required.source.claim,
            values, listOf(metric), db.habitMetricLinkDao().getAllLinksForHabit(habitId), at, ZoneId.systemDefault().id)
        val ref = CountCommandReference(attempt.input.commandId, attempt.input.inputHash, attempt.input.namespace.hash, attempt.input.namespace.dataset)
        try {
            assertEquals(CountCommandRecords.Execution.PREPARED, command(ref).status.execution); assertNull(command(ref).outcome)
            assertTrue(db.metricLogDao().getAllLogsForMetric(metric.id).isEmpty()); phases(1, 0, 0, 0)
            val child = requireNotNull(metrics.recordValuesWithReceipt(values, at, authority = target.authority, expectedMetrics = listOf(metric),
                promptHabit = required.source.habit, promptFact = required.source.claim, promptCompletionSource = true,
                parentCommand = parent, preparedCommand = attempt).command)
            assertEquals(ref, child.reference); assertEquals(parent.reference.commandId, command(ref).status.parent)
            assertEquals(CountCommandRecords.Delivery.METRICS_COMMITTED, command(parent.reference).status.delivery)
            val fact = db.metricLogDao().getAllLogsForMetric(metric.id).single(); val queued = db.syncOutboxDao().getAll()
            assertEquals(2, queued.size); assertEquals(setOf(uuid("count_fact"), fact.uuid), queued.map { it.entityUuid }.toSet())
            queued.forEach { assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, it.operationId)) }
            retainReference("child", ref); share("metric_fact" to fact.uuid, "metric_operation" to queued.single { it.entityUuid == fact.uuid }.operationId)
            save("child_intent" to JsonPrimitive(command(ref).intent.text()), "child_outcome" to JsonPrimitive(requireNotNull(command(ref).outcome).text()),
                "metric_source_hash" to JsonPrimitive(NextRequestSql.sourceHash(queued.single { it.entityUuid == fact.uuid })))
            val publication = selectedHost.currentPublication()
            metrics.deliverFormalMetricResult(parent, child, required.source, publication::isCurrentInMemory)
            assertTrue(listOf(parent.reference, ref).all { !command(it).status.pending && command(it).status.delivery == CountCommandRecords.Delivery.DELIVERED })
            phases(1, 0, 1, 1)
        } finally { editor.releaseUnexecuted(attempt) }
    }
    private suspend fun absentWhileOffline() = current {
        sync()
        val snapshot = requireNotNull(http.session(requireNotNull(tokens.localSyncAccess())) { it.roundBootstrap() })
        assertTrue(snapshot.changes.none { it.entityUuid in setOf(uuid("count_fact"), uuid("metric_fact")) })
        val (habit, metric) = ids(); assertTrue(db.completionDao().getByHabitOnce(habit).isEmpty()); assertTrue(db.metricLogDao().getAllLogsForMetric(metric).isEmpty())
    }
    private suspend fun replay(operation: String, fact: String, kind: String, sourceHash: String): JsonObject {
        val origin = requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION, operation))
        val wire = requireNotNull(db.nextRequestDao().transmission(NEXT_OPERATION, operation))
        val accepted = requireNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, operation))
        assertEquals(5, origin.protocol); assertEquals(5, wire.protocol)
        assertEquals(sourceHash, origin.sourceHash)
        assertEquals(uuid("account"), origin.accountId); assertEquals(uuid("account"), wire.accountId)
        assertEquals(uuid("server"), origin.serverInstanceId); assertEquals(uuid("server"), wire.serverInstanceId)
        assertEquals(uuid("epoch"), origin.syncEpoch); assertEquals(uuid("epoch"), wire.syncEpoch)
        assertEquals(get("device"), wire.deviceId); assertEquals(nextRequestHash(wire.wireBytes), wire.wireHash)
        val request = Json.decodeFromString<RoundSyncPushRequest>(wire.wireBytes.toString(Charsets.UTF_8))
        val op = request.operations.single()
        assertEquals(operation, op.operationId); assertEquals(fact, op.entityUuid); assertEquals(kind, op.entityType); assertEquals("upsert", op.action)
        assertEquals(NextRequestSql.rowHash(db, "next_request_origins", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, operation)), accepted.originHash)
        assertEquals(NextRequestSql.rowHash(db, "next_transmissions", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, operation)), accepted.transmissionHash)
        val result = requireNotNull(http.session(requireNotNull(tokens.localSyncAccess())) { it.roundPushFrozen(wire.wireBytes) }).results.single()
        assertEquals("already_applied", result.status); assertEquals(operation, result.operationId); assertEquals(fact, result.entityUuid); assertEquals(kind, result.entityType)
        assertEquals(origin, db.nextRequestDao().origin(NEXT_OPERATION, operation)); assertEquals(accepted, db.nextRequestDao().acceptance(NEXT_OPERATION, operation))
        assertArrayEquals(wire.wireBytes, requireNotNull(db.nextRequestDao().transmission(NEXT_OPERATION, operation)).wireBytes)
        assertEquals(nextRequestHash(accepted.resultJson.toByteArray(Charsets.UTF_8)), accepted.resultHash)
        return buildJsonObject {
            put("operation_id", operation); put("device_id", wire.deviceId); put("entity_type", kind); put("entity_uuid", fact); put("action", op.action)
            put("source_sha256", sourceHash); put("wire_sha256", wire.wireHash)
            put("origin_row_sha256", accepted.originHash); put("transmission_row_sha256", accepted.transmissionHash)
            put("result_sha256", accepted.resultHash); put("replay_status", result.status)
        }
    }
    private suspend fun reconnect() {
        sync()
        current {
            requestWitnesses += replay(uuid("count_operation"), uuid("count_fact"), "activity_event", get("count_source_hash"))
            requestWitnesses += replay(uuid("metric_operation"), uuid("metric_fact"), "metric_observation", get("metric_source_hash"))
            assertTrue(db.syncOutboxDao().getAll().isEmpty())
            phases(0, 0, 0, 0, setOf(uuid("count_operation"), uuid("metric_operation")))
        }
        val old = current { db }; shutdownTestHost(selectedHost)
        assertTrue(host.lifecycleEnded); assertFalse(old.isOpen); assertTrue(requireNotNull(CountTrustRuntime.forDatabase(old)).isGraphCloseConfirmed(old))
        selectedHost = DatabaseRuntimeHost(factory, tokens).also { auxiliary = it }
        current {
            assertNotSame(old, db)
            val parent = (habits.resumeCountCommandAfterCompleteProof(app, reference("parent")) as CountCommandRecovery.HistoricalCommitted).receipt
            val child = (metrics.resumeLinkedMetricCommand(reference("child")) as CountCommandRecovery.HistoricalCommitted).receipt
            val (habit, _) = ids()
            val source = requireNotNull(graph.widgetFactReader.afterRecoveredCount(command(parent.reference).intent.namespace, habit, uuid("habit"), uuid("count_fact")).completionSource)
            val publication = selectedHost.currentPublication(); val before = listOf(parent.reference, child.reference).map { command(it).status }
            metrics.deliverFormalMetricResult(parent, child, source, publication::isCurrentInMemory)
            assertEquals(before, listOf(parent.reference, child.reference).map { command(it).status })
            assertTrue(before.all { !it.pending && it.delivery == CountCommandRecords.Delivery.DELIVERED })
            for ((key, ref) in listOf("parent" to parent.reference, "child" to child.reference)) {
                assertEquals(get("${key}_intent"), command(ref).intent.text()); assertEquals(get("${key}_outcome"), requireNotNull(command(ref).outcome).text())
            }
            assertFacts()
            phases(0, 0, 0, 0, setOf(uuid("count_operation"), uuid("metric_operation")))
        }
    }
    private suspend fun assertFacts() {
        val (habit, metric) = ids()
        assertEquals(listOf(uuid("count_fact")), db.completionDao().getByHabitOnce(habit).map { it.uuid })
        val values = db.metricLogDao().getAllLogsForMetric(metric); assertEquals(listOf(uuid("metric_fact")), values.map { it.uuid }); assertEquals(23.625, values.single().value, 0.0)
        assertTrue(db.syncOutboxDao().getAll().isEmpty())
        val page = graph.dashboardReadRepository.readProjection(ZonedDateTime.now()); assertEquals(1L, page.counts.getValue(habit).todayQuantity)
        var shown = false; assertTrue(graph.dashboardReadRepository.publish(page) { shown = true }); assertTrue(shown)
    }
    private suspend fun pullPeer() = current {
        sync(); assertFacts()
        // Pulled facts are not this installation's local commands or follow-up obligations.
        val page = CountCommandStore(db, tokens, sessions).discover(32)
        assertTrue(page.complete && page.commands.isEmpty()); assertEquals(0L, page.retainedCount)
    }
    private suspend fun takeover() {
        current { assertFacts() }
        val before = requireNotNull(tokens.localSyncAccess())
        val device = current { graph.businessSyncRepository.makeCurrentDevicePrimary() }
        assertTrue(device.isPrimaryEditor); assertEquals(before.deviceId, device.deviceId)
        assertTrue(device.capabilityRevision > requireNotNull(before.capabilityRevision)); assertTrue(tokens.isPrimaryEditor.first())
        // A changed capability snapshot must finish its old lease before new business work.
        current {
            val id = newMetric("dual explicit primary definition"); share("new_metric" to requireNotNull(db.metricDao().getMetricById(id)).uuid)
            val operation = db.syncOutboxDao().getAll().single()
            sync(); assertTrue(db.syncOutboxDao().getAll().isEmpty())
            requestWitnesses += replay(operation.operationId, operation.entityUuid, "metric", NextRequestSql.sourceHash(operation))
            phases(0, 0, 0, 0, setOf(operation.operationId))
        }
    }
    private suspend fun formerPrimary() {
        sync(); assertFalse(tokens.isPrimaryEditor.first()); assertFalse("structure.write" in requireNotNull(tokens.localSyncAccess()).capabilities)
        denyStructure()
        current { assertFacts(); assertNotNull(db.metricDao().getMetricByUuid(uuid("new_metric"))) }
    }
    private suspend fun closeOwners() {
        closeFailure?.let { throw it }; if (closed) return
        try {
            widgets.requireInstalled()
            if (::themes.isInitialized) withTimeout(5_000) { themes.close() }
            if (::icons.isInitialized) { icons.awaitImages(); icons.close() }
            auxiliary?.let { shutdownTestHost(it) }
            if (::host.isInitialized) shutdownTestHost(host)
            else check(DatabaseRuntimeHosts.snapshot().all { it.lifecycleEnded })
            if (::rooms.isInitialized) check(rooms.createdRooms.none { it.isOpen })
            if (::scope.isInitialized) withTimeout(5_000) { scope.coroutineContext[Job]!!.cancelAndJoin() }
            if (::rooms.isInitialized) DatabaseEpochTestRooms.release(rooms)
            check(active == null || active === this)
            widgets.finishAfterResourcesClosed()
            active = null; closed = true
        } catch (failure: Throwable) { closeFailure = failure; PhysicalDatabaseRule.preserveProcessCleanupFailure(failure); throw failure }
    }
    companion object {
        private val PHASES = listOf("A1", "B1", "B2", "B3", "A2", "B4", "A3", "B5", "A4")
        private val PER_ROLE = mapOf("A" to listOf("A1", "A2", "A3", "A4"), "B" to listOf("B1", "B2", "B3", "B4", "B5"))
        private val SHARED_KEYS = setOf("account", "server", "epoch", "installation_a", "device_a", "installation_b", "device_b", "habit", "metric", "link", "count_fact", "metric_fact", "count_operation", "metric_operation", "new_metric")
        @Volatile private var active: DualPhysicalScenario? = null
    }
    @Module @InstallIn(SingletonComponent::class)
    object PreferencesModule {
        @Provides @Singleton fun data(): DataStore<Preferences> = requireNotNull(active).data
        @Provides @Singleton fun cipher(): TokenCipher = requireNotNull(active).cipher
        @Provides @Singleton fun preferences(data: DataStore<Preferences>, calendar: DeviceCalendar, tokens: TokenManager) = PreferencesManager(data, calendar, tokens)
        @Provides @Singleton fun connectivity(@ApplicationContext context: Context): ConnectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
}
