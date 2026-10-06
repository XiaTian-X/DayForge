package com.dayforge.data.api

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream

internal const val SYNC_REQUEST_LIMIT = 1_048_576
private val requestJson = Json { encodeDefaults = true }

/** The journal and HTTP must freeze the same finite bytes, without first building an unbounded string. */
@OptIn(ExperimentalSerializationApi::class)
internal suspend fun <T> encodeSyncRequest(serializer: KSerializer<T>, body: T): ByteArray =
    withContext(Dispatchers.Default) {
        val caller = currentCoroutineContext()
        val output = object : ByteArrayOutputStream(8192) {
            override fun write(value: Int) {
                caller.ensureActive(); require(size() < SYNC_REQUEST_LIMIT); super.write(value)
            }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                caller.ensureActive(); require(length <= SYNC_REQUEST_LIMIT - size()); super.write(bytes, offset, length)
            }
        }
        output.use { requestJson.encodeToStream(serializer, body, it); it.toByteArray() }
    }

internal class InvalidFrozenSyncRequest : IllegalArgumentException("SYNC_FROZEN_REQUEST_INVALID")

/** Own the caller's buffer before any suspension; validation never normalizes the transmitted bytes. */
internal fun snapshotSyncRequest(bytes: ByteArray): ByteArray {
    if (bytes.isEmpty() || bytes.size > SYNC_REQUEST_LIMIT) throw InvalidFrozenSyncRequest()
    return bytes.copyOf()
}

internal suspend fun <T> decodeFrozenSyncRequest(bytes: ByteArray, serializer: KSerializer<T>): T =
    withContext(Dispatchers.Default) {
        val caller = currentCoroutineContext()
        try {
            decodeSyncReply(bytes, SYNC_REQUEST_LIMIT, serializer, { caller.ensureActive() })
        } catch (_: NextSyncReplyInvalid) {
            throw InvalidFrozenSyncRequest()
        }
    }
