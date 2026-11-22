package com.dayforge.data.local

import android.content.Context
import android.net.ConnectivityManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.R
import com.dayforge.data.repository.*
import com.dayforge.di.DatabaseModule
import com.dayforge.domain.service.DeviceCalendar
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real original Panel/ViewModel under local authentication and no sync registration. */
@HiltAndroidTest @UninstallModules(DatabaseModule::class) @RunWith(AndroidJUnit4::class)
class RetainedLocalObligationConsumerTest : RetainedSessionJourneyFixture() {
    @Test(timeout = 150_000) fun localUnregisteredOldUnknownIsVisibleAccessibleAndNeverAdoptedOrEnded() = auth.ownedTest {
        historical(localUnregistered = true); readyAndEnroll()
        assertNotNull(epochs.tokens.localCoreWriteAccess()); assertNull(epochs.tokens.localSyncAccess())
        val stable = image(); val preferenceMap = auth.store.data.first().asMap()
        val panel = showPanel()
        val first = requireNotNull(panel.frames.value)
        val page = first.value.page.value
        assertTrue(page.complete); assertEquals(1L, page.retainedCount); assertTrue(page.entries.isEmpty())
        assertTrue(first.publication.belongsTo(epochs.host)); assertTrue(first.publication.isCurrentInMemory())
        val message = app.resources.getQuantityString(R.plurals.count_recovery_retained, 1, 1L)
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onNodeWithText(message, useUnmergedTree = true).assertExists()
        for (id in listOf(R.string.count_recovery_continue, R.string.count_recovery_view_result,
            R.string.count_recovery_end_followup, R.string.independent_metric_end_action))
            compose.onAllNodesWithText(app.getString(id)).assertCountEquals(0)
        capturePanel("local_retained_obligation")
        assertEquals(stable, image()); assertEquals(preferenceMap, auth.store.data.first().asMap())
        compose.onNodeWithText(app.getString(R.string.count_recovery_refresh)).performClick()
        compose.waitUntil(45_000) { panel.frames.value != null && panel.frames.value !== first && !panel.busy.value }
        assertFalse(panel.failed.value); assertEquals(1L, requireNotNull(panel.frames.value).value.page.value.retainedCount)
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.count_recovery_close)).performClick()
        compose.waitUntil(45_000) { panel.frames.value == null && !panel.busy.value }
        compose.onNodeWithText(app.getString(R.string.count_recovery_open)).assertIsDisplayed()
        assertEquals(stable, image()); assertEquals(preferenceMap, auth.store.data.first().asMap())
        assertNull(epochs.tokens.localSyncAccess()); assertRetained()
        assertFalse(native.ends.any { it.kind in setOf(RetainedJourneyNative.Kind.T0, RetainedJourneyNative.Kind.COUNT_T1,
            RetainedJourneyNative.Kind.METRIC_T1, RetainedJourneyNative.Kind.T2, RetainedJourneyNative.Kind.ACK) })
        assertTrue(native.observationFailures.isEmpty())
    }
    @Module @InstallIn(SingletonComponent::class)
    object PreferencesModule {
        @Provides @Singleton fun data(): DataStore<Preferences> = LegacySessionHostPreferences.active().store
        @Provides @Singleton fun cipher(): TokenCipher = LegacySessionHostPreferences.active().cipher
        @Provides @Singleton fun preferences(data: DataStore<Preferences>, calendar: DeviceCalendar, tokens: TokenManager) =
            PreferencesManager(data, calendar, tokens)
        @Provides @Singleton fun connectivity(@ApplicationContext context: Context): ConnectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
}
