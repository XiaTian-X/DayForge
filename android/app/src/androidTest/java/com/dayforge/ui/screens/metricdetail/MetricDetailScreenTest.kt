package com.dayforge.ui.screens.metricdetail

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.dayforge.R
import com.dayforge.data.local.PhysicalDatabaseRule
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.repository.MetricRepository
import com.dayforge.domain.model.IconReference
import com.dayforge.domain.model.ObjectAppearance
import com.dayforge.ui.components.LocalAccountIcons
import com.dayforge.ui.components.ObjectIconFixture
import com.dayforge.ui.screens.settings.IconLibraryFixture
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Assert.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import org.junit.Test
import java.util.Locale

/**
 * Physical Compose interaction tests plus preserved UI-state contracts.
 */
@RunWith(AndroidJUnit4::class)
class MetricDetailScreenTest {
    @get:Rule(order = 0) val storage = PhysicalDatabaseRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var viewModel: MetricDetailViewModel? = null
    private var deleted = 0
    private var icons: ObjectIconFixture? = null
    private val typedDisplayed = mutableStateOf(true)
    private fun label(id: Int) = context.getString(id)

    @After fun closeViewModel() {
        runBlocking { viewModel?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin() }
        icons?.let { fixture ->
            try { compose.runOnIdle { typedDisplayed.value = false }; compose.waitForIdle() }
            finally { runBlocking { fixture.close() } }
        }
    }

    @Test fun typedHeroRendersOwnedPixelsWithoutChangingMetricOrOutbox() {
        val fixture = ObjectIconFixture().also { icons = it }
        runBlocking { fixture.open(); fixture.install() }
        val db = storage.database
        val metric = MetricEntity(uuid = fixture.id(96), name = "Native hero", unit = "kg",
            decimalPlaces = 1, iconResId = 1, colorHex = "#123456",
            appearance = ObjectAppearance(IconReference.Asset(fixture.id(11)), "#123456", "theme"))
        val id = runBlocking { db.metricDao().insert(metric) }
        val outbox = runBlocking { db.syncOutboxDao().count() }
        val repository = MetricRepository(db, db.metricDao(), db.metricLogDao(), db.habitDao(), db.habitMetricLinkDao())
        compose.runOnUiThread {
            viewModel = MetricDetailViewModel(context, db.metricDao(), db.metricLogDao(),
                db.habitMetricLinkDao(), db.habitDao(), repository, SavedStateHandle(mapOf("metricId" to id)))
        }
        compose.setContent {
            if (typedDisplayed.value) CompositionLocalProvider(LocalAccountIcons provides fixture.controller) {
                MaterialTheme { MetricDetailScreen(id, {}, {}, viewModel = requireNotNull(viewModel)) }
            }
        }
        compose.waitUntil(5000) { viewModel?.uiState?.value?.isLoading == false }
        val image = compose.onNodeWithTag("object-icon:${metric.uuid}", useUnmergedTree = true)
        compose.waitUntil(5000) {
            val pixels = image.captureToImage().toPixelMap()
            pixels[pixels.width / 2, pixels.height / 2].toArgb() == IconLibraryFixture.red
        }
        image.assertWidthIsEqualTo(androidx.compose.ui.unit.Dp(56f)).assertHeightIsEqualTo(androidx.compose.ui.unit.Dp(56f))
        compose.onNodeWithText(metric.name).assertIsDisplayed()
        runBlocking {
            assertEquals(metric.copy(id = id), db.metricDao().getMetricById(id))
            assertEquals(outbox, db.syncOutboxDao().count())
            assertTrue(db.metricLogDao().getAllLogsForMetric(id).isEmpty())
        }
    }

