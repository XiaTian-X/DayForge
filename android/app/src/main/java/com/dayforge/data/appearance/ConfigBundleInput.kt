package com.dayforge.data.appearance

import android.util.JsonReader
import android.util.JsonToken
import com.dayforge.domain.model.ConfigBundle
import com.dayforge.domain.model.ConfigSchedule
import com.dayforge.domain.model.IconBlob
import com.dayforge.domain.model.ThemePalette
import java.io.InputStream
import java.io.StringReader
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal const val CONFIG_MANIFEST_LIMIT = 8_388_608
internal class ConfigBundleInputException(val code: String) : IllegalArgumentException(code)

/**
 * One fully validated, frozen offline template. Local keys/source IDs are not account permissions.
 * No installation, identity allocation, database write, selection change or source reopen occurs.
 */
internal class ValidatedConfigBundle private constructor(
    val manifest: ConfigBundle,
    private val source: ByteArray,
    private val zip: IconPackZip,
    private val blobs: Map<String, IconBlob>
) {
    fun exportBytes(): ByteArray = source.copyOf()

    suspend fun readBlob(sha256: String): ByteArray = withContext(Dispatchers.IO) {
        val blob = blobs[sha256] ?: throw ConfigBundleInputException("CONFIG_ENTRY_MISSING")
        val context = currentCoroutineContext()
        configArchive { zip.read("blobs/$sha256", blob.byteLength) { context.ensureActive() } }
    }

    companion object {
        /** Opens exactly once and closes on all exits. Provider owns blocking-I/O deadlines. */
        suspend fun read(openSource: () -> InputStream): ValidatedConfigBundle = withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            val checkpoint = { context.ensureActive() }
            checkpoint()
            val bytes = configArchive { openSource().use { freezeIconArchive(it, checkpoint) } }
            val zip = configArchive { IconPackZip(bytes, checkpoint, CONFIG_MANIFEST_LIMIT) }
            val rawManifest = configArchive { zip.read("manifest.json", CONFIG_MANIFEST_LIMIT, checkpoint) }
            val manifest = freezeConfig(decodeConfigManifest(rawManifest, checkpoint))
            val blobs = manifest.iconPack?.assets.orEmpty().flatMap { listOfNotNull(it.light, it.dark) }
                .associateBy { it.sha256 }
            if (zip.names != setOf("manifest.json") + blobs.keys.map { "blobs/$it" }) {
                throw ConfigBundleInputException("CONFIG_ENTRIES_MISMATCH")
            }
            for ((hash, expected) in blobs) {
                checkpoint()
                val content = configArchive { zip.read("blobs/$hash", expected.byteLength, checkpoint) }
                // Metadata/CRC alone cannot establish valid image bytes. Recycle before the next blob.
                val bitmap = if (expected.mediaType == "image/png") decodePng(content, expected) else renderSvg(content, expected)
                try { checkpoint() } finally { bitmap.recycle() }
            }
            checkpoint()
            ValidatedConfigBundle(manifest, bytes, zip, blobs)
        }
    }
}

private inline fun <T> configArchive(block: () -> T): T = try { block() }
catch (error: IconPackInputException) {
    throw ConfigBundleInputException("CONFIG_" + error.code.removePrefix("PACK_"))
}

private fun decodeConfigManifest(bytes: ByteArray, checkpoint: () -> Unit): ConfigBundle {
    try {
        val text = strictAppearanceJson(bytes, CONFIG_MANIFEST_LIMIT, checkpoint) {
            throw ConfigBundleInputException("CONFIG_MANIFEST_$it")
        }
        // Do not build an unbounded intermediate JsonElement tree just to inspect two root fields.
        // The strict preflight already validated every token/key/depth; skip other values as a stream.
        val supported = JsonReader(StringReader(text)).use { reader ->
            if (reader.peek() != JsonToken.BEGIN_OBJECT) throw ConfigBundleInputException("CONFIG_MANIFEST_INVALID")
            reader.beginObject()
            var format = false
            var version = false
            while (reader.hasNext()) {
                checkpoint()
                when (reader.nextName()) {
                    "format" -> if (reader.peek() == JsonToken.STRING) format = reader.nextString() == "dayforge.config"
                        else reader.skipValue()
                    "format_version" -> if (reader.peek() == JsonToken.NUMBER) version = reader.nextString() == "2"
                        else reader.skipValue()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            format && version
        }
        if (!supported) throw ConfigBundleInputException("CONFIG_VERSION")
        val result = Json.decodeFromString<ConfigBundle>(text)
        checkpoint()
        return result
    } catch (error: ConfigBundleInputException) { throw error }
    catch (_: SerializationException) { throw ConfigBundleInputException("CONFIG_MANIFEST_JSON") }
    catch (_: IllegalArgumentException) { throw ConfigBundleInputException("CONFIG_MANIFEST_INVALID") }
}

private fun freezeConfig(value: ConfigBundle): ConfigBundle {
    fun <T> list(items: List<T>): List<T> = Collections.unmodifiableList(items.toList())
    fun palette(colors: ThemePalette) = colors.copy(
        material = Collections.unmodifiableMap(colors.material.toMap()),
        status = Collections.unmodifiableMap(colors.status.toMap()),
        chart = Collections.unmodifiableMap(colors.chart.toMap()))
    return value.copy(
        nodes = list(value.nodes.map { node ->
            val activity = node.activity
            val schedule = activity?.schedule
            if (activity != null && schedule is ConfigSchedule.Weekly) {
                node.copy(activity = activity.copy(schedule = schedule.copy(weekdays = list(schedule.weekdays))))
            } else node
        }),
        metrics = list(value.metrics), links = list(value.links), unresolvedRoles = list(value.unresolvedRoles),
        iconPack = value.iconPack?.let { it.copy(assets = list(it.assets), roles = Collections.unmodifiableMap(it.roles.toMap())) },
        themes = list(value.themes.map { it.copy(light = palette(it.light), dark = palette(it.dark)) })
    )
}
