package com.dayforge.data.appearance

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.local.TokenManager
import com.dayforge.domain.appearance.ThemeVersionRef
import com.dayforge.domain.model.IconAsset
import com.dayforge.domain.model.IconBlob
import com.dayforge.domain.model.IconPack
import com.dayforge.domain.model.IconReference
import com.dayforge.domain.service.AccountSessionCoordinator
import java.io.File
import java.io.FileDescriptor
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
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
class AccountIconSelectionTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var parent: File
    private lateinit var scope: CoroutineScope
    private lateinit var tokens: TokenManager
    private lateinit var db: AccountIconDatabase
    private lateinit var repo: AccountIconRepository
    private lateinit var store: AccountIconStore
    private val sessions = AccountSessionCoordinator()
    private fun id(n: Int) = "99000000-0000-4000-8000-${n.toString(16).padStart(12, '0')}"
    private fun source(n: Int) = """<svg width="1" height="1"><rect width="1" height="1" fill="${if (n % 2 == 0) "#ff0000" else "#0000ff"}"/></svg>""".toByteArray()
    private fun blob(n: Int): IconBlob {
        val bytes = source(n)
        return IconBlob(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
            bytes.size, "image/svg+xml", 1, 1)
    }
    private fun pack(n: Int = 10, dark: Boolean = false, task: Boolean = false, placeholder: Boolean = false): IconPack {
        val asset = IconAsset(id(n), "icon", if (task) "task" else "general", "original", blob(n), if (dark) blob(n + 1) else null)
        return IconPack("dayforge.icon-pack", 1, id(n + 100), 1, "pack", listOf(asset),
            mapOf((if (task) "task.default" else "habit.exercise") to asset.assetId), if (placeholder) asset.assetId else null)
    }
    private fun version(pack: IconPack) = IconPackVersion(pack.packId, pack.revision)
    private suspend fun login(owner: String = id(1), server: String = id(2), epoch: String = id(3), readOnly: Boolean = false) =
        sessions.exclusive {
            tokens.saveLoginSession("synthetic-access", "synthetic-refresh", "member", owner, false)
            tokens.saveServerIdentity(server, epoch)
            tokens.saveDeviceRegistration(id(4), if (readOnly) setOf("sync.read") else setOf("sync.read", "structure.write"), true, 1)
        }
    private suspend fun install(pack: IconPack): AccountIconContext {
        val context = repo.capture(); repo.reservePack(context, pack)
        for (asset in pack.assets) for (variant in listOfNotNull(asset.light, asset.dark)) {
            val bytes = listOf(source(0), source(1)).single { blob(if (it.contentEquals(source(0))) 0 else 1) == variant }
            store.install(context, asset.assetId, variant.sha256, bytes)
        }
        return context
    }
    private suspend fun rejected(block: suspend () -> Unit): Throwable {
        val error = try { block(); null } catch (e: Exception) { if (e is CancellationException) throw e; e }
        assertNotNull("Must reject", error); return requireNotNull(error)
    }
    private fun path(context: AccountIconContext, hash: String) = File(parent,
        "account-icons-v1/${context.namespace.accountId}/${context.namespace.serverInstanceId}/${context.namespace.syncEpoch}/$hash")
    private suspend fun render(renderer: AccountIconRenderer, context: AccountIconContext, reference: IconReference,
        oneTime: Boolean = false) = renderer.renderReference(context, reference, oneTime,
        ThemeVersionRef(id(900), 1), false, IconRasterSize(8, 8), 0xff00ff00.toInt())
    @Before fun setup() = runBlocking<Unit> {
        check(app.packageName == "com.dayforge.testbed")
        assertFalse(app.getDatabasePath(AccountIconDatabase.NAME).exists())
        parent = Files.createTempDirectory(app.filesDir.toPath(), "icon-selection-").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        tokens = TokenManager(PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(parent, "auth.preferences_pb") }))
        db = AccountIconDatabase.open(app); repo = AccountIconRepository(db, tokens, sessions)
        store = AccountIconStore(repo, AccountIconFiles(parent)); login()
    }
    @After fun cleanup() = runBlocking<Unit> {
        if (::db.isInitialized) db.close()
        if (::scope.isInitialized) scope.coroutineContext[Job]!!.cancelAndJoin()
        if (::parent.isInitialized) assertTrue(parent.deleteRecursively())
        assertTrue(app.deleteDatabase(AccountIconDatabase.NAME) || !app.getDatabasePath(AccountIconDatabase.NAME).exists())
    }

    @Test fun selectionReopensSurvivesLogoutAndClearNeverDeletesOwnedFiles() = runBlocking<Unit> {
        val pack = pack(); val context = install(pack)
        assertEquals(AccountIconSelection(0, null), repo.selection(context))
        val selected = store.select(context, 0, version(pack))
        assertEquals(AccountIconSelection(1, version(pack)), selected)
        db.close(); db = AccountIconDatabase.open(app); repo = AccountIconRepository(db, tokens, sessions)
        store = AccountIconStore(repo, AccountIconFiles(parent))
        assertEquals(selected, repo.selection(context))
        sessions.exclusive { tokens.clearTokens() }; rejected { repo.selection(context) }
        login(); val fresh = repo.capture(); assertEquals(selected, repo.selection(fresh))
        assertEquals(AccountIconSelection(2, null), store.select(fresh, 1, null))
        assertArrayEquals(source(10), store.read(fresh, id(10), blob(10).sha256))
        assertEquals(pack, repo.pack(fresh, pack.packId, 1))
        assertEquals(AccountIconSelection(2, null), store.select(fresh, 2, null))
    }

    @Test fun readOnlyMayChooseReadyOwnedPackButCannotInstallOrDeclare() = runBlocking<Unit> {
        val pack = pack(); install(pack); login(readOnly = true); val context = repo.capture()
        assertEquals(AccountIconSelection(1, version(pack)), store.select(context, 0, version(pack)))
        rejected { repo.reservePack(context, pack(12)) }
        rejected { store.install(context, id(10), blob(10).sha256, source(10)) }
        assertEquals(AccountIconSelection(2, null), store.select(context, 1, null))
    }

    @Test fun namespaceChangesNeverBorrowChoiceMetadataOrFiles() = runBlocking<Unit> {
        val pack = pack(); val old = install(pack); store.select(old, 0, version(pack))
        for ((owner, server, epoch) in listOf(Triple(id(5), id(2), id(3)), Triple(id(1), id(6), id(3)), Triple(id(1), id(2), id(7)))) {
            login(owner, server, epoch); val current = repo.capture()
            assertEquals(AccountIconSelection(0, null), repo.selection(current))
            rejected { store.select(current, 0, version(pack)) }; rejected { repo.selection(old) }
            val unresolved = render(AccountIconRenderer(repo, store), current, IconReference.Asset(id(10)))
            assertNull(unresolved.raster); assertEquals(IconReference.Asset(id(10)), unresolved.resolution.reference)
        }
        login(); assertEquals(AccountIconSelection(1, version(pack)), repo.selection(repo.capture()))
    }

    @Test fun exactChoiceDoesNotAdvanceGenerationAndStaleChoiceAlwaysRejects() = runBlocking<Unit> {
        val pack = pack(); val context = install(pack)
        assertEquals(1L, store.select(context, 0, version(pack)).generation)
        assertEquals(1L, store.select(context, 1, version(pack)).generation)
        rejected { store.select(context, 0, version(pack)) }
        rejected { store.select(context, 0, null) }
        assertEquals(1L, repo.selection(context).generation)
    }

    @Test fun concurrentSameGenerationHasOneWinnerAcrossRepositoryInstances() = runBlocking<Unit> {
        val first = pack(); val second = pack(11); val context = install(first); install(second)
        val otherDb = AccountIconDatabase.open(app)
        try {
            val other = AccountIconStore(AccountIconRepository(otherDb, tokens, sessions), AccountIconFiles(parent))
            val jobs = listOf(store to first, other to second).map { (target, pack) -> async(Dispatchers.IO) {
                try { target.select(context, 0, version(pack)); true } catch (e: IllegalStateException) {
                    assertEquals("ICON_SELECTION_CHANGED", e.message); false
                }
            } }
            assertEquals(1, jobs.count { it.await() }); assertEquals(1L, repo.selection(context).generation)
        } finally { otherDb.close() }
    }

    @Test fun selectionRequiresEveryReadyVariantAndKeepsPreviousChoiceOnMissingOrCorruptFile() = runBlocking<Unit> {
        val first = pack(10); val second = pack(11, dark = true)
        val context = install(first); store.select(context, 0, version(first)); repo.reservePack(context, second)
        store.install(context, id(11), blob(11).sha256, source(11))
        // Red is shared with the first ready asset; remove its receipt to require a complete pack.
        db.openHelper.writableDatabase.execSQL("DELETE FROM icon_blob_ready WHERE sha256=?", arrayOf(blob(12).sha256))
        rejected { store.select(context, 1, version(second)) }
        store.install(context, id(11), blob(12).sha256, source(12))
        val target = path(context, blob(12).sha256)
        assertTrue(target.delete()); rejected { store.select(context, 1, version(second)) }
        // Missing ready bytes must not be rebuilt implicitly, even by selecting the current pack.
        rejected { store.select(context, 1, version(first)) }
        assertEquals(AccountIconSelection(1, version(first)), repo.selection(context))
        assertTrue(target.createNewFile()); rejected { store.select(context, 1, version(second)) }
        assertEquals(AccountIconSelection(1, version(first)), repo.selection(context))
    }

    @Test fun unrelatedUnusedPackAssetAlsoMustBeReady() = runBlocking<Unit> {
        val base = pack(); val extra = pack(11).assets.single()
        val complete = base.copy(assets = base.assets + extra)
        val context = install(base); repo.reservePack(context, complete.copy(packId = id(300)))
        rejected { store.select(context, 0, IconPackVersion(id(300), 1)) }
        assertEquals(AccountIconSelection(0, null), repo.selection(context))
    }

    @Test fun abortIgnoreAndPostWriteMutationRollbackChoiceAndDoNotModifyCatalog() = runBlocking<Unit> {
        val first = pack(); val second = pack(11); val context = install(first); install(second)
        store.select(context, 0, version(first))
        for (body in listOf("SELECT RAISE(ABORT,'injected')", "SELECT RAISE(IGNORE)",
            "UPDATE icon_pack_selection SET generation=NEW.generation+1")) {
            val timing = if (body.startsWith("UPDATE")) "AFTER" else "BEFORE"
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER selection_fault $timing UPDATE ON icon_pack_selection BEGIN $body; END")
            try { rejected { store.select(context, 1, version(second)) } }
            finally { db.openHelper.writableDatabase.execSQL("DROP TRIGGER selection_fault") }
            assertEquals(AccountIconSelection(1, version(first)), repo.selection(context))
            assertEquals(first, repo.pack(context, first.packId, 1)); assertEquals(second, repo.pack(context, second.packId, 1))
        }
        assertEquals(AccountIconSelection(2, version(second)), store.select(context, 1, version(second)))
    }

    @Test fun ignoredInitialInsertIsDetectedAndRetryRetainsGenerationZero() = runBlocking<Unit> {
        val pack = pack(); val context = install(pack)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER selection_fault BEFORE INSERT ON icon_pack_selection BEGIN SELECT RAISE(IGNORE); END")
        try { rejected { store.select(context, 0, version(pack)) } }
        finally { db.openHelper.writableDatabase.execSQL("DROP TRIGGER selection_fault") }
        assertEquals(AccountIconSelection(0, null), repo.selection(context))
        assertEquals(1L, store.select(context, 0, version(pack)).generation)
    }

    @Test fun rawInvalidSelectionColumnsFailClosedRatherThanCoercingOrFallingBack() = runBlocking<Unit> {
        val pack = pack(); val context = install(pack); store.select(context, 0, version(pack))
        val sql = db.openHelper.writableDatabase
        for (mutation in listOf("generation=0", "generation=1.5", "generation=CAST('1' AS BLOB)",
            "revision=2147483648", "revision=1.5", "packId=CAST(packId AS BLOB)", "revision=NULL",
            "packId='${id(700)}'", "packId=NULL")) {
            sql.execSQL("PRAGMA foreign_keys=OFF")
            try {
                sql.execSQL("UPDATE icon_pack_selection SET $mutation")
                rejected { repo.selection(context) }; rejected { repo.resolve(context, IconReference.Role("habit.exercise"), false) }
            } finally {
                sql.execSQL("UPDATE icon_pack_selection SET generation=1,packId=?,revision=1", arrayOf(pack.packId))
                sql.execSQL("PRAGMA foreign_keys=ON")
            }
            assertEquals(AccountIconSelection(1, version(pack)), repo.selection(context))
        }
    }

    @Test fun exhaustedGenerationAllowsReadAndExactReplayButNotMutation() = runBlocking<Unit> {
        val pack = pack(); val context = install(pack); store.select(context, 0, version(pack))
        db.openHelper.writableDatabase.execSQL("UPDATE icon_pack_selection SET generation=?", arrayOf(Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, store.select(context, Long.MAX_VALUE, version(pack)).generation)
        rejected { store.select(context, Long.MAX_VALUE, null) }
        assertEquals(Long.MAX_VALUE, repo.selection(context).generation)
    }

    @Test fun selectionForeignKeyRejectsOtherNamespaceAndUnknownVersion() = runBlocking<Unit> {
        val pack = pack(); val context = install(pack)
        for (owner in listOf(id(5), id(1))) assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
            db.openHelper.writableDatabase.execSQL("INSERT INTO icon_pack_selection VALUES(?,?,?,1,?,?)",
                arrayOf<Any>(owner, id(2), id(3), pack.packId, if (owner == id(1)) 2 else 1))
        }
        assertEquals(AccountIconSelection(0, null), repo.selection(context))
    }

    @Test fun rolesFollowPackButFixedAssetDoesNotAndSwitchInvalidatesEveryRenderer() = runBlocking<Unit> {
        val first = pack(); val second = pack(11); val context = install(first); install(second)
        val renderer = AccountIconRenderer(repo, store); val other = AccountIconRenderer(repo, store)
        val role = IconReference.Role("habit.exercise"); val fixed = IconReference.Asset(id(10))
        store.select(context, 0, version(first))
        val old = render(renderer, context, role); render(other, context, fixed)
        assertEquals(0xffff0000.toInt(), old.raster!!.bitmap.getPixel(4, 4))
        assertTrue(renderer.memoryUsage().first > 0); assertTrue(other.memoryUsage().first > 0)
        store.select(context, 1, version(first))
        assertTrue(renderer.memoryUsage().first > 0); assertTrue(other.memoryUsage().first > 0)
        store.select(context, 1, version(second))
        assertEquals(0 to 0, renderer.memoryUsage()); assertEquals(0 to 0, other.memoryUsage())
        assertFalse(old.raster.bitmap.isRecycled)
        assertEquals(0xff0000ff.toInt(), render(renderer, context, role).raster!!.bitmap.getPixel(4, 4))
        assertEquals(0xffff0000.toInt(), render(renderer, context, fixed).raster!!.bitmap.getPixel(4, 4))
        assertEquals(fixed, render(renderer, context, fixed).resolution.reference)
    }

    @Test fun missingReferencesUseOnlyExplicitDisplayPlaceholderAndKeepOriginalIdentity() = runBlocking<Unit> {
        val pack = pack(10, placeholder = true); val context = install(pack); store.select(context, 0, version(pack))
        val renderer = AccountIconRenderer(repo, store)
        for ((reference, oneTime) in listOf(IconReference.Role("habit.unknown") to false,
            IconReference.Asset(id(700)) to false, IconReference.Role("task.unknown") to true)) {
            val result = render(renderer, context, reference, oneTime)
            assertEquals(reference, result.resolution.reference); assertTrue(result.resolution.placeholder)
            assertEquals(id(10), result.resolution.asset!!.assetId); assertNotNull(result.raster)
        }
        store.select(context, 1, null)
        val unresolved = render(renderer, context, IconReference.Role("habit.unknown"))
        assertNull(unresolved.raster); assertFalse(unresolved.resolution.placeholder)
    }

    @Test fun purposeIsEnforcedAndCorruptReadyDoesNotBecomePlaceholder() = runBlocking<Unit> {
        val normal = pack(10, placeholder = true); val task = pack(11, task = true)
        val context = install(normal); install(task); store.select(context, 0, version(normal))
        val renderer = AccountIconRenderer(repo, store)
        rejected { render(renderer, context, IconReference.Role("task.default")) }
        rejected { render(renderer, context, IconReference.Role("habit.exercise"), true) }
        rejected { render(renderer, context, IconReference.Asset(id(11))) }
        rejected { render(renderer, context, IconReference.Asset(id(10)), true) }
        assertNotNull(render(renderer, context, IconReference.Asset(id(11)), true).raster)
        assertTrue(path(context, blob(10).sha256).delete())
        rejected { render(renderer, context, IconReference.Role("habit.exercise")) }
        rejected { render(renderer, context, IconReference.Role("habit.unknown")) }
    }

    @Test fun pendingFixedAssetDoesNotAuthorizePixelsAndRetainsReference() = runBlocking<Unit> {
        val context = repo.capture(); repo.reserveAsset(context, pack().assets.single())
        val reference = IconReference.Asset(id(10))
        val result = render(AccountIconRenderer(repo, store), context, reference)
        assertNull(result.raster); assertEquals(reference, result.resolution.reference)
    }

    @Test fun selectedTaskPackResolvesTaskRoleAndNeverTurnsOrdinaryHabitIntoTask() = runBlocking<Unit> {
        val pack = pack(11, task = true); val context = install(pack); store.select(context, 0, version(pack))
        val renderer = AccountIconRenderer(repo, store)
        val role = IconReference.Role("task.default")
        val task = render(renderer, context, role, true)
        assertEquals(role, task.resolution.reference); assertEquals(id(11), task.resolution.asset!!.assetId)
        assertFalse(task.resolution.placeholder); assertNotNull(task.raster)
        rejected { render(renderer, context, role) }
        assertNull(render(renderer, context, IconReference.Role("habit.exercise")).raster)
    }

    @Test fun changingChoiceCannotReuseAnOldResolutionSnapshot() = runBlocking<Unit> {
        val pack = pack(); val context = install(pack)
        val old = repo.resolve(context, IconReference.Role("habit.exercise"), false)
        store.select(context, 0, version(pack)); var called = false
        rejected { repo.withSelection(context, old.selection) { called = true } }
        assertFalse(called)
    }

    @Test fun selectionWriteThatDamagesPackMetadataRollsBackBothTables() = runBlocking<Unit> {
        val pack = pack(); val context = install(pack)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER selection_fault AFTER INSERT ON icon_pack_selection BEGIN UPDATE icon_packs SET metadataJson='broken'; END")
        try { rejected { store.select(context, 0, version(pack)) } }
        finally { db.openHelper.writableDatabase.execSQL("DROP TRIGGER selection_fault") }
        assertEquals(AccountIconSelection(0, null), repo.selection(context))
        assertEquals(pack, repo.pack(context, pack.packId, 1))
        assertArrayEquals(source(10), store.read(context, id(10), blob(10).sha256))
    }

    @Test fun laterMetadataDeclarationCannotSilentlyChangeActiveSelection() = runBlocking<Unit> {
        val pack = pack(); val context = install(pack); store.select(context, 0, version(pack))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER selection_fault AFTER INSERT ON icon_packs BEGIN UPDATE icon_pack_selection SET generation=generation+1; END")
        try { rejected { repo.reservePack(context, pack(11)) } }
        finally { db.openHelper.writableDatabase.execSQL("DROP TRIGGER selection_fault") }
        assertEquals(AccountIconSelection(1, version(pack)), repo.selection(context))
        assertNull(repo.pack(context, id(111), 1)); assertNull(repo.asset(context, id(11)))
    }

    @Test fun switchingDuringActualDrawRejectsOldReferenceResultWithoutHoldingAccountLock() = runBlocking<Unit> {
        val first = pack(); val second = pack(11); val context = install(first); install(second)
        store.select(context, 0, version(first))
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val renderer = AccountIconRenderer(repo, store, draw = { bytes, asset, dark, size, tint ->
            entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); renderIcon(bytes, asset, dark, size, tint)
        })
        val work = async(Dispatchers.IO) {
            try { render(renderer, context, IconReference.Role("habit.exercise")); null } catch (e: Exception) { e }
        }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            withTimeout(5000) { store.select(context, 1, version(second)) }
        } finally { release.countDown() }
        assertNotNull(work.await()); assertEquals(0 to 0, renderer.memoryUsage())
        assertEquals(AccountIconSelection(2, version(second)), repo.selection(context))
    }

    @Test fun authenticationChangeDuringWholePackIoRejectsSelectionAndDoesNotBlockLogin() = runBlocking<Unit> {
        val pack = pack(); val context = install(pack)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val once = AtomicBoolean(true)
        val blocked = AccountIconStore(repo, AccountIconFiles(parent, object : IconFileIo() {
            override fun read(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int): Int {
                if (once.compareAndSet(true, false)) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                return super.read(fd, bytes, offset, length)
            }
        }))
        val work = async(Dispatchers.IO) {
            try { blocked.select(context, 0, version(pack)); null } catch (e: Exception) { e }
        }
        try { assertTrue(entered.await(10, TimeUnit.SECONDS)); withTimeout(5000) { login() } }
        finally { release.countDown() }
        assertNotNull(work.await()); assertEquals(AccountIconSelection(0, null), repo.selection(repo.capture()))
    }

    @Test fun cancelDuringWholePackIoWaitsForRealReadWithoutCommittingSelection() = runBlocking<Unit> {
        val pack = pack(); val context = install(pack)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val once = AtomicBoolean(true)
        val blocked = AccountIconStore(repo, AccountIconFiles(parent, object : IconFileIo() {
            override fun read(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int): Int {
                if (once.compareAndSet(true, false)) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                return super.read(fd, bytes, offset, length)
            }
        }))
        val work = async(Dispatchers.IO) { blocked.select(context, 0, version(pack)) }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS)); work.cancel()
            assertFalse(work.isCompleted)
            assertEquals(AccountIconSelection(0, null), withTimeout(5000) { repo.selection(context) })
        } finally { release.countDown() }
        work.join(); assertTrue(work.isCancelled)
        assertEquals(AccountIconSelection(0, null), repo.selection(context))
        assertEquals(1L, store.select(context, 0, version(pack)).generation)
    }
}
