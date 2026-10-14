package com.dayforge.widget.base

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.layout.Column
import androidx.glance.text.Text
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.MainActivity
import com.dayforge.R
import com.dayforge.data.appearance.BuiltInThemes
import com.dayforge.data.appearance.DeviceThemeLoadState
import com.dayforge.data.appearance.DeviceThemeRepository
import com.dayforge.data.appearance.ThemeFileRepository
import com.dayforge.data.local.replicaStageFixture
import com.dayforge.data.repository.*
import com.dayforge.domain.service.ReplicaPresentationController
import com.dayforge.domain.service.ReplicaPresentationState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real control storage and actual Glance RemoteViews; not a launcher/physical widget installation. */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@RunWith(AndroidJUnit4::class)
class WidgetFramePresentationTest : NextCoreRequestFixture() {
    private lateinit var controller: ReplicaPresentationController
    private lateinit var publisher: WidgetDisplayPublisher
    private lateinit var theme: DeviceThemeLoadState
    private var composed = 0

    private suspend fun start(): ReplicaPresentationState.Open {
        register()
        val repository = ReplicaPresentationRepository(db, tokens, sessions)
        publisher = WidgetDisplayPublisher(tokens, sessions, repository)
        theme = DeviceThemeLoadState.Ready(DeviceThemeRepository(dataStore, ThemeFileRepository(directory),
            BuiltInThemes(app.assets)).initialize())
        controller = ReplicaPresentationController(tokens, repository)
        return open()
    }

    private suspend fun open() = withTimeout(10_000) {
        controller.state.first { it is ReplicaPresentationState.Open } as ReplicaPresentationState.Open
    }

    @After fun closeController() = runBlocking<Unit> {
        if (::controller.isInitialized) { controller.close(); controller.awaitClosed() }
    }

    private suspend fun labels(state: ReplicaPresentationState, publication: WidgetDisplayPublisher.Publication?,
        preferences: Preferences): List<String> {
        val views = withTimeout(5_000) {
            GlanceRemoteViews().compose(app, DpSize(180.dp, 120.dp)) {
                DeviceWidgetFrameSnapshot(app, state, publication, preferences, theme) {
                    composed++
                    Column {
                        Text("private cached habit")
                        Text("private action", modifier = GlanceModifier.clickable(actionStartActivity(Intent(app, MainActivity::class.java))))
                    }
                }
            }.remoteViews
        }
        var result = emptyList<String>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            fun read(view: View): List<String> = when (view) {
                is TextView -> listOf(view.text.toString())
                is ViewGroup -> (0 until view.childCount).flatMap { read(view.getChildAt(it)) }
                else -> emptyList()
            }
            result = read(views.apply(app, FrameLayout(app)))
        }
        return result
    }

    private suspend fun neutral(state: ReplicaPresentationState, publication: WidgetDisplayPublisher.Publication?,
        preferences: Preferences) {
        val before = composed
        val labels = labels(state, publication, preferences)
        assertTrue(labels.contains(app.getString(R.string.replica_display_title)))
        assertFalse(labels.contains("private cached habit")); assertFalse(labels.contains("private action"))
        assertEquals(before, composed)
    }

    @Test fun currentOwnedFrameRendersButCheckingMissingOwnerAndAnonymousNeverComposeBusiness() = runBlocking<Unit> {
        val state = start()
        val publication = requireNotNull(publisher.capturePublication())
        val frame = mutablePreferencesOf().also(publication::stampFrame)
        assertTrue(labels(state, publication, frame).containsAll(listOf("private cached habit", "private action")))
        neutral(ReplicaPresentationState.Checking, publication, frame)
        neutral(state, publication, mutablePreferencesOf())
        tokens.clearTokens()
        neutral(state, requireNotNull(publisher.capturePublication()), frame)
    }

    @Test fun actualFreezeAndCancelCannotReviveCapturedPermitAndSqlOrphanFailsFreshAdmission() = runBlocking<Unit> {
        val state = start()
        val publication = requireNotNull(publisher.capturePublication())
        val frame = mutablePreferencesOf().also(publication::stampFrame)
        val stages = NextReplicaStageStore(db, tokens, sessions)
        val stage = stages.begin(tokens.replicaStageFixture())
        val pending = withTimeout(10_000) { controller.state.first { it is ReplicaPresentationState.Pending } }
        assertNull(publisher.capturePublication())
        neutral(pending, publication, frame)
        neutral(state, publication, frame) // Delayed composition cannot use the old Open value.
        stages.cancel(stage)
        val fresh = open()
        neutral(fresh, publication, frame)
        assertTrue(labels(fresh, requireNotNull(publisher.capturePublication()), frame).contains("private cached habit"))
        NextReplicaWriteFence(db).acquire(tokens.replicaStageFixture().fence)
        assertNull(publisher.capturePublication())
        neutral(fresh, null, frame) // Fresh launcher admission checks actual SQL, not only a delayed Flow.
        val orphan = withTimeout(10_000) { controller.state.first { it is ReplicaPresentationState.Pending } }
        neutral(orphan, publication, frame)
    }

    @Test fun openDestinationEpochCannotInheritSourceCacheAndOnlyFullCurrentFrameRepairsIt() = runBlocking<Unit> {
        val state = start()
        val original = requireNotNull(publisher.capturePublication())
        val frame = mutablePreferencesOf().also(original::stampFrame)
        tokens.saveServerIdentity(id(2), id(66))
        val destination = requireNotNull(publisher.capturePublication())
        assertNotEquals(original.frameOwner, destination.frameOwner)
        neutral(state, original, frame)
        neutral(open(), destination, frame)
        destination.stampFrame(frame)
        assertTrue(labels(open(), destination, frame).contains("private cached habit"))
        assertTrue(theme is DeviceThemeLoadState.Ready)
    }
}
