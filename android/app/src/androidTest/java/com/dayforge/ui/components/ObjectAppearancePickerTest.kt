package com.dayforge.ui.components

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.room.withTransaction
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.R
import com.dayforge.data.repository.*
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.*
import com.dayforge.ui.screens.settings.IconLibraryFixture
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ObjectAppearancePickerTest : NextObjectEditorFixture() {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val shown = mutableStateOf(true)
    private var contentOpened = false

    @After fun disposeDialog() {
        // Three cases exercise only the real ViewModel/storage. With no composition, a new
        // snapshot write has no frame owner and waitForIdle cannot consume it. Do not create it.
        if (!contentOpened) return
        compose.runOnIdle { shown.value = false }
        compose.waitForIdle()
    }

    private suspend fun installed() {
        register()
        val file = File(directory, "real-icons.zip").also { it.writeBytes(IconLibraryFixture.archive()) }
        icons.install(icons.preview(icons.capture(), Uri.fromFile(file)))
    }

    @Test fun realDialogChoosesOwnedPngAndObjectTintThenRepositoryFreezesThatExactReference() = runBlocking<Unit> {
        installed()
        val snapshot = editingMetrics().getMetricForEditing(metric.id)
        val original = requireNotNull(snapshot.value).appearance!!
        val model = withContext(Dispatchers.Main) { own(ObjectAppearancePickerViewModel(icons)) }
        val selected = AtomicReference<ObjectAppearance?>()
        contentOpened = true
        compose.setContent {
            if (shown.value) MaterialTheme {
                CompositionLocalProvider(LocalAccountIcons provides icons) {
                    ObjectAppearancePicker(original, false, snapshot.authority,
                        onSelected = { selected.set(it) }, onDismiss = { shown.value = false }, viewModel = model)
                }
            }
        }
        val ready = withTimeout(5000) { model.state.first { !it.loading && it.appearance != null } }
        assertNull(ready.error)
        assertTrue(ready.choices.any { it.reference == IconReference.Role("habit.exercise") })
        assertFalse(ready.choices.any { it.reference == IconReference.Role("task.shopping") })
        assertFalse(ready.choices.any { it.reference == IconReference.Asset(IconLibraryFixture.id(12)) })
        compose.onNodeWithText("Original PNG").performClick()
        compose.onNodeWithText(app.getString(R.string.object_icon_tint_object)).performClick()
        compose.onNodeWithTag("object-icon-picker-confirm").performClick()
        compose.waitUntil(5000) { selected.get() != null }
        val picked = requireNotNull(selected.get())
        assertEquals(ObjectAppearance(IconReference.Asset(IconLibraryFixture.id(11)), original.accentColor, "object"), picked)
        editingMetrics().updateMetric(metric.copy(appearance = picked), snapshot.authority)
        assertEquals(picked, db.metricDao().getMetricById(metric.id)!!.appearance)
        assertEquals(1, count("next_request_origins")); assertEquals(1, count("sync_outbox"))
    }

    @Test fun oncePickerContainsOnlyTaskReferencesAndCannotSelectGeneralRoleOrAsset() = runBlocking<Unit> {
        installed()
        val appearance = ObjectAppearance(IconReference.Role("task.shopping"), "#123456", "theme")
        val once = habit.copy(id = 0, uuid = id(99), name = "Native task", habitType = HabitType.CHECK_IN,
            targetValue = 1, schedule = HabitSchedule.Once(), completionPolicy = "one_and_done",
            oneTimeConfirmedVersion = 0, appearance = appearance)
        val onceId = db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            val inserted = db.habitDao().insert(once)
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
            inserted
        }
        val snapshot = editingHabits().getHabitForEditing(onceId)
        val model = withContext(Dispatchers.Main) { own(ObjectAppearancePickerViewModel(icons)) }
        withContext(Dispatchers.Main) { model.open(appearance, true, snapshot.authority) }
        val ready = withTimeout(5000) { model.state.first { !it.loading && it.appearance != null } }
        assertEquals(setOf(IconReference.Role("task.shopping"), IconReference.Asset(IconLibraryFixture.id(12))),
            ready.choices.map { it.reference }.toSet())
        withContext(Dispatchers.Main) {
            model.choose(IconReference.Role("habit.exercise"))
            model.choose(IconReference.Asset(IconLibraryFixture.id(11)))
        }
        assertEquals(appearance, model.state.value.appearance)
    }

    @Test fun logoutOrSameAccountReauthenticationClearsPickerAndCannotDeliverOldChoice() = runBlocking<Unit> {
        installed()
        val snapshot = editingMetrics().getMetricForEditing(metric.id)
        val model = withContext(Dispatchers.Main) { own(ObjectAppearancePickerViewModel(icons)) }
        val old = requireNotNull(snapshot.value).appearance!!
        withContext(Dispatchers.Main) { model.open(old, false, snapshot.authority) }
        withTimeout(5000) { model.state.first { !it.loading && it.appearance != null } }
        sessions.exclusive { tokens.saveLoginSession("synthetic-new", "synthetic-refresh", "member", id(1), false) }
        assertNull(model.state.value.appearance); assertTrue(model.state.value.choices.isEmpty())
        val selected = AtomicReference<ObjectAppearance?>()
        withContext(Dispatchers.Main) { model.confirm(selected::set); model.open(old, false, snapshot.authority) }
        withTimeout(5000) { model.state.first { it.error != null && !it.loading } }
        assertNull(selected.get()); assertEquals(0, count("sync_outbox")); assertEquals(metric, db.metricDao().getMetricById(metric.id))
    }

    @Test fun missingSavedReferenceIsOfferedUnchangedWithoutInventingMaterialOrOtherAssets() = runBlocking<Unit> {
        register()
        val missing = ObjectAppearance(IconReference.Asset(id(91)), "#123456", "theme")
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.metricDao().update(metric.copy(appearance = missing))
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        val snapshot = editingMetrics().getMetricForEditing(metric.id)
        val model = withContext(Dispatchers.Main) { own(ObjectAppearancePickerViewModel(icons)) }
        withContext(Dispatchers.Main) { model.open(missing, false, snapshot.authority) }
        val ready = withTimeout(5000) { model.state.first { !it.loading && it.appearance != null } }
        assertEquals(listOf(missing.icon), ready.choices.map { it.reference })
        assertEquals(missing, ready.appearance)
        val selected = AtomicReference<ObjectAppearance?>()
        withContext(Dispatchers.Main) { model.confirm(selected::set) }
        withTimeout(5000) { while (selected.get() == null) yield() }
        assertEquals(missing, selected.get()); assertEquals(0, count("sync_outbox"))
    }
}
