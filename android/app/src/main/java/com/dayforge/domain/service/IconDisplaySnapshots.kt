package com.dayforge.domain.service

import com.dayforge.data.appearance.AccountIconNamespace
import com.dayforge.data.appearance.IconRaster
import com.dayforge.data.appearance.IconRasterSize
import com.dayforge.data.local.AccountIconMemory
import com.dayforge.domain.appearance.ThemeVersionRef
import com.dayforge.domain.model.IconReference
import java.util.LinkedHashMap

/** Exact display inputs, not an authority or an object identity. */
internal data class IconDisplayKey(
    val reference: IconReference, val oneTime: Boolean, val theme: ThemeVersionRef,
    val dark: Boolean, val size: IconRasterSize, val tint: Int
)

/** Bounded process display snapshots, like already-visible images. A new consumer still verifies
 * authority and actual file bytes asynchronously. Never used for reads, exports or ready decisions.
 * Registration precedes every restore. Registry -> snapshot -> handle is the only lock order.
 */
internal class IconDisplaySnapshots(
    private val byteLimit: Int = 2_097_152, private val entryLimit: Int = 64
) : AccountIconMemory.Cache {
    init { require(byteLimit >= 0 && entryLimit >= 0) }
    private data class Entry(val namespace: AccountIconNamespace, val raster: IconRaster)
    private val entries = LinkedHashMap<IconDisplayKey, Entry>(16, 0.75f, true)
    private var bytes = 0
    private var generation = Any()
    private var blocked = false

    @Synchronized override fun authenticationTransition(blocked: Boolean) {
        this.blocked = blocked
        invalidate()
    }

    @Synchronized fun invalidate() {
        entries.clear(); bytes = 0; generation = Any()
    }

    @Synchronized fun ticket(): Any = generation

    @Synchronized fun restore(handle: IconImageHandle) {
        if (blocked) return
        val entry = entries[handle.displayKey] ?: return
        handle.restore(entry.namespace, entry.raster)
    }

    @Synchronized fun publish(handle: IconImageHandle, stamp: Any, ticket: Any,
        namespace: AccountIconNamespace, state: IconImageState) {
        if (blocked || generation !== ticket || !handle.publish(stamp, state)) return
        val key = handle.displayKey ?: return
        remove(key)
        if (state !is IconImageState.Ready) return
        val cost = state.raster.bitmap.allocationByteCount
        if (cost > byteLimit || entryLimit == 0) return
        entries[key] = Entry(namespace, state.raster); bytes += cost
        val iterator = entries.entries.iterator()
        while (bytes > byteLimit || entries.size > entryLimit) {
            bytes -= iterator.next().value.raster.bitmap.allocationByteCount
            iterator.remove()
        }
    }

    @Synchronized fun fail(handle: IconImageHandle, stamp: Any, state: IconImageState.Failed) {
        if (handle.publish(stamp, state)) handle.displayKey?.let(::remove)
    }

    private fun remove(key: IconDisplayKey) {
        entries.remove(key)?.let { bytes -= it.raster.bitmap.allocationByteCount }
    }
}