    private fun showMetric(targetDirection: String? = null, targetUpper: Double? = null): Long {
        val db = storage.database
        val repository = MetricRepository(db, db.metricDao(), db.metricLogDao(), db.habitDao(), db.habitMetricLinkDao())
        val id = runBlocking { repository.createMetric(MetricEntity(
            name = "Device weight", unit = "kg", decimalPlaces = 1, iconResId = 1, colorHex = "#123456",
            targetDirection = targetDirection, targetValue = targetDirection?.let { 60.0 },
            targetValueUpper = targetUpper
        )) }
        compose.runOnUiThread {
            viewModel = MetricDetailViewModel(context, db.metricDao(), db.metricLogDao(),
                db.habitMetricLinkDao(), db.habitDao(), repository, SavedStateHandle(mapOf("metricId" to id)))
        }
        compose.setContent { MaterialTheme {
            MetricDetailScreen(id, onNavigateBack = {}, onDeleted = { deleted++ }, viewModel = requireNotNull(viewModel))
        } }
        compose.waitUntil(5_000) { viewModel?.uiState?.value?.isLoading == false }
        // The app bar owns the name; the hero shows the value/configuration without duplicating it.
        compose.onNodeWithText("Device weight").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.metric_configuration_summary, "kg", 1))
            .performScrollTo().assertIsDisplayed()
        return id
    }

    private fun assertTargetBeforeAndAfterRecording(direction: String, labelRes: Int, upper: Double? = null) {
        val id = showMetric(direction, upper)
        val lowerText = String.format(Locale.getDefault(), "%.1f", 60.0)
        val range = upper?.let { " - ${String.format(Locale.getDefault(), "%.1f", it)}" } ?: ""
        val expected = "${label(labelRes)}: $lowerText$range kg"
        compose.onNodeWithText(expected).performScrollTo().assertIsDisplayed()
        runBlocking { assertTrue(storage.database.metricLogDao().getAllLogsForMetric(id).isEmpty()) }
        submitValue()
        compose.onNodeWithText(expected).performScrollTo().assertIsDisplayed()
        runBlocking {
            val stored = requireNotNull(storage.database.metricDao().getMetricById(id))
            assertEquals(direction, stored.targetDirection)
            assertEquals(60.0, requireNotNull(stored.targetValue), 0.0)
            assertEquals(upper, stored.targetValueUpper)
            assertEquals(1, storage.database.metricLogDao().getAllLogsForMetric(id).size)
            assertEquals(2, storage.database.syncOutboxDao().count())
        }
    }

    @Test fun increaseTargetIsVisibleBeforeAndAfterFirstRecord() =
        assertTargetBeforeAndAfterRecording("increase", R.string.metric_target_increase)

    @Test fun decreaseTargetIsVisibleBeforeAndAfterFirstRecord() =
        assertTargetBeforeAndAfterRecording("decrease", R.string.metric_target_decrease)

    @Test fun rangeTargetIsVisibleBeforeAndAfterFirstRecord() =
        assertTargetBeforeAndAfterRecording("range", R.string.metric_target_range, 70.0)

    private fun submitValue() {
        compose.onNodeWithText(label(R.string.metric_record_value)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.metric_input_enter_value)).performTextInput("12.5")
        compose.onNodeWithText(label(R.string.metric_input_note_optional)).performTextInput("device entry")
        compose.onNode(hasText(label(R.string.metric_record_value)) and hasAnyAncestor(isDialog()) and hasClickAction()).performClick()
        compose.waitUntil(5_000) { viewModel?.uiState?.value?.logs?.size == 1 && viewModel?.uiState?.value?.showValueInput == false }
    }

    @Test fun recordDialogPersistsValueNoteAndOutboxThenRendersHistory() {
        val id = showMetric()
        submitValue()
        compose.onNodeWithText("device entry").performScrollTo().assertIsDisplayed()
        runBlocking {
            val log = storage.database.metricLogDao().getAllLogsForMetric(id).single()
            assertEquals(12.5, log.value, 0.0)
            assertEquals("kg", log.unit)
            assertEquals("device entry", log.note)
            assertEquals(2, storage.database.syncOutboxDao().count())
            closeViewModel()
            val reopened = storage.reopen()
            assertEquals(log, reopened.metricLogDao().getAllLogsForMetric(id).single())
            assertEquals(2, reopened.syncOutboxDao().count())
        }
    }

    @Test fun deleteCancelPreservesRowsAndConfirmDeletesWithSingleCallback() {
        val id = showMetric()
        submitValue()
        compose.onNodeWithText(label(R.string.metric_delete_metric)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.action_cancel)).performClick()
        compose.runOnIdle { assertEquals(0, deleted) }
        runBlocking {
            assertNotNull(storage.database.metricDao().getMetricById(id))
            assertEquals(1, storage.database.metricLogDao().getAllLogsForMetric(id).size)
            assertEquals(2, storage.database.syncOutboxDao().count())
        }
        compose.onNodeWithText(label(R.string.metric_delete_metric)).performScrollTo().performClick()
        compose.onNode(hasText(label(R.string.action_delete)) and hasAnyAncestor(isDialog()) and hasClickAction()).performClick()
        compose.waitUntil(5_000) { deleted == 1 }
        compose.runOnIdle { assertEquals(1, deleted) }
        runBlocking {
            assertNull(storage.database.metricDao().getMetricById(id))
            assertTrue(storage.database.metricLogDao().getAllLogsForMetric(id).isEmpty())
            assertTrue(storage.database.syncOutboxDao().getAll().any { it.recordType == "metric" && it.action == "delete" })
        }
    }

    @Test
    fun uiState_hasRequiredFields() {
        // Verify UiState structure - logs field is required for TrendChart
        val uiState = MetricDetailUiState(
            metric = null,
            logs = emptyList(),
            isLoading = true
        )

        assertNotNull(uiState)
        assertTrue(uiState.logs.isEmpty())
        assertNull(uiState.metric)
        assertTrue(uiState.isLoading)
    }

    @Test
    fun uiState_defaultValues() {
        // Verify default values match expected behavior
        val uiState = MetricDetailUiState()

        assertNull(uiState.metric)
        assertNull(uiState.latestValue)
        assertTrue(uiState.logs.isEmpty())
        assertTrue(uiState.links.isEmpty())
        assertTrue(uiState.isLoading)
        assertFalse(uiState.isDeleted)
    }

    @Test
    fun habitMetricLinkWithHabit_containsRequiredData() {
        // Verify link with habit data structure
        val link = com.dayforge.data.local.entity.HabitMetricLinkEntity(
            habitId = 1L,
            habitUuid = "test-uuid",
            metricId = 1L,
            metricUuid = "metric-uuid"
        )
        val linkWithHabit = HabitMetricLinkWithHabit(
            link = link,
            habitName = "Test Habit"
        )

        assertEquals(1L, linkWithHabit.link.habitId)
        assertEquals("Test Habit", linkWithHabit.habitName)
    }

    @Test
    fun habitForLinking_tracksSelectionState() {
        // Verify habit for linking structure
        val habit = HabitForLinking(
            id = 1L,
            name = "Morning Run",
            isAlreadyLinked = false
        )

        assertEquals(1L, habit.id)
        assertEquals("Morning Run", habit.name)
        assertFalse(habit.isAlreadyLinked)
    }
}
