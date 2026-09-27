package com.dayforge.domain.appearance

import com.dayforge.domain.model.ContractIntegerSerializer
import com.dayforge.domain.model.ContractStringSerializer
import com.dayforge.domain.model.isContractUuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class ThemeVersionRef(
    @Serializable(with = ContractStringSerializer::class) val themeId: String,
    @Serializable(with = ContractIntegerSerializer::class) val revision: Int
) {
    init { require(isContractUuid(themeId) && revision > 0) }
}

@Serializable
internal enum class DeviceThemeMode {
    @SerialName("system") SYSTEM,
    @SerialName("light") LIGHT,
    @SerialName("dark") DARK
}

@Serializable
internal enum class DeviceCardStyle {
    @SerialName("follow_theme") FOLLOW_THEME,
    @SerialName("personalized") PERSONALIZED
}

/** Device preference only; independent light/dark versions, not account-synced object appearance. */
@Serializable
internal data class DeviceThemeSelection(
    val light: ThemeVersionRef,
    val dark: ThemeVersionRef,
    val mode: DeviceThemeMode,
    val cardStyle: DeviceCardStyle
)

internal data class SavedThemeSelection(val revision: Long, val selection: DeviceThemeSelection) {
    init { require(revision > 0) }
}

/** A single preference snapshot plus both revalidated immutable palettes, usable without an Activity. */
internal class LoadedDeviceTheme(
    val saved: SavedThemeSelection,
    val light: ResolvedTheme,
    val dark: ResolvedTheme
) {
    init {
        require(!light.dark && dark.dark)
        require(ThemeVersionRef(light.themeId, light.revision) == saved.selection.light)
        require(ThemeVersionRef(dark.themeId, dark.revision) == saved.selection.dark)
    }

    fun resolve(systemDark: Boolean): ResolvedTheme = when (saved.selection.mode) {
        DeviceThemeMode.SYSTEM -> if (systemDark) dark else light
        DeviceThemeMode.LIGHT -> light
        DeviceThemeMode.DARK -> dark
    }
}
