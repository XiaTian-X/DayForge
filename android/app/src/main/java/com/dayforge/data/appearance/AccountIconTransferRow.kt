package com.dayforge.data.appearance

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.Query

/** Durable intent/attempt/confirmation, never credentials, bytes or a business cursor. */
@Entity(tableName = "icon_transfers", primaryKeys = ["accountId", "serverInstanceId", "syncEpoch", "operationId"],
    indices = [Index(value = ["accountId", "serverInstanceId", "syncEpoch", "kind", "targetId", "revision", "variant"], unique = true)])
internal data class AccountIconTransferRow(
    val accountId: String, val serverInstanceId: String, val syncEpoch: String,
    val operationId: String, val kind: String, val targetId: String, val revision: Int, val variant: String,
    val metadataHash: String, val state: String, val generation: Long,
    val deviceId: String?, val failureCode: String?, val confirmationHash: String?, val readyMask: Int
)

internal data class AccountIconTransferAudit(val invalidValues: Boolean, val count: Long)

@Dao
internal interface AccountIconTransferDao {
    @Query("""SELECT
        EXISTS(SELECT 1 FROM icon_transfers WHERE accountId=:account AND serverInstanceId=:server AND syncEpoch=:epoch AND
            (typeof(operationId)<>'text' OR typeof(kind)<>'text' OR typeof(targetId)<>'text' OR typeof(variant)<>'text' OR
             typeof(metadataHash)<>'text' OR typeof(state)<>'text' OR typeof(revision)<>'integer' OR revision<0 OR revision>2147483647 OR
             typeof(generation)<>'integer' OR generation<0 OR typeof(readyMask)<>'integer' OR readyMask<0 OR readyMask>3 OR
             (deviceId IS NOT NULL AND typeof(deviceId)<>'text') OR
             (failureCode IS NOT NULL AND typeof(failureCode)<>'text') OR
             (confirmationHash IS NOT NULL AND typeof(confirmationHash)<>'text'))) AS invalidValues,
        (SELECT COUNT(*) FROM icon_transfers WHERE accountId=:account AND serverInstanceId=:server AND syncEpoch=:epoch) AS count
    """)
    suspend fun audit(account: String, server: String, epoch: String): AccountIconTransferAudit

    @Query("SELECT * FROM icon_transfers WHERE accountId=:account AND serverInstanceId=:server AND syncEpoch=:epoch ORDER BY operationId LIMIT 37769")
    suspend fun rows(account: String, server: String, epoch: String): List<AccountIconTransferRow>

    @Insert
    suspend fun insert(row: AccountIconTransferRow)

    @Query("""UPDATE icon_transfers SET state=:nextState,generation=:nextGeneration,deviceId=:device,
        failureCode=:failure,confirmationHash=:confirmation,readyMask=:ready
        WHERE accountId=:account AND serverInstanceId=:server AND syncEpoch=:epoch AND operationId=:operation
        AND generation=:generation AND state=:state""")
    suspend fun transition(account: String, server: String, epoch: String, operation: String,
        generation: Long, state: String, nextState: String, nextGeneration: Long, device: String?,
        failure: String?, confirmation: String?, ready: Int): Int
}
