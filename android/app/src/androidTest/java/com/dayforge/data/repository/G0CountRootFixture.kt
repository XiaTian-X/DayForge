package com.dayforge.data.repository

import com.dayforge.data.history.AuthenticatedHistoryDirectory

import android.database.sqlite.SQLiteDatabase
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.local.NextReplicaFenceSql
import com.dayforge.data.local.SyncSchemaCallback
import com.dayforge.data.history.HistoryBlockCodec.Ref
import com.dayforge.data.history.HistoryBlockPlanner.LeafRange
import com.dayforge.data.history.HistoryBlockPlanner.OrderKey
import com.dayforge.data.history.HistoryBlockPlanner.Partition
import com.dayforge.data.history.HistoryBlockPlanner.Scope
import com.dayforge.data.history.HistoryBlockPlanner.UnitKey
import com.dayforge.domain.model.CountHistory
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.security.KeyStore
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import org.junit.After
import org.junit.Assert.*

/** Explicit test-only H/marker schema. Issuance always starts in the original full-validated
 * Store; the separate reader only authenticates Q and never grants P-currentness or writes. */
abstract class G0CountRootFixture : G0HouseholdColdOwnerFixture() {
    private val rootAlias = G0CountRootSeal.KEY_PREFIX + UUID.randomUUID()
    internal val rootSeal: G0CountRootSeal get() = G0CountRootSeal(rootAlias)
    @After fun removeRootKey() { deleteRootKey() }
    internal fun deleteRootKey() { KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(rootAlias) } }

    override fun domainOpeningCallback(): RoomDatabase.Callback = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            SyncSchemaCallback.onCreate(db)
            db.execSQL(G0CountBlockSql.createNodes); db.execSQL(G0CountBlockSql.createRoot)
            db.execSQL(G0CountRootSql.createSeal); db.execSQL(G0CountRootSql.createMarker)
            db.execSQL(G0CountRootSql.initializeMarker)
            G0CountRootSql.dirtyTriggers.values.forEach(db::execSQL)
            NextReplicaFenceSql.install(db)
        }
        override fun onOpen(db: SupportSQLiteDatabase) = SyncSchemaCallback.onOpen(db)
    }

    internal suspend fun openIssuer(http: NextSyncHttp, install: Boolean = true): G0HouseholdCountStore {
        val reference = coldReference()
        freshGateForColdOpen(recurringReads = true, countWrites = true, householdCountWrites = true,
            countBlockScope = true, countRootScope = true)
        storage.reopen()
        val expected = currentHousehold.map { requireNotNull(db.habitDao().getHabitById(it.id)) }
        return G0HouseholdCountStore(db, tokens, sessions, authority, domainGate, sender(http),
            jointAuthority = true, incrementalBlockScope = true, rootSeal = rootSeal).also { store ->
            store.initialize(expected, reference.schema)
            if (install) store.installIncrementalCountBlocks()
        }
    }

    internal data class RootOpened(val reader: G0CountRootReader, val owner: G0RoomColdOwnerFactory)
    internal suspend fun openRootReader(configure: (G0CountRootReader) -> Unit = {}): RootOpened {
        val reference = coldReference()
        val owner = G0RoomColdOwnerFactory(reference.main, reference.identity,
            delegateFactory = G0TimingFactory(endFaults), onFailure = { domainGate.invalidateConnection() })
        freshGateForColdOpen(recurringReads = true, countWrites = true, householdCountWrites = true,
            coldLifecycle = owner, countBlockScope = true, countRootScope = true)
        storage.reopen()
        val reader = G0CountRootReader(db, tokens, sessions, authority, domainGate, owner, rootSeal)
        configure(reader)
        reader.initialize(reference.schema)
        return RootOpened(reader, owner)
    }
    internal suspend fun rootDay(reader: G0CountRootReader, uuid: String, date: LocalDate): G0CountRootReader.View {
        var result: G0CountRootReader.View? = null
        reader.readDay(uuid, date) { result = it }
        return requireNotNull(result)
    }
    internal suspend fun rejectedUnchangedRoot(block: suspend () -> Unit): Throwable {
        val before = pageNativeState()
        val failure = runCatching { block() }.exceptionOrNull()
        assertNotNull("expected rejection", failure)
        assertEquals("failed operation may not repair or alter P/Q/H/marker", before, pageNativeState())
        return requireNotNull(failure)
    }
    internal fun closeFailedRootOwner() { db.close(); assertFalse(db.isOpen) }
    internal fun rootImage(): ByteArray { reopenReference(); return storage.snapshotClosedDatabase() }
    internal fun restoreRootImage(image: ByteArray) {
        db.close(); freshGateForColdOpen(); storage.restoreClosedDatabase(image)
    }
    internal fun <T> nativeRoot(read: (SQLiteDatabase) -> T): T = SQLiteDatabase.openDatabase(
        app.getDatabasePath("habit_database").path, null, SQLiteDatabase.OPEN_READONLY).use(read)
    internal fun editRootFile(edit: (SQLiteDatabase) -> Unit) {
        SQLiteDatabase.openDatabase(app.getDatabasePath("habit_database").path, null,
            SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.beginTransaction()
            try { edit(raw); raw.setTransactionSuccessful() } finally { raw.endTransaction() }
        }
    }
    internal data class Head(val root: G0CountRootSql.RootRow, val packet: G0CountRootSeal.Packet,
        val marker: G0CountRootSql.Marker)
    internal fun head(): Head = nativeRoot { raw ->
        val root = raw.rawQuery(G0CountRootSql.readBoundedRoot, null).use { requireNotNull(G0CountRootSql.captureRootRow(it)) }
        val packet = raw.rawQuery(G0CountRootSql.readSeal, null).use { requireNotNull(G0CountRootSql.captureSeal(it)) }
        val marker = raw.rawQuery(G0CountRootSql.readMarker, null).use { requireNotNull(G0CountRootSql.captureMarker(it)) }
        assertEquals(root.binding, packet.binding); assertEquals(root.root, packet.root)
        assertEquals(root.revision, packet.revision); assertEquals(marker.generation, packet.generation)
        assertFalse(marker.dirty); assertTrue(rootSeal.match(packet, root.binding))
        Head(root, packet, marker)
    }
    internal fun restoreMarkerForFault(raw: SQLiteDatabase, marker: G0CountRootSql.Marker) {
        // Test-only bypass of normal controlled invalidation, explicitly not an admitted writer.
        raw.execSQL("UPDATE ${G0CountRootSql.markerTable} SET generation=?,dirty=? WHERE slot=1",
            arrayOf(marker.generation, if (marker.dirty) 1L else 0L))
    }
    internal fun rootNodeBytes(digest: String): ByteArray = nativeRoot { raw ->
        raw.rawQuery("SELECT body FROM ${G0CountBlockSql.nodes} WHERE digest=?", arrayOf(digest)).use { cursor ->
            check(cursor.moveToFirst()); cursor.getBlob(0).also { check(!cursor.moveToNext()) }
        }
    }
    /** Test oracle scans Q only to locate a precise damaged leaf, never the cold reader path. */
    internal fun storedUnit(kind: String, uuid: String, month: YearMonth): Ref {
        val current = head()
        val loader = AuthenticatedHistoryDirectory.Loader { digest, limit ->
            rootNodeBytes(digest).also { assertTrue(it.size <= limit) }
        }
        val record = AuthenticatedHistoryDirectory().readPoint(current.root.root,
            OrderKey(LocalDate.of(1970, 1, 1), "U|$kind|$uuid|$month"), loader).records.single()
        fun DataInputStream.text(): String {
            val n = readInt(); check(n in 1..128 && n <= available() / 2)
            return CharArray(n) { readChar() }.concatToString()
        }
        return DataInputStream(ByteArrayInputStream(record.payload.toByteArray())).use { input ->
            assertEquals(0x43494d31, input.readInt()); assertEquals(1, input.readInt()); assertEquals(1, input.readInt())
            assertEquals(kind, input.text()); assertEquals(uuid, input.text()); assertEquals(month.toString(), input.text())
            val height = input.readInt(); val count = input.readLong(); val digest = input.text()
            assertEquals(0, input.available())
            Ref(LeafRange(UnitKey(Partition(Scope(current.root.binding), "count-incremental-$kind-v1", uuid, null), month),
                null, null), height, count, digest)
        }
    }
    internal fun assertRootDay(expected: CountHistory, actual: G0CountBusinessBlocks.DayRead) {
        assertEquals(expected.quantities[actual.date] ?: 0L, actual.quantity)
        assertEquals(expected.policies[actual.date], actual.storedPolicy)
        assertEquals(expected.policies[actual.date] ?: if (actual.date == expected.today) expected.todayPolicy else null,
            actual.effectivePolicy)
        assertEquals(actual.date in expected.unknownDates, actual.unknown)
        assertEquals(expected.completions.filter { it.recordedLocalDate == actual.date.toString() }, actual.facts)
        assertEquals(expected.today, actual.summary.today); assertEquals(expected.todayQuantity, actual.summary.todayQuantity)
        assertEquals(expected.completedToday, actual.summary.completedToday); assertEquals(expected.firstDate, actual.summary.firstDate)
        assertEquals(expected.qualifiedDates.size, actual.summary.qualifiedDayCount)
        assertEquals(expected.quantities.values.fold(0L, Math::addExact), actual.summary.totalQuantity)
    }
}
