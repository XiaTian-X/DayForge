package com.dayforge.data.appearance

import android.content.res.Configuration
import android.os.Looper
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.domain.appearance.AppearanceContrast
import com.dayforge.domain.appearance.ResolvedTheme
import com.dayforge.domain.model.ThemeRoles
import com.dayforge.testing.legacy.GlobalColorTheme
import com.dayforge.testing.legacy.ColorSchemeGenerator
import com.dayforge.ui.theme.SeedPaletteFixtures
import com.dayforge.ui.theme.toComposeColors
import com.dayforge.widget.base.toGlanceColors
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BuiltInThemesTest {
    private val app = InstrumentationRegistry.getInstrumentation()
    private val assets = app.targetContext.assets
    private val parent = Files.createTempDirectory(app.targetContext.filesDir.toPath(), "builtin-themes-").toFile()
    private fun loader() = BuiltInThemes(assets)
    private fun repository() = ThemeFileRepository(parent)
    private val seeds = listOf("FF1976D2", "FF4CAF50", "FFE91E63", "FF1A237E", "FF1B5E20", "FF880E4F")
    @After fun cleanup() { assertTrue(parent.deleteRecursively()) }

    @Test fun sevenPackagedThemesHaveStableIdentityCompletePalettesAndOriginalModeAvailability() = runBlocking<Unit> {
        val loaded = loader().readAll()
        assertEquals(listOf("Ocean", "Nature", "Vibrant", "Dusk", "Forest", "Coral", "OLED"), loaded.map { it.definition.name })
        assertEquals((1..7).map { "df000000-0000-4000-8000-" + it.toString().padStart(12, '0') }, loaded.map { it.definition.themeId })
        assertEquals(listOf("ocean", "nature", "vibrant"), BuiltInTheme.entries.filter { it.suitableForLight }.map { it.slug })
        assertEquals(listOf("dusk", "forest", "coral", "oled"), BuiltInTheme.entries.filter { it.suitableForDark }.map { it.slug })
        for (theme in loaded) {
            assertEquals(1, theme.definition.revision)
            for (palette in listOf(theme.definition.light, theme.definition.dark)) {
                assertEquals(ThemeRoles.material, palette.material.keys)
                assertEquals(ThemeRoles.status, palette.status.keys)
                assertEquals(ThemeRoles.chart, palette.chart.keys)
            }
        }
        assertThrows(UnsupportedOperationException::class.java) { (loaded as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (loaded[0].definition.light.material as MutableMap).clear() }
    }

    @Test fun allMaterialColorsMatchIndependentOldSeedAndOledBaselinesIncludingNineFormerDefaults() = runBlocking<Unit> {
        val fixtures = SeedPaletteFixtures.load()
        for ((index, theme) in loader().readAll().withIndex()) for (dark in listOf(false, true)) {
            val resolved = ResolvedTheme.from(theme.definition, dark)
            val colors = resolved.toComposeColors()
            val expected = if (index == 6) SeedPaletteFixtures.oledColors else
                fixtures.single { it.seed == seeds[index] && it.dark == dark }.colors
            assertEquals("theme $index dark=$dark", expected, SeedPaletteFixtures.colors(colors))
            val extra = if (dark || index == 6) listOf(
                0xff000000, 0xff3b383e, 0xff141218, 0xff211f26, 0xff2b2930, 0xff36343b, 0xff1d1b20, 0xff0f0d13
            ) else listOf(
                0xff000000, 0xfffef7ff, 0xffded8e1, 0xfff3edf7, 0xffece6f0, 0xffe6e0e9, 0xfff7f2fa, 0xffffffff
            )
            assertEquals(listOf(expected.first()) + extra.map { it.toInt() }, extraColors(colors))
            val legacy = GlobalColorTheme(id = BuiltInTheme.entries[index].slug, name = theme.definition.name,
                seedColor = if (index == 6) "#000000" else "#" + seeds[index].drop(2),
                suitableForLight = true, suitableForDark = true, isDefault = true)
            val prior = if (dark) ColorSchemeGenerator.generateDarkColorScheme(legacy)
                else ColorSchemeGenerator.generateLightColorScheme(legacy)
            assertEquals(expected, SeedPaletteFixtures.colors(prior))
            assertEquals(listOf(expected.first()) + extra.map { it.toInt() }, extraColors(prior))
            assertEquals(resolved.material.getValue("primary"), colors.primary.toArgb())
            for (night in listOf(false, true)) {
                val base = app.targetContext
                val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                })
                assertEquals(expected.first(), resolved.toGlanceColors().primary.getColor(context).toArgb())
                assertEquals(colors.surface.toArgb(), resolved.toGlanceColors().widgetBackground.getColor(context).toArgb())
            }
        }
    }

    private fun extraColors(scheme: ColorScheme) = with(scheme) {
        listOf(surfaceTint, scrim, surfaceBright, surfaceDim, surfaceContainer, surfaceContainerHigh,
            surfaceContainerHighest, surfaceContainerLow, surfaceContainerLowest).map { it.toArgb() }
    }

    @Test fun statusAndChartRolesAreExplicitAndTextPairsHaveAdequateContrast() = runBlocking<Unit> {
        val light = listOf(0xff146c2e, 0xffffffff, 0xffa0f6ac, 0xff002109, 0xff795900, 0xffffffff, 0xffffdf9b, 0xff261900)
        val dark = listOf(0xff85d992, 0xff003914, 0xff005321, 0xffa0f6ac, 0xfff4bf48, 0xff402d00, 0xff5c4300, 0xffffdf9b)
        val names = listOf("success", "on_success", "success_container", "on_success_container", "warning", "on_warning", "warning_container", "on_warning_container")
        for (theme in loader().readAll()) for (night in listOf(false, true)) {
            val resolved = ResolvedTheme.from(theme.definition, night)
            assertEquals((if (night) dark else light).map { it.toInt() }, names.map { resolved.status.getValue(it) })
            val status = resolved.status; val material = resolved.material
            for ((statusName, materialName) in listOf("pending" to "primary", "on_pending" to "on_primary",
                "pending_container" to "primary_container", "on_pending_container" to "on_primary_container")) {
                assertEquals(material.getValue(materialName), status.getValue(statusName))
            }
            for (role in listOf("success", "success_container", "warning", "warning_container", "pending", "pending_container")) {
                assertTrue("${theme.definition.name} $night $role", AppearanceContrast.ratio(status.getValue("on_$role"), status.getValue(role)) >= 4.5)
            }
            assertEquals(mapOf("line" to material.getValue("primary"), "target" to material.getValue("tertiary"),
                "grid" to material.getValue("outline_variant"), "selection" to status.getValue("success")), resolved.chart)
        }
    }

    @Test fun packagedInputIsOpenedOnceOffMainAndFullyClosedBeforeAnyInstallation() = runBlocking<Unit> {
        val opened = mutableListOf<String>(); var closed = 0
        val loaded = BuiltInThemes { path ->
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper()); opened.add(path)
            object : ByteArrayInputStream(assets.open(path).use { it.readBytes() }) {
                override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(3, len))
                override fun close() { closed++; super.close() }
            }
        }.readAll()
        assertEquals(BuiltInTheme.entries.map { it.assetPath }, opened); assertEquals(7, closed)
        assertFalse(File(parent, "theme-definitions-v1").exists())
        for ((index, theme) in loaded.withIndex()) {
            assertArrayEquals(assets.open(BuiltInTheme.entries[index].assetPath).use { it.readBytes() }, theme.exportBytes())
        }
    }

    @Test fun malformedMissingOrMismatchedPackagedInputDoesNotInstallAPartialSet() = runBlocking<Unit> {
        val entry = BuiltInTheme.OLED
        val original = assets.open(entry.assetPath).bufferedReader().use { it.readText() }
        for (bad in listOf("{}", original.replace(entry.themeId, BuiltInTheme.OCEAN.themeId),
            original.replace("\"revision\": 1", "\"revision\": 2"), original.replace("\"OLED\"", "\"Other\""))) {
            val loader = BuiltInThemes { path ->
                if (path == entry.assetPath) bad.byteInputStream() else assets.open(path)
            }
            assertThrows(ThemeInputException::class.java) { runBlocking { loader.installAll(repository()) } }
            assertFalse(File(parent, "theme-definitions-v1").exists())
        }
        val missing = IOException("synthetic missing asset")
        val loader = BuiltInThemes { path -> if (path == entry.assetPath) throw missing else assets.open(path) }
        assertSame(missing, assertThrows(IOException::class.java) { runBlocking { loader.installAll(repository()) } })
        assertFalse(File(parent, "theme-definitions-v1").exists())
    }

    @Test fun installedFilesSurviveReopenAndConcurrentRetriesWithoutChangingLegacyFiles() = runBlocking<Unit> {
        val legacy = File(parent, "themes").also { check(it.mkdir()) }
        val old = File(legacy, "old.json").also { it.writeText("synthetic legacy") }
        val choices = File(parent, "synthetic-preferences").also { it.writeText("unchanged") }
        (1..4).map { async { loader().installAll(repository()) } }.awaitAll()
        assertEquals(7, checkNotNull(File(parent, "theme-definitions-v1").listFiles()).size)
        for (entry in BuiltInTheme.entries) {
            assertArrayEquals(assets.open(entry.assetPath).use { it.readBytes() }, repository().read(entry.themeId, 1).exportBytes())
        }
        assertEquals("synthetic legacy", old.readText()); assertEquals("unchanged", choices.readText())
    }

    @Test fun fileWriteFailureKeepsAlreadyInstalledVersionsAndRetryCompletesTheSet() = runBlocking<Unit> {
        var writes = 0
        val failure = IOException("synthetic third theme write failure")
        val faulty = ThemeFileRepository(parent, object : ThemeFileIo() {
            override fun write(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int): Int {
                writes++
                if (writes == 3) throw failure
                return super.write(fd, bytes, offset, length)
            }
        })
        assertSame(failure, assertThrows(IOException::class.java) { runBlocking { loader().installAll(faulty) } })
        val root = File(parent, "theme-definitions-v1")
        assertEquals(BuiltInTheme.entries.take(2).map { "${it.themeId}-1.json" }.toSet(), root.list()!!.toSet())
        val first = repository().read(BuiltInTheme.OCEAN.themeId, 1).exportBytes()
        assertEquals(7, loader().installAll(repository()).size)
        assertEquals(7, root.list()!!.size)
        assertArrayEquals(first, repository().read(BuiltInTheme.OCEAN.themeId, 1).exportBytes())
    }

    @Test fun conflictingExistingVersionCannotBeOverwrittenByBuiltinInitialization() = runBlocking<Unit> {
        val entry = BuiltInTheme.OCEAN
        val modified = assets.open(entry.assetPath).bufferedReader().use { it.readText() }.replace("#005FAF", "#FF0000")
        repository().install(ValidatedTheme.parse(modified.toByteArray()))
        val error = assertThrows(ThemeInputException::class.java) { runBlocking { loader().installAll(repository()) } }
        assertEquals("THEME_VERSION_REUSED", error.code)
        assertArrayEquals(modified.toByteArray(), repository().read(entry.themeId, 1).exportBytes())
        assertEquals(1, File(parent, "theme-definitions-v1").list()!!.size)
    }

    @Test fun cancellationDuringPreviewClosesSourceAndDoesNotInstallAnyFiles() = runBlocking<Unit> {
        lateinit var caller: Job
        var opened = 0; var closed = 0
        val loader = BuiltInThemes { path ->
            opened++
            if (opened == 3) caller.cancel()
            object : ByteArrayInputStream(assets.open(path).use { it.readBytes() }) {
                override fun close() { closed++; super.close() }
            }
        }
        caller = launch(start = CoroutineStart.LAZY) { loader.installAll(repository()) }
        caller.start(); caller.join()
        assertTrue(caller.isCancelled); assertEquals(3, opened); assertEquals(3, closed)
        assertFalse(File(parent, "theme-definitions-v1").exists())
        assertEquals(7, loader().installAll(repository()).size)
    }
}
