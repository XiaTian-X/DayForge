package com.dayforge.data.appearance

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "icon_assets", primaryKeys = ["accountId", "serverInstanceId", "syncEpoch", "assetId"])
internal data class AccountIconAssetRow(
    val accountId: String, val serverInstanceId: String, val syncEpoch: String,
    val assetId: String, val metadataJson: String
)

@Entity(tableName = "icon_packs", primaryKeys = ["accountId", "serverInstanceId", "syncEpoch", "packId", "revision"])
internal data class AccountIconPackRow(
    val accountId: String, val serverInstanceId: String, val syncEpoch: String,
    val packId: String, val revision: Int, val metadataJson: String
)

/** Reserved only: no bytes, readiness, server acknowledgement or active-pack state. */
@Entity(
    tableName = "icon_blob_reservations",
    primaryKeys = ["accountId", "serverInstanceId", "syncEpoch", "sha256"],
    indices = [Index(value = ["accountId", "serverInstanceId", "syncEpoch", "operationId"], unique = true)]
)
internal data class AccountIconBlobRow(
    val accountId: String, val serverInstanceId: String, val syncEpoch: String,
    val sha256: String, val byteLength: Int, val mediaType: String,
    val width: Int, val height: Int, val operationId: String
)

/** Local validation receipt, not remote acknowledgement. Reservations remain the durable journal. */
@Entity(
    tableName = "icon_blob_ready",
    primaryKeys = ["accountId", "serverInstanceId", "syncEpoch", "sha256"],
    foreignKeys = [ForeignKey(entity = AccountIconBlobRow::class,
        parentColumns = ["accountId", "serverInstanceId", "syncEpoch", "sha256"],
        childColumns = ["accountId", "serverInstanceId", "syncEpoch", "sha256"])]
)
internal data class AccountIconReadyRow(
    val accountId: String, val serverInstanceId: String, val syncEpoch: String,
    val sha256: String, val operationId: String, val validationProfile: String
)

/** Device-local presentation preference. A null pair is an explicit no-pack choice. */
@Entity(
    tableName = "icon_pack_selection",
    primaryKeys = ["accountId", "serverInstanceId", "syncEpoch"],
    foreignKeys = [ForeignKey(entity = AccountIconPackRow::class,
        parentColumns = ["accountId", "serverInstanceId", "syncEpoch", "packId", "revision"],
        childColumns = ["accountId", "serverInstanceId", "syncEpoch", "packId", "revision"])],
    indices = [Index(value = ["accountId", "serverInstanceId", "syncEpoch", "packId", "revision"])]
)
internal data class AccountIconSelectionRow(
    val accountId: String, val serverInstanceId: String, val syncEpoch: String,
    val generation: Long, val packId: String?, val revision: Int?
)

/** Fresh SQL scalars, not a cached proof or a stored row; checked before Room coerces values. */
internal data class AccountIconAudit(
    val invalidStoredValues: Boolean, val invalidReadyValues: Boolean, val invalidSelectionValues: Boolean,
    val assetCount: Long, val packCount: Long, val metadataBytes: Long
)

@Dao
internal interface AccountIconDao {
    // The original predicates/counts share one Room dispatch. All real rows are still read and
    // audited in the caller's same transaction; never reuse this result across calls.
    @Query("""SELECT
        (EXISTS(SELECT 1 FROM icon_assets WHERE accountId=:account AND serverInstanceId=:server AND syncEpoch=:epoch AND (typeof(assetId)<>'text' OR typeof(metadataJson)<>'text'))
         OR EXISTS(SELECT 1 FROM icon_packs WHERE accountId=:account AND serverInstanceId=:server AND syncEpoch=:epoch AND (typeof(packId)<>'text' OR typeof(metadataJson)<>'text' OR typeof(revision)<>'integer' OR revision<1 OR revision>2147483647))
         OR EXISTS(SELECT 1 FROM icon_blob_reservations WHERE accountId=:account AND serverInstanceId=:server AND syncEpoch=:epoch AND (typeof(sha256)<>'text' OR typeof(mediaType)<>'text' OR typeof(operationId)<>'text' OR typeof(byteLength)<>'integer' OR typeof(width)<>'integer' OR typeof(height)<>'integer' OR byteLength<1 OR byteLength>2097152 OR width<1 OR width>1024 OR height<1 OR height>1024))) AS invalidStoredValues,
        EXISTS(SELECT 1 FROM icon_blob_ready WHERE accountId=:account AND serverInstanceId=:server AND syncEpoch=:epoch AND (typeof(sha256)<>'text' OR typeof(operationId)<>'text' OR typeof(validationProfile)<>'text')) AS invalidReadyValues,
        EXISTS(SELECT 1 FROM icon_pack_selection WHERE accountId=:account AND serverInstanceId=:server AND syncEpoch=:epoch AND (typeof(generation)<>'integer' OR generation<1 OR (packId IS NULL)!=(revision IS NULL) OR (packId IS NOT NULL AND (typeof(packId)<>'text' OR typeof(revision)<>'integer' OR revision<1 OR revision>2147483647)))) AS invalidSelectionValues,
        (SELECT COUNT(*) FROM icon_assets WHERE accountId=:account AND serverInstanceId=:server AND syncEpoch=:epoch) AS assetCount,
        (SELECT COUNT(*) FROM icon_packs WHERE accountId=:account AND serverInstanceId=:server AND syncEpoch=:epoch) AS packCount,
        (SELECT COALESCE(SUM(size), 0) FROM (SELECT LENGTH(CAST(metadataJson AS BLOB)) AS size FROM icon_assets WHERE accountId=:account AND serverInstanceId=:server AND syncEpoch=:epoch UNION ALL SELECT LENGTH(CAST(metadataJson AS BLOB)) AS size FROM icon_packs WHERE accountId=:account AND serverInstanceId=:server AND syncEpoch=:epoch)) AS metadataBytes
    """)
    suspend fun audit(account: String, server: String, epoch: String): AccountIconAudit

