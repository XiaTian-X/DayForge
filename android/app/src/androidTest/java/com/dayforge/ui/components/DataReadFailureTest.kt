package com.dayforge.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DataReadFailureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun localizedFailureAndRetryRemainReachableInNarrowLargeFontWindows() {
        var retries = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                MaterialTheme { DataReadFailure({ retries++ }, Modifier.width(280.dp).height(180.dp)) }
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.data_read_failed)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.action_retry)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }
}
