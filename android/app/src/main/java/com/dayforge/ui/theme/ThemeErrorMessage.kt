package com.dayforge.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.dayforge.R

/** Display a stable diagnostic code, not arbitrary provider messages or private file paths. */
@Composable
internal fun themeErrorMessage(error: Throwable?): String {
    val code = error?.message?.takeIf { it.matches(Regex("THEME_[A-Z_]+")) }
    val resource = when (code) {
        "THEME_BUILTIN_RESERVED" -> R.string.theme_error_cannot_delete_preset
        "THEME_VERSION", "THEME_SELECTION_VERSION" -> R.string.theme_error_version
        "THEME_IN_USE" -> R.string.theme_error_in_use
        "THEME_CATALOG_CONFLICT", "THEME_SELECTION_CONFLICT" -> R.string.theme_error_changed
        "THEME_LIMIT", "THEME_CATALOG_LIMIT", "THEME_STORAGE_LIMIT" -> R.string.theme_error_limit
        "THEME_DOCUMENT_TIMEOUT" -> R.string.theme_error_timeout
        else -> R.string.theme_error_operation
    }
    val message = stringResource(resource)
    return if (code == null) message else "$message ($code)"
}
