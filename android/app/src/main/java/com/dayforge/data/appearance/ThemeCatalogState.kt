@file:kotlinx.serialization.UseSerializers(com.dayforge.domain.model.ContractStringSerializer::class)

package com.dayforge.data.appearance

import androidx.datastore.preferences.core.stringPreferencesKey
import com.dayforge.domain.appearance.DeviceThemeSelection
import com.dayforge.domain.appearance.ThemeVersionRef
import com.dayforge.domain.model.ContractIntegerSerializer
import com.dayforge.domain.model.ContractLongSerializer
import com.dayforge.domain.model.isContractUuid
import java.security.MessageDigest
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement

internal val THEME_CATALOG_KEY = stringPreferencesKey("appearance_theme_catalog_v1")
internal const val THEME_CATALOG_CUSTOM_LIMIT = 128
internal class ThemeCatalogException(val code: String) : IllegalArgumentException(code)
private val themeDigestPattern = Regex("[0-9a-f]{64}")

@Serializable
internal enum class ThemeInstallPhase {
    @SerialName("installing") INSTALLING,
    @SerialName("active") ACTIVE,
    @SerialName("deleting") DELETING
}

@Serializable
internal data class ThemeCatalogSlot(
    val ref: ThemeVersionRef,
    val digest: String,
    val operationId: String,
    val phase: ThemeInstallPhase
) {
    init { require(themeDigestPattern.matches(digest) && isContractUuid(operationId)) }
}

@Serializable
internal data class ThemeCatalogState(
    @Serializable(with = ContractIntegerSerializer::class) val formatVersion: Int,
    @Serializable(with = ContractLongSerializer::class) val revision: Long,
    val slots: List<ThemeCatalogSlot>
) {
    init {
        require(formatVersion == 1 && revision > 0)
        require(slots.size <= THEME_CATALOG_CUSTOM_LIMIT + BuiltInTheme.entries.size)
        require(slots.map { it.ref }.toSet().size == slots.size)
        require(slots.map { it.operationId }.toSet().size == slots.size)
        require(Long.MAX_VALUE - revision >= slots.count { it.phase != ThemeInstallPhase.ACTIVE })
        val builtIns = BuiltInTheme.entries.associateBy { it.themeId }
        require(slots.count { it.ref.themeId !in builtIns } <= THEME_CATALOG_CUSTOM_LIMIT)
        for (entry in builtIns.values) {
            val slot = slots.singleOrNull { it.ref.themeId == entry.themeId }
            require(slot != null && slot.ref.revision == entry.revision && slot.phase == ThemeInstallPhase.ACTIVE)
        }
    }

    fun requireSelectable(choice: DeviceThemeSelection) {
        for (ref in listOf(choice.light, choice.dark)) {
            if (slots.none { it.ref == ref && it.phase == ThemeInstallPhase.ACTIVE }) {
                throw ThemeCatalogException("THEME_NOT_AVAILABLE")
            }
        }
        BuiltInTheme.entries.find { it.themeId == choice.light.themeId }?.let {
            if (!it.suitableForLight) throw ThemeCatalogException("THEME_MODE_UNSUPPORTED")
        }
        BuiltInTheme.entries.find { it.themeId == choice.dark.themeId }?.let {
            if (!it.suitableForDark) throw ThemeCatalogException("THEME_MODE_UNSUPPORTED")
        }
    }

    fun verify(theme: ValidatedTheme) {
        val slot = slots.singleOrNull { it.ref == theme.ref() && it.phase == ThemeInstallPhase.ACTIVE }
            ?: throw ThemeCatalogException("THEME_NOT_AVAILABLE")
        if (slot.digest != theme.definitionDigest()) throw ThemeCatalogException("THEME_CONTENT_CHANGED")
    }

    fun next(updated: List<ThemeCatalogSlot>): ThemeCatalogState {
        if (updated == slots) return this
        if (revision == Long.MAX_VALUE) throw ThemeCatalogException("THEME_CATALOG_EXHAUSTED")
        return copy(revision = revision + 1, slots = Collections.unmodifiableList(updated.toList()))
    }

    companion object {
        // DataStore snapshots are repeatedly observed/read without changing the catalog. Retain
        // just one exact raw value, never an identity-only or unbounded cache. The decoded tree is
        // deeply immutable; file bytes are still verified separately at every required boundary.
        private data class Decoded(val raw: String, val state: ThemeCatalogState)
        @Volatile private var lastDecoded: Decoded? = null

        suspend fun decode(raw: String?): ThemeCatalogState? = withContext(Dispatchers.Default) {
            if (raw == null) return@withContext null
            if (raw.length > 65_536) throw ThemeCatalogException("THEME_CATALOG_LIMIT")
            val context = currentCoroutineContext()
            context.ensureActive()
            lastDecoded?.let { if (it.raw == raw) return@withContext it.state }
            try {
                val text = strictAppearanceJson(raw.toByteArray(Charsets.UTF_8), 65_536, { context.ensureActive() }) {
                    throw ThemeCatalogException("THEME_CATALOG_$it")
                }
                val decoded = Json.decodeFromString<ThemeCatalogState>(text)
                val immutable = decoded.copy(slots = Collections.unmodifiableList(decoded.slots.toList()))
                context.ensureActive()
                lastDecoded = Decoded(raw, immutable)
                immutable
            } catch (error: ThemeCatalogException) { throw error }
            catch (_: SerializationException) { throw ThemeCatalogException("THEME_CATALOG_JSON") }
            catch (_: IllegalArgumentException) { throw ThemeCatalogException("THEME_CATALOG_INVALID") }
        }
    }
}

/** Semantic identity: map order, whitespace and a UTF-8 BOM do not change an immutable definition. */
internal fun ValidatedTheme.definitionDigest(): String {
    fun sorted(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { sorted(it.value) })
        is JsonArray -> JsonArray(value.map { sorted(it) })
        else -> value
    }
    val canonical = sorted(Json.encodeToJsonElement(definition)).toString().toByteArray(Charsets.UTF_8)
    return MessageDigest.getInstance("SHA-256").digest(canonical).joinToString("") {
        (it.toInt() and 255).toString(16).padStart(2, '0')
    }
}
