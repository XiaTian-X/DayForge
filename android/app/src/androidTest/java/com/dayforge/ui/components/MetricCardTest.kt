package com.dayforge.ui.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
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

    @Test fun empty_card_expansion_reveals_never_recorded_without_navigating() {
        var clicks = 0
        compose.setContent { MaterialTheme {
            MetricCard(metric, null, null, onClick = { clicks++ })
        } }
        val never = compose.activity.getString(R.string.metric_card_never_recorded)
        compose.onNodeWithText(compose.activity.getString(R.string.metric_card_no_records)).assertIsDisplayed()
        compose.onNodeWithText(never).assertDoesNotExist()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.content_description_expand)).performClick()
        compose.onNodeWithText(never).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, clicks) }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.content_description_collapse)).performClick()
        compose.onNodeWithText(never).assertDoesNotExist()
    }
}
