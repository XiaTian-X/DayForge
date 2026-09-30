package com.dayforge.di

import com.dayforge.domain.service.DeviceThemeController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt EntryPoint for accessing DeviceThemeController from non-Hilt components (like Glance widgets).
 *
 * Usage:
 * ```kotlin
 * val themeController = DeviceThemeControllerEntryPoint.from(context).themeController()
 * ```
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DeviceThemeControllerEntryPoint {
    fun themeController(): DeviceThemeController

    companion object {
        /**
         * Gets DeviceThemeController from the Hilt component.
         *
         * @param context Application context
         * @return DeviceThemeControllerEntryPoint instance
         */
        fun from(context: android.content.Context): DeviceThemeControllerEntryPoint {
            return dagger.hilt.android.EntryPointAccessors.fromApplication(
                context.applicationContext,
                DeviceThemeControllerEntryPoint::class.java
            )
        }
    }
}
