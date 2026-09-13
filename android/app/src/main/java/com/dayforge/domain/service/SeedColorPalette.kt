package com.dayforge.domain.service

import me.tatarka.google.material.hct.Hct
import me.tatarka.google.material.palettes.TonalPalette

/**
 * DayForge's fixed seed-color design policy, shared by rendering and reference export.
 * HCT conversion stays in the color library; brand chroma and role tones are explicit here.
 * Changing these values is a visual design change, not an incidental library migration.
 */
internal class SeedColorPalette(seedArgb: Int, private val dark: Boolean = false) {
    private val source = Hct.fromInt(seedArgb)
    private val palettes = mapOf(
        ToneFamily.PRIMARY to TonalPalette.fromHueAndChroma(source.hue, maxOf(48.0, source.chroma)),
        ToneFamily.SECONDARY to TonalPalette.fromHueAndChroma(source.hue, 16.0),
        ToneFamily.TERTIARY to TonalPalette.fromHueAndChroma(source.hue + 60.0, 24.0),
        ToneFamily.NEUTRAL to TonalPalette.fromHueAndChroma(source.hue, 4.0),
        ToneFamily.NEUTRAL_VARIANT to TonalPalette.fromHueAndChroma(source.hue, 8.0),
        ToneFamily.ERROR to TonalPalette.fromHueAndChroma(25.0, 84.0)
    )

    operator fun get(role: SeedColorRole): Int = palettes.getValue(role.family)
        .tone(if (dark) role.darkTone else role.lightTone)
}

internal enum class ToneFamily { PRIMARY, SECONDARY, TERTIARY, NEUTRAL, NEUTRAL_VARIANT, ERROR }

/** The 27 roles already exposed by GlobalColorTheme; additional Material3 roles retain its defaults. */
internal enum class SeedColorRole(val family: ToneFamily, val lightTone: Int, val darkTone: Int) {
    PRIMARY(ToneFamily.PRIMARY, 40, 80),
    ON_PRIMARY(ToneFamily.PRIMARY, 100, 20),
    PRIMARY_CONTAINER(ToneFamily.PRIMARY, 90, 30),
    ON_PRIMARY_CONTAINER(ToneFamily.PRIMARY, 10, 90),
    INVERSE_PRIMARY(ToneFamily.PRIMARY, 80, 40),
    SECONDARY(ToneFamily.SECONDARY, 40, 80),
    ON_SECONDARY(ToneFamily.SECONDARY, 100, 20),
    SECONDARY_CONTAINER(ToneFamily.SECONDARY, 90, 30),
    ON_SECONDARY_CONTAINER(ToneFamily.SECONDARY, 10, 90),
    TERTIARY(ToneFamily.TERTIARY, 40, 80),
    ON_TERTIARY(ToneFamily.TERTIARY, 100, 20),
    TERTIARY_CONTAINER(ToneFamily.TERTIARY, 90, 30),
    ON_TERTIARY_CONTAINER(ToneFamily.TERTIARY, 10, 90),
    ERROR(ToneFamily.ERROR, 40, 80),
    ON_ERROR(ToneFamily.ERROR, 100, 20),
    ERROR_CONTAINER(ToneFamily.ERROR, 90, 30),
    ON_ERROR_CONTAINER(ToneFamily.ERROR, 10, 80),
    BACKGROUND(ToneFamily.NEUTRAL, 99, 10),
    ON_BACKGROUND(ToneFamily.NEUTRAL, 10, 90),
    SURFACE(ToneFamily.NEUTRAL, 99, 10),
    ON_SURFACE(ToneFamily.NEUTRAL, 10, 90),
    SURFACE_VARIANT(ToneFamily.NEUTRAL_VARIANT, 90, 30),
    ON_SURFACE_VARIANT(ToneFamily.NEUTRAL_VARIANT, 30, 80),
    OUTLINE(ToneFamily.NEUTRAL_VARIANT, 50, 60),
    OUTLINE_VARIANT(ToneFamily.NEUTRAL_VARIANT, 80, 30),
    INVERSE_SURFACE(ToneFamily.NEUTRAL, 20, 90),
    INVERSE_ON_SURFACE(ToneFamily.NEUTRAL, 95, 20)
}
