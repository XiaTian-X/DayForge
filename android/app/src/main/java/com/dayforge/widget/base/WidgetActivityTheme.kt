package com.dayforge.widget.base

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.dayforge.MainActivity
import com.dayforge.R
import com.dayforge.data.appearance.DeviceThemeLoadState
import com.dayforge.di.DeviceThemeControllerEntryPoint
import com.dayforge.ui.theme.DayForgeTheme
import com.dayforge.ui.theme.DayForgeWindow
import com.dayforge.ui.theme.ThemeLoadScreen
import com.dayforge.ui.theme.themeErrorMessage

/** Cold entrypoint uses the process controller, not MainActivity or a regenerated palette. */
@Composable
internal fun WidgetActivityTheme(activity: ComponentActivity, translucent: Boolean = true,
    content: @Composable () -> Unit) {
    val controller = remember(activity) { DeviceThemeControllerEntryPoint.from(activity).themeController() }
    val state by controller.state.collectAsState()
    WidgetActivityThemeSnapshot(activity, state, translucent, controller::retry, onManage = {
        activity.startActivity(Intent(activity, MainActivity::class.java))
        activity.finish()
    }, onClose = activity::finish, content = content)
}

/** Normal content is gated; recovery must never call a business confirmation/dismiss callback. */
@Composable
internal fun WidgetActivityThemeSnapshot(activity: ComponentActivity, state: DeviceThemeLoadState,
    translucent: Boolean, onRetry: () -> Unit, onManage: () -> Unit, onClose: () -> Unit,
    content: @Composable () -> Unit) {
    // Standalone pages previously used Material's default typography. Keep their geometry while
    // sharing the exact saved colors/card style, rather than adopting MainActivity's type scale.
    val typography = MaterialTheme.typography
    val loaded = (state as? DeviceThemeLoadState.Ready)?.theme
    if (loaded != null) DayForgeTheme(loaded, typography = typography) {
        WidgetActivityFrame(activity, translucent, content)
    } else MaterialTheme(typography = typography) {
        if (!translucent) WidgetActivityFrame(activity, false) {
            ThemeLoadScreen(state, onRetry, onManage)
        } else AlertDialog(
            onDismissRequest = onClose,
            title = { Text(stringResource(if (state is DeviceThemeLoadState.Failed)
                R.string.theme_load_failed else R.string.common_loading)) },
            text = {
                if (state is DeviceThemeLoadState.Failed) Text(themeErrorMessage(state.error))
                else CircularProgressIndicator()
            },
            confirmButton = {
                if (state is DeviceThemeLoadState.Failed) TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.action_retry))
                } else TextButton(onClick = onClose) { Text(stringResource(R.string.action_cancel)) }
            },
            dismissButton = {
                if (state is DeviceThemeLoadState.Failed) {
                    androidx.compose.foundation.layout.Column {
                        TextButton(onClick = onManage) { Text(stringResource(R.string.theme_recovery_title)) }
                        TextButton(onClick = onClose) { Text(stringResource(R.string.action_cancel)) }
                    }
                }
            }
        )
    }
}

@Composable
private fun WidgetActivityFrame(activity: ComponentActivity, translucent: Boolean,
    content: @Composable () -> Unit) {
    if (translucent) content()
    else DayForgeWindow(activity, navigationBarColor = MaterialTheme.colorScheme.background) { modifier ->
        Box(modifier) { content() }
    }
}
