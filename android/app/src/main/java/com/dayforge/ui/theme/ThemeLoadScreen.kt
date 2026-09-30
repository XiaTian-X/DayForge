package com.dayforge.ui.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dayforge.R
import com.dayforge.data.appearance.DeviceThemeLoadState

/** Neutral loading/error chrome only: never render the normal app with a fabricated saved palette. */
@Composable
internal fun ThemeLoadScreen(state: DeviceThemeLoadState, onRetry: () -> Unit, onManage: () -> Unit) {
    MaterialTheme(typography = Typography) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                if (state is DeviceThemeLoadState.Failed) {
                    Text(stringResource(R.string.theme_load_failed))
                    Text(themeErrorMessage(state.error), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                    TextButton(onClick = onManage) { Text(stringResource(R.string.theme_recovery_title)) }
                } else CircularProgressIndicator()
            }
        }
    }
}