    @Query("SELECT * FROM icon_pack_selection WHERE accountId=:account AND serverInstanceId=:server AND syncEpoch=:epoch")
    suspend fun selection(account: String, server: String, epoch: String): AccountIconSelectionRow?

    @Insert
    suspend fun insertSelection(row: AccountIconSelectionRow)

    @Query("UPDATE icon_pack_selection SET generation=:next, packId=:pack, revision=:revision WHERE accountId=:account AND serverInstanceId=:server AND syncEpoch=:epoch AND generation=:expected")
    suspend fun updateSelection(account: String, server: String, epoch: String, expected: Long, next: Long,
        pack: String?, revision: Int?): Int

    @Query("SELECT * FROM icon_assets WHERE accountId=:account AND serverInstanceId=:server AND syncEpoch=:epoch LIMIT 1001")
    suspend fun assets(account: String, server: String, epoch: String): List<AccountIconAssetRow>

    @Query("SELECT * FROM icon_packs WHERE accountId=:account AND serverInstanceId=:server AND syncEpoch=:epoch LIMIT 32769")
    suspend fun packs(account: String, server: String, epoch: String): List<AccountIconPackRow>

    @Query("SELECT * FROM icon_blob_reservations WHERE accountId=:account AND serverInstanceId=:server AND syncEpoch=:epoch LIMIT 2001")
    suspend fun blobs(account: String, server: String, epoch: String): List<AccountIconBlobRow>

    @Query("SELECT * FROM icon_blob_ready WHERE accountId=:account AND serverInstanceId=:server AND syncEpoch=:epoch LIMIT 2001")
    suspend fun ready(account: String, server: String, epoch: String): List<AccountIconReadyRow>

    @Insert
    suspend fun insertAsset(row: AccountIconAssetRow)
    @Insert
    suspend fun insertPack(row: AccountIconPackRow)
    @Insert
    suspend fun insertBlob(row: AccountIconBlobRow)
    @Insert
    suspend fun insertReady(row: AccountIconReadyRow)
}

/** Separate lifetime from HabitDatabase.clearAllData; never destructively reset on mismatch. */
@Database(
    entities = [AccountIconAssetRow::class, AccountIconPackRow::class, AccountIconBlobRow::class,
        AccountIconReadyRow::class, AccountIconSelectionRow::class, AccountIconTransferRow::class],
    version = 4, exportSchema = true
)
internal abstract class AccountIconDatabase : RoomDatabase() {
    abstract fun icons(): AccountIconDao
    abstract fun transfers(): AccountIconTransferDao

