package com.dayforge.ui.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
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
class PostCheckInDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val metrics = listOf(LinkedMetricInfo("Weight", 7, null, "kg", 1),
        LinkedMetricInfo("Steps", 9, null, "steps", 0))

    @Test fun record_is_disabled_for_empty_input_and_submits_only_entered_values_with_preference() {
        var result: Pair<List<MetricValueInput>, Boolean>? = null
        compose.setContent { MaterialTheme { Box(Modifier.fillMaxSize()) {
            PostCheckInDialog("Walk", metrics, { values, never -> result = values to never }, {}, {})
        } } }
        val record = compose.onNodeWithText(compose.activity.getString(R.string.post_checkin_record))
        record.assertIsNotEnabled()
        compose.onNodeWithText("Weight (kg)").performTextInput("68.5")
        record.assertIsEnabled()
        compose.onNode(isToggleable()).performClick()
        record.performClick()
        compose.runOnIdle { assertEquals(listOf(MetricValueInput(7, 68.5)) to true, result) }
    }

    @Test fun skip_preserves_checkbox_choice_and_never_records_typed_values() {
        var recorded = false
        var skipped: Boolean? = null
        compose.setContent { MaterialTheme { Box(Modifier.fillMaxSize()) {
            PostCheckInDialog("Walk", metrics, { _, _ -> recorded = true }, { skipped = it }, {})
        } } }
        compose.onNodeWithText("Weight (kg)").performTextInput("68.5")
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.post_checkin_skip)).performClick()
        compose.runOnIdle { assertEquals(true, skipped); assertFalse(recorded) }
    }
}
