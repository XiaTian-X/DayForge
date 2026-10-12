package com.dayforge.data.repository

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.*
import com.dayforge.data.local.*
import com.dayforge.data.local.entity.*
import com.dayforge.data.model.*
import com.dayforge.domain.appearance.*
import com.dayforge.domain.model.*
import com.dayforge.domain.service.AccountIconController
import com.dayforge.domain.service.AccountIconRuntime
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Room/DataStore/material files/private archive/TCP once ACK; no formal app or data. */
@RunWith(AndroidJUnit4::class)
class NextReplicaArchiveTest : NextCoreRequestFixture() {
    @get:org.junit.Rule(order = -2) val widgetRefresh = com.dayforge.widget.IsolatedWidgetRefreshRule()
    private val json = Json { encodeDefaults = true }
    private lateinit var iconDb: AccountIconDatabase
    private lateinit var icons: AccountIconRepository
    private lateinit var iconFiles: AccountIconFiles
    private lateinit var iconStore: AccountIconStore
    private lateinit var transfers: AccountIconTransfers
    private lateinit var themes: ThemeFileRepository
    private lateinit var builtIns: BuiltInThemes
    private lateinit var http: NextSyncHttp
    private lateinit var peer: MaterialSocketServer
    private var task: HabitEntity? = null
    private var remoteCount = false
    private val svg = """<svg width="1" height="1"><rect width="1" height="1" fill="#000000"/></svg>""".toByteArray()
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun blob() = IconBlob(sha(svg), svg.size, "image/svg+xml", 1, 1)
    private fun asset(purpose: String = "general") = IconAsset(id(100), "Original icon", purpose, "template", blob(), blob())
    private fun pack() = IconPack("dayforge.icon-pack", 1, id(101), 1, "Original style", listOf(asset()),
        mapOf("habit.custom" to id(100), "metric.custom" to id(100)), id(100))
    private fun configureIcons() {
        icons = AccountIconRepository(iconDb, tokens, sessions); iconFiles = AccountIconFiles(directory)
        iconStore = AccountIconStore(icons, iconFiles); transfers = AccountIconTransfers(iconDb, icons, iconStore)
    }
    @Before fun materialSetup() {
        assertFalse(app.getDatabasePath(AccountIconDatabase.NAME).exists())
        iconDb = AccountIconDatabase.open(app); assertEquals(5, iconDb.openHelper.writableDatabase.version); configureIcons()
        themes = ThemeFileRepository(directory); builtIns = BuiltInThemes(app.assets)
    }
    @After fun materialCleanup() {
        if (::iconDb.isInitialized) iconDb.close()
        assertTrue(app.deleteDatabase(AccountIconDatabase.NAME) || !app.getDatabasePath(AccountIconDatabase.NAME).exists())
    }
    private fun reader(files: AccountIconFiles = iconFiles, owner: TokenManager = tokens,
        coordinator: com.dayforge.domain.service.AccountSessionCoordinator = sessions,
        values: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> = dataStore) =
        NextReplicaArchiveInputReader(app, db, owner, coordinator, preferences, http, iconDb, icons, transfers,
            files, values, themes, builtIns)
    private fun service(store: ReplicaPrivateArchiveStore = ReplicaPrivateArchiveStore(directory)) = NextReplicaArchiveService(reader(), store, tokens)
    private suspend fun storageHash() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().hash }
    private suspend fun inspect(block: suspend (ReplicaArchiveInput, ReplicaArchiveFileAccess) -> Unit) =
        reader().inspect(access(), storageHash(), block)
    private fun archive(receipt: ReplicaArchiveReceipt) = File(directory,
        "replica-archives-v1/${receipt.namespace.accountId}/${receipt.namespace.serverInstanceId}/${receipt.namespace.syncEpoch}/${receipt.archiveId}.candidate")
    private fun iconRoot() = File(directory, "account-icons-v1/${id(1)}/${id(2)}/${id(3)}")
    private fun themeRoot() = File(directory, "theme-definitions-v1")
    private fun metadata() = ChallengeMetadata(1, listOf(habit.uuid, timerHabit.uuid).map { uuid ->
        val head = initialChallengeRoundHead(uuid)
        ChallengeCheckpoint(head, listOf(ChallengeRoundRecord(head, null, null, null)))
    }, if (remoteCount) listOf(ChallengeBirth("activity_event", id(50), initialChallengeRoundHead(habit.uuid))) else emptyList())
    private fun canonical(kind: String, uuid: String, payload: JsonObject) = SyncV2Change(0, kind, uuid, "upsert", 1,
        JsonObject(payload + mapOf("public_id" to JsonPrimitive(uuid), "revision" to JsonPrimitive(1),
            "created_at" to JsonPrimitive(time), "updated_at" to JsonPrimitive(time), "deleted_at" to JsonNull)), time)
    private fun oldCount() = canonical("activity_event", id(50), buildJsonObject {
        put("activity_uuid", habit.uuid); put("event_type", "count_delta"); put("value", 3)
        for (key in listOf("duration_seconds", "duration_milliseconds", "started_at", "ended_at", "reverts_event_uuid")) put(key, JsonNull)
        put("occurred_at", time); put("received_at", time); put("local_date", "2026-10-06"); put("timezone", "Asia/Shanghai")
        put("note", "Original unknown rule"); put("source_type", "app"); put("source_device_id", id(4))
        put("external_event_id", id(50)); put("metadata", buildJsonObject {})
    })
    private suspend fun initialize(fixed: Boolean = false, once: Boolean = false, unknownCount: Boolean = false) {
        register(); remoteCount = unknownCount
        db.withTransaction {
            val sql = db.openHelper.writableDatabase; sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            if (fixed) { habit = habit.copy(appearance = habit.appearance!!.copy(icon = IconReference.Asset(id(100)))); db.habitDao().update(habit) }
            if (once) {
                val row = habit.copy(id = 0, uuid = id(13), name = "Once", habitType = HabitType.CHECK_IN,
                    schedule = HabitSchedule.Once(), targetValue = 1, completionPolicy = "one_and_done", oneTimeConfirmedVersion = 0,
                    appearance = ObjectAppearance(IconReference.Role("task.custom"), "#123456", "object"))
                task = row.copy(id = db.habitDao().insert(row))
            }
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        val channel = channel { input ->
            if (input.path.endsWith("/identity")) reply(input) else {
                assertEquals("/api/v2/sync/rounds/push", input.path)
                val request = json.decodeFromString<RoundSyncPushRequest>(input.body.toString(Charsets.UTF_8))
                val operation = request.operations.single()
                val results = json.decodeFromString<NextSyncPushResponse>(successReply(input, (operation.baseRevision ?: 0) + 1) { body ->
                    if (operation.payload["one_time"] is JsonObject) {
                        val intent = operation.payload.getValue("one_time").jsonObject
                        JsonObject(body + ("one_time_state_after" to buildJsonObject {
                            put("version", intent.getValue("expected_version").jsonPrimitive.int + 1)
                            put("head_event_uuid", intent.getValue("event_uuid")); put("completion_event_uuid", intent.getValue("event_uuid"))
                        }))
                    } else if (operation.entityType in setOf("plan_node", "metric")) JsonObject(body + ("created_at" to JsonPrimitive(time))) else body
                }.bytes.toString(Charsets.UTF_8)).results
                val meta = metadata()
                MaterialSocketServer.Reply(json.encodeToString(RoundSyncPushResponse(results, 1, meta.checkpoints, meta.births)).toByteArray())
            }
        }
        http = channel.first; peer = channel.second
        val core = sender(http); val timers = NextTimerRequestStore(db, tokens, sessions, core)
        val onceStore = OneTimeLocalIntentStore(db, tokens, sessions, preferences)
        val changes = listOf(habit, timerHabit).plus(listOfNotNull(task)).map {
            canonical("plan_node", it.uuid, NextStructureMapper.writePlan(it))
        } + canonical("metric", metric.uuid, NextStructureMapper.writeMetric(metric)) + (if (unknownCount) listOf(oldCount()) else emptyList())
        val meta = metadata()
        NextSyncMergeStore(db, tokens, sessions, OneTimeAcceptedEventStore(db, tokens, sessions, onceStore, timerRequests = timers), timers)
            .bootstrap(access(), null, RoundSyncBootstrapResponse(changes, 20, time,
                task?.let { listOf(OneTimeProjection(it.uuid, OneTimeState(0, null, null))) }.orEmpty(), 1, meta.checkpoints, meta.births))
    }
    private suspend fun completeMaterial() {
        val context = icons.capture(); icons.reserveAsset(context, asset()); icons.reservePack(context, pack())
        iconStore.install(context, id(100), blob().sha256, svg); iconStore.select(context, 0, IconPackVersion(id(101), 1))
        transfers.enqueuePack(context, IconPackVersion(id(101), 1))
        while (true) {
            val attempt = transfers.prepareNext(context) ?: break
            when (attempt.job.kind) {
                IconTransferKind.DECLARE_ASSET -> transfers.confirmAsset(attempt, AssetRecord(attempt.binding, attempt.asset!!, listOf("dark", "light")))
                IconTransferKind.DECLARE_PACK -> transfers.confirmPack(attempt, PackDeclaration(attempt.binding, attempt.pack!!))
                IconTransferKind.UPLOAD -> transfers.confirmUpload(attempt, AssetTransferReceipt(attempt.binding, id(100), attempt.job.variant, blob()))
                IconTransferKind.DOWNLOAD -> error("No download queued")
            }
        }
    }
    private suspend fun initializeThemes() {
        ThemeCatalogRepository(dataStore, themes, builtIns).initialize()
        DeviceThemeStore(dataStore, themes).select(0, DeviceThemeSelection(ThemeVersionRef(BuiltInTheme.OCEAN.themeId, 1),
            ThemeVersionRef(BuiltInTheme.DUSK.themeId, 1), DeviceThemeMode.SYSTEM, DeviceCardStyle.PERSONALIZED))
    }
    private suspend fun unchanged(block: suspend () -> Unit) {
        val before = storage.snapshotClosedDatabase(); db.openHelper.writableDatabase
        val prefs = dataStore.data.first(); val scope = access(); val calls = peer.requests.size
        val iconsBefore = icons.recoveryMetadata(icons.capture())
        block()
        assertArrayEquals(before, storage.snapshotClosedDatabase()); assertEquals(prefs, dataStore.data.first())
        assertEquals(scope, access()); assertEquals(calls, peer.requests.size); assertEquals(iconsBefore, icons.recoveryMetadata(icons.capture()))
    }

    @Test fun completeEmptyMaterialInputKeepsAllBusinessAndWhitelistWithoutInitializingAppearance() = runBlocking<Unit> {
        initialize()
        lateinit var expired: ReplicaArchiveFileAccess
        unchanged { inspect { input, access ->
            expired = access
            assertFalse(input.activationAuthorized); assertFalse(input.hasReview); assertTrue(input.missingAssets.isEmpty())
            assertEquals(3, input.business.records.size); assertEquals(36, input.storage.contents.getValue("tables").jsonObject.keys
                .count { !it.startsWith("sqlite_") && it !in setOf("android_metadata", "room_master_table") })
            assertEquals(8, input.icons.contents.getValue("tables").jsonObject.size); assertTrue(input.iconFiles.files.isEmpty())
            assertNull(input.themes.preferences.selectionRaw); assertNull(input.themes.preferences.catalogRaw); assertTrue(input.themes.files.isEmpty())
            assertEquals(setOf("current", "authority"), input.uses.map { it.source }.toSet())
            assertFalse(input.manifest.toString().contains("synthetic-first")); assertFalse(input.manifest.toString().contains("synthetic-refresh"))
            assertThrows(UnsupportedOperationException::class.java) { (input.uses as MutableList).clear() }
        } }
        assertFalse(iconRoot().exists()); assertFalse(themeRoot().exists())
        assertEquals("SYNC_ARCHIVE_ACCESS_CLOSED", rejected { expired.theme("not-a-file") }.message)
    }

    @Test fun fullMaterialThemeAndBusinessArchiveSurvivesColdValidationWithoutPublishingAnyStage() = runBlocking<Unit> {
        initialize(fixed = true); completeMaterial(); initializeThemes()
        val before = dataStore.data.first(); val raw = storageHash(); val calls = peer.requests.size
        val receipt = service().create(access(), raw)
        assertFalse(receipt.activationAuthorized); assertEquals(receipt.byteLength, archive(receipt).length())
        assertEquals(receipt.contentHash, sha(archive(receipt).readBytes()))
        assertTrue(receipt.entries.any { it.name == "icons/${blob().sha256}" }); assertEquals(7, receipt.entries.count { it.name.startsWith("themes/") })
        assertEquals(setOf("input.json", "business.json", "icons.json"), receipt.entries.take(3).map { it.name }.toSet())
        assertEquals(before, dataStore.data.first()); assertEquals(raw, storageHash()); assertEquals(calls, peer.requests.size)
        storage.reopen(); db.openHelper.writableDatabase
        service(ReplicaPrivateArchiveStore(directory)).verify(access(), receipt)
        assertThrows(UnsupportedOperationException::class.java) { (receipt.entries as MutableList).clear() }
        assertTrue(db.nextRecoveryDao().rows().isEmpty())
    }

    @Test fun missingOwnedAssetIsExplicitAndCannotBorrowPixelsOrTurnIntoAPlaceholder() = runBlocking<Unit> {
        initialize(fixed = true)
        unchanged { inspect { input, access ->
            assertEquals(setOf(id(100)), input.missingAssets); assertTrue(input.hasReview)
            assertTrue(input.uses.any { it.source == "authority" && it.icon == IconReference.Asset(id(100)) })
            rejected { access.icon(blob().sha256) }
        } }
        assertFalse(iconRoot().exists())
    }

    @Test fun taskPurposeCannotAuthorizeAnOrdinaryHistoricalOrCurrentAssetUse() = runBlocking<Unit> {
        initialize(fixed = true)
        val context = icons.capture(); icons.reserveAsset(context, asset("task")); iconStore.install(context, id(100), blob().sha256, svg)
        unchanged { assertEquals("SYNC_ARCHIVE_ICON_PURPOSE_INVALID", rejected { inspect { _, _ -> error("Must not consume") } }.message) }
    }

    @Test fun pendingTransfersAndOriginalPartialBytesArePreservedInACandidateNotPromoted() = runBlocking<Unit> {
        initialize(fixed = true)
        val context = icons.capture(); icons.reserveAsset(context, asset()); transfers.enqueueAsset(context, id(100))
        val reservation = icons.recoveryMetadata(context).installations.single().reservation
        iconFiles.exclusive(context.namespace, true, { icons.reauthorize(context) }) { directory ->
            File(directory!!.path, ".install-${reservation.operationId}.part").writeBytes(byteArrayOf(1, 2, 3))
        }
        lateinit var receipt: ReplicaArchiveReceipt
        inspect { input, access ->
            assertTrue(input.hasReview); assertTrue(input.icons.hasUnresolvedTransfers)
            val file = input.iconFiles.files.single(); assertTrue(file.temporary); assertEquals(3, file.byteLength)
            assertArrayEquals(byteArrayOf(1, 2, 3), access.icon(file.name))
            receipt = ReplicaPrivateArchiveStore(directory).create(input, access) { assertEquals(input.icons.original, icons.capture().access) }
        }
        service().verify(access(), receipt)
        assertTrue(transfers.jobs(context).all { it.state == IconTransferState.PENDING })
        assertTrue(icons.recoveryMetadata(context).installations.all { it.validationProfile == null })
        assertEquals(3L, receipt.entries.single { it.name.startsWith("icons/") }.byteLength)
    }

    @Test fun changedCurrentRoleStillRetainsBothOldAuthorityAndOriginalRequestAssetReferences() = runBlocking<Unit> {
        initialize(fixed = true); completeMaterial()
        producer().writeRounds(producer().captureRounds()) {
            db.habitDao().update(db.habitDao().getHabitById(habit.id)!!.copy(appearance = habit.appearance!!.copy(icon = IconReference.Asset(id(102)))))
        }
        producer().writeRounds(producer().captureRounds()) {
            db.habitDao().update(db.habitDao().getHabitById(habit.id)!!.copy(appearance = habit.appearance!!.copy(icon = IconReference.Role("habit.custom"))))
        }
        unchanged { inspect { input, _ ->
            assertTrue(input.uses.any { it.source == "authority" && it.icon == IconReference.Asset(id(100)) })
            assertTrue(input.uses.any { it.source == "request" && it.icon == IconReference.Asset(id(102)) })
            assertTrue(input.uses.any { it.source == "current" && it.identity == habit.uuid && it.icon == IconReference.Role("habit.custom") })
            assertEquals(setOf(id(102)), input.missingAssets); assertTrue(input.hasReview)
        } }
    }

    @Test fun actualPreparedConfigurationKeepsAllocatedAssetDependenciesAndUnfinishedSourceState() = runBlocking<Unit> {
        initialize()
        val source = ConfigBundleOutput.create(ConfigFileFixture.manifest(), ConfigFileFixture::content)
        val materialImports = AccountIconImport(icons, iconStore, transfers)
        val controller = AccountIconController({ AccountIconRuntime(icons, iconStore,
            AccountIconRenderer(icons, iconStore), materialImports,
            AccountIconDocuments(materialImports, app.contentResolver), {}) }, tokens)
        val imports = NextConfigImportRepository(db, tokens, sessions, controller, NextObjectCreator(db, tokens, sessions, controller), app)
        val importId = try {
            val preview = imports.previewReplacement(source); assertTrue(preview.eligible)
            imports.confirmReplacement(preview)
        } finally { controller.awaitImages(); controller.close() }
        unchanged { inspect { input, _ ->
            val journal = input.business.localFacts.configuration.journals.single()
            assertEquals(importId, journal.importId); assertTrue(journal.hasUnsettledWork); assertTrue(journal.allocatedAssets.isNotEmpty())
            assertEquals(journal.allocatedAssets, input.missingAssets); assertTrue(input.hasReview)
            assertTrue(input.uses.filter { it.source == "configuration" }.all { it.identity == importId && it.oneTime == null })
            assertNull(input.themes.preferences.selectionRaw); assertTrue(input.themes.files.isEmpty())
        } }
    }

    @Test fun actualOnceTcpAcknowledgementFeedsEntireBusinessProofAndPrivateArchiveNotJustLedger() = runBlocking<Unit> {
        initialize(once = true)
        val once = OneTimeLocalIntentStore(db, tokens, sessions, preferences)
        once.appendRounds(producer().captureRounds(), OneTimeLocalCommand(task!!.uuid,
            PendingOneTimeIntent(id(200), OneTimeIntent(id(201), "complete", 0, null, null)), millis, "Asia/Shanghai"))
        val core = sender(http); val timers = NextTimerRequestStore(db, tokens, sessions, core)
        val accepted = OneTimeAcceptedEventStore(db, tokens, sessions, once, timerRequests = timers)
        assertTrue(NextOneTimeRequestStore(db, tokens, sessions, http, accepted, core).sendAndAccept(access(), id(200)) is NextOneTimeOutcome.Accepted)
        assertTrue(db.syncOutboxDao().getAll().isEmpty()); assertNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, id(200)))
        unchanged { inspect { input, _ ->
            assertTrue(input.business.records.containsKey(ReplicaRecordKey("activity_event", id(201))))
            assertFalse(input.business.hasProjectionReview); assertFalse(input.hasReview)
            assertEquals(NextRecoveryRequestState.ACCEPTED, input.business.localFacts.configuration.requests.states[NextRecoveryKey(NEXT_OPERATION, id(200))])
        } }
        val receipt = service().create(access(), storageHash()); service().verify(access(), receipt)
        assertFalse(receipt.activationAuthorized)
        // The image comparison deliberately cold-reopens the fixture; never reuse its old DAO owner.
        assertEquals(id(201), OneTimeLocalIntentStore(db, tokens, sessions, preferences).read(task!!.uuid).queue.optimisticState.completionEventUuid)
    }

    @Test fun oldServerCountWithoutOriginalPolicyRemainsReviewEvenIfTodayTargetCouldCompleteIt() = runBlocking<Unit> {
        initialize(unknownCount = true)
        unchanged { inspect { input, _ ->
            val key = ReplicaRecordKey("activity_event", id(50))
            assertTrue(input.business.records.containsKey(key)); assertTrue(key in input.business.unconfirmed)
            assertTrue(input.hasReview); assertFalse(input.activationAuthorized)
            assertTrue(input.business.localFacts.configuration.requests.states.isEmpty())
        } }
    }

    @Test fun sourceEditDuringArchiveIOMakesWholeResultStaleWithoutRemovingThePrivateCandidate() = runBlocking<Unit> {
        initialize(); val old = access(); val original = storageHash(); var created: ReplicaArchiveReceipt? = null
        assertEquals("SYNC_RECOVERY_PREVIEW_CHANGED", rejected {
            reader().inspect(old, original) { input, access ->
                created = ReplicaPrivateArchiveStore(directory).create(input, access) { assertEquals(old, tokens.localSyncAccess()) }
                producer().writeRounds(producer().captureRounds()) { metrics().updateMetric(metric.copy(name = "Concurrent real edit")) }
            }
        }.message)
        assertTrue(archive(requireNotNull(created)).isFile)
        assertEquals("Concurrent real edit", db.metricDao().getMetricById(metric.id)!!.name)
        assertFalse(requireNotNull(created).activationAuthorized); assertNotEquals(original, storageHash())
    }

    @Test fun originalIconSqlWhitespaceChangeAndThemePreferenceChangeCannotReuseTheInput() = runBlocking<Unit> {
        initialize(fixed = true); completeMaterial(); initializeThemes()
        assertEquals("ICON_RECOVERY_PREVIEW_CHANGED", rejected { inspect { _, _ ->
            iconDb.openHelper.writableDatabase.execSQL("UPDATE icon_assets SET metadataJson=' '||metadataJson")
        } }.message)
        val key = stringPreferencesKey("appearance_theme_selection_v1")
        assertEquals("THEME_RECOVERY_PREFERENCES_CHANGED", rejected { inspect { _, _ ->
            dataStore.edit { it[key] = " " + requireNotNull(it[key]) }
        } }.message)
    }

    @Test fun unrelatedPreferencesDoNotEnterArchiveButChangedAuthorityDeniesFurtherBorrowedPixels() = runBlocking<Unit> {
        initialize(fixed = true); completeMaterial()
        inspect { input, access ->
            dataStore.edit { it[stringPreferencesKey("unrelated")] = "unrelated-value" }
            assertArrayEquals(svg, access.icon(blob().sha256)); assertFalse(input.manifest.toString().contains("unrelated-value"))
        }
        assertEquals("SYNC_ACCOUNT_CHANGED", rejected { inspect { _, access ->
            sessions.exclusive { tokens.saveDeviceRegistration(id(5), caps, true, 2) }
            access.icon(blob().sha256)
        } }.message)
    }

    @Test fun mismatchedStoresOrCoordinatorAreRejectedBeforeAnyDataOrFileRead() = runBlocking<Unit> {
        initialize()
        for (factory in listOf<() -> NextReplicaArchiveInputReader>(
            { reader(files = AccountIconFiles(directory)) }, { reader(owner = TokenManager(dataStore)) },
            { reader(coordinator = com.dayforge.domain.service.AccountSessionCoordinator()) })) {
            assertEquals("SYNC_ARCHIVE_STORE_MISMATCH", rejected { factory() }.message)
        }
        assertFalse(File(directory, "replica-archives-v1").exists())
    }

    @Test fun borrowedAccessCannotEscapeToAnotherCoroutineOrSurviveCancellation() = runBlocking<Unit> {
        initialize(fixed = true); completeMaterial(); var borrowed: ReplicaArchiveFileAccess? = null
        inspect { _, access ->
            borrowed = access
            supervisorScope {
                val child = async { access.icon(blob().sha256) }
                assertEquals("SYNC_ARCHIVE_ACCESS_CLOSED", rejected { child.await() }.message)
            }
        }
        assertEquals("SYNC_ARCHIVE_ACCESS_CLOSED", rejected { borrowed!!.icon(blob().sha256) }.message)
        inspect { _, access -> assertArrayEquals(svg, access.icon(blob().sha256)) }
    }

    @Test fun coldReadbackRejectsSameLengthContentFramingTruncationAndTrailingBytesWithoutRepair() = runBlocking<Unit> {
        initialize(); val receipt = service().create(access(), storageHash()); val file = archive(receipt); val original = file.readBytes()
        val forged = ReplicaArchiveReceipt(receipt.namespace, "../owned-target", receipt.inputHash,
            receipt.byteLength, receipt.contentHash, receipt.entries)
        assertEquals("SYNC_ARCHIVE_RECEIPT_INVALID", rejected { service().verify(access(), forged) }.message)
        assertArrayEquals(original, file.readBytes())
        val corruptions = listOf(original.copyOf().also { it[0] = 0 }, original.copyOf().also { it[it.lastIndex] = 1 },
            original.copyOf().also { it[it.lastIndex - 10] = (it[it.lastIndex - 10].toInt() xor 1).toByte() },
            original.copyOf(original.size - 1), original + byteArrayOf(0))
        for (bad in corruptions) {
            file.writeBytes(bad)
            rejected { service(ReplicaPrivateArchiveStore(directory)).verify(access(), receipt) }
            assertArrayEquals(bad, file.readBytes())
        }
        file.writeBytes(original); service().verify(access(), receipt)
    }

    @Test fun existingCandidateIsNeverOverwrittenAndAllWriteReadOrSyncFailuresKeepSourceIntact() = runBlocking<Unit> {
        initialize(); val raw = storageHash(); val same = ReplicaPrivateArchiveStore(directory, identity = { id(210) })
        val receipt = service(same).create(access(), raw); val bytes = archive(receipt).readBytes()
        rejected { service(same).create(access(), raw) }; assertArrayEquals(bytes, archive(receipt).readBytes())
        val failures = listOf(
            object : ReplicaArchiveIo() { override fun write(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int) = 0 },
            object : ReplicaArchiveIo() { override fun write(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int) = length + 1 },
            object : ReplicaArchiveIo() { override fun read(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int) = throw IOException("synthetic-read") },
            object : ReplicaArchiveIo() { override fun sync(fd: FileDescriptor) = throw IOException("synthetic-sync") },
            object : ReplicaArchiveIo() {
                private var syncs = 0
                override fun sync(fd: FileDescriptor) { if (++syncs == 2) throw IOException("synthetic-directory-sync"); super.sync(fd) }
            }
        )
        for ((index, fault) in failures.withIndex()) {
            rejected { service(ReplicaPrivateArchiveStore(directory, fault) { id(220 + index) }).create(access(), raw) }
            assertEquals(raw, storageHash()); assertTrue(File(archive(receipt).parentFile, "${id(220 + index)}.candidate").exists())
        }
        service().verify(access(), receipt)
    }

    @Test fun validShortReadsAndWritesStillVerifyAllFramesThroughEof() = runBlocking<Unit> {
        initialize(); val raw = storageHash(); var writes = 0; var reads = 0
        val short = object : ReplicaArchiveIo() {
            override fun write(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int): Int {
                writes++; return super.write(fd, bytes, offset, minOf(length, 4093))
            }
            override fun read(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int): Int {
                reads++; return super.read(fd, bytes, offset, minOf(length, 4091))
            }
        }
        val store = ReplicaPrivateArchiveStore(directory, short)
        val receipt = service(store).create(access(), raw)
        assertTrue(writes > 1); assertTrue(reads > 1)
        service(store).verify(access(), receipt); assertEquals(raw, storageHash())
        assertEquals(receipt.contentHash, sha(archive(receipt).readBytes()))
        assertFalse(receipt.activationAuthorized)
    }

    @Test fun cancellationDuringActualArchiveWriteJoinsAndReleasesAllLeasesWithoutDeletingSource() = runBlocking<Unit> {
        initialize(fixed = true); completeMaterial(); val original = storageHash(); val auth = access()
        var writes = 0; lateinit var operation: Job
        val fault = object : ReplicaArchiveIo() {
            override fun write(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int): Int {
                val result = super.write(fd, bytes, offset, length); writes++; operation.cancel(); return result
            }
        }
        supervisorScope {
            operation = launch(start = CoroutineStart.LAZY) { service(ReplicaPrivateArchiveStore(directory, fault) { id(230) }).create(auth, original) }
            operation.start(); operation.join(); assertTrue(operation.isCancelled)
        }
        assertTrue(writes > 0); assertEquals(original, storageHash())
        val normal = service().create(access(), original); service().verify(access(), normal)
        assertArrayEquals(svg, File(iconRoot(), blob().sha256).readBytes())
    }

    @Test fun anotherAccountCannotVerifyThePrivateCandidateAndLinksOrSpecialTargetsAreRefused() = runBlocking<Unit> {
        initialize(); val receipt = service().create(access(), storageHash()); val file = archive(receipt); val original = file.readBytes()
        sessions.exclusive { tokens.saveLoginSession("synthetic-other", "synthetic-other-refresh", "member", id(9), false); register() }
        assertEquals("SYNC_ARCHIVE_SCOPE_CHANGED", rejected { service().verify(access(), receipt) }.message)
        assertArrayEquals(original, file.readBytes())
        sessions.exclusive { tokens.saveLoginSession("synthetic-first", "synthetic-refresh", "member", id(1), false); register() }
        assertTrue(file.delete()); val target = File(file.parentFile, "owned-target").also { it.writeBytes(original) }
        Files.createSymbolicLink(file.toPath(), target.toPath()); rejected { service().verify(access(), receipt) }
        assertArrayEquals(original, target.readBytes()); assertTrue(file.delete()); assertTrue(file.mkdir())
        rejected { service().verify(access(), receipt) }; assertTrue(file.delete()); file.writeBytes(original)
        service().verify(access(), receipt)
    }
}