    companion object {
        const val NAME = "account_icon_database"
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Room publishes the new identity after migration; prove the original baseline first.
                db.query("SELECT id, identity_hash, typeof(identity_hash), typeof(id) FROM room_master_table").use {
                    check(it.moveToFirst() && it.getString(2) == "text" && it.getString(3) == "integer" &&
                        it.getLong(0) == 42L && it.getString(1) == "897ac35249ffe16fe3d16ea58dac5b54" && !it.moveToNext()) {
                        "ICON_V1_SCHEMA_IDENTITY"
                    }
                }
                db.execSQL("CREATE TABLE IF NOT EXISTS `icon_blob_ready` (`accountId` TEXT NOT NULL, `serverInstanceId` TEXT NOT NULL, `syncEpoch` TEXT NOT NULL, `sha256` TEXT NOT NULL, `operationId` TEXT NOT NULL, `validationProfile` TEXT NOT NULL, PRIMARY KEY(`accountId`, `serverInstanceId`, `syncEpoch`, `sha256`), FOREIGN KEY(`accountId`, `serverInstanceId`, `syncEpoch`, `sha256`) REFERENCES `icon_blob_reservations`(`accountId`, `serverInstanceId`, `syncEpoch`, `sha256`) ON UPDATE NO ACTION ON DELETE NO ACTION)")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Room updates identity/version only after the entire migration chain. A 1→3
                // open still carries the original v1 identity already proved by MIGRATION_1_2.
                val identity = when (db.version) {
                    1 -> "897ac35249ffe16fe3d16ea58dac5b54"
                    2 -> "04c8718e516aed6eaacbdc5777ea61af"
                    else -> error("ICON_SELECTION_SCHEMA_VERSION")
                }
                db.query("SELECT id, identity_hash, typeof(identity_hash), typeof(id) FROM room_master_table").use {
                    check(it.moveToFirst() && it.getString(2) == "text" && it.getString(3) == "integer" &&
                        it.getLong(0) == 42L && it.getString(1) == identity && !it.moveToNext()) {
                        "ICON_SELECTION_SCHEMA_IDENTITY"
                    }
                }
                // A properly rolled-back upgrade leaves neither object. Never adopt even a
                // structurally valid foreign table and its unproven active-choice rows.
                db.query("SELECT name FROM sqlite_master WHERE name COLLATE NOCASE IN ('icon_pack_selection', 'index_icon_pack_selection_accountId_serverInstanceId_syncEpoch_packId_revision')").use {
                    check(!it.moveToFirst()) { "ICON_SELECTION_SCHEMA_COLLISION" }
                }
                db.execSQL("CREATE TABLE IF NOT EXISTS `icon_pack_selection` (`accountId` TEXT NOT NULL, `serverInstanceId` TEXT NOT NULL, `syncEpoch` TEXT NOT NULL, `generation` INTEGER NOT NULL, `packId` TEXT, `revision` INTEGER, PRIMARY KEY(`accountId`, `serverInstanceId`, `syncEpoch`), FOREIGN KEY(`accountId`, `serverInstanceId`, `syncEpoch`, `packId`, `revision`) REFERENCES `icon_packs`(`accountId`, `serverInstanceId`, `syncEpoch`, `packId`, `revision`) ON UPDATE NO ACTION ON DELETE NO ACTION)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_icon_pack_selection_accountId_serverInstanceId_syncEpoch_packId_revision` ON `icon_pack_selection` (`accountId`, `serverInstanceId`, `syncEpoch`, `packId`, `revision`)")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // The identity/version stays at the ORIGINAL version throughout a Room chain.
                val identity = when (db.version) {
                    1 -> "897ac35249ffe16fe3d16ea58dac5b54"
                    2 -> "04c8718e516aed6eaacbdc5777ea61af"
                    3 -> "4e6a7afa36b3ad419626fcdfbe1e7817"
                    else -> error("ICON_TRANSFER_SCHEMA_VERSION")
                }
                db.query("SELECT id, identity_hash, typeof(identity_hash), typeof(id) FROM room_master_table").use {
                    check(it.moveToFirst() && it.getString(2) == "text" && it.getString(3) == "integer" &&
                        it.getLong(0) == 42L && it.getString(1) == identity && !it.moveToNext()) {
                        "ICON_TRANSFER_SCHEMA_IDENTITY"
                    }
                }
                db.query("SELECT name FROM sqlite_master WHERE name COLLATE NOCASE IN ('icon_transfers', 'index_icon_transfers_accountId_serverInstanceId_syncEpoch_kind_targetId_revision_variant')").use {
                    check(!it.moveToFirst()) { "ICON_TRANSFER_SCHEMA_COLLISION" }
                }
                db.execSQL("CREATE TABLE `icon_transfers` (`accountId` TEXT NOT NULL, `serverInstanceId` TEXT NOT NULL, `syncEpoch` TEXT NOT NULL, `operationId` TEXT NOT NULL, `kind` TEXT NOT NULL, `targetId` TEXT NOT NULL, `revision` INTEGER NOT NULL, `variant` TEXT NOT NULL, `metadataHash` TEXT NOT NULL, `state` TEXT NOT NULL, `generation` INTEGER NOT NULL, `deviceId` TEXT, `failureCode` TEXT, `confirmationHash` TEXT, `readyMask` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `serverInstanceId`, `syncEpoch`, `operationId`))")
                db.execSQL("CREATE UNIQUE INDEX `index_icon_transfers_accountId_serverInstanceId_syncEpoch_kind_targetId_revision_variant` ON `icon_transfers` (`accountId`, `serverInstanceId`, `syncEpoch`, `kind`, `targetId`, `revision`, `variant`)")
            }
        }
        fun open(context: Context): AccountIconDatabase =
            Room.databaseBuilder(context.applicationContext, AccountIconDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
    }
}
