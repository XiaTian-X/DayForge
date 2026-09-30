package com.dayforge.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.dayforge.domain.appearance.AppearanceContrast
import com.dayforge.domain.appearance.ContrastUse

internal enum class SemanticStatus(val role: String) { SUCCESS("success"), WARNING("warning"), PENDING("pending"), ERROR("error") }
internal data class StatusAppearance(val main: Color, val onMain: Color, val icon: Color, val text: Color)

/** Saved semantic roles, display-only contrast. Does not classify business or sync states. */
@Composable
internal fun rememberStatusAppearance(status: SemanticStatus, background: Color, opaqueBackdrop: Color): StatusAppearance {
    val theme = LocalResolvedTheme.current
    val material = MaterialTheme.colorScheme
    return remember(theme, material, status, background, opaqueBackdrop) {
        val (main, onMain) = if (theme != null && status != SemanticStatus.ERROR) {
            theme.status.getValue(status.role) to theme.status.getValue("on_${status.role}")
        } else when (status) {
            SemanticStatus.SUCCESS -> material.primary.toArgb() to material.onPrimary.toArgb()
            SemanticStatus.WARNING, SemanticStatus.PENDING -> material.tertiary.toArgb() to material.onTertiary.toArgb()
            SemanticStatus.ERROR -> material.error.toArgb() to material.onError.toArgb()
        }
        val parent = AppearanceContrast.composite(background.toArgb(), opaqueBackdrop.toArgb())
        StatusAppearance(
            main = Color(main),
            onMain = Color(AppearanceContrast.resolve(onMain, main, parent, ContrastUse.TEXT).argb),
            icon = Color(AppearanceContrast.resolve(main, background.toArgb(), opaqueBackdrop.toArgb(), ContrastUse.LARGE_TEXT_OR_GRAPHIC).argb),
            text = Color(AppearanceContrast.resolve(main, background.toArgb(), opaqueBackdrop.toArgb(), ContrastUse.TEXT).argb)
        )
    }
}

@Composable
internal fun rememberReadableStatusIcon(preferred: Color, background: Color, opaqueBackdrop: Color): Color =
    remember(preferred, background, opaqueBackdrop) {
        Color(AppearanceContrast.resolve(preferred.toArgb(), background.toArgb(), opaqueBackdrop.toArgb(),
            ContrastUse.LARGE_TEXT_OR_GRAPHIC).argb)
    }
