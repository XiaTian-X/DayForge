package com.dayforge.domain.model

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeConfigContractTest {
    // Default Json deliberately: nested schedule type and icon kind must be self-describing.
    private val json = Json

    @Test
    fun completeThemeRoundTripsWithoutRegeneratingColors() {
        val source = fixture("theme")
        val theme = json.decodeFromJsonElement<ThemeDefinition>(source)
        assertEquals(source, json.encodeToJsonElement(theme))
        assertEquals(36, theme.light.material.size)
        assertEquals(36, theme.dark.material.size)
        assertEquals(12, theme.light.status.size)
        assertEquals(4, theme.dark.chart.size)
        assertEquals("#E5E3E7", theme.light.material["surface_container_highest"])
        assertEquals("#343438", theme.dark.material["surface_container_highest"])
        assertEquals("#AFC6FF", theme.dark.chart["line"])
        val stored = theme.copy(generatorId = "unknown-future-generator-v99", seed = "#123456")
        assertEquals(theme.light, stored.light)
        assertEquals(theme.dark, stored.dark)
    }

    @Test
    fun configKeepsEveryModeScheduleLinkAndExplicitMissingRole() {
        val source = fixture("config")
        val config = json.decodeFromJsonElement<ConfigBundle>(source)
        assertEquals(source, json.encodeToJsonElement(config))
        assertEquals(7, config.nodes.size)
        val activities = config.nodes.mapNotNull { it.activity }
        assertEquals(listOf("check", "count", "count", "duration", "duration", "check"), activities.map { it.trackingMode })
        assertEquals(listOf(false, false, true, false, true, false), activities.map { it.isCountdown })
        assertTrue(activities[0].schedule is ConfigSchedule.Daily)
        assertTrue(activities[1].schedule is ConfigSchedule.Weekly)
        assertTrue(activities[2].schedule is ConfigSchedule.Interval)
        assertTrue(activities[3].schedule is ConfigSchedule.Monthly)
        assertTrue(activities[5].schedule is ConfigSchedule.Once)
        assertEquals("one_and_done", activities.last().completionPolicy)
        assertEquals("#802196F3", config.nodes[2].appearance.accentColor)
        assertEquals("by_time", config.metrics.single().aggregationType)
        assertTrue(config.links.single().promptOnComplete)
        assertEquals(listOf("goal.default", "metric.weight"), config.unresolvedRoles)
    }

    @Test
    fun sharedInvalidThemeAndConfigCasesAreAllRejected() {
        val cases = fixture("theme-config-invalid").jsonArray
        assertEquals(61, cases.size)
        cases.forEach { raw ->
            val case = raw.jsonObject
            val base = case.getValue("base").jsonPrimitive.content
            val changed = replace(fixture(base), case.getValue("path").jsonArray, case.getValue("value"))
            val failure = runCatching {
                when (base) {
                    "theme" -> json.decodeFromJsonElement<ThemeDefinition>(changed)
                    "config" -> json.decodeFromJsonElement<ConfigBundle>(changed)
                    "icon-pack" -> json.decodeFromJsonElement<IconPack>(changed)
                    else -> error("Unconsumed fixture base")
                }
            }.exceptionOrNull()
            assertTrue(case.getValue("name").toString(), failure is IllegalArgumentException)
        }
    }

    @Test
    fun emptyConfigRequiresEveryExplicitFieldAndNoThemeDefaultsLeakIn() {
        val config = ConfigBundle("dayforge.config", 2, emptyList(), emptyList(), emptyList(), null, emptyList(), emptyList())
        val tree = json.encodeToJsonElement(config).jsonObject
        assertEquals(config, json.decodeFromJsonElement<ConfigBundle>(tree))
        tree.keys.forEach { key ->
            assertThrows(key, IllegalArgumentException::class.java) {
                json.decodeFromJsonElement<ConfigBundle>(JsonObject(tree - key))
            }
        }
        val theme = json.decodeFromJsonElement<ThemeDefinition>(fixture("theme"))
        assertThrows(IllegalArgumentException::class.java) {
            theme.light.copy(material = theme.light.material - "surface_dim")
        }
    }

    @Test
    fun duplicateReferencesAndCollectionBudgetsAreRejected() {
        val base = json.decodeFromJsonElement<ConfigBundle>(fixture("config"))
        assertThrows(IllegalArgumentException::class.java) {
            base.copy(links = base.links + base.links[0].copy(key = "another_link"))
        }
        assertThrows(IllegalArgumentException::class.java) { base.copy(themes = base.themes + base.themes) }
        assertThrows(IllegalArgumentException::class.java) { base.copy(nodes = List(1001) { base.nodes[0] }) }
        assertThrows(IllegalArgumentException::class.java) { base.copy(metrics = List(1001) { base.metrics[0] }) }
        assertThrows(IllegalArgumentException::class.java) { base.copy(links = List(5001) { base.links[0] }) }
        assertThrows(IllegalArgumentException::class.java) { base.copy(themes = List(17) { base.themes[0] }) }
    }

    @Test
    fun iconDiscriminatorIsStableOutsideTheOriginalFixtureJsonConfiguration() {
        val refs = fixture("icon-references").jsonArray
        refs.forEach {
            val icon = it.jsonObject.getValue("icon")
            assertEquals(icon, json.encodeToJsonElement(json.decodeFromJsonElement<IconReference>(icon)))
        }
    }

    private fun replace(root: JsonElement, path: List<JsonElement>, value: JsonElement): JsonElement {
        if (path.isEmpty()) return value
        val rest = path.drop(1)
        return when (root) {
            is JsonObject -> {
                val key = path.first().jsonPrimitive.content
                JsonObject(root + (key to replace(root[key] ?: JsonNull, rest, value)))
            }
            is JsonArray -> JsonArray(root.mapIndexed { index, child ->
                if (index == path.first().jsonPrimitive.int) replace(child, rest, value) else child
            })
            else -> error("Invalid fixture path")
        }
    }

    private fun fixture(name: String): JsonElement = InstrumentationRegistry.getInstrumentation()
        .context.assets.open("next/$name.json").bufferedReader().use { json.parseToJsonElement(it.readText()) }
}
