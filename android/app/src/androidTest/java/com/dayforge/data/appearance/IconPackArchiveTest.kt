package com.dayforge.data.appearance

import android.graphics.Color
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IconPackArchiveTest {
    private val svg = """<svg width="1" height="1"><rect width="1" height="1" fill="#ff0000"/></svg>""".toByteArray()
    private val png by lazy {
        val fixture = InstrumentationRegistry.getInstrumentation().context.assets.open("next/png.json").bufferedReader().use { it.readText() }
        Base64.decode(Json.parseToJsonElement(fixture).jsonArray.first().jsonObject.getValue("png").jsonPrimitive.content, Base64.DEFAULT)
    }
    private fun hash(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    private fun assetId(n: Int) = "91000000-0000-4000-8000-%012d".format(n)
    private fun blob(data: ByteArray, media: String = "image/svg+xml") = """{"sha256":"${hash(data)}","byte_length":${data.size},"media_type":"$media","width":1,"height":1}"""
    private fun manifest(data: ByteArray = svg, media: String = "image/svg+xml", dark: ByteArray? = null, task: Boolean = false): String = """{
        "format":"dayforge.icon-pack","format_version":1,"pack_id":"${assetId(1)}","revision":1,"name":"统一风格",
        "assets":[{"asset_id":"${assetId(2)}","name":"red","purpose":"${if (task) "task" else "general"}","color_mode":"template",
        "light":${blob(data, media)},"dark":${dark?.let { blob(it, media) } ?: "null"}}],
        "roles":{"${if (task) "task" else "habit"}.custom":"${assetId(2)}"},"placeholder_asset_id":${if (task) "null" else "\"${assetId(2)}\""}}"""
    private fun entries(text: String = manifest(), images: List<ByteArray> = listOf(svg)) =
        listOf("manifest.json" to text.toByteArray()) + images.distinctBy(::hash).map { "blobs/${hash(it)}" to it }
    private fun zip(entries: List<Pair<String, ByteArray>>, stored: Boolean = false): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> entries.forEach { (name, bytes) ->
            val entry = ZipEntry(name)
            if (stored) { entry.method = ZipEntry.STORED; entry.size = bytes.size.toLong(); entry.crc = CRC32().apply { update(bytes) }.value }
            zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry()
        } }
        return output.toByteArray()
    }
    private suspend fun read(bytes: ByteArray) = ValidatedIconPack.read { ByteArrayInputStream(bytes) }
    private suspend fun rejected(bytes: ByteArray, code: String? = null) {
        val error = runCatching { read(bytes) }.exceptionOrNull()
        assertTrue("Unexpected failure: $error", error is IconPackInputException)
        if (code != null) assertEquals(code, (error as IconPackInputException).code)
    }

    // Independent raw ZIP fixture: explicit little-endian records, not the production reader or DTO writer.
    private fun ByteArrayOutputStream.u16(value: Int) { write(value and 255); write(value ushr 8 and 255) }
    private fun ByteArrayOutputStream.u32(value: Long) { repeat(4) { write((value ushr (it * 8)).toInt() and 255) } }
    private fun ByteArrayOutputStream.u64(value: Long) { repeat(8) { write((value ushr (it * 8)).toInt() and 255) } }
    private data class Raw(val name: String, val bytes: ByteArray, val flags: Int = 0, val mode: Int = 0,
        val method: Int = 0, val packed: ByteArray = bytes, val local64: Boolean = false, val central64: Boolean = false,
        val descriptor: Int = 0, val extra: ByteArray = byteArrayOf())
    private fun raw(entries: List<Raw>, end64: Boolean = false, comment: ByteArray = byteArrayOf()): ByteArray {
        val output = ByteArrayOutputStream()
        val records = ByteArrayOutputStream()
        entries.forEach { item ->
            val offset = output.size()
            val name = item.name.toByteArray()
            val crc = CRC32().apply { update(item.bytes) }.value
            val flags = item.flags or if (item.descriptor > 0) 8 else 0
            val version = if (item.local64 || item.central64) 45 else 20
            val localExtra = ByteArrayOutputStream().apply {
                if (item.local64) { u16(1); u16(16); u64(item.bytes.size.toLong()); u64(item.packed.size.toLong()) }
                write(item.extra)
            }.toByteArray()
            output.apply {
                u32(0x04034b50); u16(version); u16(flags); u16(item.method); u16(0); u16(0)
                u32(if (item.descriptor > 0) 0 else crc)
                u32(if (item.local64) 0xffffffff else if (item.descriptor > 0) 0 else item.packed.size.toLong())
                u32(if (item.local64) 0xffffffff else if (item.descriptor > 0) 0 else item.bytes.size.toLong())
                u16(name.size); u16(localExtra.size); write(name); write(localExtra); write(item.packed)
                if (item.descriptor > 0) {
                    if (item.descriptor in setOf(16, 24)) u32(0x08074b50)
                    u32(crc)
                    if (item.descriptor >= 20) { u64(item.packed.size.toLong()); u64(item.bytes.size.toLong()) }
                    else { u32(item.packed.size.toLong()); u32(item.bytes.size.toLong()) }
                }
            }
            val centralExtra = ByteArrayOutputStream().apply {
                if (item.central64) { u16(1); u16(24); u64(item.bytes.size.toLong()); u64(item.packed.size.toLong()); u64(offset.toLong()) }
                write(item.extra)
            }.toByteArray()
            records.apply {
                u32(0x02014b50); u16(3 * 256 + version); u16(version); u16(flags); u16(item.method); u16(0); u16(0); u32(crc)
                u32(if (item.central64) 0xffffffff else item.packed.size.toLong()); u32(if (item.central64) 0xffffffff else item.bytes.size.toLong())
                u16(name.size); u16(centralExtra.size); u16(0); u16(0); u16(0); u32(item.mode.toLong() shl 16)
                u32(if (item.central64) 0xffffffff else offset.toLong()); write(name); write(centralExtra)
            }
        }
        val directory = output.size()
        output.write(records.toByteArray())
        if (end64) {
            val end = output.size()
            output.apply {
                u32(0x06064b50); u64(44); u16(45); u16(45); u32(0); u32(0)
                u64(entries.size.toLong()); u64(entries.size.toLong()); u64(records.size().toLong()); u64(directory.toLong())
                u32(0x07064b50); u32(0); u64(end.toLong()); u32(1)
            }
        }
        output.apply {
            u32(0x06054b50); u16(0); u16(0); u16(if (end64) 65535 else entries.size); u16(if (end64) 65535 else entries.size)
            u32(if (end64) 0xffffffff else records.size().toLong()); u32(if (end64) 0xffffffff else directory.toLong())
            u16(comment.size); write(comment)
        }
        return output.toByteArray()
    }
    private fun deflate(bytes: ByteArray): ByteArray {
        val compressor = Deflater(9, true)
        try {
            compressor.setInput(bytes); compressor.finish()
            val output = ByteArrayOutputStream(); val buffer = ByteArray(4096)
            while (!compressor.finished()) { val count = compressor.deflate(buffer); check(count > 0); output.write(buffer, 0, count) }
            return output.toByteArray()
        } finally { compressor.end() }
    }
    private fun ByteArray.put32(at: Int, value: Long) { repeat(4) { this[at + it] = (value ushr (it * 8)).toByte() } }
    private fun ByteArray.findSignature(value: Long) = (0..size - 4).first { at -> (0..3).all { this[at + it] == (value ushr (it * 8)).toByte() } }
    private fun multiManifest(variants: List<Pair<String, String?>>, roles: Int = 0): String {
        val assets = variants.mapIndexed { index, (light, dark) -> """{"asset_id":"${assetId(index + 10)}","name":"asset$index",
            "purpose":"general","color_mode":"original","light":$light,"dark":${dark ?: "null"}}""" }.joinToString(",")
        val mappings = (0 until roles).joinToString(",") { "\"habit.role_$it\":\"${assetId(10)}\"" }
        return """{"format":"dayforge.icon-pack","format_version":1,"pack_id":"${assetId(1)}","revision":1,
            "name":"budget","assets":[$assets],"roles":{$mappings},"placeholder_asset_id":null}"""
    }

    @Test fun platformStoredAndDeflatedArchivesDecodeRealImagesAndPreserveBytes() = runBlocking {
        for (stored in listOf(false, true)) for ((image, media) in listOf(svg to "image/svg+xml", png to "image/png")) {
            val pack = read(zip(entries(manifest(image, media), listOf(image)).reversed(), stored))
            assertEquals("统一风格", pack.manifest.name)
            assertArrayEquals(image, pack.readBlob(hash(image)))
            val description = pack.manifest.assets.single().light
            val bitmap = if (stored) {
                if (media == "image/png") decodePng(pack.readBlob(hash(image)), description) else renderSvg(pack.readBlob(hash(image)), description)
            } else {
                if (media == "image/png") decodePng(image, description) else renderSvg(image, description)
            }
            try { assertEquals(Color.RED, bitmap.getPixel(0, 0)) } finally { bitmap.recycle() }
        }
    }

    @Test fun taskRolesAndSharedOrSeparateVariantsValidateAllRequiredBlobs() = runBlocking {
        val dark = String(svg).replace("#ff0000", "#00ff00").toByteArray()
        for (variant in listOf(svg, dark)) {
            val pack = read(zip(entries(manifest(dark = variant, task = true), listOf(svg, variant))))
            assertEquals("task", pack.manifest.assets.single().purpose)
            assertEquals(setOf("task.custom"), pack.manifest.roles.keys)
            assertArrayEquals(variant, pack.readBlob(hash(variant)))
        }
    }

    @Test fun classicAndZip64HeadersDescriptorsAndCommentsWorkWithinBudgets() = runBlocking {
        for (local64 in listOf(false, true)) for (central64 in listOf(false, true)) for (end64 in listOf(false, true)) {
            for (descriptor in listOf(0, 12, 16, 20, 24)) {
                val source = raw(entries().map { (name, bytes) -> Raw(name, bytes, method = 8, packed = deflate(bytes),
                    local64 = local64, central64 = central64, descriptor = descriptor) }, end64, byteArrayOf(1, 2, 3))
                val pack = read(source)
                assertArrayEquals(svg, pack.readBlob(hash(svg)))
            }
        }
    }

    @Test fun sourceIsOpenedOnceClosedAndFrozenAgainstLaterChanges() = runBlocking {
        val bytes = zip(entries())
        var opened = 0; var closed = 0
        val pack = ValidatedIconPack.read {
            opened++
            object : ByteArrayInputStream(bytes) {
                override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 7))
                override fun available(): Int = error("available must not be used")
                override fun close() { closed++; super.close() }
            }
        }
        bytes.fill(0)
        assertEquals(1, opened); assertEquals(1, closed)
        val first = pack.readBlob(hash(svg)); first.fill(0)
        assertArrayEquals(svg, pack.readBlob(hash(svg)))
        assertThrows(UnsupportedOperationException::class.java) { (pack.manifest.assets as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (pack.manifest.roles as MutableMap).clear() }
        assertEquals(1, pack.manifest.assets.size)
    }

    @Test fun zeroProgressStreamsWorkAndIoOrCancellationNeverBecomeFormatSuccess() = runBlocking {
        var closed = false
        val zero = object : ByteArrayInputStream(zip(entries())) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = 0
            override fun close() { closed = true }
        }
        assertArrayEquals(svg, ValidatedIconPack.read { zero }.readBlob(hash(svg))); assertTrue(closed)
        for (failure in listOf(IOException("synthetic IO"), CancellationException("synthetic cancellation"))) {
            closed = false
            val caught = runCatching { ValidatedIconPack.read { object : InputStream() {
                override fun read(): Int = throw failure
                override fun close() { closed = true }
            } } }.exceptionOrNull()
            assertSame(failure, caught); assertTrue(closed)
        }
        for (count in listOf(-2, 65_537)) {
            val error = runCatching { ValidatedIconPack.read { object : InputStream() {
                override fun read(): Int = -1
                override fun read(b: ByteArray, off: Int, len: Int): Int = count
            } } }.exceptionOrNull()
            assertEquals("PACK_READ_INVALID", (error as IconPackInputException).code)
        }
    }

    @Test fun coroutineCancellationClosesSourceAndCannotPublishPreview() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var closed = false; var published = false
        val work = launch(Dispatchers.Default) {
            ValidatedIconPack.read { object : InputStream() {
                override fun read(): Int = error("bulk path required")
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    started.complete(Unit)
                    Thread.sleep(5)
                    b[off] = 1; return 1
                }
                override fun close() { closed = true }
            } }
            published = true
        }
        started.await(); work.cancelAndJoin()
        assertTrue(closed); assertFalse(published)
    }

    @Test fun compressedInputLimitIncludesEqualityAndConsumesOnlyOneExcessByte() {
        for (size in listOf(ICON_ARCHIVE_LIMIT, ICON_ARCHIVE_LIMIT + 10)) {
            var consumed = 0
            val source = object : InputStream() {
                override fun read(): Int = if (consumed++ < size) 0 else -1
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (consumed == size) return -1
                    val n = minOf(size - consumed, len); consumed += n; return n
                }
            }
            if (size == ICON_ARCHIVE_LIMIT) assertEquals(size, freezeIconArchive(source) {}.size)
            else assertEquals("PACK_ARCHIVE_LIMIT", assertThrows(IconPackInputException::class.java) { freezeIconArchive(source) {} }.code)
            assertEquals(minOf(size, ICON_ARCHIVE_LIMIT + 1), consumed)
        }
    }

    @Test fun missingExtraDuplicateAndUnsafePathsAreRejected() = runBlocking {
        rejected(zip(listOf("manifest.json" to manifest().toByteArray())), "PACK_ENTRIES_MISMATCH")
        rejected(zip(listOf("blobs/${hash(svg)}" to svg)), "PACK_ENTRY_MISSING")
        rejected(zip(entries() + ("blobs/${"0".repeat(64)}" to svg)), "PACK_ENTRIES_MISMATCH")
        rejected(raw((entries() + entries().first()).map { Raw(it.first, it.second) }), "PACK_ZIP_DUPLICATE")
        for (name in listOf("../manifest.json", "/manifest.json", "C:/manifest.json", "blobs/../manifest.json", "blobs/", "blobs\\a", "MANIFEST.JSON", "manifest.json\u0000")) {
            rejected(raw(listOf(Raw(name, byteArrayOf(1)))), "PACK_ZIP_PATH")
        }
    }

    @Test fun linksSpecialFilesEncryptionAndUnknownCompressionAreRejected() = runBlocking {
        for (mode in listOf(0xa1ff, 0x41ff, 0x21ff, 0x11ff))
            rejected(raw(entries().map { Raw(it.first, it.second, mode = mode) }), "PACK_ZIP_LINK")
        for (flag in listOf(1, 64, 0x2000, 0x4000))
            rejected(raw(entries().map { Raw(it.first, it.second, flags = flag) }), "PACK_ZIP_UNSUPPORTED")
        rejected(raw(entries().map { Raw(it.first, it.second, method = 9) }), "PACK_ZIP_UNSUPPORTED")
    }

    @Test fun centralAndLocalHeaderDisagreementOverlapOrTrailingDataAreRejected() = runBlocking {
        val source = raw(entries().map { Raw(it.first, it.second) })
        val central = source.findSignature(0x02014b50)
        for (at in listOf(6, 8, 14, 18, 22, 30, central + 42)) {
            val corrupt = source.copyOf(); corrupt[at] = (corrupt[at].toInt() xor 1).toByte()
            rejected(corrupt)
        }
        for (length in listOf(0, 1, 21, source.size - 1, central + 10)) rejected(source.copyOf(length))
        rejected(byteArrayOf(0) + source); rejected(source + byteArrayOf(0))
        val end = source.size - 22
        val split = source.copyOf(); split[end + 4] = 1; rejected(split)
        val duplicateOffset = source.copyOf()
        val second = central + 46 + "manifest.json".length
        duplicateOffset.put32(second + 42, 0); rejected(duplicateOffset)
    }

    @Test fun forgedCrcAndIncompleteOrTrailingDeflateStreamsAreRejected() = runBlocking {
        val stored = raw(entries().map { Raw(it.first, it.second) })
        stored[30 + "manifest.json".length] = 0
        rejected(stored, "PACK_ZIP_CRC")
        val text = manifest().toByteArray(); val compressed = deflate(text)
        for (bad in listOf(compressed.copyOf(compressed.size - 1), compressed + byteArrayOf(0), compressed + compressed)) {
            rejected(raw(listOf(Raw("manifest.json", text, method = 8, packed = bad), Raw("blobs/${hash(svg)}", svg))), "PACK_ZIP_DEFLATE")
        }
    }

    @Test fun forgedExpandedSizeCannotAllocateOrInflatePastEntryBudget() = runBlocking {
        val bomb = raw(listOf(Raw("manifest.json", byteArrayOf(1), method = 8, packed = deflate(ByteArray(100_000)))))
        rejected(bomb, "PACK_ENTRY_LIMIT")
        rejected(raw(listOf(Raw("manifest.json", ByteArray(ICON_MANIFEST_LIMIT + 1)))), "PACK_ENTRY_LIMIT")
        val source = raw(entries().map { Raw(it.first, it.second) })
        source.put32(source.findSignature(0x02014b50) + 24, 0xffffffff)
        rejected(source)
    }

    @Test fun duplicateDecodedJsonKeysUtf8AndMalformedOrWrongVersionManifestsFail() = runBlocking {
        for (bad in listOf(manifest().replace("\"revision\":1", "\"revision\":1,\"revis\\u0069on\":2"),
            manifest().replace("\"width\":1", "\"width\":1,\"width\":1")))
            rejected(zip(entries(bad)), "PACK_MANIFEST_DUPLICATE")
        rejected(zip(entries().map { if (it.first == "manifest.json") it.first to byteArrayOf(0xc3.toByte(), 0x28) else it }), "PACK_MANIFEST_UTF8")
        for (bad in listOf(manifest().replace("\"format_version\":1", "\"format_version\":2"), manifest() + "{}",
            manifest().replace("\"revision\":1", "\"revision\":\"1\""), manifest().replace("统一风格", "\\ud800"),
            manifest().replace("\"assets\":", "\"unknown\":null,\"assets\":"))) rejected(zip(entries(bad)))
    }

    @Test fun manifestByteAndDepthLimitsAreInclusiveBeforeModelValidation() {
        val text = manifest()
        val exact = text.toByteArray() + ByteArray(ICON_MANIFEST_LIMIT - text.toByteArray().size) { 32 }
        assertEquals(assetId(1), decodeIconManifest(exact) {}.packId)
        assertEquals("PACK_MANIFEST_LIMIT", assertThrows(IconPackInputException::class.java) { decodeIconManifest(exact + 32) {} }.code)
        for (depth in listOf(16, 17)) {
            val deep = ("[".repeat(depth) + "0" + "]".repeat(depth)).toByteArray()
            val error = assertThrows(IconPackInputException::class.java) { decodeIconManifest(deep) {} }
            assertEquals(if (depth == 17) "PACK_MANIFEST_DEPTH" else "PACK_MANIFEST_JSON", error.code)
        }
    }

    @Test fun hashLengthImageDimensionsAndSafetyAreVerifiedBeforeReturning() = runBlocking {
        val wrongHash = manifest().replace(hash(svg), "0".repeat(64))
        val error = runCatching { read(zip(listOf("manifest.json" to wrongHash.toByteArray(), "blobs/${"0".repeat(64)}" to svg))) }.exceptionOrNull()
        assertNotNull(error)
        for (bad in listOf(manifest().replace("\"width\":1", "\"width\":2"), manifest().replace("\"byte_length\":${svg.size}", "\"byte_length\":${svg.size - 1}")))
            assertTrue(runCatching { read(zip(entries(bad))) }.isFailure)
        val unsafe = """<svg width="1" height="1"><script/></svg>""".toByteArray()
        assertTrue(runCatching { read(zip(entries(manifest(unsafe), listOf(unsafe)))) }.isFailure)
        val corruptPng = png.copyOf(png.size - 1)
        assertTrue(runCatching { read(zip(entries(manifest(corruptPng, "image/png"), listOf(corruptPng)))) }.isFailure)
    }

    @Test fun consistentUnicodeNameExtrasAreAcceptedButAlternativePathsAndLinksFail() = runBlocking {
        fun unicode(name: String, replacement: String = name): ByteArray = ByteArrayOutputStream().apply {
            val data = replacement.toByteArray()
            u16(0x7075); u16(5 + data.size); write(1); u32(CRC32().apply { update(name.toByteArray()) }.value); write(data)
        }.toByteArray()
        val valid = raw(entries().map { Raw(it.first, it.second, extra = unicode(it.first)) })
        assertArrayEquals(svg, read(valid).readBlob(hash(svg)))
        rejected(raw(entries().map { Raw(it.first, it.second, extra = unicode(it.first, "../manifest.json")) }), "PACK_ZIP_PATH")
        val link = ByteArrayOutputStream().apply { u16(0x000d); u16(13); write(ByteArray(13)) }.toByteArray()
        rejected(raw(entries().map { Raw(it.first, it.second, extra = link) }), "PACK_ZIP_LINK")
        val incomplete = byteArrayOf(1, 2, 3)
        rejected(raw(entries().map { Raw(it.first, it.second, extra = incomplete) }))
    }

    @Test fun invalidZip64DiskOffsetsAndDescriptorsCannotAliasOtherRecords() = runBlocking {
        val source = raw(entries().map { Raw(it.first, it.second, local64 = true, central64 = true, descriptor = 24) }, end64 = true)
        val end = source.findSignature(0x06064b50)
        val descriptor = source.findSignature(0x08074b50)
        val central = source.findSignature(0x02014b50)
        for ((at, value) in listOf(end + 16 to 1L, end + 24 to 257L, end + 48 to 0L, descriptor + 8 to 1L,
            central + 46 + "manifest.json".length + 4 + 4 to 0xffffffffL)) {
            val bad = source.copyOf(); bad.put32(at, value); rejected(bad)
        }
        val directory = raw(entries().map { Raw(it.first, it.second) })
        directory.put32(directory.findSignature(0x02014b50) + 38, 0x10)
        rejected(directory, "PACK_ZIP_LINK")
        val unknownVersion = source.copyOf(); unknownVersion[end + 14] = 99
        rejected(unknownVersion, "PACK_ZIP_UNSUPPORTED")
    }

    @Test fun manifestAndAssetRoleAndZipEntryLimitsAcceptEquality() = runBlocking {
        val padding = manifest().let { it + " ".repeat(ICON_MANIFEST_LIMIT - it.toByteArray().size) }
        assertEquals(assetId(1), read(zip(entries(padding))).manifest.packId)
        val shared = multiManifest(List(128) { blob(svg) to null }, roles = 256)
        assertEquals(128, read(zip(entries(shared))).manifest.assets.size)
        assertEquals(256, read(zip(entries(shared))).manifest.roles.size)
        rejected(zip(entries(multiManifest(List(129) { blob(svg) to null }))), "PACK_MANIFEST_INVALID")
        rejected(zip(entries(multiManifest(listOf(blob(svg) to null), roles = 257))), "PACK_MANIFEST_INVALID")
        val images = List(256) { index -> svg + "<!--$index-->".toByteArray() }
        val full = multiManifest(images.chunked(2).map { blob(it[0]) to blob(it[1]) })
        val maximum = read(zip(entries(full, images)))
        assertEquals(128, maximum.manifest.assets.size)
        assertArrayEquals(images.last(), maximum.readBlob(hash(images.last())))
        rejected(raw((entries(full, images) + ("blobs/${"0".repeat(64)}" to svg)).map { Raw(it.first, it.second) }))
    }

    @Test fun fullSixtyFourMebibyteExpansionIsSequentialAndOneExtraByteIsRejected() = runBlocking {
        fun image(index: Int): ByteArray {
            val prefix = svg + "<!--$index-->".toByteArray()
            return prefix + ByteArray(524_288 - prefix.size) { 32 }
        }
        val descriptions = List(128) { blob(image(it)) }
        val text = multiManifest(descriptions.chunked(2).map { it[0] to it[1] })
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(text.toByteArray()); zip.closeEntry()
            repeat(128) { index ->
                val data = image(index)
                zip.putNextEntry(ZipEntry("blobs/${hash(data)}")); zip.write(data); zip.closeEntry()
            }
        }
        val pack = read(output.toByteArray())
        assertEquals(64, pack.manifest.assets.size)
        assertArrayEquals(image(127), pack.readBlob(hash(image(127))))
        val excessive = multiManifest(descriptions.chunked(2).map { it[0] to it[1] } + (blob(byteArrayOf(0), "image/png") to null))
        assertEquals("PACK_MANIFEST_INVALID", assertThrows(IconPackInputException::class.java) {
            decodeIconManifest(excessive.toByteArray()) {}
        }.code)
    }

    @Test fun closingFailureAndInvalidSingleByteFallbackNeverReturnAPack() = runBlocking {
        val failure = IOException("synthetic close")
        val caught = runCatching { ValidatedIconPack.read { object : ByteArrayInputStream(zip(entries())) {
            override fun close() { throw failure }
        } } }.exceptionOrNull()
        assertSame(failure, caught)
        for (value in listOf(-2, 256)) {
            val error = runCatching { ValidatedIconPack.read { object : InputStream() {
                override fun read(b: ByteArray, off: Int, len: Int): Int = 0
                override fun read(): Int = value
            } } }.exceptionOrNull()
            assertEquals("PACK_READ_INVALID", (error as IconPackInputException).code)
        }
    }
}
