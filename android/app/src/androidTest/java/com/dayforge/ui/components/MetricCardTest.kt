package com.dayforge.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.R
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class MetricCardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val metric = MetricEntity(name = "Weight", unit = "kg", decimalPlaces = 1,
        iconResId = 1, colorHex = "#123456")

    @Test fun card_renders_rounded_value_and_invokes_detail_navigation() {
        var clicks = 0
        compose.setContent { MaterialTheme {
            MetricCard(metric, 68.56, null, onClick = { clicks++ })
        } }
        compose.onNodeWithText("68.6 kg").assertIsDisplayed()
        compose.onNodeWithText("Weight").performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun empty_card_keeps_status_visible_and_only_toggles_chart_without_navigating() {
        var clicks = 0
        compose.setContent { MaterialTheme {
            MetricCard(metric, null, null, onClick = { clicks++ })
        } }
        val never = compose.activity.getString(R.string.metric_card_never_recorded)
        val emptyChart = compose.activity.getString(R.string.chart_no_data)
        compose.onNodeWithText("--").assertIsDisplayed()
        compose.onNodeWithText(never).assertIsDisplayed()
        compose.onNodeWithText(emptyChart).assertDoesNotExist()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.content_description_expand)).performClick()
        compose.onNodeWithText(never).assertIsDisplayed()
        compose.onNodeWithText(emptyChart).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, clicks) }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.content_description_collapse)).performClick()
        compose.onNodeWithText(never).assertIsDisplayed()
        compose.onNodeWithText(emptyChart).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, clicks) }
    }

    @Test fun configured_directions_remain_accessible_with_and_without_records() {
        val current = mutableStateOf(metric)
        val value = mutableStateOf<Double?>(null)
        compose.setContent { MaterialTheme {
            MetricCard(current.value, value.value, null, onClick = {})
        } }
        val directions = listOf(
            "increase" to R.string.edit_metric_target_increase,
            "decrease" to R.string.edit_metric_target_decrease,
            "range" to R.string.edit_metric_target_range
        )
        directions.forEach { (direction, label) ->
            val description = compose.activity.getString(
                R.string.metric_card_target_direction, compose.activity.getString(label)
            )
            listOf(null, 68.5).forEach { latest ->
                compose.runOnIdle {
                    current.value = metric.copy(targetDirection = direction, targetValue = 60.0,
                        targetValueUpper = if (direction == "range") 70.0 else null)
                    value.value = latest
                }
                compose.onNodeWithContentDescription(description).assertIsDisplayed()
            }
        }
        compose.runOnIdle { current.value = metric }
        directions.forEach { (_, label) ->
            compose.onNodeWithContentDescription(compose.activity.getString(
                R.string.metric_card_target_direction, compose.activity.getString(label)
            )).assertDoesNotExist()
        }
    }
}
