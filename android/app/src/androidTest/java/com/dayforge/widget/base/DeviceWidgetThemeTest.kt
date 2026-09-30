package com.dayforge.widget.base

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.background
import androidx.glance.layout.Box
import androidx.glance.layout.size
import androidx.glance.text.Text
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.R
import com.dayforge.data.appearance.BuiltInTheme
import com.dayforge.data.appearance.BuiltInThemes
import com.dayforge.data.appearance.DeviceThemeLoadState
import com.dayforge.data.appearance.DeviceThemeRepository
import com.dayforge.data.appearance.ThemeFileRepository
import com.dayforge.domain.appearance.DeviceCardStyle
import com.dayforge.domain.appearance.DeviceThemeMode
import com.dayforge.domain.appearance.ThemeVersionRef
import com.dayforge.domain.service.DeviceThemeController
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@RunWith(AndroidJUnit4::class)
class DeviceWidgetThemeTest {
    private val app = InstrumentationRegistry.getInstrumentation()
    private val context = app.targetContext
    private val directory = Files.createTempDirectory(context.filesDir.toPath(), "widget-theme-").toFile()
    private val dataScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preferences = PreferenceDataStoreFactory.create(scope = dataScope) { File(directory, "prefs.preferences_pb") }
    private val repository = DeviceThemeRepository(preferences, ThemeFileRepository(directory), BuiltInThemes(context.assets))
    private val controller = DeviceThemeController(repository, CoroutineScope(SupervisorJob() + Dispatchers.IO))
    private fun configured(dark: Boolean): Context = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    })
    private suspend fun mode(mode: DeviceThemeMode, style: DeviceCardStyle = DeviceCardStyle.FOLLOW_THEME) {
        val current = withTimeout(5000) { controller.current() }.saved
        val result = controller.select(current.revision, current.selection.copy(mode = mode, cardStyle = style,
            dark = ThemeVersionRef(BuiltInTheme.OLED.themeId, 1)))
        withTimeout(5000) { controller.state.first { it is DeviceThemeLoadState.Ready && it.theme.saved == result.saved } }
    }
    private suspend fun background(context: Context): Int {
        val views = withTimeout(5000) { GlanceRemoteViews().compose(context, DpSize(32.dp, 32.dp)) {
            DeviceWidgetThemeSnapshot(context, controller.state.value) {
                Box(GlanceModifier.size(32.dp).background(GlanceTheme.colors.background)) {}
            }
        }.remoteViews }
        var result = 0
        app.runOnMainSync {
            val root = views.apply(context, FrameLayout(context))
            val px = (32 * context.resources.displayMetrics.density).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, px, px)
            val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            try { root.draw(Canvas(bitmap)); result = bitmap.getPixel(px / 2, px / 2) }
            finally { bitmap.recycle() }
        }
        return result
    }
    @After fun finish() = runBlocking<Unit> {
        controller.close()
        dataScope.coroutineContext.job.cancelAndJoin()
        assertTrue(directory.deleteRecursively())
    }

    @Test fun coldControllerRendersActualRemoteViewsWithExplicitModesAndSystemPairWithoutActivity() = runBlocking<Unit> {
        // Real storage/controller initialization, not MainActivity or synthetic colors.
        mode(DeviceThemeMode.LIGHT)
        for (night in listOf(false, true)) assertEquals(0xfffdfcff.toInt(), background(configured(night)))
        mode(DeviceThemeMode.DARK)
        for (night in listOf(false, true)) assertEquals(0xff000000.toInt(), background(configured(night)))
        mode(DeviceThemeMode.SYSTEM)
        assertEquals(0xfffdfcff.toInt(), background(configured(false)))
        assertEquals(0xff000000.toInt(), background(configured(true)))
    }

    @Test fun actualWidgetCardResolverPreservesPersonalizedColorsAndUsesCommittedSelection() = runBlocking<Unit> {
        mode(DeviceThemeMode.DARK)
        val resolver = WidgetColorResolver(configured(false), controller)
        val themed = resolver.resolveWidgetColors("#FF123456")
        assertEquals(0xff004d40.toInt(), themed.backgroundColorArgb)
        mode(DeviceThemeMode.LIGHT, DeviceCardStyle.PERSONALIZED)
        val personalized = resolver.resolveWidgetColors("#FF123456")
        assertEquals(0xff123456.toInt(), personalized.backgroundColorArgb)
        assertNotEquals(themed.backgroundColorArgb, personalized.backgroundColorArgb)
        val saved = repository.savedSelection()!!
        // Cached ticks do not reopen theme definitions; only explicit reload checks later damage.
        assertTrue(File(directory, "theme-definitions-v1/${saved.selection.light.themeId}-1.json").delete())
        repeat(10) { assertEquals(personalized, resolver.resolveWidgetColors("#FF123456")) }
    }

    @Test fun failedThemeRendersUnavailableInsteadOfNormalContentOrDefaultColors() = runBlocking<Unit> {
        mode(DeviceThemeMode.LIGHT)
        val ref = repository.savedSelection()!!.selection.light
        assertTrue(File(directory, "theme-definitions-v1/${ref.themeId}-1.json").delete())
        controller.retry()
        withTimeout(5000) { controller.state.first { it is DeviceThemeLoadState.Failed } }
        val views = withTimeout(5000) { GlanceRemoteViews().compose(context, DpSize(160.dp, 80.dp)) {
            DeviceWidgetThemeSnapshot(context, controller.state.value) { Text("normal-content-must-not-render") }
        }.remoteViews }
        app.runOnMainSync {
            val root = views.apply(context, FrameLayout(context))
            fun labels(view: View): List<String> = when (view) {
                is TextView -> listOf(view.text.toString())
                is ViewGroup -> (0 until view.childCount).flatMap { labels(view.getChildAt(it)) }
                else -> emptyList()
            }
            val labels = labels(root)
            assertTrue(labels.contains(context.getString(R.string.theme_unavailable)))
            assertFalse(labels.contains("normal-content-must-not-render"))
        }
    }
}
