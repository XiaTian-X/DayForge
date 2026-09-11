package com.dayforge.domain.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dayforge.widget.checkin.CheckInWidget
import com.dayforge.widget.counting.CountingWidget
import com.dayforge.widget.timer.TimerWidget
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class AccountLocalStateCleanerTest {
    @Test
    fun `account cleanup clears widget bindings after local stores`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferenceNames = listOf(
            CheckInWidget.PREFS_NAME,
            CountingWidget.PREFS_NAME,
            TimerWidget.PREFS_NAME
        )
        preferenceNames.forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit()
                .putLong("habit_id_1", 42L)
                .commit()
        }
        var storesCleared = false

        AccountLocalStateCleaner.clear(context) { storesCleared = true }

        assertTrue(storesCleared)
        preferenceNames.forEach { name ->
            assertTrue(
                context.getSharedPreferences(name, Context.MODE_PRIVATE).all.isEmpty()
            )
        }
    }
}
