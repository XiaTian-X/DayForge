package com.dayforge.ui.screens.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.appearance.*
import com.dayforge.domain.appearance.ThemeVersionRef
import com.dayforge.domain.model.ThemeDefinition
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeIdentityLabelsTest {
    private fun source() = runBlocking { ValidatedTheme.read {
        InstrumentationRegistry.getInstrumentation().context.assets.open("next/theme.json")
    } }.definition
    private fun choice(source: ThemeDefinition, id: String, revision: Int, builtin: BuiltInTheme? = null): ThemeChoiceSummary {
        val definition = source.copy(themeId = id, revision = revision, name = "Same name")
        return ThemeChoiceSummary(ThemeCatalogItem(ThemeCatalogSlot(ThemeVersionRef(id, revision),
            "0".repeat(64), id, ThemeInstallPhase.ACTIVE), builtin, ThemeCatalogContent.Available(definition)), 1)
    }

    @Test fun suffixesReuseLineageAcrossVersionsAndIgnoreNamesAndCatalogOrder() {
        val source = source()
        val one = "11111111-1111-4111-8111-000000000001"
        val two = "22222222-2222-4222-8222-000000000002"
        val choices = listOf(choice(source, one, 1), choice(source, one, 2), choice(source, two, 1),
            choice(source, BuiltInTheme.OCEAN.themeId, 1, BuiltInTheme.OCEAN))
        val expected = mapOf(one to "00000001", two to "00000002")
        assertEquals(expected, themeIdentitySuffixes(choices))
        assertEquals(expected, themeIdentitySuffixes(choices.reversed()))
        assertTrue(themeIdentitySuffixes(emptyList()).isEmpty())
        assertEquals(listOf(1, 2, 1, 1), choices.map { it.ref.revision })
        assertEquals(one, choices[1].ref.themeId)
    }

    @Test fun collisionsExpandThroughFullIdentityAtTheCustomCatalogLimit() {
        val source = source()
        val choices = (1..128).map { index ->
            // Keep the last 28 characters equal: only the first four vary.
            choice(source, "${index.toString(16).padStart(4, '0')}0000-0000-4000-8000-0123456789ab", 1)
        }
        val labels = themeIdentitySuffixes(choices)
        assertEquals(128, labels.size)
        assertEquals(128, labels.values.toSet().size)
        for (choice in choices) assertEquals(choice.ref.themeId.replace("-", ""), labels[choice.ref.themeId])
        assertEquals(labels, themeIdentitySuffixes(choices.reversed()))
    }
}
