package com.dayforge.ui.screens.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import android.content.Context
import com.dayforge.data.local.PreferencesManager
import com.dayforge.domain.service.ThemeManager
import com.dayforge.widget.WidgetRefreshScheduler
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

@RunWith(AndroidJUnit4::class)
class SettingsAppearanceWidgetRefreshTest {
    private val context = mockk<Context>()
    private val preferences = mockk<PreferencesManager>(relaxed = true)
    private val themes = mockk<ThemeManager>()
    private lateinit var workflow: SettingsAppearanceWorkflow

    @Before fun setup() {
        every { themes.lightThemes } returns MutableStateFlow(emptyList())
        every { themes.darkThemes } returns MutableStateFlow(emptyList())
        mockkObject(WidgetRefreshScheduler)
        every { WidgetRefreshScheduler.request(context) } returns mockk()
        workflow = SettingsAppearanceWorkflow(context, preferences, themes, mockk(), mockk(), mockk())
    }

    @After fun teardown() { unmockkObject(WidgetRefreshScheduler) }

    @Test fun each_appearance_change_commits_preferences_before_requesting_refresh() = runTest {
        workflow.changeTheme("dark")
        workflow.changeLightColorTheme("light")
        workflow.changeDarkColorTheme("oled")
        workflow.changeCardColorStyle("personalized")
        coVerifyOrder {
            preferences.setThemeMode("dark")
            WidgetRefreshScheduler.request(context)
            preferences.setLightColorTheme("light")
            WidgetRefreshScheduler.request(context)
            preferences.setDarkColorTheme("oled")
            WidgetRefreshScheduler.request(context)
            preferences.setCardColorStyle("personalized")
            WidgetRefreshScheduler.request(context)
        }
        verify(exactly = 4) { WidgetRefreshScheduler.request(context) }
    }

    @Test fun failed_preference_write_does_not_schedule_misleading_refresh() = runTest {
        coEvery { preferences.setThemeMode(any()) } throws IllegalStateException("write failed")
        try {
            workflow.changeTheme("dark")
            org.junit.Assert.fail("Expected write failure")
        } catch (_: IllegalStateException) { }
        verify(exactly = 0) { WidgetRefreshScheduler.request(any()) }
    }
}
