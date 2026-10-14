package com.dayforge.widget.base

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.key
import androidx.datastore.preferences.core.Preferences
import androidx.glance.currentState
import androidx.glance.GlanceTheme
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.text.Text
import com.dayforge.MainActivity
import com.dayforge.R
import com.dayforge.data.appearance.DeviceThemeLoadState
import com.dayforge.di.DeviceThemeControllerEntryPoint
import com.dayforge.data.repository.WidgetDisplayPublisher
import com.dayforge.domain.service.ReplicaPresentationState
import kotlinx.coroutines.CancellationException

/** Shared cold-start source even when no Activity has ever started. Collection performs no file IO. */
@Composable
internal fun DeviceWidgetTheme(context: Context, content: @Composable () -> Unit) {
    val entry = remember(context.applicationContext) { DeviceThemeControllerEntryPoint.from(context) }
    val controller = remember(entry) { entry.themeController() }
    val replica = remember(entry) { entry.replicaPresentation() }
    val publisher = remember(entry) { entry.widgetDisplayPublisher() }
    val themeState by controller.state.collectAsState()
    val replicaState by replica.state.collectAsState()
    val invalidation by publisher.invalidations.collectAsState()
    val preferences = currentState<Preferences>()
    // A Glance update may reuse its composition. Never reuse a captured scope across a new
    // frame, authentication invalidation or replica lifetime; SQL control is re-read first.
    key(replicaState, invalidation, preferences[WidgetDisplayPublisher.FRAME_OWNER_KEY]) {
        val publication by produceState<WidgetDisplayPublisher.Publication?>(null) {
            if ((replicaState as? ReplicaPresentationState.Open)?.permit?.canRender() == true) {
                value = try { publisher.capturePublication() } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    null // A failed control read never publishes cached personal content.
                }
            }
        }
        DeviceWidgetFrameSnapshot(context, replicaState, publication, preferences, themeState, content)
    }
}

/** Actual final Glance composition boundary, also used by one-shot RemoteViews tests. */
@Composable
internal fun DeviceWidgetFrameSnapshot(context: Context, replica: ReplicaPresentationState,
    publication: WidgetDisplayPublisher.Publication?, preferences: Preferences,
    theme: DeviceThemeLoadState, content: @Composable () -> Unit) {
    if (replica is ReplicaPresentationState.Open && replica.permit.canRender() &&
        publication?.isCurrentInMemory() == true && publication.ownsFrame(preferences)) {
        DeviceWidgetThemeSnapshot(context, theme, content)
    } else {
        Text(context.getString(R.string.replica_display_title),
            modifier = GlanceModifier.clickable(actionStartActivity(Intent(context, MainActivity::class.java))))
    }
}

/** Shared snapshot renderer. One-shot RemoteViews compositions must not own a never-ending collector. */
@Composable
internal fun DeviceWidgetThemeSnapshot(context: Context, state: DeviceThemeLoadState, content: @Composable () -> Unit) {
    val loaded = (state as? DeviceThemeLoadState.Ready)?.theme
    if (loaded != null) {
        val colors = remember(loaded) { loaded.toGlanceColors() }
        GlanceTheme(colors = colors, content = content)
    } else {
        Text(context.getString(if (state is DeviceThemeLoadState.Failed) R.string.theme_unavailable else R.string.theme_loading),
            modifier = GlanceModifier.clickable(actionStartActivity(Intent(context, MainActivity::class.java))))
    }
}
