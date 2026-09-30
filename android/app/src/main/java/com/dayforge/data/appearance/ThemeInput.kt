package com.dayforge.data.appearance

import com.dayforge.domain.model.ThemeDefinition
import com.dayforge.domain.model.ThemePalette
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal const val THEME_FILE_LIMIT = 1_048_576
internal class ThemeInputException(val code: String) : IllegalArgumentException(code)

/** One validated, immutable preview; import confirmation never reopens the provider URI. */
internal class ValidatedTheme private constructor(val definition: ThemeDefinition, private val source: ByteArray) {
    fun exportBytes(): ByteArray = source.copyOf()
    /** Exact bytes only, not file identity/hash/mtime; never exposes the frozen backing array. */
    internal fun matchesSource(bytes: ByteArray): Boolean = source.contentEquals(bytes)

    companion object {
        /** Opens once on IO and closes on every exit; provider owns blocking-read deadlines. */
        suspend fun read(openSource: () -> InputStream): ValidatedTheme = withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            val checkpoint = { context.ensureActive() }
            checkpoint()
            val bytes = openSource().use { readThemeBytes(it, checkpoint) }
            parse(bytes, checkpoint)
        }

        internal fun parse(bytes: ByteArray, checkpoint: () -> Unit = {}): ValidatedTheme {
            if (bytes.size > THEME_FILE_LIMIT) throw ThemeInputException("THEME_LIMIT")
            val frozen = bytes.copyOf()
            try {
                val text = strictAppearanceJson(frozen, THEME_FILE_LIMIT, checkpoint) { throw ThemeInputException("THEME_$it") }
                val objectValue = Json.parseToJsonElement(text).jsonObject
                if (objectValue["format"]?.jsonPrimitive?.content != "dayforge.theme" ||
                    objectValue["format_version"]?.toString() != "1") throw ThemeInputException("THEME_VERSION")
                val theme = Json.decodeFromString<ThemeDefinition>(text)
                fun freeze(palette: ThemePalette) = palette.copy(
                    material = Collections.unmodifiableMap(palette.material.toMap()),
                    status = Collections.unmodifiableMap(palette.status.toMap()),
                    chart = Collections.unmodifiableMap(palette.chart.toMap()))
                checkpoint()
                return ValidatedTheme(theme.copy(light = freeze(theme.light), dark = freeze(theme.dark)), frozen)
            } catch (error: ThemeInputException) { throw error }
            catch (_: SerializationException) { throw ThemeInputException("THEME_JSON") }
            catch (_: IllegalArgumentException) { throw ThemeInputException("THEME_INVALID") }
        }
    }
}

/** Exact EOF and cap, including streams which return short/zero reads; never uses available(). */
internal fun readThemeBytes(source: InputStream, checkpoint: () -> Unit): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        checkpoint()
        val requested = minOf(buffer.size, THEME_FILE_LIMIT + 1 - output.size())
        var count = source.read(buffer, 0, requested)
        if (count !in -1..requested) throw ThemeInputException("THEME_READ_INVALID")
        if (count == 0) {
            val single = source.read()
            if (single !in -1..255) throw ThemeInputException("THEME_READ_INVALID")
            if (single < 0) count = -1 else { buffer[0] = single.toByte(); count = 1 }
        }
        if (count == -1) return output.toByteArray()
        if (output.size() + count > THEME_FILE_LIMIT) throw ThemeInputException("THEME_LIMIT")
        output.write(buffer, 0, count)
    }
}
