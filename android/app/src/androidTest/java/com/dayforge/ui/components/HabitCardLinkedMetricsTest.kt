package com.dayforge.ui.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.R
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class HabitCardLinkedMetricsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val habit = HabitEntity(name = "Walk", habitType = HabitType.CHECK_IN,
        iconResId = 1, colorHex = "#123456", schedule = HabitSchedule.Daily)

    @Test fun habit_without_links_omits_linked_metric_section() {
        compose.setContent { MaterialTheme { HabitCard(habit, {}, {}, {}) } }
        compose.onNodeWithText("Walk").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.linked_metrics_title)).assertDoesNotExist()
    }

    @Test fun habit_forwards_linked_metric_navigation_without_opening_habit() {
        var habitClicks = 0
        var selected: Long? = null
        compose.setContent { MaterialTheme {
            HabitCard(habit, { habitClicks++ }, {}, {},
                linkedMetrics = listOf(LinkedMetricInfo("Weight", 7, 68.5, "kg", 1)),
                onMetricClick = { selected = it })
        } }
        compose.onNodeWithText("Weight").assertDoesNotExist()
        compose.onNodeWithContentDescription("Expand").performClick()
        compose.onNodeWithText("68.5 kg").assertIsDisplayed()
        compose.onNodeWithText("Weight").performClick()
        compose.runOnIdle { assertEquals(7L, selected); assertEquals(0, habitClicks) }
    }
}
