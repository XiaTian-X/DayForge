package com.dayforge.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dayforge.R
import com.dayforge.data.appearance.BuiltInTheme
import com.dayforge.data.appearance.IconRasterSize
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.domain.appearance.ThemeVersionRef
import com.dayforge.domain.appearance.AppearanceContrast
import com.dayforge.domain.appearance.ContrastUse
import com.dayforge.domain.appearance.parseAppearanceColor
import com.dayforge.domain.model.ObjectAppearance
import com.dayforge.domain.service.AccountIconController
import com.dayforge.domain.service.IconImageState
import com.dayforge.ui.theme.LocalResolvedTheme
import com.dayforge.ui.theme.toComposeImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

/** The Activity supplies the process service; components never construct a repository or database. */
internal val LocalAccountIcons = staticCompositionLocalOf<AccountIconController?> { null }

@Composable
internal fun ObjectIcon(habit: HabitEntity, legacyTint: Color, size: Dp,
    background: Color = MaterialTheme.colorScheme.surface) = ObjectIcon(
    habit.uuid, habit.appearance, if (habit.habitType == com.dayforge.data.model.HabitType.GOAL) false else when (habit.completionPolicy) {
        "recurring" -> false
        "one_and_done" -> true
        else -> null
    }, habit.iconResId, legacyTint, size, background = background
)

@Composable
internal fun ObjectIcon(metric: MetricEntity, legacyTint: Color, size: Dp,
    background: Color = MaterialTheme.colorScheme.surface) =
    ObjectIcon(metric.uuid, metric.appearance, false, metric.iconResId, legacyTint, size, background = background)

/** Null is the still-active v4 row, not an inferred role or a fallback for a typed reference. */
@Composable
internal fun ObjectIcon(
    objectKey: String, appearance: ObjectAppearance?, oneTime: Boolean?, legacyIconId: Int,
    legacyTint: Color, size: Dp, modifier: Modifier = Modifier,
    background: Color = MaterialTheme.colorScheme.surface
) {
    if (appearance == null) {
        Icon(getIconForResId(legacyIconId), contentDescription = null,
            tint = legacyTint, modifier = modifier.size(size))
        return
    }
    val icons = LocalAccountIcons.current
    val resolved = LocalResolvedTheme.current
    val theme = ThemeVersionRef(resolved?.themeId ?: BuiltInTheme.OCEAN.themeId, resolved?.revision ?: 1)
    val dark = resolved?.dark ?: isSystemInDarkTheme()
    val pixels = with(LocalDensity.current) { size.roundToPx().coerceIn(1, 1024) }.let { IconRasterSize(it, it) }
    val preferred = if (appearance.iconTint == "object") parseAppearanceColor(appearance.accentColor)
        else MaterialTheme.colorScheme.primary.toArgb()
    val backdrop = AppearanceContrast.composite(MaterialTheme.colorScheme.background.toArgb(), 0xff000000.toInt())
    val tint = remember(preferred, background, backdrop) {
        AppearanceContrast.resolve(preferred, background.toArgb(), backdrop, ContrastUse.LARGE_TEXT_OR_GRAPHIC).argb
    }
    Box(modifier.size(size).testTag("object-icon:$objectKey")) {
        if (icons == null || oneTime == null) MissingObjectIcon(tint)
        else {
            val handle = remember(icons, objectKey, appearance, oneTime, theme, dark, pixels, tint) { icons.referenceImage() }
            DisposableEffect(handle) { onDispose { handle.close() } }
            LaunchedEffect(handle) {
                // Both requests and UI snapshots dispatch to Android Main, never eager IO continuations.
                withContext(Dispatchers.Main) {
                    handle.requests.collectLatest { request ->
                        if (request != null) icons.loadReference(handle, request, appearance.icon,
                            oneTime, theme, dark, pixels, tint)
                    }
                }
            }
            val image by handle.state.collectAsState(context = Dispatchers.Main)
            when (val current = image) {
                is IconImageState.Ready -> Image(current.raster.toComposeImage(), contentDescription = null,
                    modifier = Modifier.matchParentSize())
                else -> MissingObjectIcon(tint)
            }
        }
    }
}

/** A geometric marker: it never changes the saved reference or borrows a built-in content icon. */
@Composable
private fun BoxScope.MissingObjectIcon(tint: Int) {
    val description = stringResource(R.string.icon_library_image_failed)
    Canvas(Modifier.matchParentSize().semantics { contentDescription = description }) {
        drawCircle(Color(tint), radius = size.minDimension * 0.3f, style = Stroke(1.5.dp.toPx()))
    }
}
