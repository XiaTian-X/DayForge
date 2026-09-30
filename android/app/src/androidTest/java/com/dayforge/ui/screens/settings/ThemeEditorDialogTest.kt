package com.dayforge.ui.screens.settings

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.R
import com.dayforge.data.appearance.ValidatedTheme
import com.dayforge.domain.appearance.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeEditorDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val primary = ThemeColorField(false, ThemeColorGroup.MATERIAL, "primary")
    private fun initial(): ThemeEditorState {
        val source = runBlocking { ValidatedTheme.read {
            InstrumentationRegistry.getInstrumentation().context.assets.open("next/theme.json")
        } }.definition
        return ThemeEditorState(open = true, draft = ThemeEditDraft.start(source, ThemeVersionRef(source.themeId, 2)))
    }
    private fun scroll(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("theme-edit-fields").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }
    private fun field(field: ThemeColorField) = "theme-edit-color-${field.key}"

    @Test fun realTextInputsResetAndValidationKeepSaveGatingAndSourceUntouched() {
        val state = mutableStateOf(initial()); val source = state.value.draft!!.source
        var previews = 0
        compose.setContent { MaterialTheme { ThemeEditorDialog(state.value,
            { state.value = state.value.copy(draft = state.value.draft!!.withName(it)) },
            { key, value -> state.value = state.value.copy(draft = state.value.draft!!.withColor(key, value)) },
            { key -> state.value = state.value.copy(draft = state.value.draft!!.reset(key)) },
            { previews++ }, {}, {}, {}) } }
        scroll(field(primary)).performTextReplacement("#bad")
        compose.onNodeWithTag("theme-edit-save").assertIsNotEnabled()
        scroll(field(primary)).performTextReplacement("#123456")
        compose.waitUntil(5000) {
            val pixels = compose.onNodeWithTag(field(primary)).captureToImage().toPixelMap()
            var count = 0
            for (y in 0 until pixels.height) for (x in 0 until pixels.width)
                if (pixels[x, y].toArgb() == Color(0xff123456).toArgb()) count++
            count > 10
        }
        compose.onNodeWithTag("theme-edit-reset-${primary.key}").performClick()
        compose.runOnIdle { assertEquals(source.light.material["primary"], state.value.draft!!.color(primary)) }
        scroll("theme-edit-name").performTextReplacement("")
        compose.onNodeWithTag("theme-edit-save").assertIsNotEnabled()
        scroll("theme-edit-name").performTextReplacement("Personal")
        compose.onNodeWithTag("theme-edit-save").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, previews); assertEquals(source, state.value.draft!!.source) }
    }

    @Test fun paletteAndGroupSurviveUiRestorationAndChangedDraftNeedsExplicitDiscard() {
        val initial = initial()
        val state = initial.copy(draft = initial.draft!!.withName("Changed"))
        val restoration = StateRestorationTester(compose)
        var cancelled = 0
        restoration.setContent { MaterialTheme { ThemeEditorDialog(state, {}, { _, _ -> }, {}, {}, {}, {}, { cancelled++ }) } }
        scroll("theme-edit-side-dark").performClick()
        scroll("theme-edit-group-chart").performClick()
        val darkLine = ThemeColorField(true, ThemeColorGroup.CHART, "line")
        scroll(field(darkLine)).assertTextContains(state.draft!!.source.dark.chart.getValue("line"))
        restoration.emulateSavedInstanceStateRestore()
        scroll(field(darkLine)).assertIsDisplayed()
        compose.onNodeWithTag("theme-edit-close").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.theme_editor_discard)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.action_cancel)).performClick()
        compose.runOnIdle { assertEquals(0, cancelled) }
        compose.onNodeWithTag("theme-edit-close").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.action_confirm)).performClick()
        compose.runOnIdle { assertEquals(1, cancelled) }
    }

    @Test fun frozenPreviewIsReadOnlyAndConfirmationReturnsTheExactCandidate() {
        val initial = initial()
        val draft = initial.draft!!.withColor(primary, "#112233")
        val preview = runBlocking { ValidatedTheme.read { Json.encodeToString(draft.definition()).byteInputStream() } }
        var edited = 0
        var confirmed: ValidatedTheme? = null
        compose.setContent { MaterialTheme { ThemeEditorDialog(initial.copy(draft = draft, preview = preview, locked = true),
            { edited++ }, { _, _ -> edited++ }, { edited++ }, {}, { confirmed = it }, {}, {}) } }
        val input = scroll(field(primary)).assertTextContains("#112233")
        // Read-only controls may omit SetText or expose a rejecting action across locked Compose versions.
        if (input.fetchSemanticsNode().config.getOrNull(SemanticsActions.SetText) != null) {
            input.performSemanticsAction(SemanticsActions.SetText) { assertFalse(it(AnnotatedString("#000000"))) }
        }
        input.assertTextContains("#112233")
        compose.onNodeWithTag("theme-edit-reset-${primary.key}").assertDoesNotExist()
        compose.onNodeWithTag("theme-edit-back").assertDoesNotExist()
        compose.onNodeWithTag("theme-edit-save").performClick()
        compose.runOnIdle { assertEquals(0, edited); assertSame(preview, confirmed) }
    }
}
