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

/** Never log server text, URLs, credentials, or arbitrary exception messages. */
internal fun materialDiagnosticCode(value: String?): String = when (value) {
    "SERVER_IDENTITY_MISMATCH", "SYNC_EPOCH_MISMATCH", "ASSET_CONTENT_PENDING",
    "ASSET_ID_REUSED", "PACK_VERSION_REUSED", "MATERIAL_RECOVERY_READ_ONLY",
    "MATERIAL_RECOVERY_QUERY_INVALID", "MATERIAL_RECOVERY_SCOPE_CHANGED",
    "ICON_RECOVERY_STAGE_CHANGED", "ICON_RECOVERY_ACCESS_CLOSED", "ICON_RECOVERY_AUTHORITY_CHANGED",
    "ICON_SESSION_CHANGED", "ICON_SERVER_IDENTITY_CHANGED", "MATERIAL_SCOPE_CHANGED",
    "MATERIAL_CALL_CANCELLED", "MATERIAL_NETWORK_UNAVAILABLE", "MATERIAL_ROUTE_INVALID",
    "MATERIAL_ROUTE_CHANGED", "AUTHENTICATION_SESSION_CHANGED", "MATERIAL_CREDENTIAL_INVALID",
    "ICON_FILES_MISSING", "ICON_INSTALL_CHANGED", "ICON_NOT_READY", "ICON_FILE_CAPACITY",
    "ICON_DIRECTORY_CHANGED" -> value
    else -> "unspecified"
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
                val category = when (error) {
                    is MaterialHttpFailure -> "http-${error.status}"
                    is MaterialReplyInvalid -> "invalid-reply"
                    is java.net.SocketTimeoutException -> "socket-timeout"
                    is java.net.ConnectException -> "connect"
                    is java.io.InterruptedIOException -> "interrupted-io"
                    else -> if (error.cause is java.io.EOFException) "response-eof" else "io"
                }
                val code = materialDiagnosticCode((error as? MaterialHttpFailure)?.code ?: error.message)
                android.util.Log.w("IconCatalog", "$category/$code")
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
