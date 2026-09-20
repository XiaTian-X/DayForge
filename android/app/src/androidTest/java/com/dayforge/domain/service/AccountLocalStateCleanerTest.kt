package com.dayforge.domain.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dayforge.widget.checkin.CheckInWidget
import com.dayforge.widget.counting.CountingWidget
import com.dayforge.widget.timer.TimerWidget
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountLocalStateCleanerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferenceNames = listOf(CheckInWidget.PREFS_NAME, CountingWidget.PREFS_NAME, TimerWidget.PREFS_NAME)
    @After fun cleanupBindings() {
        preferenceNames.forEach { name -> assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()) }
    }

    @Test
    fun account_cleanup_clears_widget_bindings_after_local_stores() = runTest {
        preferenceNames.forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit()
                .putLong("habit_id_1", 42L)
                .commit()
        }
        var storesCleared = false

        AccountLocalStateCleaner.clear(context) {
            preferenceNames.forEach { name ->
                assertEquals(42L, context.getSharedPreferences(name, Context.MODE_PRIVATE).getLong("habit_id_1", -1L))
            }
            storesCleared = true
        }

        assertTrue(storesCleared)
        preferenceNames.forEach { name ->
            assertTrue(
                context.getSharedPreferences(name, Context.MODE_PRIVATE).all.isEmpty()
            )
        }
    }

    @Test fun failedStoreClearPropagatesAndPreservesBindings() = runTest {
        preferenceNames.forEach { name ->
            assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().putLong("habit_id_1", 42L).commit())
        }
        val failure = IllegalStateException("store clear failed")
        try {
            AccountLocalStateCleaner.clear(context) { throw failure }
            fail("Store failure must propagate")
        } catch (actual: IllegalStateException) { assertSame(failure, actual) }
        preferenceNames.forEach { name ->
            assertEquals(42L, context.getSharedPreferences(name, Context.MODE_PRIVATE).getLong("habit_id_1", -1L))
        }
    }
}
