package com.dayforge.data.appearance

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.dayforge.domain.appearance.ThemeVersionRef
import java.io.IOException
import java.security.MessageDigest
import java.util.Collections
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class ThemeRecoveryFile(
    val name: String, val version: ThemeVersionRef, val operationId: String?, val temporary: Boolean,
    val byteLength: Int, val contentHash: String
)

/** Device-local appearance intent, not an account authorization or a theme activation command. */
internal data class DeviceThemeRecoverySnapshot(
    val preferences: DeviceThemeRecoveryPreferences, val files: List<ThemeRecoveryFile>
) {
    val activationAuthorized: Boolean get() = false
}

internal class ThemeRecoveryFileAccess internal constructor(private val readFile: suspend (String) -> ByteArray) {
    @Volatile private var open = true
    internal fun close() { open = false }
    suspend fun read(name: String): ByteArray {
        check(open) { "THEME_RECOVERY_ACCESS_CLOSED" }
        val bytes = readFile(name)
        check(open) { "THEME_RECOVERY_ACCESS_CLOSED" }
        return bytes
    }
}

/**
 * Complete theme closure, including unselected installed versions and interrupted journals.
 * Uses the existing DataStore, with a whitelist, and the existing process file lease. No catalog
 * mutex is acquired inside that lease; concurrent preference edits invalidate the result instead.
 * Never applies themes, initializes/replays a catalog or cleans partial writes. The enclosing
 * replica archive must additionally bind/recheck its account authentication and business proof.
 */
internal class DeviceThemeRecoveryReader(
    preferences: DataStore<Preferences>, private val files: ThemeFileRepository,
    private val builtIns: BuiltInThemes
) {
    private val selection = DeviceThemeStore(preferences, files)
    private data class Owned(val version: ThemeVersionRef, val slot: ThemeCatalogSlot?, val temporary: Boolean)

    suspend fun <T> inspect(block: suspend (DeviceThemeRecoverySnapshot, ThemeRecoveryFileAccess) -> T): T =
        files.inspectRecovery { directory ->
            val before = selection.recoveryPreferences()
            val owned = linkedMapOf<String, Owned>()
            val inventory = directory?.inventory().orEmpty()
            fun add(ref: ThemeVersionRef, slot: ThemeCatalogSlot?) {
                val name = "${ref.themeId}-${ref.revision}.json"
                owned[name] = Owned(ref, slot, false)
                check(slot != null && slot.phase != ThemeInstallPhase.ACTIVE || name in inventory) {
                    "THEME_RECOVERY_ACTIVE_FILE_MISSING"
                }
                slot?.let { owned[".theme-${it.operationId}.part"] = Owned(ref, slot, true) }
            }
            if (before.catalog != null) for (slot in before.catalog.slots) add(slot.ref, slot)
            else before.selected?.selection?.let { choice ->
                for (ref in setOf(choice.light, choice.dark)) add(ref, null)
            }
            check(inventory.all { it in owned }) { "THEME_RECOVERY_UNKNOWN_FILE" }
            val fixed = if (owned.values.any { entry -> BuiltInTheme.entries.any { it.themeId == entry.version.themeId } })
                builtIns.readAll().associateBy { it.ref() } else emptyMap()
            for (entry in owned.values) BuiltInTheme.entries.find { it.themeId == entry.version.themeId }?.let {
                check(entry.version.revision == it.revision && entry.version in fixed) { "THEME_RECOVERY_BUILTIN_CHANGED" }
            }
            // Catalog digests cannot redefine a built-in, including an interrupted/unselected entry.
            for (slot in before.catalog?.slots.orEmpty()) fixed[slot.ref]?.let {
                check(slot.digest == it.definitionDigest()) { "THEME_RECOVERY_BUILTIN_CHANGED" }
            }
            suspend fun readOwned(name: String): ByteArray {
                val coroutine = currentCoroutineContext()
                coroutine.ensureActive()
                val entry = requireNotNull(owned[name]) { "THEME_RECOVERY_FILE_NOT_OWNED" }
                check(name in inventory) { "THEME_RECOVERY_FILE_NOT_PRESENT" }
                val source = directory ?: throw IOException("THEME_DIRECTORY_MISSING")
                val bytes = if (entry.temporary) source.temporary(requireNotNull(entry.slot).operationId)
                    else source.version(entry.version)
                if (!entry.temporary) {
                    val theme = ValidatedTheme.parse(bytes) { coroutine.ensureActive() }
                    check(theme.ref() == entry.version) { "THEME_RECOVERY_FILE_IDENTITY" }
                    entry.slot?.let { check(theme.definitionDigest() == it.digest) { "THEME_RECOVERY_CONTENT_CHANGED" } }
                    fixed[entry.version]?.let {
                        check(theme.definition == it.definition) { "THEME_RECOVERY_BUILTIN_CHANGED" }
                    }
                }
                coroutine.ensureActive()
                return bytes
            }
            val witnesses = inventory.sorted().map { name ->
                val entry = owned.getValue(name)
                val bytes = readOwned(name)
                ThemeRecoveryFile(name, entry.version, entry.slot?.operationId, entry.temporary, bytes.size, hash(bytes))
            }
            suspend fun verifyPreferencesAndNames() {
                check(selection.recoveryPreferences() == before) { "THEME_RECOVERY_PREFERENCES_CHANGED" }
                check(directory?.inventory().orEmpty() == inventory) { "THEME_RECOVERY_INVENTORY_CHANGED" }
            }
            verifyPreferencesAndNames()
            val byName = witnesses.associateBy { it.name }
            val access = ThemeRecoveryFileAccess { name ->
                val witness = requireNotNull(byName[name]) { "THEME_RECOVERY_FILE_NOT_CAPTURED" }
                readOwned(name).also { bytes ->
                    check(bytes.size == witness.byteLength && hash(bytes) == witness.contentHash) {
                        "THEME_RECOVERY_CONTENT_CHANGED"
                    }
                }
            }
            try {
                val value = block(DeviceThemeRecoverySnapshot(before, Collections.unmodifiableList(witnesses)), access)
                verifyPreferencesAndNames()
                for (witness in witnesses) access.read(witness.name)
                verifyPreferencesAndNames()
                value
            } finally { access.close() }
        }

    private fun hash(bytes: ByteArray): String = buildString(64) {
        val digits = "0123456789abcdef"
        for (byte in MessageDigest.getInstance("SHA-256").digest(bytes)) {
            val value = byte.toInt() and 255
            append(digits[value ushr 4]); append(digits[value and 15])
        }
    }
}
