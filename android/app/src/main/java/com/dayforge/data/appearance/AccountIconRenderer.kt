package com.dayforge.data.appearance

import com.dayforge.data.local.LocalIconAccess
import com.dayforge.domain.appearance.ThemeVersionRef
import com.dayforge.domain.model.IconAsset
import java.util.LinkedHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Internal, inactive account renderer. Cache hits are never file-integrity or ownership proofs. */
internal class AccountIconRenderer(
    private val metadata: AccountIconRepository,
    private val store: AccountIconStore,
    private val byteLimit: Int = 8_388_608,
    private val entryLimit: Int = 128,
    private val draw: (ByteArray, IconAsset, Boolean, IconRasterSize, Int) -> IconRaster = ::renderIcon
) : com.dayforge.data.local.AccountIconMemory.Cache {
    init { require(byteLimit in 0..8_388_608 && entryLimit in 0..128) }

    private data class Key(
        val access: LocalIconAccess, val asset: IconAsset, val theme: ThemeVersionRef,
        val dark: Boolean, val tint: Int, val size: IconRasterSize, val rendererVersion: Int = 1
    )
    private val monitor = Any()
    private var access: LocalIconAccess? = null
    private var generation = Any()
    private var bytes = 0
    private val cache = LinkedHashMap<Key, IconRaster>(16, 0.75f, true)
    private var transitionBlocked = false

    init { metadata.registerCache(this) }

    override fun authenticationTransition(blocked: Boolean) = synchronized(monitor) {
        clear()
        transitionBlocked = blocked
    }

    /** Credential-free diagnostic; does not expose cached pictures or account identities. */
    internal fun memoryUsage(): Pair<Int, Int> = synchronized(monitor) { cache.size to bytes }

    /** Only drops memory references. In-flight work cannot refill the invalidated generation. */
    fun invalidate() = synchronized(monitor) { clear() }

    private fun clear() {
        cache.clear(); bytes = 0; access = null; generation = Any()
        // Published immutable bitmaps may still be held by Compose/Glance; never recycle them.
    }

    suspend fun render(context: AccountIconContext, assetId: String, theme: ThemeVersionRef,
        dark: Boolean, size: IconRasterSize, tint: Int): IconRaster {
        metadata.reauthorize(context)
        val result = renders.withLock {
            // Keep the process render lease until actual blocking drawing and cancellation finish.
            withContext(Dispatchers.IO) {
                val stamp = metadata.authorized(context) { synchronized(monitor) {
                    check(!transitionBlocked) { "ICON_RENDER_INVALIDATED" }
                    if (access != context.access) { clear(); access = context.access }
                    generation
                } }
                val asset = requireNotNull(metadata.asset(context, assetId)) { "ICON_ASSET_NOT_OWNED" }
                val blob = selectedIconBlob(asset, dark)
                // Even hits read/validate the actual owned ready file. UI should retain a snapshot,
                // not call this from recomposition or timer ticks. No disk-render cache is created.
                val source = store.read(context, assetId, blob.sha256)
                val key = Key(context.access, asset, theme, dark, tint, size)
                val hit = metadata.authorized(context) { synchronized(monitor) {
                    check(generation === stamp) { "ICON_RENDER_INVALIDATED" }
                    cache[key]?.also { check(!it.bitmap.isRecycled) { "ICON_RASTER_RECYCLED" } }
                } }
                val raster = if (hit != null) hit else {
                    val rendered = draw(source, asset, dark, size, tint)
                    currentCoroutineContext().ensureActive()
                    check(rendered.blob == blob && rendered.width == size.width && rendered.height == size.height &&
                        !rendered.bitmap.isMutable && !rendered.bitmap.isRecycled) { "ICON_RASTER_INVALID" }
                    metadata.authorized(context) { synchronized(monitor) {
                        check(generation === stamp) { "ICON_RENDER_INVALIDATED" }
                        val cost = rendered.bitmap.allocationByteCount
                        if (cost <= byteLimit && entryLimit > 0) {
                            cache[key] = rendered; bytes += cost
                            val entries = cache.entries.iterator()
                            while (bytes > byteLimit || cache.size > entryLimit) {
                                bytes -= entries.next().value.bitmap.allocationByteCount; entries.remove()
                            }
                        }
                        rendered
                    } }
                }
                raster to stamp
            }
        }
        return metadata.authorized(context) { synchronized(monitor) {
            check(generation === result.second) { "ICON_RENDER_INVALIDATED" }
            result.first
        } }
    }

    companion object { private val renders = Mutex() }
}
