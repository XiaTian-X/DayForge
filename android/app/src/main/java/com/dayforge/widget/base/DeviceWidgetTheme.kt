package com.dayforge.widget.base

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.glance.GlanceTheme
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.text.Text
import com.dayforge.MainActivity
import com.dayforge.R
import com.dayforge.data.appearance.DeviceThemeLoadState
import com.dayforge.di.DeviceThemeControllerEntryPoint

/** Shared cold-start source even when no Activity has ever started. Collection performs no file IO. */
@Composable
internal fun DeviceWidgetTheme(context: Context, content: @Composable () -> Unit) {
    val controller = remember(context.applicationContext) { DeviceThemeControllerEntryPoint.from(context).themeController() }
    val state by controller.state.collectAsState()
    DeviceWidgetThemeSnapshot(context, state, content)
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
