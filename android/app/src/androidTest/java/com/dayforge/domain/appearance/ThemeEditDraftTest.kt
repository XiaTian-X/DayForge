package com.dayforge.domain.appearance

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.domain.model.ThemeDefinition
import com.dayforge.domain.model.ThemeRoles
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeEditDraftTest {
    private fun source(): ThemeDefinition = InstrumentationRegistry.getInstrumentation().context.assets
        .open("next/theme.json").bufferedReader().use { Json.decodeFromString(it.readText()) }
    private fun draft(source: ThemeDefinition = source()) =
        ThemeEditDraft.start(source, ThemeVersionRef(source.themeId, source.revision + 1))
    private fun rejected(code: String, action: () -> Unit) {
        try { action(); fail("expected $code") }
        catch (error: IllegalArgumentException) { assertEquals(code, error.message) }
    }

    @Test fun all104FieldsPreserveCompleteSavedValuesAndGeneratorMetadataWithoutExecution() {
        val original = source()
        val draft = draft(original)
        assertEquals(104, ThemeColorField.all.size)
        assertEquals(104, ThemeColorField.all.map { it.key }.toSet().size)
        for (field in ThemeColorField.all) {
            assertEquals(field, ThemeColorField.fromKey(field.key))
            assertEquals(draft.originalColor(field), draft.color(field))
        }
        assertFalse(draft.changed)
        val next = draft.definition()
        assertEquals(original.light, next.light); assertEquals(original.dark, next.dark)
        assertEquals(original.generatorId, next.generatorId); assertEquals(original.seed, next.seed)
        assertEquals(original.revision + 1, next.revision)
        assertEquals(ThemeRoles.material, next.light.material.keys)
        assertEquals(ThemeRoles.status, next.dark.status.keys)
        assertEquals(ThemeRoles.chart, next.dark.chart.keys)
    }

    @Test fun editingEachGroupAndSideIsIndependentAndNeverMutatesSourceOrEarlierDrafts() {
        val original = source()
        val initial = draft(original)
        var edited = initial.withName("Personal colors")
        for ((index, field) in ThemeColorField.all.withIndex()) {
            val color = "#${(index + 1).toString(16).padStart(6, '0')}"
            edited = edited.withColor(field, color)
            assertEquals(initial.originalColor(field), initial.color(field))
        }
        val output = edited.definition()
        val rendered = ThemeEditDraft.start(output, ThemeVersionRef(output.themeId, output.revision + 1))
        for ((index, field) in ThemeColorField.all.withIndex())
            assertEquals("#${(index + 1).toString(16).padStart(6, '0')}", rendered.color(field))
        assertEquals(original, initial.source); assertTrue(edited.changed)
        assertEquals(104, edited.edits.size)
    }

    @Test fun invalidRawInputIsVisibleAndCannotCreateACompleteThemeUntilCorrected() {
        val field = ThemeColorField(false, ThemeColorGroup.MATERIAL, "primary")
        for (value in listOf("", "#12345", "#AABBCCDD", " #AABBCC", "#GG0000", "#1234567")) {
            val invalid = draft().withColor(field, value)
            assertEquals(value, invalid.color(field)); assertFalse(invalid.valid)
            rejected("THEME_EDIT_INVALID") { invalid.definition() }
            assertTrue(invalid.withColor(field, "#aBc123").valid)
        }
        assertFalse(draft().withName("\nName").validName)
        assertFalse(draft().withName(" ").validName)
        assertFalse(draft().withName("a".repeat(81)).validName)
        for (name in listOf("\uD800", "\uDC00", "\uD800x", "x\uDC00")) {
            val invalid = draft().withName(name)
            assertFalse(invalid.validName)
            rejected("THEME_EDIT_INVALID") { invalid.definition() }
        }
        assertTrue(draft().withName("😀".repeat(80)).validName)
        rejected("THEME_EDIT_INPUT_LIMIT") { draft().withName("a".repeat(161)) }
        rejected("THEME_EDIT_INPUT_LIMIT") { draft().withColor(field, "a".repeat(17)) }
    }

    @Test fun resetRemovesOnlyOneEditAndRestoredInputsAreFrozenAndRevalidated() {
        val original = source()
        val one = ThemeColorField(false, ThemeColorGroup.STATUS, "success")
        val two = ThemeColorField(true, ThemeColorGroup.CHART, "selection")
        val inputs = mutableMapOf(one.key to "#012345", two.key to "#FEDCBA")
        val restored = ThemeEditDraft.restore(original, ThemeVersionRef(original.themeId, 2), "Restored", inputs)
        inputs[one.key] = "#FF0000"
        assertEquals("#012345", restored.color(one))
        val reset = restored.reset(one)
        assertEquals(restored.originalColor(one), reset.color(one))
        assertEquals("#FEDCBA", reset.color(two)); assertEquals("Restored", reset.name)
        assertEquals("#012345", restored.color(one))
        assertEquals(reset.edits, reset.withColor(one, reset.originalColor(one)).edits)
        rejected("THEME_EDIT_FIELD") { ThemeEditDraft.restore(original, ThemeVersionRef(original.themeId, 2),
            "Restored", mapOf("light.material.future_role" to "#012345")) }
        rejected("THEME_EDIT_FIELD") { ThemeColorField(false, ThemeColorGroup.STATUS, "error") }
    }

    @Test fun sourceAndProducedMapsRemainImmutableAfterCallerMutation() {
        val original = source()
        val caller = original.light.material.toMutableMap()
        val supplied = original.copy(light = original.light.copy(material = caller))
        val draft = draft(supplied)
        caller["primary"] = "broken"
        assertEquals(original.light.material["primary"], draft.source.light.material["primary"])
        for (map in listOf(draft.source.light.material, draft.definition().dark.status,
            draft.withColor(ThemeColorField(true, ThemeColorGroup.CHART, "grid"), "#000000").edits)) {
            try { (map as MutableMap<String, String>)[map.keys.first()] = "#123456"; fail("mutable saved draft") }
            catch (_: UnsupportedOperationException) { }
        }
    }

    @Test fun candidateIdentityMustBeNewRevisionOrNewIdentityAtVersionOne() {
        val source = source()
        rejected("THEME_EDIT_IDENTITY") { ThemeEditDraft.start(source, ThemeVersionRef(source.themeId, 1)) }
        rejected("THEME_EDIT_IDENTITY") { ThemeEditDraft.start(source,
            ThemeVersionRef("50000000-0000-0000-0000-000000000002", 2)) }
        val fork = ThemeEditDraft.start(source, ThemeVersionRef("50000000-0000-0000-0000-000000000002", 1))
        assertEquals(source.light, fork.definition().light)
        assertEquals(source.dark, fork.definition().dark)
        assertEquals(1, fork.definition().revision)
        // Workflows must reject exhausted revision rather than overflowing; no arithmetic here.
        val last = source.copy(revision = Int.MAX_VALUE)
        rejected("THEME_EDIT_IDENTITY") { ThemeEditDraft.start(last, ThemeVersionRef(last.themeId, Int.MAX_VALUE)) }
    }
}
