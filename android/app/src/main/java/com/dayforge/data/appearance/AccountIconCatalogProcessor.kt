package com.dayforge.data.appearance

import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

internal data class IconCatalogBatch(val supported: Boolean, val pages: Int, val entries: Int,
    val limited: Boolean = false, val stopped: Boolean = false)

/** Shares the runtime's sole material owner with transfer/recovery; never holds a DB lock over HTTP. */
internal class AccountIconCatalogProcessor(
    private val metadata: AccountIconRepository, private val catalog: AccountIconRemoteCatalog,
    private val owner: Mutex
) {
    init { require(catalog.usesMetadata(metadata)) { "ICON_CATALOG_STORE_MISMATCH" } }
    suspend fun run(context: AccountIconContext, http: AccountIconHttp): IconCatalogBatch = owner.withLock {
        withTimeout(240_000) {
            http.session(context) { session ->
                var pages = 0
                var entries = 0
                repeat(4) {
                    val attempt = catalog.next(context)
                    val page = try { session.catalog(attempt) } catch (error: IOException) {
                        metadata.reauthorize(context)
                        if (error is MaterialHttpFailure && error.code in setOf("SERVER_IDENTITY_MISMATCH", "SYNC_EPOCH_MISMATCH"))
                            throw IllegalStateException("ICON_SERVER_IDENTITY_CHANGED")
                        return@session IconCatalogBatch(true, pages, entries, stopped = true)
                    }
                    catalog.accept(attempt, page)
                    pages++; entries += page.entries.size
                    if (!page.hasMore) return@session IconCatalogBatch(true, pages, entries)
                }
                IconCatalogBatch(true, pages, entries, limited = true)
            } ?: IconCatalogBatch(false, 0, 0)
        }
    }
}
