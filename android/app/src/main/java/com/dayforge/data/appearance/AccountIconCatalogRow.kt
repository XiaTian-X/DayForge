package com.dayforge.data.appearance

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.Query

/** Independent immutable material history; never a business cursor or readiness receipt. */
@Entity(tableName = "icon_catalog_state", primaryKeys = ["accountId", "serverInstanceId", "syncEpoch"])
internal data class AccountIconCatalogStateRow(
    val accountId: String, val serverInstanceId: String, val syncEpoch: String,
    val generation: Long, val cursor: Long, val through: Long
)

@Entity(tableName = "icon_catalog_entries", primaryKeys = ["accountId", "serverInstanceId", "syncEpoch", "sequence"],
    indices = [Index(value = ["accountId", "serverInstanceId", "syncEpoch", "kind", "targetId", "revision"], unique = true)])
internal data class AccountIconCatalogEntryRow(
    val accountId: String, val serverInstanceId: String, val syncEpoch: String,
    val sequence: Long, val kind: String, val targetId: String, val revision: Int, val metadataHash: String
)

internal data class AccountIconCatalogAudit(val invalidValues: Boolean, val count: Long)

@Dao
internal interface AccountIconCatalogDao {
    @Query("""SELECT
        (EXISTS(SELECT 1 FROM icon_catalog_state WHERE accountId=:account AND serverInstanceId=:server AND syncEpoch=:epoch AND
          (typeof(generation)<>'integer' OR generation<1 OR typeof(cursor)<>'integer' OR cursor<0 OR cursor>33768 OR
           typeof(through)<>'integer' OR through<cursor OR through>33768 OR (cursor=0 AND through<>0)))
         OR EXISTS(SELECT 1 FROM icon_catalog_entries WHERE accountId=:account AND serverInstanceId=:server AND syncEpoch=:epoch AND
          (typeof(sequence)<>'integer' OR sequence<1 OR sequence>33768 OR typeof(kind)<>'text' OR typeof(targetId)<>'text' OR
           typeof(metadataHash)<>'text' OR typeof(revision)<>'integer' OR revision<0 OR revision>2147483647))) AS invalidValues,
        (SELECT COUNT(*) FROM icon_catalog_entries WHERE accountId=:account AND serverInstanceId=:server AND syncEpoch=:epoch) AS count
    """)
    suspend fun audit(account: String, server: String, epoch: String): AccountIconCatalogAudit

    @Query("SELECT * FROM icon_catalog_state WHERE accountId=:account AND serverInstanceId=:server AND syncEpoch=:epoch")
    suspend fun state(account: String, server: String, epoch: String): AccountIconCatalogStateRow?

    @Query("SELECT * FROM icon_catalog_entries WHERE accountId=:account AND serverInstanceId=:server AND syncEpoch=:epoch ORDER BY sequence LIMIT 33769")
    suspend fun entries(account: String, server: String, epoch: String): List<AccountIconCatalogEntryRow>

    @Insert suspend fun insertState(row: AccountIconCatalogStateRow)
    @Insert suspend fun insertEntry(row: AccountIconCatalogEntryRow)

    @Query("""UPDATE icon_catalog_state SET generation=:nextGeneration,cursor=:nextCursor,through=:nextThrough
        WHERE accountId=:account AND serverInstanceId=:server AND syncEpoch=:epoch
        AND generation=:generation AND cursor=:cursor AND through=:through""")
    suspend fun advance(account: String, server: String, epoch: String, generation: Long, cursor: Long, through: Long,
        nextGeneration: Long, nextCursor: Long, nextThrough: Long): Int
}
