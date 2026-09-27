package com.dayforge.widget.base

import com.dayforge.domain.appearance.DeviceThemeMode
import com.dayforge.domain.appearance.LoadedDeviceTheme

/** Cold-loaded device selection; forced modes stay fixed even if the launcher changes night mode. */
internal fun LoadedDeviceTheme.toGlanceColors() = when (saved.selection.mode) {
    DeviceThemeMode.SYSTEM -> glanceThemeColors(light, dark)
    DeviceThemeMode.LIGHT -> light.toGlanceColors()
    DeviceThemeMode.DARK -> dark.toGlanceColors()
}
