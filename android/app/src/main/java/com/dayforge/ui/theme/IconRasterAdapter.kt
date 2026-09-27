package com.dayforge.ui.theme

import androidx.compose.ui.graphics.asImageBitmap
import com.dayforge.data.appearance.IconRaster

/** Borrowed immutable pixels. Do not recycle the backing bitmap while consumers retain it. */
internal fun IconRaster.toComposeImage() = bitmap.asImageBitmap()
