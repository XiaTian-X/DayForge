package com.dayforge.widget.checkin

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.PhysicalDatabaseRule
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CheckInWidgetConfigActivityTest {
    @get:Rule(order = 0) val storage = PhysicalDatabaseRule()
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun testFilterOnlyCheckInHabits() {
        runBlocking {
            storage.database.habitDao().insert(HabitEntity(name = "Daily Exercise", habitType = HabitType.CHECK_IN,
                iconResId = 1, colorHex = "#4CAF50", schedule = HabitSchedule.Daily, targetValue = 1))
            storage.database.habitDao().insert(HabitEntity(name = "Water Glasses", habitType = HabitType.COUNTING,
                iconResId = 2, colorHex = "#2196F3", schedule = HabitSchedule.Daily, targetValue = 8))
            assertEquals(2, storage.database.habitDao().getAllHabitsOnce().size)
        }
        val intent = Intent(context, CheckInWidgetConfigActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 71001)
        ActivityScenario.launch<CheckInWidgetConfigActivity>(intent).use {
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Daily Exercise").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithText("Daily Exercise").assertIsDisplayed().assertHasClickAction()
            compose.onNodeWithText("Water Glasses").assertDoesNotExist()
        }
    }
}
