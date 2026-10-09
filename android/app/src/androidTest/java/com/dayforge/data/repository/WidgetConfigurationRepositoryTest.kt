package com.dayforge.data.repository

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.local.PhysicalDatabaseRule
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.IconReference
import com.dayforge.domain.model.ObjectAppearance
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.widget.IsolatedWidgetRefreshRule
import com.dayforge.widget.checkin.CheckInWidget
import com.dayforge.widget.checkin.CheckInWidgetReceiver
import com.dayforge.widget.counting.CountingWidget
import com.dayforge.widget.counting.CountingWidgetReceiver
import com.dayforge.widget.timer.TimerWidget
import com.dayforge.widget.timer.TimerWidgetReceiver
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.mockk.*
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Real Hilt/Room/account/binding preferences. Only launcher discovery or commit result is injected. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class WidgetConfigurationRepositoryTest {
    @get:Rule(order = 0) val widgets = IsolatedWidgetRefreshRule()
    @get:Rule(order = 1) val storage = PhysicalDatabaseRule()
    @get:Rule(order = 2) val hilt = HiltAndroidRule(this)
    @Inject lateinit var repository: WidgetConfigurationRepository
    @Inject lateinit var habits: HabitRepository
    @Inject lateinit var creator: NextObjectCreator
    @Inject lateinit var tokens: TokenManager
    @Inject lateinit var publisher: WidgetDisplayPublisher
    @Inject lateinit var sessions: AccountSessionCoordinator
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val db get() = storage.database
    private val platform = mockk<AppWidgetManager>()
    private val widgetId = 73101
    private val specs = listOf(
        WidgetConfigurationSpec(HabitType.CHECK_IN, CheckInWidget.PREFS_NAME, CheckInWidget.PREF_HABIT_ID_PREFIX, CheckInWidgetReceiver::class.java),
        WidgetConfigurationSpec(HabitType.COUNTING, CountingWidget.PREFS_NAME, CountingWidget.PREF_HABIT_ID_PREFIX, CountingWidgetReceiver::class.java),
        WidgetConfigurationSpec(HabitType.TIMER, TimerWidget.PREFS_NAME, TimerWidget.PREF_HABIT_ID_PREFIX, TimerWidgetReceiver::class.java))
    private fun id(n: Int) = "ab350000-0000-4000-8000-${n.toString(16).padStart(12, '0')}"
    private fun preferences(spec: WidgetConfigurationSpec) = app.getSharedPreferences(spec.preferences, Context.MODE_PRIVATE)
    private fun binding(spec: WidgetConfigurationSpec) = preferences(spec).getLong(spec.prefix + widgetId, -1)
    private suspend fun proof() = db.withTransaction { nextRestartDatabaseProof(db) }
    private suspend fun create(spec: WidgetConfigurationSpec, name: String): HabitEntity {
        val n = habits.createHabit(name, "", spec.type, 0, "#123456", HabitSchedule.Daily, targetValue = 1,
            completionPolicy = "recurring", appearance = ObjectAppearance(IconReference.Role("habit.custom"), "#123456", "theme"),
            creationAuthority = creator.capture())
        return requireNotNull(habits.getHabitById(n))
    }
    @Before fun setup() = runBlocking<Unit> {
        hilt.inject()
        sessions.exclusive { tokens.clearTokens(); tokens.saveLoginSession("synthetic-widget", "synthetic-refresh", "member", id(1), false) }
        specs.forEach { assertTrue(preferences(it).edit().clear().commit()) }
        mockkStatic(AppWidgetManager::class)
        every { AppWidgetManager.getInstance(any()) } returns platform
        every { platform.getAppWidgetIds(any()) } returns intArrayOf()
    }
    @After fun cleanup() = runBlocking<Unit> {
        unmockkStatic(AppWidgetManager::class)
        specs.forEach { assertTrue(preferences(it).edit().clear().commit()) }
        if (::tokens.isInitialized) sessions.exclusive { tokens.clearTokens() }
    }

    @Test fun allThreeTypesKeepFilteringAndSuccessfulBindingsWithoutBusinessWrites() = runBlocking<Unit> {
        val rows = specs.associateWith { create(it, "Configuration ${it.type}") }
        val before = proof()
        for (spec in specs) {
            val snapshot = repository.load(spec, widgetId)
            assertEquals(listOf(rows.getValue(spec)), snapshot.habits)
            var displayed = false
            repository.configure(snapshot, rows.getValue(spec)) { scope -> assertTrue(scope { displayed = true }) }
            assertTrue(displayed); assertEquals(rows.getValue(spec).id, binding(spec))
        }
        assertEquals(3, widgets.requestCount); assertEquals(before, proof())
    }

    @Test fun duplicatesAreRecheckedAfterSelectionWithoutChangingAnotherBinding() = runBlocking<Unit> {
        val spec = specs.first(); val row = create(spec, "Duplicate selection")
        val snapshot = repository.load(spec, widgetId)
        val before = proof()
        every { platform.getAppWidgetIds(any()) } returns intArrayOf(73102)
        assertTrue(preferences(spec).edit().putLong(spec.prefix + 73102, row.id).commit())
        try { repository.configure(snapshot, row) { fail("must not display") }; fail("must reject duplicate") }
        catch (error: IllegalStateException) { assertEquals("WIDGET_ALREADY_CONFIGURED", error.message) }
        assertEquals(-1L, binding(spec)); assertEquals(row.id, preferences(spec).getLong(spec.prefix + 73102, -1))
        assertEquals(0, widgets.requestCount); assertEquals(before, proof())
        assertTrue(repository.load(spec, widgetId).habits.isEmpty())
    }

    @Test fun accountSwitchAndReusedObjectIdentityCannotBindOldSelection() = runBlocking<Unit> {
        val spec = specs.first(); val row = create(spec, "Account selection")
        val snapshot = repository.load(spec, widgetId)
        sessions.exclusive {
            tokens.saveLoginSession("synthetic-other", "synthetic-refresh", "other", id(2), false)
            assertTrue(preferences(spec).edit().putLong(spec.prefix + widgetId, 999).commit())
        }
        val before = proof()
        try { repository.configure(snapshot, row) { fail("old display") }; fail("must expire") }
        catch (_: WidgetConfigurationExpired) { }
        assertEquals(999L, binding(spec)); assertEquals(before, proof())
        assertTrue(preferences(spec).edit().clear().commit())
        val current = repository.load(spec, widgetId)
        db.openHelper.writableDatabase.execSQL("UPDATE habits SET uuid=? WHERE id=?", arrayOf<Any?>(id(99), row.id))
        val reused = proof()
        try { repository.configure(current, row) { fail("reused display") }; fail("must reject identity") }
        catch (error: IllegalStateException) { assertEquals("WIDGET_HABIT_CHANGED", error.message) }
        assertEquals(-1L, binding(spec)); assertEquals(reused, proof())
    }

    @Test fun failureAndCancellationRestoreOnlyOwnBindingAndRequestCurrentRecovery() = runBlocking<Unit> {
        val spec = specs.last(); val row = create(spec, "Interrupted selection")
        assertTrue(preferences(spec).edit().putLong(spec.prefix + widgetId, 88).commit())
        val snapshot = repository.load(spec, widgetId); val before = proof()
        val failure = IOException("host failed")
        try { repository.configure(snapshot, row) { assertEquals(row.id, binding(spec)); throw failure }; fail("must propagate") }
        catch (error: IOException) { assertSame(failure, error) }
        assertEquals(88L, binding(spec)); assertEquals(1, widgets.requestCount)
        val entered = CompletableDeferred<Unit>()
        val configuring = launch(Dispatchers.IO) {
            repository.configure(snapshot, row) { assertEquals(row.id, binding(spec)); entered.complete(Unit); awaitCancellation() }
        }
        try { withTimeout(5000) { entered.await(); configuring.cancelAndJoin() } }
        finally { configuring.cancelAndJoin() }
        assertEquals(88L, binding(spec)); assertEquals(2, widgets.requestCount); assertEquals(before, proof())
        withTimeout(5000) { sessions.exclusive { } }
    }

    @Test fun oldSameValueConfigurationCannotPublishOrUndoNewSuccessfulBinding() = runBlocking<Unit> {
        val spec = specs.first(); val row = create(spec, "Concurrent selection")
        val first = repository.load(spec, widgetId); val second = repository.load(spec, widgetId)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var display = "new"
        val before = proof()
        val old = async(Dispatchers.IO) {
            try {
                repository.configure(first, row) { scope -> entered.complete(Unit); release.await(); assertFalse(scope { display = "old" }) }
                fail("old operation cannot succeed")
            } catch (_: WidgetConfigurationExpired) { }
        }
        try {
            withTimeout(5000) { entered.await() }
            repository.configure(second, row) { scope -> assertTrue(scope { display = "new" }) }
            release.complete(Unit); withTimeout(5000) { old.await() }
            assertEquals("new", display); assertEquals(row.id, binding(spec)); assertEquals(before, proof())
        } finally { release.complete(Unit); old.cancelAndJoin() }
    }

    @Test fun falseCommitCannotReportSuccessEvenWhenPreferencesMemoryChanged() = runBlocking<Unit> {
        val spec = specs.first(); val row = create(spec, "Commit selection")
        assertTrue(preferences(spec).edit().putLong(spec.prefix + widgetId, 89).commit())
        val raw = preferences(spec); val proxy = mockk<SharedPreferences>(); var commits = 0
        every { proxy.contains(any()) } answers { raw.contains(firstArg()) }
        every { proxy.getLong(any(), any()) } answers { raw.getLong(firstArg(), secondArg()) }
        every { proxy.edit() } answers {
            val edit = raw.edit(); val wrapper = mockk<SharedPreferences.Editor>()
            every { wrapper.putLong(any(), any()) } answers { edit.putLong(firstArg(), secondArg()); wrapper }
            every { wrapper.remove(any()) } answers { edit.remove(firstArg()); wrapper }
            every { wrapper.commit() } answers { val result = edit.commit(); commits++; if (commits == 1) false else result }
            wrapper
        }
        val context = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = if (name == spec.preferences) proxy else super.getSharedPreferences(name, mode)
        }
        val store = WidgetConfigurationRepository(context, db, tokens, publisher, sessions)
        val snapshot = store.load(spec, widgetId); val before = proof()
        try { store.configure(snapshot, row) { fail("must not render") }; fail("must not succeed") }
        catch (error: IllegalStateException) { assertEquals("WIDGET_BINDING_NOT_PERSISTED", error.message) }
        assertEquals(2, commits); assertEquals(89L, binding(spec)); assertEquals(before, proof())
    }

    @Test fun initialLateSourceAndItsFailureExpireWithoutRetaggingThePage() = runBlocking<Unit> {
        val spec = specs.first(); create(spec, "Initial source selection")
        val raw = db.habitDao(); val gated = spyk(raw); val proxy = spyk(db)
        every { proxy.habitDao() } returns gated
        val store = WidgetConfigurationRepository(app, proxy, tokens, publisher, sessions)
        val before = proof()
        for (failed in listOf(false, true)) {
            sessions.exclusive { tokens.saveLoginSession("synthetic-first", "synthetic-refresh", "member", id(1), false) }
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            coEvery { gated.getVisibleHabitsOnce() } coAnswers {
                val rows = raw.getVisibleHabitsOnce(); entered.complete(Unit); release.await()
                if (failed) throw IOException("old initial source failed")
                rows
            }
            val reading = async(Dispatchers.IO) {
                try { store.load(spec, widgetId); fail("old page must expire"); null }
                catch (error: WidgetConfigurationExpired) { error }
            }
            try {
                withTimeout(5000) { entered.await() }
                sessions.exclusive { tokens.saveLoginSession("synthetic-other", "synthetic-refresh", "other", id(2), false) }
                release.complete(Unit)
                val expired = withTimeout(5000) { requireNotNull(reading.await()) }
                if (failed) assertTrue(expired.suppressed.single() is IOException)
                assertEquals(-1L, binding(spec)); assertEquals(before, proof())
            } finally { release.complete(Unit); reading.cancelAndJoin() }
        }
    }
}
