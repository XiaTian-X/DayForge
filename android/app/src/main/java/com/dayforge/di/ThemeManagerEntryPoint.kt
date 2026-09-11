package com.dayforge.di

import com.dayforge.domain.service.ThemeManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt EntryPoint for accessing ThemeManager from non-Hilt components (like Glance widgets).
 *
 * Usage:
 * ```kotlin
 * val themeManager = ThemeManagerEntryPoint.from(context).themeManager()
 * ```
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ThemeManagerEntryPoint {
    fun themeManager(): ThemeManager

    companion object {
        /**
         * Gets ThemeManager from the Hilt component.
         *
         * @param context Application context
         * @return ThemeManagerEntryPoint instance
         */
        fun from(context: android.content.Context): ThemeManagerEntryPoint {
            return dagger.hilt.android.EntryPointAccessors.fromApplication(
                context.applicationContext,
                ThemeManagerEntryPoint::class.java
            )
        }
    }
}