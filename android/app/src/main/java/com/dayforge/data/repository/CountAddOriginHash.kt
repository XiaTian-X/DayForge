package com.dayforge.data.repository

import android.database.Cursor
import com.dayforge.data.local.entity.NextRequestOriginEntity
import java.nio.ByteBuffer
import java.security.MessageDigest

/** The existing NextRequestSql raw-row framing for this ONE independently constructed row.
 * Real-Room differential tests must compare this to NextRequestSql.rowHash before integration.
 * No supplied native row or journal can define the expected origin/day binding.
 */
internal object CountAddOriginHash {
    fun hash(origin: NextRequestOriginEntity): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun bytes(value: ByteArray) {
            digest.update(ByteBuffer.allocate(4).putInt(value.size).array())
            digest.update(value)
        }
        fun text(value: String) = bytes(value.toByteArray(Charsets.UTF_8))
        text("next_request_origins")
        val values = linkedMapOf<String, Any?>("kind" to origin.kind, "requestId" to origin.requestId,
            "queueId" to origin.queueId, "protocol" to origin.protocol.toLong(), "accountId" to origin.accountId,
            "serverInstanceId" to origin.serverInstanceId, "syncEpoch" to origin.syncEpoch,
            "sourceHash" to origin.sourceHash, "intentJson" to origin.intentJson)
        for ((name, value) in values) {
            text(name)
            digest.update(when (value) { null -> Cursor.FIELD_TYPE_NULL; is Long -> Cursor.FIELD_TYPE_INTEGER
                is String -> Cursor.FIELD_TYPE_STRING; else -> error("COUNT_ADD_ORIGIN_TYPE") }.toByte())
            when (value) { is Long -> bytes(ByteBuffer.allocate(8).putLong(value).array()); is String -> text(value) }
        }
        val digits = "0123456789abcdef"
        return buildString(64) { for (byte in digest.digest()) {
            val unsigned = byte.toInt() and 255
            append(digits[unsigned ushr 4]); append(digits[unsigned and 15])
        } }
    }
}
