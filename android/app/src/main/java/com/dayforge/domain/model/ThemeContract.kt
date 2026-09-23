@file:kotlinx.serialization.UseSerializers(ContractStringSerializer::class)

package com.dayforge.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Stable file roles, not an enumeration of whatever the current UI library defaults happen to be. */
object ThemeRoles {
    val material: Set<String> = setOf(
        "primary",
        "on_primary",
        "primary_container",
        "on_primary_container",
        "inverse_primary",
        "secondary",
        "on_secondary",
        "secondary_container",
        "on_secondary_container",
        "tertiary",
        "on_tertiary",
        "tertiary_container",
        "on_tertiary_container",
        "background",
        "on_background",
        "surface",
        "on_surface",
        "surface_variant",
        "on_surface_variant",
        "surface_tint",
        "inverse_surface",
        "inverse_on_surface",
        "error",
        "on_error",
        "error_container",
        "on_error_container",
        "outline",
        "outline_variant",
        "scrim",
        "surface_bright",
        "surface_dim",
        "surface_container",
        "surface_container_high",
        "surface_container_highest",
        "surface_container_low",
        "surface_container_lowest"
    )
    val status: Set<String> = setOf(
        "success",
        "on_success",
        "success_container",
        "on_success_container",
        "warning",
        "on_warning",
        "warning_container",
        "on_warning_container",
        "pending",
        "on_pending",
        "pending_container",
        "on_pending_container"
    )
    val chart: Set<String> = setOf(
        "line",
        "target",
        "grid",
        "selection"
    )
}

@Serializable
data class ThemePalette(
    val material: Map<String, String>,
    val status: Map<String, String>,
    val chart: Map<String, String>
) {
    init {
        require(material.keys == ThemeRoles.material)
        require(status.keys == ThemeRoles.status)
        require(chart.keys == ThemeRoles.chart)
        require((material.values + status.values + chart.values).all(::isRgbColor))
    }
}

/** A saved theme is a complete palette snapshot; generator metadata never executes imported code. */
@Serializable
data class ThemeDefinition(
    val format: String,
    @Serializable(with = ContractIntegerSerializer::class)
    @SerialName("format_version") val formatVersion: Int,
    @SerialName("theme_id") val themeId: String,
    @Serializable(with = ContractIntegerSerializer::class)
    val revision: Int,
    val name: String,
    @SerialName("generator_id") val generatorId: String?,
    val seed: String?,
    val light: ThemePalette,
    val dark: ThemePalette
) {
    init {
        require(format == "dayforge.theme" && formatVersion == 1)
        require(isContractUuid(themeId) && revision > 0 && isContractName(name))
        require((generatorId == null) == (seed == null))
        require(generatorId == null || Regex("[a-z][a-z0-9.-]{0,63}").matches(generatorId))
        require(seed == null || isRgbColor(seed))
    }
}
