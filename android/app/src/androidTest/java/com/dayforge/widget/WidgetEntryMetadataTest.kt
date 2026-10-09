package com.dayforge.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.widget.checkin.CheckInWidgetConfigActivity
import com.dayforge.widget.checkin.CheckInWidgetReceiver
import com.dayforge.widget.counting.CountingWidgetConfigActivity
import com.dayforge.widget.counting.CountingWidgetReceiver
import com.dayforge.widget.focus.FocusWidgetReceiver
import com.dayforge.widget.motivation.MotivationWidgetReceiver
import com.dayforge.widget.progress.ProgressWidgetReceiver
import com.dayforge.widget.timer.TimerWidgetConfigActivity
import com.dayforge.widget.timer.TimerWidgetReceiver
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Installed APK/platform metadata, not source text or a mocked launcher/provider parser. */
@RunWith(AndroidJUnit4::class)
class WidgetEntryMetadataTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val configured = mapOf(
        CheckInWidgetReceiver::class.java.name to CheckInWidgetConfigActivity::class.java.name,
        CountingWidgetReceiver::class.java.name to CountingWidgetConfigActivity::class.java.name,
        TimerWidgetReceiver::class.java.name to TimerWidgetConfigActivity::class.java.name
    )
    private val automatic = setOf(FocusWidgetReceiver::class.java.name, ProgressWidgetReceiver::class.java.name,
        MotivationWidgetReceiver::class.java.name)
    private val all = configured.keys + automatic

    @Test fun installedProvidersLinkOnlyTheThreeSelectionActivitiesAndKeepAutomaticCreation() {
        val providers = AppWidgetManager.getInstance(context).installedProviders
            .filter { it.provider.packageName == context.packageName }
        assertEquals(all, providers.map { it.provider.className }.toSet())
        assertEquals(6, providers.size)
        providers.forEach { provider ->
            val expected = configured[provider.provider.className]
            if (expected == null) assertNull(provider.configure)
            else {
                assertEquals(ComponentName(context.packageName, expected), provider.configure)
                val info = context.packageManager.getActivityInfo(requireNotNull(provider.configure), 0)
                assertTrue(info.enabled); assertTrue(info.exported)
            }
            assertTrue(provider.initialLayout != 0)
            assertEquals(if (provider.provider.className == FocusWidgetReceiver::class.java.name)
                1_800_000 else 86_400_000, provider.updatePeriodMillis)
        }
    }

    @Test fun obsoleteFocusConfigurationIsAbsentFromTheInstalledManifestAndConfigurationResolution() {
        val removed = ComponentName(context.packageName, "com.dayforge.widget.focus.FocusWidgetConfigActivity")
        try {
            context.packageManager.getActivityInfo(removed, 0)
            fail("Obsolete exported Focus configuration is still installed")
        } catch (_: PackageManager.NameNotFoundException) { }
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).setPackage(context.packageName)
        val matches = context.packageManager.queryIntentActivities(intent, 0)
        assertEquals(configured.values.toSet(), matches.map { it.activityInfo.name }.toSet())
        assertEquals(3, matches.size)
        assertNull(context.packageManager.resolveActivity(Intent().setComponent(removed), 0))
    }

    @Test fun installedUpdateBroadcastReceiversRemainEnabledForAllSixTypes() {
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).setPackage(context.packageName)
        val matches = context.packageManager.queryBroadcastReceivers(intent, 0)
        assertEquals(all, matches.map { it.activityInfo.name }.toSet())
        assertEquals(6, matches.size)
        matches.forEach { assertTrue(it.activityInfo.enabled); assertTrue(it.activityInfo.exported) }
    }
}
