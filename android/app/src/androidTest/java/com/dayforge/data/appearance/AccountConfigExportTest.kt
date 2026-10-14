package com.dayforge.data.appearance

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.local.TokenManager
import com.dayforge.domain.model.ConfigBundle
import com.dayforge.domain.model.IconAsset
import com.dayforge.domain.model.IconPack
import com.dayforge.domain.model.IconReference
import com.dayforge.domain.service.AccountSessionCoordinator
import java.io.File
import java.io.FileDescriptor
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountConfigExportTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var parent: File
    private lateinit var scope: CoroutineScope
    private lateinit var tokens: TokenManager
    private lateinit var db: AccountIconDatabase
    private lateinit var metadata: AccountIconRepository
    private lateinit var store: AccountIconStore
    private val sessions = AccountSessionCoordinator()
    private val full get() = ConfigFileFixture.manifest()
    private fun id(n: Int) = "86000000-0000-4000-8000-${n.toString(16).padStart(12, '0')}"
    private suspend fun login(owner: String = id(1), server: String = id(2), epoch: String = id(3)) = sessions.exclusive {
        tokens.saveLoginSession("synthetic-access", "synthetic-refresh", "member", owner, false)
        tokens.saveServerIdentity(server, epoch)
        tokens.saveDeviceRegistration(id(4), setOf("sync.read", "structure.write"), true, 1)
    }
    private fun uses(source: ConfigBundle) = source.nodes.map {
        ConfigIconUse(it.appearance.icon, it.activity?.completionPolicy == "one_and_done")
    } + source.metrics.map { ConfigIconUse(it.appearance.icon, false) }
    private fun manifest(pack: IconPack?, unresolved: List<String>, source: ConfigBundle = full) =
        source.copy(iconPack = pack, unresolvedRoles = unresolved)
    private fun exporter(io: IconFileIo = IconFileIo()) =
        AccountConfigExport(metadata, AccountIconStore(metadata, AccountIconFiles(parent, io)))
    private suspend fun install(context: AccountIconContext, pack: IconPack) {
        metadata.reservePack(context, pack)
        store.installPack(context, pack) { hash -> ConfigFileFixture.content(
            pack.assets.flatMap { listOfNotNull(it.light, it.dark) }.first { it.sha256 == hash }) }
    }
    private suspend fun prepare(context: AccountIconContext, service: AccountConfigExport = exporter(),
        source: ConfigBundle = full) = service.prepare(context, uses(source)) { pack, unresolved -> manifest(pack, unresolved, source) }
    private fun path(context: AccountIconContext, hash: String) = File(parent,
        "account-icons-v1/${context.namespace.accountId}/${context.namespace.serverInstanceId}/${context.namespace.syncEpoch}/$hash")
    private fun durable() = db.openHelper.readableDatabase.query(
        "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'icon_%' ORDER BY name").use { tables ->
        buildMap<String, List<List<String?>>> {
            while (tables.moveToNext()) {
                val table = tables.getString(0)
                put(table, db.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY rowid").use { rows ->
                    buildList { while (rows.moveToNext()) add((0 until rows.columnCount).map {
                        if (rows.isNull(it)) null else rows.getString(it)
                    }) }
                })
            }
        }
    }
    private suspend fun failure(block: suspend () -> Unit): Throwable =
        requireNotNull(runCatching { block() }.exceptionOrNull()) { "Expected explicit export failure" }

    @Before fun setup() = runBlocking<Unit> {
        check(app.packageName == "com.dayforge.testbed")
        assertFalse(app.getDatabasePath(AccountIconDatabase.NAME).exists())
        parent = Files.createTempDirectory(app.filesDir.toPath(), "config-owned-").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        tokens = TokenManager(PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(parent, "auth.preferences_pb") }))
        db = AccountIconDatabase.open(app)
        metadata = AccountIconRepository(db, tokens, sessions)
        store = AccountIconStore(metadata, AccountIconFiles(parent))
        login()
    }
    @After fun cleanup() = runBlocking<Unit> {
        if (::db.isInitialized) db.close()
        if (::scope.isInitialized) scope.coroutineContext[Job]!!.cancelAndJoin()
        if (::parent.isInitialized) assertTrue(parent.deleteRecursively())
        assertTrue(app.deleteDatabase(AccountIconDatabase.NAME) || !app.getDatabasePath(AccountIconDatabase.NAME).exists())
    }

    @Test fun actualSelectedRolesAndFixedAssetsOutsidePackExportOnlyUsedOwnedVariants() = runBlocking<Unit> {
        val context = metadata.capture()
        val source = full
        val original = source.iconPack!!
        // Keep the fixed asset outside the selected pack; also leave an unrelated reservation unready.
        val selected = original.copy(assets = listOf(original.assets[1]),
            roles = mapOf("task.default" to original.assets[1].assetId), placeholderAssetId = null)
        install(context, selected)
        val fixed = original.assets[0]
        metadata.reserveAsset(context, fixed)
        for (blob in listOfNotNull(fixed.light, fixed.dark)) store.install(context, fixed.assetId, blob.sha256, ConfigFileFixture.content(blob))
        metadata.reserveAsset(context, fixed.copy(assetId = id(99), name = "Unrelated pending asset"))
        store.select(context, 0, IconPackVersion(selected.packId, selected.revision))
        val before = durable()
        val readBytes = AtomicInteger()
        val service = exporter(object : IconFileIo() {
            override fun read(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int): Int =
                super.read(fd, bytes, offset, length).also { if (it > 0) readBytes.addAndGet(it) }
        })
        val result = prepare(context, service)
        val exported = service.publish(result) { it }
        assertEquals(setOf(fixed.assetId, original.assets[1].assetId), exported.manifest.iconPack!!.assets.map { it.assetId }.toSet())
        assertEquals(selected.roles, exported.manifest.iconPack.roles)
        assertNull(exported.manifest.iconPack.placeholderAssetId)
        assertNotEquals(selected.packId, exported.manifest.iconPack.packId)
        assertNull(metadata.pack(context, exported.manifest.iconPack.packId, 1))
        assertEquals(listOf("goal.default", "habit.water", "metric.weight"), exported.manifest.unresolvedRoles)
        assertEquals(uses(source), uses(exported.manifest))
        assertEquals(listOf(ConfigFileFixture.svg, ConfigFileFixture.dark, ConfigFileFixture.png).sumOf { it.size }, readBytes.get())
        assertEquals(before, durable())
        val frozen = exported.exportBytes()
        exported.exportBytes().fill(0)
        assertArrayEquals(frozen, exported.exportBytes())
        db.close(); db = AccountIconDatabase.open(app)
        metadata = AccountIconRepository(db, tokens, sessions)
        store = AccountIconStore(metadata, AccountIconFiles(parent))
        assertEquals(before, durable())
        assertEquals(exported.manifest.iconPack.assets, metadata.configDependencies(context, uses(source)).assets)
    }

    @Test fun sharedHashDeduplicatesBytesButRequiresEachFixedAssetToBeOwned() = runBlocking<Unit> {
        val context = metadata.capture()
        val original = full.iconPack!!
        val first = original.assets[0].copy(dark = original.assets[0].light)
        val second = first.copy(assetId = id(11), name = "Other identity")
        metadata.reserveAsset(context, first); metadata.reserveAsset(context, second)
        store.install(context, first.assetId, first.light.sha256, ConfigFileFixture.svg)
        val source = full.copy(nodes = full.nodes.filter { it.key == "goal" || it.key == "count" }, metrics = emptyList(), links = emptyList(),
            iconPack = original.copy(assets = listOf(first, second), roles = emptyMap(), placeholderAssetId = null),
            unresolvedRoles = listOf("goal.default"))
        val refs = listOf(ConfigIconUse(IconReference.Asset(first.assetId), false), ConfigIconUse(IconReference.Asset(second.assetId), false))
        val dependency = metadata.configDependencies(context, refs)
        assertEquals(2, dependency.assets.size); assertEquals(1, dependency.blobs.size)
        assertEquals(minOf(first.assetId, second.assetId), dependency.blobs.values.single().assetId)
        assertEquals(1, prepare(context, source = source).bundle.manifest.iconPack!!.assets.size)
        assertEquals("ICON_ASSET_NOT_OWNED", failure {
            metadata.configDependencies(context, refs + ConfigIconUse(IconReference.Asset(id(12)), false))
        }.message)
    }

    @Test fun unresolvedRolesDoNotReadFilesOrSubstituteSelectedPlaceholder() = runBlocking<Unit> {
        val context = metadata.capture()
        val source = full.copy(nodes = listOf(full.nodes.first()), metrics = emptyList(), links = emptyList(),
            unresolvedRoles = listOf("goal.default"))
        val first = full.iconPack!!.assets.first()
        val selected = full.iconPack!!.copy(assets = listOf(first), roles = emptyMap(), placeholderAssetId = first.assetId)
        install(context, selected); store.select(context, 0, IconPackVersion(selected.packId, 1))
        val before = durable()
        val service = exporter(object : IconFileIo() {
            override fun read(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int): Int = error("No placeholder read")
        })
        val result = prepare(context, service, source)
        assertNull(result.bundle.manifest.iconPack)
        assertEquals(listOf("goal.default"), result.bundle.manifest.unresolvedRoles)
        assertEquals(before, durable())
        assertEquals(IconReference.Role("goal.default"), result.bundle.manifest.nodes.single().appearance.icon)
    }

    @Test fun unreadyMissingAndDamagedBytesCannotBecomeUnresolvedOrSuccessfulExport() = runBlocking<Unit> {
        val context = metadata.capture(); val pack = full.iconPack!!
        metadata.reservePack(context, pack)
        assertEquals("ICON_NOT_READY", failure { prepare(context) }.message)
        install(context, pack)
        val before = durable(); val file = path(context, pack.assets[0].light.sha256)
        val bytes = file.readBytes()
        assertTrue(file.delete())
        failure { prepare(context) }
        file.writeBytes(bytes.also { it[0] = 0 })
        failure { prepare(context) }
        assertEquals(before, durable())
    }

    @Test fun taskPurposeAndManifestDependencyChangesRejectBeforeByteReads() = runBlocking<Unit> {
        val context = metadata.capture(); install(context, full.iconPack!!)
        for (use in listOf(ConfigIconUse(IconReference.Role("task.default"), false),
            ConfigIconUse(IconReference.Role("habit.water"), true),
            ConfigIconUse(IconReference.Asset(full.iconPack!!.assets[1].assetId), false))) {
            assertEquals("ICON_PURPOSE_MISMATCH", failure { metadata.configDependencies(context, listOf(use)) }.message)
        }
        val service = exporter(object : IconFileIo() {
            override fun read(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int): Int = error("Invalid source must fail before I/O")
        })
        assertEquals("CONFIG_ICON_SNAPSHOT_CHANGED", failure {
            service.prepare(context, uses(full)) { pack, missing -> manifest(pack!!.copy(packId = id(99)), missing) }
        }.message)
        assertEquals("CONFIG_ICON_REFERENCES_CHANGED", failure {
            service.prepare(context, uses(full)) { pack, missing ->
                val source = manifest(pack, missing)
                source.copy(nodes = source.nodes.reversed())
            }
        }.message)
    }

    @Test fun frozenInputsAndMetadataCannotBeMutatedIntoExtraDependencies() = runBlocking<Unit> {
        val context = metadata.capture(); install(context, full.iconPack!!)
        val references = uses(full).toMutableList()
        val service = exporter()
        val result = service.prepare(context, references) { pack, missing ->
            references.clear()
            assertThrows(UnsupportedOperationException::class.java) { (pack!!.assets as MutableList<IconAsset>).clear() }
            assertThrows(UnsupportedOperationException::class.java) { (missing as MutableList<String>).clear() }
            manifest(pack, missing)
        }
        assertEquals(uses(full), uses(result.bundle.manifest))
        assertEquals("CONFIG_ICON_DEPENDENCY_LIMIT", failure {
            metadata.configDependencies(context, (0..256).map { ConfigIconUse(IconReference.Role("habit.r$it"), false) })
        }.message)
        assertTrue(failure { metadata.configDependencies(context, List(2001) { ConfigIconUse(IconReference.Role("goal.default"), false) }) }
            is IllegalArgumentException)
    }

    @Test fun originalAuthenticationDevicePermissionsAndReplicaGuardPrepareAndLatePublication() = runBlocking<Unit> {
        val context = metadata.capture(); install(context, full.iconPack!!)
        val service = exporter(); val result = prepare(context, service)
        val before = durable()
        val transitions: List<suspend () -> Unit> = listOf(
            { login() }, { login(owner = id(9)) }, { login(server = id(9)) }, { login(epoch = id(9)) },
            { sessions.exclusive { tokens.saveDeviceRegistration(id(9), setOf("sync.read", "structure.write"), true, 1) } },
            { sessions.exclusive { tokens.saveDeviceRegistration(id(4), setOf("sync.read"), true, 2) } },
            { sessions.exclusive { tokens.clearAuthenticationTokens() } }
        )
        for (transition in transitions) {
            transition()
            assertEquals("ICON_SESSION_CHANGED", failure { service.publish(result) { error("Stale publication") } }.message)
            assertEquals("ICON_SESSION_CHANGED", failure { prepare(context, service) }.message)
            assertEquals(before, durable())
        }
    }

    @Test fun readOnlyAuthorityCanExportOfflineAndSameGenerationRefreshKeepsPreparedResultValid() = runBlocking<Unit> {
        val original = metadata.capture(); install(original, full.iconPack!!)
        sessions.exclusive { tokens.saveDeviceRegistration(id(4), setOf("sync.read"), true, 2) }
        val context = metadata.capture(); assertFalse(context.access.canDeclare)
        val service = exporter(); val before = durable(); val result = prepare(context, service)
        sessions.exclusive {
            assertTrue(tokens.saveRefreshedTokens(requireNotNull(tokens.authenticationSnapshot()),
                "refreshed-synthetic-access", "refreshed-synthetic-refresh", "member", id(1), false))
        }
        assertEquals(context.access, metadata.capture().access)
        assertEquals(full.nodes.size, service.publish(result) { it.manifest.nodes.size })
        assertEquals(before, durable())
    }

    private class ReadBarrier : IconFileIo() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        private val once = AtomicBoolean(true)
        override fun read(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int): Int {
            if (once.compareAndSet(true, false)) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
            return super.read(fd, bytes, offset, length)
        }
    }

    @Test fun loginDuringActualFileReadDoesNotWaitForAccountLockAndRejectsOldResult() = runBlocking<Unit> {
        val context = metadata.capture(); install(context, full.iconPack!!)
        val barrier = ReadBarrier(); val service = exporter(barrier); val before = durable()
        val work = async(Dispatchers.IO) { runCatching { prepare(context, service) }.exceptionOrNull() }
        try {
            assertTrue(barrier.entered.await(10, TimeUnit.SECONDS))
            withTimeout(5000) { login(owner = id(9)) }
        } finally { barrier.release.countDown() }
        assertEquals("ICON_SESSION_CHANGED", work.await()?.message)
        assertEquals(before, durable())
    }

    @Test fun selectionChangeDuringReadOrBeforePublicationRejectsFrozenOldStyle() = runBlocking<Unit> {
        val context = metadata.capture(); val pack = full.iconPack!!; install(context, pack)
        store.select(context, 0, IconPackVersion(pack.packId, 1))
        val service = exporter(); val prepared = prepare(context, service)
        val barrier = ReadBarrier(); val work = async(Dispatchers.IO) {
            runCatching { prepare(context, exporter(barrier)) }.exceptionOrNull()
        }
        try {
            assertTrue(barrier.entered.await(10, TimeUnit.SECONDS))
            withTimeout(5000) { store.select(context, 1, null) }
        } finally { barrier.release.countDown() }
        assertEquals("CONFIG_ICON_SNAPSHOT_CHANGED", work.await()?.message)
        assertEquals("ICON_SELECTION_CHANGED", failure { service.publish(prepared) { error("Old style") } }.message)
        assertEquals(AccountIconSelection(2, null), metadata.selection(context))
    }

    @Test fun cancellationJoinsActualReadAndReleasesFileLeaseWithoutMutation() = runBlocking<Unit> {
        val context = metadata.capture(); install(context, full.iconPack!!)
        val before = durable(); val barrier = ReadBarrier()
        val work = async(Dispatchers.IO) { prepare(context, exporter(barrier)) }
        try {
            assertTrue(barrier.entered.await(10, TimeUnit.SECONDS)); work.cancel()
            assertFalse(work.isCompleted)
            assertEquals(before, withTimeout(5000) { durable() })
        } finally { barrier.release.countDown() }
        work.join(); assertTrue(work.isCancelled); assertFalse(work.children.any())
        assertEquals(before, durable())
        assertEquals(full.nodes.size, withTimeout(5000) { prepare(context).bundle.manifest.nodes.size })
    }
}
