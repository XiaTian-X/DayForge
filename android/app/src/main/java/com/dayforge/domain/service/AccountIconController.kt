package com.dayforge.domain.service

import android.content.Context
import android.net.Uri
import com.dayforge.data.appearance.*
import com.dayforge.data.local.AccountIconMemory
import com.dayforge.data.local.TokenManager
import com.dayforge.domain.appearance.ThemeVersionRef
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

internal sealed interface IconImageState {
    data object Loading : IconImageState
    data object Empty : IconImageState
    data class Ready(val raster: IconRaster) : IconImageState
    data class Failed(val error: Exception) : IconImageState
}

/** Consumer owns this reference; invalidation drops pixels immediately, without recycling them. */
internal class IconImageHandle : AccountIconMemory.Cache {
    private val monitor = Any()
    private var generation = Any()
    private var blocked = false
    private var closed = false
    private val mutable = MutableStateFlow<IconImageState>(IconImageState.Loading)
    val state = mutable.asStateFlow()
    override fun authenticationTransition(blocked: Boolean) = synchronized(monitor) {
        generation = Any(); this.blocked = blocked; mutable.value = IconImageState.Empty
    }
    fun close() = synchronized(monitor) { closed = true; generation = Any(); mutable.value = IconImageState.Empty }
    internal fun begin(): Any? = synchronized(monitor) {
        if (closed || blocked) return@synchronized null
        generation = Any(); mutable.value = IconImageState.Loading; generation
    }
    internal fun publish(stamp: Any, state: IconImageState) = synchronized(monitor) {
        if (!closed && !blocked && generation === stamp) mutable.value = state
    }
}

internal data class IconPackSource(val context: AccountIconContext, val pack: com.dayforge.domain.model.IconPack,
    val preview: AccountIconPackPreview? = null)

internal class AccountIconRuntime(
    val metadata: AccountIconRepository, val store: AccountIconStore, val renderer: AccountIconRenderer,
    val imports: AccountIconImport, val documents: AccountIconDocuments, val close: () -> Unit
)

/** Lazy process-singleton storage, using the same authoritative credentials/coordinator as login. */
@Singleton
class AccountIconController internal constructor(
    private val factory: () -> AccountIconRuntime,
    private val tokens: TokenManager
) {
    @Inject constructor(@ApplicationContext context: Context, tokens: TokenManager, sessions: AccountSessionCoordinator) : this({
        val db = AccountIconDatabase.open(context)
        val metadata = AccountIconRepository(db, tokens, sessions)
        val store = AccountIconStore(metadata, AccountIconFiles(context.filesDir))
        val imports = AccountIconImport(metadata, store)
        AccountIconRuntime(metadata, store, AccountIconRenderer(metadata, store), imports,
            AccountIconDocuments(imports, context.contentResolver), db::close)
    }, tokens)

    private val runtime = lazy(factory)
    private val imageMonitor = Any()
    private var imageCalls = 0
    private var imagesIdle = CompletableDeferred<Unit>().apply { complete(Unit) }
    private suspend fun storage() = withContext(Dispatchers.IO) { runtime.value }
    internal fun registerConsumer(cache: AccountIconMemory.Cache) = tokens.registerIconCache(cache)
    internal suspend fun capture() = storage().metadata.capture()
    internal suspend fun library(context: AccountIconContext) = storage().metadata.library(context)
    internal suspend fun <T> publish(context: AccountIconContext, block: () -> T) = storage().metadata.authorized(context, block)
    internal suspend fun preview(context: AccountIconContext, uri: Uri): AccountIconPackPreview {
        val storage = storage()
        storage.metadata.reauthorize(context)
        val value = storage.documents.preview(uri)
        check(value.context.access == context.access) { "ICON_SESSION_CHANGED" }
        storage.metadata.reauthorize(context)
        return value
    }
    internal suspend fun install(preview: AccountIconPackPreview) = storage().imports.confirm(preview)
    internal suspend fun select(context: AccountIconContext, expected: Long, version: IconPackVersion?) =
        storage().store.select(context, expected, version)
    internal fun image(): IconImageHandle = IconImageHandle().also(::registerConsumer)
    internal suspend fun load(handle: IconImageHandle, source: IconPackSource, assetId: String,
        theme: ThemeVersionRef, dark: Boolean, size: IconRasterSize, tint: Int) {
        val stamp = handle.begin() ?: return
        synchronized(imageMonitor) {
            if (imageCalls++ == 0) imagesIdle = CompletableDeferred()
        }
        try {
            val storage = storage()
            val image = source.preview?.let {
                check(it.context.access == source.context.access && it.manifest == source.pack) { "ICON_SESSION_CHANGED" }
                storage.renderer.renderPreview(it, assetId, dark, size, tint)
            } ?: run {
                check(storage.metadata.pack(source.context, source.pack.packId, source.pack.revision) == source.pack) { "ICON_PACK_NOT_OWNED" }
                check(source.pack.assets.any { it.assetId == assetId }) { "ICON_ASSET_NOT_OWNED" }
                storage.renderer.render(source.context, assetId, theme, dark, size, tint)
            }
            storage.metadata.authorized(source.context) { handle.publish(stamp, IconImageState.Ready(image)) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // A stale handle is already empty; it cannot display a new owner's result/error.
            handle.publish(stamp, IconImageState.Failed(error))
        } finally {
            synchronized(imageMonitor) { if (--imageCalls == 0) imagesIdle.complete(Unit) }
        }
    }
    /** After disposing consumers; completion means real image I/O and drawing have joined. */
    internal suspend fun awaitImages() {
        while (true) {
            val pending = synchronized(imageMonitor) { if (imageCalls == 0) null else imagesIdle } ?: return
            pending.await()
        }
    }
    /** Only an explicit process/test owner may close after joining all its callers. */
    internal fun close() { if (runtime.isInitialized()) runtime.value.close() }
}
