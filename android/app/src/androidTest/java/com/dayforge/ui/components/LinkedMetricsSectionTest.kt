package com.dayforge.ui.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.dayforge.R
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class LinkedMetricsSectionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun collapsed_section_expands_formats_values_navigates_and_collapses() {
        var selected: Long? = null
        compose.setContent { MaterialTheme {
            LinkedMetricsSection(listOf(
                LinkedMetricInfo("Weight", 7, 68.56, "kg", 1),
                LinkedMetricInfo("Steps", 9, null, "steps", 0)
            ), onMetricClick = { selected = it })
        } }
        compose.onNodeWithText("Weight").assertDoesNotExist()
        compose.onNodeWithContentDescription("Expand").performClick()
        compose.onNodeWithText("68.6 kg").assertIsDisplayed()
        compose.onNodeWithText("-- steps").assertIsDisplayed()
        compose.onNodeWithText("Weight").performClick()
        compose.runOnIdle { assertEquals(7L, selected) }
        compose.onNodeWithContentDescription("Collapse").performClick()
        compose.onNodeWithText("Weight").assertDoesNotExist()
    }
}
