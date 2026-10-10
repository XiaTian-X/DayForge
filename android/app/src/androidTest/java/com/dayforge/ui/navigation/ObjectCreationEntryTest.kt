package com.dayforge.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.R
import com.dayforge.data.api.dto.ChallengeMetadata
import com.dayforge.data.local.entity.NextSyncStateEntity
import com.dayforge.data.repository.NextChallengeStore
import com.dayforge.data.repository.NextObjectEditorFixture
import com.dayforge.data.repository.NextObjectCreator
import com.dayforge.ui.screens.createmetric.CreateMetricScreen
import com.dayforge.ui.screens.createmetric.CreateMetricViewModel
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Physical Compose entry plus real account/Room creator; no formal APK or backend switch. */
@RunWith(AndroidJUnit4::class)
class ObjectCreationEntryTest : NextObjectEditorFixture() {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun loadingNeverRendersLegacyFormAndAdmissionErrorKeepsContentClosed() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val delayed = mockk<NextObjectCreator>()
        coEvery { delayed.captureForNavigation() } coAnswers { gate.await(); creator.captureForNavigation() }
        val model = withContext(Dispatchers.Main) { own(ObjectCreationEntryViewModel(delayed)) }
        compose.setContent { ObjectCreationEntry({}, model) { Text("Actual creation form") } }
        compose.onNodeWithText("Actual creation form").assertDoesNotExist()
        gate.complete(Unit) // Fixture's initialized rows have no accepted challenge cursor.
        compose.waitUntil(5_000) { model.state.value is ObjectCreationEntryState.Error }
        compose.onNodeWithText("Actual creation form").assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.action_retry)).assertIsDisplayed()
        assertEquals(0, db.syncOutboxDao().count())
    }

    @Test fun legacyEntryKeepsExistingMetricFormWithoutInitializingAppearance() = runBlocking<Unit> {
        db.clearAllData()
        val model = withContext(Dispatchers.Main) { own(ObjectCreationEntryViewModel(creator)) }
        val form = withContext(Dispatchers.Main) { own(CreateMetricViewModel(app, creatingMetrics(), db.metricDao(), db.habitDao())) }
        compose.setContent { ObjectCreationEntry({}, model) { CreateMetricScreen(form, it, {}) } }
        compose.onNodeWithText(app.getString(R.string.create_metric_screen_title)).assertIsDisplayed()
        assertNull((model.state.value as ObjectCreationEntryState.Ready).authority)
        assertNull(form.uiState.value.creationAuthority); assertNull(form.uiState.value.appearance)
        assertEquals(0, db.nextSyncStateDao().rows().size)
    }

    @Test fun challengeEntryPassesSameInMemoryAuthorityIntoActualMetricForm() = runBlocking<Unit> {
        db.clearAllData(); register()
        val state = NextSyncStateEntity(id(1), id(2), id(3), id(4), 1, 0, "a".repeat(64), "b".repeat(64), challengeContract = 1)
        db.withTransaction {
            NextChallengeStore(db).mergeInTransaction(access(), null, state, ChallengeMetadata(1, emptyList(), emptyList()))
            db.nextSyncStateDao().insert(state)
        }
        val model = withContext(Dispatchers.Main) { own(ObjectCreationEntryViewModel(creator)) }
        val form = withContext(Dispatchers.Main) { own(CreateMetricViewModel(app, creatingMetrics(), db.metricDao(), db.habitDao())) }
        compose.setContent { ObjectCreationEntry({}, model) { CreateMetricScreen(form, it, {}) } }
        compose.onNodeWithText(app.getString(R.string.create_metric_screen_title)).assertIsDisplayed()
        compose.waitUntil(5_000) { form.uiState.value.creationAuthority != null }
        val authority = (model.state.value as ObjectCreationEntryState.Ready).authority!!
        assertSame(authority, form.uiState.value.creationAuthority); assertNotNull(authority.rounds)
        assertNotNull(form.uiState.value.appearance)
        compose.runOnIdle { model.retry() } // Ready is not silently reissued on recomposition/retry.
        assertSame(authority, (model.state.value as ObjectCreationEntryState.Ready).authority)
        assertFalse(db.nextRequestDao().hasAny())
    }
}
