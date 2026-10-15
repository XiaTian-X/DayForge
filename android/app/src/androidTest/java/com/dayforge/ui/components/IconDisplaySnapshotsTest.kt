package com.dayforge.ui.components

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.appearance.AccountIconNamespace
import com.dayforge.data.appearance.IconRaster
import com.dayforge.data.appearance.IconRasterSize
import com.dayforge.data.local.AccountIconMemory
import com.dayforge.domain.appearance.ThemeVersionRef
import com.dayforge.domain.model.IconBlob
import com.dayforge.domain.model.IconReference
import com.dayforge.domain.service.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IconDisplaySnapshotsTest {
    private fun id(n: Int) = "b0160000-0000-4000-8000-${n.toString().padStart(12, '0')}"
    private val namespace = AccountIconNamespace(id(1), id(2), id(3))
    private val key = IconDisplayKey(IconReference.Asset(id(4)), false, ThemeVersionRef(id(5), 1),
        false, IconRasterSize(8, 8), 0xff123456.toInt())
    private fun raster(): IconRaster {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val frozen = requireNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false)); bitmap.recycle()
        return IconRaster(frozen, IconBlob("a".repeat(64), 100, "image/png", 8, 8))
    }
    private fun put(cache: IconDisplaySnapshots, key: IconDisplayKey = this.key, raster: IconRaster = raster()) {
        val handle = IconImageHandle(key)
        val stamp = requireNotNull(handle.begin())
        assertTrue(handle.bind(stamp, namespace))
        cache.publish(handle, stamp, cache.ticket(), namespace, IconImageState.Ready(raster))
        handle.close()
    }
    private fun get(cache: IconDisplaySnapshots, key: IconDisplayKey = this.key) =
        IconImageHandle(key).also(cache::restore)

    @Test fun exactKeysAndLruByteAndEntryBoundsDoNotRecycleBorrowedPixels() {
        val image = raster()
        val cache = IconDisplaySnapshots(byteLimit = image.bitmap.allocationByteCount * 2, entryLimit = 2)
        val dark = key.copy(dark = true); val tinted = key.copy(tint = 0)
        put(cache, key, image); put(cache, dark)
        assertSame(image, (get(cache).state.value as IconImageState.Ready).raster) // touches LRU
        put(cache, tinted)
        assertSame(IconImageState.Loading, get(cache, dark).state.value)
        assertTrue(get(cache).state.value is IconImageState.Ready)
        for (other in listOf(key.copy(oneTime = true), key.copy(size = IconRasterSize(16, 16)),
            key.copy(theme = key.theme.copy(revision = 2)), key.copy(reference = IconReference.Asset(id(6))))) {
            assertSame(IconImageState.Loading, get(cache, other).state.value)
        }
        val tiny = IconDisplaySnapshots(byteLimit = image.bitmap.allocationByteCount - 1)
        put(tiny, raster = image)
        assertSame(IconImageState.Loading, get(tiny).state.value)
        cache.invalidate(); assertFalse(image.bitmap.isRecycled)
    }

    @Test fun registryTransitionClearsSnapshotsAndRejectsLateOrClosedPublication() {
        val registry = AccountIconMemory()
        val cache = IconDisplaySnapshots().also(registry::register)
        put(cache)
        val handle = IconImageHandle(key).also(registry::register)
        cache.restore(handle)
        assertTrue(handle.state.value is IconImageState.Ready)
        val stamp = requireNotNull(handle.begin()); val ticket = cache.ticket()
        registry.beginTransition()
        assertSame(IconImageState.Empty, handle.state.value)
        val during = IconImageHandle(key).also(registry::register)
        cache.restore(during); assertSame(IconImageState.Empty, during.state.value)
        registry.endTransition()
        cache.publish(handle, stamp, ticket, namespace, IconImageState.Ready(raster()))
        assertSame(IconImageState.Empty, handle.state.value)
        assertSame(IconImageState.Loading, get(cache).state.value)
        val closed = IconImageHandle(key)
        val closedStamp = requireNotNull(closed.begin()); closed.close()
        cache.publish(closed, closedStamp, cache.ticket(), namespace, IconImageState.Ready(raster()))
        assertSame(IconImageState.Loading, get(cache).state.value)
    }
}
