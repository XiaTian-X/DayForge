package com.dayforge.domain.appearance

import com.dayforge.domain.model.ThemeDefinition
import com.dayforge.domain.model.ThemePalette
import com.dayforge.domain.model.ThemeRoles
import com.dayforge.domain.model.isContractName
import com.dayforge.domain.model.isRgbColor
import java.util.Collections

internal enum class ThemeColorGroup(val key: String, roles: Set<String>) {
    MATERIAL("material", ThemeRoles.material),
    STATUS("status", ThemeRoles.status),
    CHART("chart", ThemeRoles.chart);

    val roles: Set<String> = Collections.unmodifiableSet(roles.toSet())
}

/** Stable keys for small saved input values, not the source palette or catalog stored in a Bundle. */
internal data class ThemeColorField(val dark: Boolean, val group: ThemeColorGroup, val role: String) {
    init { require(role in group.roles) { "THEME_EDIT_FIELD" } }
    val key: String get() = "${if (dark) "dark" else "light"}.${group.key}.$role"

    companion object {
        val all: List<ThemeColorField> = Collections.unmodifiableList(
            listOf(false, true).flatMap { dark -> ThemeColorGroup.entries.flatMap { group ->
                group.roles.map { ThemeColorField(dark, group, it) }
            } }
        )
        private val byKey = all.associateBy { it.key }
        fun fromKey(key: String): ThemeColorField = byKey[key]
            ?: throw IllegalArgumentException("THEME_EDIT_FIELD")
    }
}

/**
 * An immutable edit session of all 104 saved colors. Invalid bounded input stays visible for
 * correction, but cannot produce a ThemeDefinition. No generator, storage or active selection.
 * Catalog protection and candidate identity reservation belong to the repository workflow.
 */
internal class ThemeEditDraft private constructor(
    val source: ThemeDefinition,
    val target: ThemeVersionRef,
    val name: String,
    val edits: Map<String, String>
) {
    val validName: Boolean get() = isContractName(name) && wellFormedUnicode(name)
    val valid: Boolean get() = validName && edits.values.all(::isRgbColor)
    val changed: Boolean get() = name != source.name || edits.any { (key, value) ->
        value != originalColor(ThemeColorField.fromKey(key))
    }

    fun originalColor(field: ThemeColorField): String {
        val palette = if (field.dark) source.dark else source.light
        return when (field.group) {
            ThemeColorGroup.MATERIAL -> palette.material
            ThemeColorGroup.STATUS -> palette.status
            ThemeColorGroup.CHART -> palette.chart
        }.getValue(field.role)
    }

    fun color(field: ThemeColorField): String = edits[field.key] ?: originalColor(field)

    fun withName(value: String): ThemeEditDraft {
        require(value.length <= NAME_INPUT_LIMIT) { "THEME_EDIT_INPUT_LIMIT" }
        return ThemeEditDraft(source, target, value, edits)
    }

    fun withColor(field: ThemeColorField, value: String): ThemeEditDraft {
        require(value.length <= COLOR_INPUT_LIMIT) { "THEME_EDIT_INPUT_LIMIT" }
        val next = if (value == originalColor(field)) edits - field.key else edits + (field.key to value)
        return ThemeEditDraft(source, target, name, Collections.unmodifiableMap(next))
    }

    /** Explicit reset removes only this input, not any other role or the saved source version. */
    fun reset(field: ThemeColorField): ThemeEditDraft =
        ThemeEditDraft(source, target, name, Collections.unmodifiableMap(edits - field.key))

    fun definition(): ThemeDefinition {
        require(valid) { "THEME_EDIT_INVALID" }
        fun palette(dark: Boolean): ThemePalette {
            fun colors(group: ThemeColorGroup) = Collections.unmodifiableMap(group.roles.associateWith { role ->
                color(ThemeColorField(dark, group, role))
            })
            return ThemePalette(colors(ThemeColorGroup.MATERIAL), colors(ThemeColorGroup.STATUS), colors(ThemeColorGroup.CHART))
        }
        return source.copy(themeId = target.themeId, revision = target.revision, name = name,
            light = palette(false), dark = palette(true))
    }

    companion object {
        // 80 Unicode code points may require 160 UTF-16 units; permit correction but bound Bundle input.
        const val NAME_INPUT_LIMIT = 160
        const val COLOR_INPUT_LIMIT = 16

        private fun wellFormedUnicode(value: String): Boolean {
            var index = 0
            while (index < value.length) {
                val character = value[index++]
                if (character.isHighSurrogate()) {
                    if (index == value.length || !value[index++].isLowSurrogate()) return false
                } else if (character.isLowSurrogate()) return false
            }
            return true
        }

        fun start(source: ThemeDefinition, target: ThemeVersionRef): ThemeEditDraft {
            require(if (target.themeId == source.themeId) target.revision > source.revision
                else target.revision == 1) { "THEME_EDIT_IDENTITY" }
            fun freeze(palette: ThemePalette) = ThemePalette(
                Collections.unmodifiableMap(palette.material.toMap()),
                Collections.unmodifiableMap(palette.status.toMap()),
                Collections.unmodifiableMap(palette.chart.toMap()))
            val frozen = source.copy(light = freeze(source.light), dark = freeze(source.dark))
            return ThemeEditDraft(frozen, target, frozen.name, emptyMap())
        }

        /** Revalidate restored keys/size/inputs; a future or corrupt draft never becomes defaults. */
        fun restore(source: ThemeDefinition, target: ThemeVersionRef, name: String,
            edits: Map<String, String>): ThemeEditDraft {
            require(edits.size <= ThemeColorField.all.size) { "THEME_EDIT_INPUT_LIMIT" }
            var draft = start(source, target).withName(name)
            for ((key, value) in edits) draft = draft.withColor(ThemeColorField.fromKey(key), value)
            return draft
        }
    }
}
