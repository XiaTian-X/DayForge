package com.dayforge.data.appearance

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

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

@Dao
internal interface AccountIconDao {
    // Check SQLite's stored values before Room's generated (long -> int) coercion.
    @Query("SELECT EXISTS(SELECT 1 FROM icon_assets WHERE accountId=:account AND serverInstanceId=:server AND syncEpoch=:epoch AND (typeof(assetId)<>'text' OR typeof(metadataJson)<>'text')) OR EXISTS(SELECT 1 FROM icon_packs WHERE accountId=:account AND serverInstanceId=:server AND syncEpoch=:epoch AND (typeof(packId)<>'text' OR typeof(metadataJson)<>'text' OR typeof(revision)<>'integer' OR revision<1 OR revision>2147483647)) OR EXISTS(SELECT 1 FROM icon_blob_reservations WHERE accountId=:account AND serverInstanceId=:server AND syncEpoch=:epoch AND (typeof(sha256)<>'text' OR typeof(mediaType)<>'text' OR typeof(operationId)<>'text' OR typeof(byteLength)<>'integer' OR typeof(width)<>'integer' OR typeof(height)<>'integer' OR byteLength<1 OR byteLength>2097152 OR width<1 OR width>1024 OR height<1 OR height>1024))")
    suspend fun invalidStoredValues(account: String, server: String, epoch: String): Boolean

    @Query("SELECT COUNT(*) FROM icon_assets WHERE accountId=:account AND serverInstanceId=:server AND syncEpoch=:epoch")
    suspend fun assetCount(account: String, server: String, epoch: String): Long

    @Query("SELECT COUNT(*) FROM icon_packs WHERE accountId=:account AND serverInstanceId=:server AND syncEpoch=:epoch")
    suspend fun packCount(account: String, server: String, epoch: String): Long

    @Query("SELECT COALESCE(SUM(size), 0) FROM (SELECT LENGTH(CAST(metadataJson AS BLOB)) AS size FROM icon_assets WHERE accountId=:account AND serverInstanceId=:server AND syncEpoch=:epoch UNION ALL SELECT LENGTH(CAST(metadataJson AS BLOB)) AS size FROM icon_packs WHERE accountId=:account AND serverInstanceId=:server AND syncEpoch=:epoch)")
    suspend fun metadataBytes(account: String, server: String, epoch: String): Long

    @Query("SELECT * FROM icon_assets WHERE accountId=:account AND serverInstanceId=:server AND syncEpoch=:epoch LIMIT 1001")
    suspend fun assets(account: String, server: String, epoch: String): List<AccountIconAssetRow>

    @Query("SELECT * FROM icon_packs WHERE accountId=:account AND serverInstanceId=:server AND syncEpoch=:epoch LIMIT 32769")
    suspend fun packs(account: String, server: String, epoch: String): List<AccountIconPackRow>

    @Query("SELECT * FROM icon_blob_reservations WHERE accountId=:account AND serverInstanceId=:server AND syncEpoch=:epoch LIMIT 2001")
    suspend fun blobs(account: String, server: String, epoch: String): List<AccountIconBlobRow>

    @Insert
    suspend fun insertAsset(row: AccountIconAssetRow)
    @Insert
    suspend fun insertPack(row: AccountIconPackRow)
    @Insert
    suspend fun insertBlob(row: AccountIconBlobRow)
}

/** Separate lifetime from HabitDatabase.clearAllData; never destructively reset on mismatch. */
@Database(
    entities = [AccountIconAssetRow::class, AccountIconPackRow::class, AccountIconBlobRow::class],
    version = 1, exportSchema = true
)
internal abstract class AccountIconDatabase : RoomDatabase() {
    abstract fun icons(): AccountIconDao

    companion object {
        const val NAME = "account_icon_database"
        fun open(context: Context): AccountIconDatabase =
            Room.databaseBuilder(context.applicationContext, AccountIconDatabase::class.java, NAME).build()
    }
}
