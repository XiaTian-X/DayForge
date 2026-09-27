package com.dayforge.widget.base

import androidx.glance.ImageProvider
import com.dayforge.data.appearance.IconRaster

/** Same immutable pixels as the app, already tinted; no MainActivity, I/O or second color filter. */
internal fun IconRaster.toGlanceImage(): ImageProvider = ImageProvider(bitmap)
