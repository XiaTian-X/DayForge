package com.dayforge.data.appearance

import com.dayforge.domain.model.ConfigBundle
import com.dayforge.domain.model.IconBlob
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream

/**
 * A complete offline template, never a backup or an account installation. Callers provide an
 * authorized snapshot and bytes; no URI, URL, database, identity allocation or selection is used.
 * A missing fixed asset fails instead of silently turning into a role/placeholder.
 */
internal object ConfigBundleOutput {
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun create(manifest: ConfigBundle, readBlob: suspend (IconBlob) -> ByteArray): ValidatedConfigBundle =
        withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            val checkpoint = { context.ensureActive() }
            checkpoint()
            // Serializing into a bounded sink prevents an invalid/mutated collection from allocating
            // an unlimited JSON string. Decode again BEFORE any source callback to validate nested
            // mutable lists/maps and detach the entire snapshot from the caller.
            val encoded = ConfigOutputBuffer(CONFIG_MANIFEST_LIMIT, "CONFIG_MANIFEST_LIMIT", checkpoint)
            Json.encodeToStream(ConfigBundle.serializer(), manifest, encoded)
            val manifestBytes = encoded.bytes()
            val snapshot = decodeConfigManifest(manifestBytes, checkpoint)
            val blobs = snapshot.iconPack?.assets.orEmpty().flatMap { listOfNotNull(it.light, it.dark) }
                .associateBy { it.sha256 }.toSortedMap()
            val output = ConfigOutputBuffer(ICON_ARCHIVE_LIMIT, "CONFIG_ARCHIVE_LIMIT", checkpoint)
            ZipOutputStream(output).use { zip ->
                fun entry(name: String, bytes: ByteArray) {
                    checkpoint()
                    zip.putNextEntry(ZipEntry(name).apply { time = 0L })
                    var offset = 0
                    while (offset < bytes.size) {
                        checkpoint()
                        val count = minOf(65_536, bytes.size - offset)
                        zip.write(bytes, offset, count)
                        offset += count
                    }
                    zip.closeEntry()
                }
                entry("manifest.json", manifestBytes)
                for ((hash, expected) in blobs) {
                    checkpoint()
                    // Suspend callbacks may replace the session or cancel the caller. Ownership
                    // belongs to the caller; this layer still cannot publish a cancelled archive.
                    val content = readBlob(expected)
                    checkpoint()
                    if (content.size != expected.byteLength) throw ConfigBundleInputException("CONFIG_BLOB_LENGTH")
                    entry("blobs/$hash", content.copyOf())
                }
            }
            checkpoint()
            // Use the real import boundary, including CRC, exact dependency set, hashes and native
            // PNG/SVG pixels. Metadata or a successful ZipOutputStream alone is never success.
            ValidatedConfigBundle.parse(output.bytes(), checkpoint)
        }
}

/** Exact byte cap including ZIP final records; chunked writers cannot overshoot before checking. */
internal class ConfigOutputBuffer(private val limit: Int, private val code: String,
    private val checkpoint: () -> Unit = {}) : OutputStream() {
    private val output = ByteArrayOutputStream()
    init { require(limit >= 0) }

    override fun write(value: Int) {
        checkpoint()
        if (output.size() == limit) throw ConfigBundleInputException(code)
        output.write(value)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        checkpoint()
        if (length > limit - output.size()) throw ConfigBundleInputException(code)
        output.write(bytes, offset, length)
    }

    fun bytes(): ByteArray = output.toByteArray()
}
