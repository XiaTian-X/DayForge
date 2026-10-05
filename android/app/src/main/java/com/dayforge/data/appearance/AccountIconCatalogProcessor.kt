package com.dayforge.data.appearance

import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

internal data class IconCatalogBatch(val supported: Boolean, val pages: Int, val entries: Int,
    val limited: Boolean = false, val stopped: Boolean = false)

internal data class IconCatalogWork(val batch: IconCatalogBatch, val retryable: Boolean = false)

internal fun materialRetryable(error: IOException): Boolean = when (error) {
    is MaterialReplyInvalid -> false
    is MaterialHttpFailure -> error.status == 401 || error.status >= 500 || error.status in setOf(408, 425, 429)
    else -> true
}

/** Shares the runtime's sole material owner with transfer/recovery; never holds a DB lock over HTTP. */
internal class AccountIconCatalogProcessor(
    private val metadata: AccountIconRepository, private val catalog: AccountIconRemoteCatalog,
    private val owner: Mutex
) {
    init { require(catalog.usesMetadata(metadata)) { "ICON_CATALOG_STORE_MISMATCH" } }
    suspend fun run(context: AccountIconContext, http: AccountIconHttp): IconCatalogBatch = owner.withLock {
        withTimeout(240_000) {
            http.session(context) { session -> admitted(context, session).batch }
                ?: IconCatalogBatch(false, 0, 0)
        }
    }

    /** Caller owns the same runtime mutex and already admitted this exact HTTP session. */
    internal suspend fun admitted(context: AccountIconContext, session: AccountIconHttp.Session): IconCatalogWork {
        session.authorize(context)
        var pages = 0
        var entries = 0
        repeat(4) {
            val attempt = catalog.next(context)
            val page = try { session.catalog(attempt) } catch (error: IOException) {
                metadata.reauthorize(context)
                if (error is MaterialHttpFailure && error.code in setOf("SERVER_IDENTITY_MISMATCH", "SYNC_EPOCH_MISMATCH"))
                    throw IllegalStateException("ICON_SERVER_IDENTITY_CHANGED")
                return IconCatalogWork(IconCatalogBatch(true, pages, entries, stopped = true), materialRetryable(error))
            }
            catalog.accept(attempt, page)
            pages++; entries += page.entries.size
            if (!page.hasMore) return IconCatalogWork(IconCatalogBatch(true, pages, entries))
        }
        return IconCatalogWork(IconCatalogBatch(true, pages, entries, limited = true))
    }
}
