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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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

internal enum class IconMaterialOutcome { COMPLETE, RETRY, ATTENTION, UNSUPPORTED, INACTIVE }

internal class AccountIconRuntime(
    val metadata: AccountIconRepository, val store: AccountIconStore, val renderer: AccountIconRenderer,
    val imports: AccountIconImport, val documents: AccountIconDocuments, val close: () -> Unit,
    private val maximumRoundMillis: Long = 240_000
) {
    val transfers get() = imports.transfers
    init {
        require(store.usesMetadata(metadata) && transfers.usesStores(metadata, store)) { "ICON_TRANSFER_STORE_MISMATCH" }
        require(maximumRoundMillis in 1..240_000)
    }
    private val materialOwner = kotlinx.coroutines.sync.Mutex()
    val catalog by lazy { metadata.remoteCatalog(transfers) }
    val processor by lazy { com.dayforge.data.appearance.AccountIconTransferProcessor(metadata, store, transfers, materialOwner) }
    val catalogProcessor by lazy { com.dayforge.data.appearance.AccountIconCatalogProcessor(metadata, catalog, materialOwner) }

    suspend fun install(preview: AccountIconPackPreview) = materialOwner.withLock { imports.confirm(preview) }

    /** One captured session/route and sole owner, including the real file recovery and all phases. */
    suspend fun synchronize(context: AccountIconContext, http: AccountIconHttp): IconMaterialOutcome = materialOwner.withLock {
        withTimeoutOrNull(maximumRoundMillis) {
            http.session(context) { session ->
                session.authorize(context)
                // Audit both durable journals BEFORE deleting even an exact owned temporary file.
                transfers.work(context)
                catalog.next(context)
                try { store.recoverDownloads(context) }
                catch (error: Exception) {
                    if (error is CancellationException) throw error
                    // A local file failure is not a transient remote/network failure.
                    throw IllegalStateException("ICON_RECOVERY_REQUIRED", error)
                }
                processor.admitted(context, session)
                val directory = catalogProcessor.admitted(context, session)
                val work = transfers.work(context)
                when {
                    directory.retryable || directory.batch.limited || work.pending -> IconMaterialOutcome.RETRY
                    directory.batch.stopped || work.blocked -> IconMaterialOutcome.ATTENTION
                    else -> IconMaterialOutcome.COMPLETE
                }
            } ?: IconMaterialOutcome.UNSUPPORTED
        } ?: IconMaterialOutcome.RETRY // Only our deadline; parent cancellation still propagates after IO joins.
    }
}

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
        val imports = AccountIconImport(metadata, store, AccountIconTransfers(db, metadata, store))
        AccountIconRuntime(metadata, store, AccountIconRenderer(metadata, store), imports,
            AccountIconDocuments(imports, context.contentResolver), db::close)
    }, tokens)

    private val runtime = lazy(factory)
    private val materialWakeups = MutableSharedFlow<Unit>(extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST)
    internal val workRequests = materialWakeups.asSharedFlow()
    private val references = mutableListOf<WeakReference<IconImageHandle>>()
    private val imageMonitor = Any()
    private var imageCalls = 0
    private var imagesIdle = CompletableDeferred<Unit>().apply { complete(Unit) }
    private suspend fun <T> io(block: suspend (AccountIconRuntime) -> T): T =
        withContext(Dispatchers.IO) { block(runtime.value) }
    internal fun registerConsumer(cache: AccountIconMemory.Cache) = tokens.registerIconCache(cache)
    internal suspend fun capture() = io { it.metadata.capture() }
    internal suspend fun transferJobs(context: AccountIconContext) = io { it.transfers.jobs(context) }
    internal suspend fun synchronize(http: AccountIconHttp): IconMaterialOutcome = withContext(Dispatchers.IO) {
        // Incomplete/unknown authority never initializes the material DB/runtime or sends HTTP.
        val access = tokens.localIconAccess() ?: return@withContext IconMaterialOutcome.INACTIVE
        val storage = runtime.value
        val context = storage.metadata.capture()
        check(context.access == access) { "ICON_SESSION_CHANGED" }
        val result = storage.synchronize(context, http)
        if (result != IconMaterialOutcome.UNSUPPORTED)
            storage.metadata.authorized(context) { refreshReferences(context.namespace) }
        result
    }
    internal suspend fun refreshCatalog(http: com.dayforge.data.appearance.AccountIconHttp) = io {
        val context = it.metadata.capture()
        val result = it.catalogProcessor.run(context, http)
        if (result.entries > 0) it.metadata.authorized(context) { refreshReferences(context.namespace) }
        result
    }
    internal suspend fun transfer(http: com.dayforge.data.appearance.AccountIconHttp) = io {
        val context = it.metadata.capture()
        val result = it.processor.run(context, http)
        if (result.downloaded) it.metadata.authorized(context) { refreshReferences(context.namespace) }
        result
    }
    internal suspend fun library(context: AccountIconContext) = io {
        val catalog = it.metadata.library(context)
        it.metadata.authorized(context) { refreshReferences(context.namespace) }
        catalog
    }
    /** New fixed references require owned immutable metadata; an unchanged missing reference is retained. */
    internal suspend fun authorizeEditReference(session: com.dayforge.data.local.LocalDataSession,
        reference: IconReference, previous: IconReference, oneTime: Boolean) {
        if (reference is IconReference.Role) {
            require(com.dayforge.domain.model.iconAllowed(reference, oneTime)) { "ICON_PURPOSE_MISMATCH" }
            return
        }
        val access = tokens.localIconAccess()
        if (access == null) {
            check(reference == previous) { "ICON_ACCESS_DENIED" }
            return
        }
        check(access.session == session) { "ICON_SESSION_CHANGED" }
        io {
            val context = it.metadata.capture()
            check(context.access == access) { "ICON_SESSION_CHANGED" }
            val asset = it.metadata.asset(context, (reference as IconReference.Asset).assetId)
            if (asset == null) check(reference == previous) { "ICON_ASSET_NOT_OWNED" }
            else require(com.dayforge.domain.model.iconAllowed(reference, oneTime, asset)) { "ICON_PURPOSE_MISMATCH" }
            it.metadata.reauthorize(context)
        }
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
    internal suspend fun install(preview: AccountIconPackPreview) = try {
        io {
            val receipt = it.install(preview)
            it.metadata.authorized(preview.context) { refreshReferences(preview.context.namespace) }
            receipt
        }
    } finally {
        // Includes file-phase failure/cancellation after durable queue commit; never emits per tick/page.
        materialWakeups.tryEmit(Unit)
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
