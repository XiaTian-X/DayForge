package com.dayforge.data.appearance

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

internal data class IconTransferBatch(val supported: Boolean, val completed: Int,
    val recovered: Int, val stopped: Boolean, val downloaded: Boolean, val limited: Boolean = false,
    val contentPending: Int = 0)

/** One owner per canonical runtime. No account lock or database transaction spans HTTP/file IO. */
internal class AccountIconTransferProcessor(
    private val metadata: AccountIconRepository, private val store: AccountIconStore,
    private val queue: AccountIconTransfers
) {
    init { require(queue.usesStores(metadata, store)) }
    private val owner = Mutex()

    suspend fun run(context: AccountIconContext, http: AccountIconHttp): IconTransferBatch = owner.withLock {
        withTimeout(240_000) {
            http.session(context) { session ->
                // Acquiring the sole owner proves the previous request and real IO have joined.
                val recovered = queue.recoverInterrupted(context)
                var completed = 0
                var downloaded = false
                val deferred = mutableSetOf<String>()
                fun result(stopped: Boolean = false, limited: Boolean = false) = IconTransferBatch(
                    true, completed, recovered, stopped || deferred.isNotEmpty(), downloaded, limited, deferred.size)
                repeat(32) {
                    val attempt = queue.prepareNext(context, deferred)
                        ?: return@session result()
                    try {
                        when (attempt.job.kind) {
                            IconTransferKind.DECLARE_ASSET -> queue.confirmAsset(attempt, session.declareAsset(attempt))
                            IconTransferKind.DECLARE_PACK -> queue.confirmPack(attempt, session.declarePack(attempt))
                            IconTransferKind.UPLOAD -> {
                                val bytes = local(attempt) { store.read(context, attempt.job.targetId, blob(attempt).sha256) }
                                queue.confirmUpload(attempt, session.upload(attempt, bytes))
                            }
                            IconTransferKind.DOWNLOAD -> {
                                val bytes = session.download(attempt)
                                local(attempt) { store.installDownloaded(context, attempt.job.targetId, blob(attempt).sha256, bytes) }
                                local(attempt) { queue.confirmDownload(attempt) }
                                downloaded = true
                            }
                        }
                        completed++
                    } catch (error: CancellationException) {
                        throw error // Sending remains durable; never release through a replacement account.
                    } catch (_: LocalContentFailure) {
                        return@session result(stopped = true)
                    } catch (error: MaterialHttpFailure) {
                        if (error.code in setOf("SERVER_IDENTITY_MISMATCH", "SYNC_EPOCH_MISMATCH"))
                            throw IllegalStateException("ICON_SERVER_IDENTITY_CHANGED")
                        if (attempt.job.kind == IconTransferKind.DOWNLOAD && error.status == 409 &&
                            error.code == "ASSET_CONTENT_PENDING") {
                            // Metadata may arrive before another device publishes bytes. Retry the
                            // same intent later, once per batch, without starving eligible uploads.
                            queue.release(attempt)
                            deferred += attempt.job.operationId
                            return@repeat
                        }
                        val failure = when {
                            error.status == 401 || error.status >= 500 || error.status in setOf(408, 429) -> null
                            error.code in setOf("ASSET_ID_REUSED", "PACK_VERSION_REUSED") -> IconTransferFailure.REMOTE_ID_REUSED
                            error.status == 413 -> IconTransferFailure.REMOTE_QUOTA
                            error.status == 403 -> IconTransferFailure.REMOTE_CAPABILITY
                            error.status == 404 -> IconTransferFailure.REMOTE_NOT_FOUND
                            else -> IconTransferFailure.REMOTE_METADATA
                        }
                        if (failure == null) queue.release(attempt) else queue.block(attempt, failure)
                        return@session result(stopped = true)
                    } catch (_: MaterialReplyInvalid) {
                        queue.block(attempt, IconTransferFailure.REMOTE_METADATA)
                        return@session result(stopped = true)
                    } catch (_: IOException) {
                        // Reauthorization/CAS inside release rejects any changed account/replica.
                        queue.release(attempt)
                        return@session result(stopped = true)
                    }
                }
                result(limited = true)
            } ?: IconTransferBatch(false, 0, 0, false, false)
        }
    }

    private fun blob(attempt: IconTransferAttempt) = if (attempt.job.variant == "light")
        requireNotNull(attempt.asset).light else requireNotNull(attempt.asset?.dark)

    private class LocalContentFailure : IOException("ICON_LOCAL_CONTENT")
    private suspend fun <T> local(attempt: IconTransferAttempt, block: suspend () -> T): T = try { block() }
    catch (error: Exception) {
        if (error is CancellationException) throw error
        // Only file/content failures become blocked; stale authority is rejected by the queue.
        if (error !is IOException && error !is IllegalArgumentException && error !is IllegalStateException) throw error
        queue.block(attempt, IconTransferFailure.LOCAL_CONTENT)
        throw LocalContentFailure()
    }
}
