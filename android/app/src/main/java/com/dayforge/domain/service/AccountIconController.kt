package com.dayforge.domain.service

import android.content.Context
import android.net.Uri
import com.dayforge.data.appearance.*
import com.dayforge.data.local.AccountIconMemory
import com.dayforge.data.local.TokenManager
import com.dayforge.domain.appearance.ThemeVersionRef
import com.dayforge.domain.model.IconReference
import java.lang.ref.WeakReference
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
    private var namespace: AccountIconNamespace? = null
    private val mutable = MutableStateFlow<IconImageState>(IconImageState.Loading)
    private val reload = MutableStateFlow<Any?>(generation)
    val state = mutable.asStateFlow()
    internal val requests = reload.asStateFlow()
    override fun authenticationTransition(blocked: Boolean) = synchronized(monitor) {
        generation = Any(); this.blocked = blocked; mutable.value = IconImageState.Empty
        reload.value = if (blocked || closed) null else generation
    }
    fun close() = synchronized(monitor) {
        closed = true; generation = Any(); mutable.value = IconImageState.Empty; reload.value = null
    }
    internal fun begin(expected: Any? = null): Any? = synchronized(monitor) {
        if (closed || blocked) return@synchronized null
        if (expected != null && expected !== generation) return@synchronized null
        if (expected == null) generation = Any()
        mutable.value = IconImageState.Loading; generation
    }
    /** A surviving object consumer may reauthenticate, but never rebind to a different replica. */
    internal fun bind(stamp: Any, value: AccountIconNamespace): Boolean = synchronized(monitor) {
        if (closed || blocked || generation !== stamp || (namespace != null && namespace != value)) false
        else { namespace = value; true }
    }
    internal fun refresh(value: AccountIconNamespace) = synchronized(monitor) {
        if (!closed && !blocked && (namespace == null || namespace == value)) {
            generation = Any(); mutable.value = IconImageState.Empty; reload.value = generation
        }
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
    private val references = mutableListOf<WeakReference<IconImageHandle>>()
    private val imageMonitor = Any()
    private var imageCalls = 0
    private var imagesIdle = CompletableDeferred<Unit>().apply { complete(Unit) }
    private suspend fun <T> io(block: suspend (AccountIconRuntime) -> T): T =
        withContext(Dispatchers.IO) { block(runtime.value) }
    internal fun registerConsumer(cache: AccountIconMemory.Cache) = tokens.registerIconCache(cache)
    internal suspend fun capture() = io { it.metadata.capture() }
    internal suspend fun library(context: AccountIconContext) = io {
        val catalog = it.metadata.library(context)
        it.metadata.authorized(context) { refreshReferences(context.namespace) }
        catalog
    }
    // Publication has no payload queued back to Main; guards + short state update share authority.
    internal suspend fun publish(context: AccountIconContext, block: () -> Unit) = io { it.metadata.authorized(context, block) }
    internal suspend fun preview(context: AccountIconContext, uri: Uri): AccountIconPackPreview = io { storage ->
        storage.metadata.reauthorize(context)
        val value = storage.documents.preview(uri)
        check(value.context.access == context.access) { "ICON_SESSION_CHANGED" }
        storage.metadata.reauthorize(context)
        value
    }
    internal suspend fun install(preview: AccountIconPackPreview) = io {
        val receipt = it.imports.confirm(preview)
        it.metadata.authorized(preview.context) { refreshReferences(preview.context.namespace) }
        receipt
    }
    internal suspend fun select(context: AccountIconContext, expected: Long, version: IconPackVersion?) =
        io { it.store.select(context, expected, version) }
    internal fun image(): IconImageHandle = IconImageHandle().also(::registerConsumer)
    internal fun referenceImage(): IconImageHandle = image().also { handle -> synchronized(references) {
        references.removeAll { it.get() == null }
        references.add(WeakReference(handle))
    } }
    private fun refreshReferences(namespace: AccountIconNamespace) = synchronized(references) {
        val iterator = references.iterator()
        while (iterator.hasNext()) {
            val handle = iterator.next().get()
            if (handle == null) iterator.remove() else handle.refresh(namespace)
        }
    }
    internal suspend fun load(handle: IconImageHandle, source: IconPackSource, assetId: String,
        theme: ThemeVersionRef, dark: Boolean, size: IconRasterSize, tint: Int) =
        imageWork(handle) { storage, stamp ->
            val image = source.preview?.let {
                check(it.context.access == source.context.access && it.manifest == source.pack) { "ICON_SESSION_CHANGED" }
                storage.renderer.renderPreview(it, assetId, dark, size, tint)
            } ?: run {
                check(storage.metadata.pack(source.context, source.pack.packId, source.pack.revision) == source.pack) { "ICON_PACK_NOT_OWNED" }
                check(source.pack.assets.any { it.assetId == assetId }) { "ICON_ASSET_NOT_OWNED" }
                storage.renderer.render(source.context, assetId, theme, dark, size, tint)
            }
            storage.metadata.authorized(source.context) { handle.publish(stamp, IconImageState.Ready(image)) }
        }
    internal suspend fun loadReference(handle: IconImageHandle, request: Any, reference: IconReference,
        oneTime: Boolean, theme: ThemeVersionRef, dark: Boolean, size: IconRasterSize, tint: Int) =
        imageWork(handle, request) { storage, stamp ->
            val context = storage.metadata.capture()
            check(storage.metadata.authorized(context) { handle.bind(stamp, context.namespace) }) { "ICON_IMAGE_NAMESPACE_CHANGED" }
            val rendered = storage.renderer.renderReference(context, reference, oneTime, theme, dark, size, tint)
            storage.metadata.withSelection(context, rendered.resolution.selection) {
                handle.publish(stamp, rendered.raster?.let(IconImageState::Ready) ?: IconImageState.Empty)
            }
        }
    private suspend fun imageWork(handle: IconImageHandle, request: Any? = null,
        block: suspend (AccountIconRuntime, Any) -> Unit) {
        val stamp = handle.begin(request) ?: return
        synchronized(imageMonitor) {
            if (imageCalls++ == 0) imagesIdle = CompletableDeferred()
        }
        try {
            io { block(it, stamp) }
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
