package com.dayforge.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.R
import com.dayforge.data.local.entity.MetricEntity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrendChartTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val metric = MetricEntity(name = "Weight", unit = "kg", iconResId = 1, colorHex = "#123456")
    private fun label(id: Int) = compose.activity.getString(id)

    @Test fun timeRangeEnum_hasCorrectValues() {
        assertEquals(7, TimeRange.SEVEN_DAYS.days)
        assertEquals(30, TimeRange.THIRTY_DAYS.days)
    }

    @Test fun timeRangeEnum_hasTwoOptions() {
        assertEquals(2, TimeRange.entries.size)
        assertTrue(TimeRange.entries.contains(TimeRange.SEVEN_DAYS))
        assertTrue(TimeRange.entries.contains(TimeRange.THIRTY_DAYS))
        compose.setContent { MaterialTheme { TrendChart(metric, emptyList()) } }
        compose.onNodeWithText(label(R.string.chart_30_days)).assertIsNotSelected().performClick().assertIsSelected()
        compose.onNodeWithText(label(R.string.chart_7_days)).assertIsNotSelected().performClick().assertIsSelected()
        compose.onNodeWithText(label(R.string.chart_30_days)).assertIsNotSelected()
    }

    @Test fun timeRangeEnum_defaultIsSevenDays() {
        assertEquals(7, TimeRange.SEVEN_DAYS.days)
        compose.setContent { MaterialTheme { TrendChart(metric, emptyList()) } }
        compose.onNodeWithText(label(R.string.chart_7_days)).assertIsSelected()
        compose.onNodeWithText(label(R.string.chart_30_days)).assertIsNotSelected()
    }

    @Test fun aggregationClickEmitsSelectionAndExternalUpdateRecomposes() {
        val currentMetric = mutableStateOf(metric)
        val changes = mutableListOf<AggregationType>()
        compose.setContent { MaterialTheme {
            TrendChart(currentMetric.value, emptyList(), onAggregationTypeChange = { changes.add(it) })
        } }
        compose.onNodeWithText(label(R.string.chart_daily_average)).performClick()
        compose.onNodeWithText(label(R.string.chart_daily_total)).performClick()
        compose.onNodeWithText(label(R.string.chart_daily_total)).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(AggregationType.SUM), changes)
            currentMetric.value = metric.copy(aggregationType = "by_time")
        }
        compose.onNodeWithText(label(R.string.chart_all_records)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(AggregationType.SUM), changes) }
    }
}
