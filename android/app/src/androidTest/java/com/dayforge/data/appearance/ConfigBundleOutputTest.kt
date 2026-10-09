package com.dayforge.data.appearance

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.domain.appearance.SvgValidationException
import com.dayforge.domain.model.ConfigBundle
import com.dayforge.domain.model.IconBlob
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Independent raw shared fixture; neither the exporter nor the importer creates its expectations. */
internal object ConfigFileFixture {
    val svg = """<svg width="1" height="1"><rect width="1" height="1" fill="#ff0000"/></svg>""".toByteArray()
    val dark = String(svg).replace("#ff0000", "#00ff00").toByteArray()
    fun fixture(name: String) = InstrumentationRegistry.getInstrumentation().context.assets.open("next/$name.json")
        .bufferedReader().use { Json.parseToJsonElement(it.readText()) }
    val png get() = Base64.decode(fixture("png").jsonArray.first().jsonObject.getValue("png").jsonPrimitive.content, Base64.DEFAULT)
    fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    fun manifest(): ConfigBundle {
        val root = fixture("config").jsonObject
        val pack = root.getValue("icon_pack").jsonObject
        val assets = pack.getValue("assets").jsonArray
        fun blob(bytes: ByteArray, type: String) = buildJsonObject {
            put("sha256", hash(bytes)); put("byte_length", bytes.size); put("media_type", type); put("width", 1); put("height", 1)
        }
        val changed = JsonObject(root + ("icon_pack" to JsonObject(pack + ("assets" to JsonArray(listOf(
            JsonObject(assets[0].jsonObject + mapOf("light" to blob(svg, "image/svg+xml"), "dark" to blob(dark, "image/svg+xml"))),
            JsonObject(assets[1].jsonObject + mapOf("light" to blob(png, "image/png"), "dark" to JsonNull))
        ))))))
        return Json.decodeFromJsonElement(changed)
    }
    fun content(blob: IconBlob): ByteArray = listOf(svg, dark, png).single { hash(it) == blob.sha256 }.copyOf()
    fun empty() = ConfigBundle("dayforge.config", 2, emptyList(), emptyList(), emptyList(), null, emptyList(), emptyList())
    fun archive(manifest: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json").apply {
                method = ZipEntry.STORED; size = manifest.size.toLong(); crc = CRC32().apply { update(manifest) }.value
            })
            zip.write(manifest); zip.closeEntry()
        }
        return output.toByteArray()
    }
}

