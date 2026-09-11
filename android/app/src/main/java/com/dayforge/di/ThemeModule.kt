package com.dayforge.di

import android.content.Context
import com.dayforge.domain.repository.CustomThemeRepository
import com.dayforge.domain.service.ThemeExportService
import com.dayforge.domain.service.ThemeImportService
import com.dayforge.domain.service.ThemeManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for providing theme-related dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object ThemeModule {

    @Provides
    @Singleton
    fun provideCustomThemeRepository(
        @ApplicationContext context: Context
    ): CustomThemeRepository {
        return CustomThemeRepository(context)
    }

    @Provides
    @Singleton
    fun provideThemeManager(
        @ApplicationContext context: Context,
        customThemeRepository: CustomThemeRepository
    ): ThemeManager {
        return ThemeManager(context, customThemeRepository)
    }

    @Provides
    @Singleton
    fun provideThemeImportService(
        customThemeRepository: CustomThemeRepository,
        themeManager: ThemeManager,
        @ApplicationContext context: Context
    ): ThemeImportService {
        return ThemeImportService(customThemeRepository, themeManager, context)
    }

    @Provides
    @Singleton
    fun provideThemeExportService(
        themeManager: ThemeManager,
        customThemeRepository: CustomThemeRepository,
        @ApplicationContext context: Context
    ): ThemeExportService {
        return ThemeExportService(themeManager, customThemeRepository, context)
    }
}