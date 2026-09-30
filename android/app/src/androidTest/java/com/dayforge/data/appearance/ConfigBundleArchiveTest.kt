package com.dayforge.data.appearance

import android.graphics.Color
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.domain.appearance.SvgValidationException
import com.dayforge.domain.model.ConfigSchedule
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConfigBundleArchiveTest {
    private val svg = """<svg width="1" height="1"><rect width="1" height="1" fill="#ff0000"/></svg>""".toByteArray()
    private val dark = String(svg).replace("#ff0000", "#00ff00").toByteArray()
    private val png by lazy {
        Base64.decode(fixture("png").jsonArray.first().jsonObject.getValue("png").jsonPrimitive.content, Base64.DEFAULT)
    }
    private fun fixture(name: String): JsonElement = InstrumentationRegistry.getInstrumentation().context
        .assets.open("next/$name.json").bufferedReader().use { Json.parseToJsonElement(it.readText()) }
    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    private fun blob(bytes: ByteArray, media: String) = buildJsonObject {
        put("sha256", hash(bytes)); put("byte_length", bytes.size); put("media_type", media)
        put("width", 1); put("height", 1)
    }
    private fun manifest(): JsonObject {
        val base = fixture("config").jsonObject
        val pack = base.getValue("icon_pack").jsonObject
        val assets = pack.getValue("assets").jsonArray
        return JsonObject(base + ("icon_pack" to JsonObject(pack + ("assets" to JsonArray(listOf(
            JsonObject(assets[0].jsonObject + mapOf("light" to blob(svg, "image/svg+xml"), "dark" to blob(dark, "image/svg+xml"))),
            JsonObject(assets[1].jsonObject + mapOf("light" to blob(png, "image/png"), "dark" to JsonNull))
        ))))))
    }
    private val empty = """{"format":"dayforge.config","format_version":2,"nodes":[],"metrics":[],"links":[],"icon_pack":null,"unresolved_roles":[],"themes":[]}"""
    private fun entries(text: String = manifest().toString(), images: List<ByteArray> = listOf(svg, dark, png)) =
        listOf("manifest.json" to text.toByteArray()) + images.distinctBy(::hash).map { "blobs/${hash(it)}" to it }
    private fun zip(items: List<Pair<String, ByteArray>>, stored: Boolean = false): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { stream -> items.forEach { (name, bytes) ->
            val entry = ZipEntry(name)
            if (stored) { entry.method = ZipEntry.STORED; entry.size = bytes.size.toLong(); entry.crc = CRC32().apply { update(bytes) }.value }
            stream.putNextEntry(entry); stream.write(bytes); stream.closeEntry()
        } }
        return output.toByteArray()
    }
    private suspend fun read(bytes: ByteArray) = ValidatedConfigBundle.read { ByteArrayInputStream(bytes) }
    private suspend fun rejected(bytes: ByteArray, code: String? = null) {
        val failure = runCatching { read(bytes) }.exceptionOrNull()
        assertTrue("unexpected failure $failure", failure is ConfigBundleInputException)
        if (code != null) assertEquals(code, (failure as ConfigBundleInputException).code)
    }
    private fun replace(root: JsonElement, path: List<JsonElement>, value: JsonElement): JsonElement {
        if (path.isEmpty()) return value
        return when (root) {
            is JsonObject -> {
                val key = path.first().jsonPrimitive.content
                JsonObject(root + (key to replace(root[key] ?: JsonNull, path.drop(1), value)))
            }
            is JsonArray -> JsonArray(root.mapIndexed { index, child ->
                if (index == path.first().jsonPrimitive.int) replace(child, path.drop(1), value) else child
            })
            else -> error("invalid fixture path")
        }
    }

    @Test fun completeTemplateKeepsAllBusinessFieldsAndValidatesActualImages() = runBlocking {
        for (stored in listOf(false, true)) {
            val template = read(zip(entries().reversed(), stored))
            val config = template.manifest
            assertEquals(7, config.nodes.size)
            val activities = config.nodes.mapNotNull { it.activity }
            assertEquals(listOf("check", "count", "count", "duration", "duration", "check"), activities.map { it.trackingMode })
            assertEquals(listOf(false, false, true, false, true, false), activities.map { it.isCountdown })
            assertEquals(listOf("Daily", "Weekly", "Interval", "Monthly", "Daily", "Once"), activities.map { it.schedule.javaClass.simpleName })
            assertEquals("one_and_done", activities.last().completionPolicy)
            assertNull((activities.last().schedule as ConfigSchedule.Once).dueDate)
            assertEquals("2026-12-31", config.nodes.first().goal!!.dueDate)
            assertEquals("#802196F3", config.nodes[2].appearance.accentColor)
            assertEquals("by_time", config.metrics.single().aggregationType)
            assertEquals(70.5, config.metrics.single().targetValue!!, 0.0)
            assertEquals(90.25, config.metrics.single().targetValueUpper!!, 0.0)
            assertEquals(1.25, config.links.single().coefficient, 0.0)
            assertTrue(config.links.single().promptOnComplete)
            assertEquals(listOf("goal.default", "metric.weight"), config.unresolvedRoles)
            assertEquals("#AFC6FF", config.themes.single().dark.chart["line"])
            for ((content, expectedPixel) in listOf(svg to Color.RED, dark to Color.GREEN, png to Color.RED)) {
                assertArrayEquals(content, template.readBlob(hash(content)))
                val descriptor = config.iconPack!!.assets.flatMap { listOfNotNull(it.light, it.dark) }.first { it.sha256 == hash(content) }
                val bitmap = if (descriptor.mediaType == "image/png") decodePng(template.readBlob(hash(content)), descriptor)
                    else renderSvg(template.readBlob(hash(content)), descriptor)
                try { assertEquals(expectedPixel, bitmap.getPixel(0, 0)) } finally { bitmap.recycle() }
            }
        }
    }

    @Test fun explicitEmptyTemplateAndUnresolvedOnlyTemplateNeedNoIconBytes() = runBlocking {
        val config = read(zip(entries(empty, emptyList()))).manifest
        assertTrue(config.nodes.isEmpty()); assertTrue(config.metrics.isEmpty()); assertTrue(config.links.isEmpty())
        assertNull(config.iconPack); assertTrue(config.themes.isEmpty())
        val source = manifest()
        val root = JsonObject(source + mapOf("nodes" to JsonArray(listOf(source.getValue("nodes").jsonArray[0])),
            "metrics" to JsonArray(emptyList()), "links" to JsonArray(emptyList()), "icon_pack" to JsonNull,
            "unresolved_roles" to JsonArray(listOf(JsonPrimitive("goal.default")))))
        assertEquals("goal.default", read(zip(entries(root.toString(), emptyList()))).manifest.unresolvedRoles.single())
    }

    @Test fun sameHashLightAndDarkAreReadOnceAsAnExactDependencySet() = runBlocking {
        val root = manifest(); val pack = root.getValue("icon_pack").jsonObject
        val changed = pack.getValue("assets").jsonArray.map { asset ->
            JsonObject(asset.jsonObject + ("dark" to asset.jsonObject.getValue("light")))
        }
        val tree = JsonObject(root + ("icon_pack" to JsonObject(pack + ("assets" to JsonArray(changed)))))
        val template = read(zip(entries(tree.toString(), listOf(svg, png))))
        assertArrayEquals(svg, template.readBlob(hash(svg)))
        assertArrayEquals(png, template.readBlob(hash(png)))
    }

    @Test fun metadataIsDeeplyImmutableIncludingSchedulesAndAllThemePalettes() = runBlocking {
        val config = read(zip(entries())).manifest
        for (items in listOf(config.nodes, config.metrics, config.links, config.unresolvedRoles, config.themes,
            config.iconPack!!.assets, (config.nodes[2].activity!!.schedule as ConfigSchedule.Weekly).weekdays)) {
            assertThrows(UnsupportedOperationException::class.java) { (items as MutableList).clear() }
        }
        assertThrows(UnsupportedOperationException::class.java) { (config.iconPack!!.roles as MutableMap).clear() }
        for (palette in listOf(config.themes.single().light, config.themes.single().dark)) {
            for (colors in listOf(palette.material, palette.status, palette.chart)) {
                assertThrows(UnsupportedOperationException::class.java) { (colors as MutableMap).clear() }
            }
        }
        assertEquals(listOf(1, 3, 5), (config.nodes[2].activity!!.schedule as ConfigSchedule.Weekly).weekdays)
        assertEquals(36, config.themes.single().light.material.size)
    }

    @Test fun sourceOpensOnceClosesAndAllLaterReadsUseFrozenIndependentCopies() = runBlocking {
        val source = zip(entries()); val original = source.copyOf()
        var opened = 0; var closed = 0
        val template = ValidatedConfigBundle.read {
            assertNotEquals(android.os.Looper.getMainLooper().thread, Thread.currentThread())
            opened++
            object : ByteArrayInputStream(source) {
                override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(len, 7))
                override fun available(): Int = error("not a length oracle")
                override fun close() { closed++; super.close() }
            }
        }
        source.fill(0); template.exportBytes().fill(0); template.readBlob(hash(svg)).fill(0)
        assertArrayEquals(original, template.exportBytes()); assertArrayEquals(svg, template.readBlob(hash(svg)))
        assertEquals(1, opened); assertEquals(1, closed)
        val error = runCatching { template.readBlob("a".repeat(64)) }.exceptionOrNull()
        assertEquals("CONFIG_ENTRY_MISSING", (error as ConfigBundleInputException).code)
    }

    @Test fun manifestEightMiBBoundaryIsRealAndNeverRelaxesStandaloneIconBudget() = runBlocking {
        val text = empty.padEnd(CONFIG_MANIFEST_LIMIT, ' ')
        val archive = zip(entries(text, emptyList()))
        assertTrue(read(archive).manifest.nodes.isEmpty())
        rejected(zip(entries(text + " ", emptyList())), "CONFIG_ENTRY_LIMIT")
        val iconError = runCatching { ValidatedIconPack.read { ByteArrayInputStream(archive) } }.exceptionOrNull()
        assertEquals("PACK_ENTRY_LIMIT", (iconError as IconPackInputException).code)
        assertTrue(read(zip(entries(empty.padEnd(ICON_MANIFEST_LIMIT + 1, ' '), emptyList()))).manifest.nodes.isEmpty())
    }

    @Test fun everySharedInvalidConfigCaseStillFailsThroughArchiveBoundary() = runBlocking {
        val cases = fixture("theme-config-invalid").jsonArray.filter { it.jsonObject.getValue("base").jsonPrimitive.content == "config" }
        assertEquals(49, cases.size)
        for (raw in cases) {
            val case = raw.jsonObject
            val changed = replace(manifest(), case.getValue("path").jsonArray, case.getValue("value"))
            rejected(zip(entries(changed.toString())))
        }
    }

    @Test fun versionsMissingFieldsUnknownHistoryAndInvalidReferencesAreNeverDefaulted() = runBlocking {
        for (text in listOf(empty.replace("dayforge.config", "dayforge.icon-pack"), empty.replace(":2,", ":1,"),
            empty.replace(":2,", ":\"2\","), empty.replace(":2,", ":true,"))) {
            rejected(zip(entries(text, emptyList())), "CONFIG_VERSION")
        }
        val root = Json.parseToJsonElement(empty).jsonObject
        for (key in root.keys) rejected(zip(entries(JsonObject(root - key).toString(), emptyList())))
        for (key in listOf("history", "owner", "device", "outbox", "revision", "completion_event_uuid")) {
            rejected(zip(entries(JsonObject(root + (key to JsonArray(emptyList()))).toString(), emptyList())))
        }
        val source = manifest()
        val bad = replace(source, listOf(JsonPrimitive("nodes"), JsonPrimitive(2), JsonPrimitive("parent_key")), JsonPrimitive("check"))
        rejected(zip(entries(bad.toString())), "CONFIG_MANIFEST_INVALID")
    }

    @Test fun duplicateKeysUtf8UnicodeDepthAndTrailingDocumentsAreRejected() = runBlocking {
        rejected(zip(entries(empty.replace("\"nodes\":", "\"nodes\":[],\"no\\u0064es\":"), emptyList())), "CONFIG_MANIFEST_DUPLICATE")
        rejected(zip(listOf("manifest.json" to byteArrayOf(0xc3.toByte(), 0x28))), "CONFIG_MANIFEST_UTF8")
        rejected(zip(entries(empty.replace("\"nodes\":[]", "\"nodes\":[\"\\uD800\"]"), emptyList())), "CONFIG_MANIFEST_UNICODE")
        rejected(zip(entries("[".repeat(17) + "0" + "]".repeat(17), emptyList())), "CONFIG_MANIFEST_DEPTH")
        rejected(zip(entries(empty + empty, emptyList())), "CONFIG_MANIFEST_JSON")
    }

    @Test fun missingExtraAndUnsafeEntriesNeverProducePartialPreview() = runBlocking {
        rejected(zip(entries().dropLast(1)), "CONFIG_ENTRIES_MISMATCH")
        rejected(zip(entries() + ("blobs/${"a".repeat(64)}" to svg)), "CONFIG_ENTRIES_MISMATCH")
        for (name in listOf("../manifest.json", "/manifest.json", "blobs/UPPER", "icons/", "theme.json")) {
            rejected(zip(entries() + (name to svg)), "CONFIG_ZIP_PATH")
        }
        rejected(zip(entries(empty, listOf(svg))), "CONFIG_ENTRIES_MISMATCH")
        rejected(zip(entries()).let { it + byteArrayOf(0) }, "CONFIG_ZIP_INVALID")
    }

    @Test fun actualBytesMustMatchHashLengthDimensionsAndSafeImageProfile() = runBlocking {
        val root = manifest()
        val badHash = zip(entries().map { (name, bytes) -> if (name == "blobs/${hash(svg)}") name to dark else name to bytes })
        val hashError = runCatching { read(badHash) }.exceptionOrNull()
        assertEquals("SVG_HASH", (hashError as SvgValidationException).code)
        for ((field, value) in listOf("byte_length" to JsonPrimitive(svg.size - 1), "width" to JsonPrimitive(2))) {
            val changed = replace(root, listOf(JsonPrimitive("icon_pack"), JsonPrimitive("assets"), JsonPrimitive(0),
                JsonPrimitive("light"), JsonPrimitive(field)), value)
            val error = runCatching { read(zip(entries(changed.toString()))) }.exceptionOrNull()
            if (field == "byte_length") assertEquals("CONFIG_ENTRY_LIMIT", (error as ConfigBundleInputException).code)
            else assertEquals("SVG_DIMENSIONS", (error as SvgValidationException).code)
        }
        val unsafe = """<svg width="1" height="1"><script/></svg>""".toByteArray()
        val changed = replace(root, listOf(JsonPrimitive("icon_pack"), JsonPrimitive("assets"), JsonPrimitive(0), JsonPrimitive("light")), blob(unsafe, "image/svg+xml"))
        assertTrue(runCatching { read(zip(entries(changed.toString(), listOf(unsafe, dark, png)))) }.exceptionOrNull() is SvgValidationException)
        val invalidPng = byteArrayOf(1, 2, 3)
        val pngTree = replace(root, listOf(JsonPrimitive("icon_pack"), JsonPrimitive("assets"), JsonPrimitive(1), JsonPrimitive("light")), blob(invalidPng, "image/png"))
        assertTrue(runCatching { read(zip(entries(pngTree.toString(), listOf(svg, dark, invalidPng)))) }.exceptionOrNull() is PngValidationException)
    }

    @Test fun zeroProgressShortReadsIoAndCloseFailureKeepTheirMeaning() = runBlocking {
        val source = zip(entries(empty, emptyList()))
        assertTrue(ValidatedConfigBundle.read { object : ByteArrayInputStream(source) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = 0
        } }.manifest.nodes.isEmpty())
        for (failure in listOf(IOException("synthetic IO"), CancellationException("synthetic cancellation"))) {
            var closed = false
            val error = runCatching { ValidatedConfigBundle.read { object : InputStream() {
                override fun read(): Int = throw failure
                override fun close() { closed = true }
            } } }.exceptionOrNull()
            assertSame(failure, error); assertTrue(closed)
        }
        val closeFailure = IOException("synthetic close failure")
        assertSame(closeFailure, runCatching { ValidatedConfigBundle.read { object : ByteArrayInputStream(source) {
            override fun close(): Unit = throw closeFailure
        } } }.exceptionOrNull())
        for (count in listOf(-2, 65_537)) {
            val error = runCatching { ValidatedConfigBundle.read { object : InputStream() {
                override fun read(): Int = -1
                override fun read(b: ByteArray, off: Int, len: Int): Int = count
            } } }.exceptionOrNull()
            assertEquals("CONFIG_READ_INVALID", (error as ConfigBundleInputException).code)
        }
    }

    @Test fun cancellationJoinsAndClosesWithoutPublishingTemplate() = runBlocking {
        val started = CompletableDeferred<Unit>(); var closed = false; var published = false
        val work = launch(Dispatchers.Default) {
            ValidatedConfigBundle.read { object : InputStream() {
                override fun read(): Int = error("bulk path required")
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    started.complete(Unit); Thread.sleep(5); b[off] = 1; return 1
                }
                override fun close() { closed = true }
            } }
            published = true
        }
        started.await(); work.cancelAndJoin(); assertTrue(closed); assertFalse(published)
    }
}