@RunWith(AndroidJUnit4::class)
class ConfigBundleOutputTest {
    @Test fun completeExportHasExactDependencyClosureAndReimportsAllFieldsAndImages() = runBlocking {
        val manifest = ConfigFileFixture.manifest()
        val reads = mutableListOf<String>()
        val bundle = ConfigBundleOutput.create(manifest) { blob ->
            assertNotEquals(android.os.Looper.getMainLooper().thread, Thread.currentThread())
            reads += blob.sha256
            ConfigFileFixture.content(blob)
        }
        assertEquals(manifest, bundle.manifest)
        val expected = listOf(ConfigFileFixture.svg, ConfigFileFixture.dark, ConfigFileFixture.png).map(ConfigFileFixture::hash).sorted()
        assertEquals(expected, reads)
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bundle.exportBytes())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names += entry.name
                val bytes = zip.readBytes()
                if (entry.name == "manifest.json") assertEquals(Json.encodeToJsonElement(manifest), Json.parseToJsonElement(String(bytes)))
                else assertArrayEquals(ConfigFileFixture.content(manifest.iconPack!!.assets.flatMap { listOfNotNull(it.light, it.dark) }
                    .single { it.sha256 == entry.name.removePrefix("blobs/") }), bytes)
            }
        }
        assertEquals(listOf("manifest.json") + expected.map { "blobs/$it" }, names)
        val imported = ValidatedConfigBundle.read { ByteArrayInputStream(bundle.exportBytes()) }
        assertEquals(manifest, imported.manifest)
        assertEquals("by_time", imported.manifest.metrics.single().aggregationType)
        assertEquals(60, imported.manifest.nodes[4].activity!!.targetValue)
        assertEquals("2026-12-31", imported.manifest.nodes.first().goal!!.dueDate)
        assertEquals("#AFC6FF", imported.manifest.themes.single().dark.chart["line"])
    }

    @Test fun emptyAndUnresolvedOnlyExportsDoNotRequestAnyBytes() = runBlocking {
        val empty = ConfigBundleOutput.create(ConfigFileFixture.empty()) { error("No blob may be requested") }
        assertTrue(empty.manifest.nodes.isEmpty())
        val full = ConfigFileFixture.manifest()
        val roles = full.copy(nodes = listOf(full.nodes.first()), metrics = emptyList(), links = emptyList(),
            iconPack = null, unresolvedRoles = listOf("goal.default"))
        assertEquals(roles, ConfigBundleOutput.create(roles) { error("Unresolved is not a fake asset") }.manifest)
    }

    @Test fun deduplicationFrozenCopiesAndStableReplayNeverReopenSources() = runBlocking<Unit> {
        val full = ConfigFileFixture.manifest()
        val manifest = full.copy(iconPack = full.iconPack!!.copy(assets = full.iconPack.assets.map { it.copy(dark = it.light) }))
        val returned = mutableListOf<ByteArray>()
        val bundle = ConfigBundleOutput.create(manifest) { ConfigFileFixture.content(it).also(returned::add) }
        assertEquals(2, returned.size)
        val original = bundle.exportBytes()
        returned.forEach { it.fill(0) }
        bundle.exportBytes().fill(0)
        val blob = manifest.iconPack!!.assets.first().light
        bundle.readBlob(blob.sha256).fill(0)
        assertArrayEquals(original, bundle.exportBytes())
        assertArrayEquals(ConfigFileFixture.svg, bundle.readBlob(blob.sha256))
        assertArrayEquals(original, ConfigBundleOutput.create(manifest, ConfigFileFixture::content).exportBytes())
        assertThrows(UnsupportedOperationException::class.java) { (bundle.manifest.nodes as MutableList).clear() }
    }

    @Test fun invalidMutatedNestedMetadataIsRejectedBeforeAnyByteRead() = runBlocking {
        val full = ConfigFileFixture.manifest()
        val nodes = full.nodes.toMutableList()
        val mutable = full.copy(nodes = nodes)
        nodes.add(nodes.first())
        var reads = 0
        val failure = runCatching { ConfigBundleOutput.create(mutable) { reads++; ConfigFileFixture.content(it) } }.exceptionOrNull()
        assertTrue(failure is ConfigBundleInputException)
        assertEquals(0, reads)
        val palette = full.themes.single().light.material.toMutableMap()
        val theme = full.themes.single().copy(light = full.themes.single().light.copy(material = palette))
        val badTheme = full.copy(themes = listOf(theme))
        palette["unrecognized"] = "#123456"
        assertTrue(runCatching { ConfigBundleOutput.create(badTheme) { reads++; ConfigFileFixture.content(it) } }.exceptionOrNull() is ConfigBundleInputException)
        assertEquals(0, reads)
    }

    @Test fun wrongLengthHashAndUnsafeActualImagesCannotBecomeExportSuccess() = runBlocking {
        val manifest = ConfigFileFixture.manifest()
        val length = runCatching { ConfigBundleOutput.create(manifest) { ByteArray(0) } }.exceptionOrNull()
        assertEquals("CONFIG_BLOB_LENGTH", length?.message)
        val wrong = runCatching { ConfigBundleOutput.create(manifest) { blob ->
            ConfigFileFixture.content(blob).also { it[0] = 0 }
        } }.exceptionOrNull()
        assertTrue(wrong is SvgValidationException || wrong is PngValidationException)
        val unsafe = """<svg width="1" height="1"><script/></svg>""".toByteArray()
        val changed = manifest.copy(iconPack = manifest.iconPack!!.copy(assets = manifest.iconPack.assets.mapIndexed { index, asset ->
            if (index == 0) asset.copy(light = asset.light.copy(sha256 = ConfigFileFixture.hash(unsafe), byteLength = unsafe.size)) else asset
        }))
        val image = runCatching { ConfigBundleOutput.create(changed) { blob ->
            if (blob.sha256 == ConfigFileFixture.hash(unsafe)) unsafe else ConfigFileFixture.content(blob)
        } }.exceptionOrNull()
        assertTrue(image is SvgValidationException)
    }

    @Test fun byteSourceFailuresAndCancellationKeepTheirIdentityWithoutReturningPartialArchive() = runBlocking {
        val manifest = ConfigFileFixture.manifest()
        val failure = IOException("fixed asset is not locally ready")
        assertSame(failure, runCatching { ConfigBundleOutput.create(manifest) { throw failure } }.exceptionOrNull())
        val started = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        val task = async(Dispatchers.IO) {
            ConfigBundleOutput.create(manifest) {
                started.complete(Unit)
                try { CompletableDeferred<ByteArray>().await() } finally { released.complete(Unit) }
            }
        }
        withTimeout(3000) { started.await(); task.cancelAndJoin(); released.await() }
        assertTrue(task.isCancelled); assertFalse(task.children.any())
    }

    @Test fun exactEightAndThirtyTwoMiBOutputCapsRejectBeforeOvershootAndReturnIndependentBytes() {
        for ((limit, code) in listOf(CONFIG_MANIFEST_LIMIT to "CONFIG_MANIFEST_LIMIT", ICON_ARCHIVE_LIMIT to "CONFIG_ARCHIVE_LIMIT")) {
            val buffer = ConfigOutputBuffer(limit, code)
            val block = ByteArray(65_536) { 42 }
            repeat(limit / block.size) { buffer.write(block) }
            val snapshot = buffer.bytes()
            assertEquals(limit, snapshot.size)
            snapshot[0] = 0
            assertEquals(42, buffer.bytes()[0].toInt())
            assertEquals(code, assertThrows(ConfigBundleInputException::class.java) { buffer.write(1) }.code)
            assertEquals(code, assertThrows(ConfigBundleInputException::class.java) { buffer.write(block, 0, 1) }.code)
            assertEquals(limit, buffer.bytes().size)
        }
        var checkpoints = 0
        ConfigOutputBuffer(0, "cap") { checkpoints++ }.write(ByteArray(0))
        assertEquals(1, checkpoints)
    }
}
