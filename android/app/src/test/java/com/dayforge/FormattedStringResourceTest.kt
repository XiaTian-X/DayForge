package com.dayforge

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class FormattedStringResourceTest {

    @Test
    fun englishFormatArgumentsPreserveTheirMeaning() {
        val context = localizedContext("en")

        assertEquals(
            "Next: Habit (in 5 min)",
            context.getString(R.string.widget_focus_next_in, "Habit", "5 min")
        )
        assertEquals(
            "第2次/共4次 (已完成1次)",
            context.getString(R.string.notification_slot_progress, 2, 4, 1)
        )
    }

    @Test
    fun chineseTimerFormatsPreserveArgumentOrderAndPadding() {
        val context = localizedContext("zh-CN")

        assertEquals(
            "下一个: 习惯 (5 分钟后)",
            context.getString(R.string.widget_focus_next_in, "习惯", "5 分钟")
        )
        assertEquals(
            "第2次/共4次 (已完成1次)",
            context.getString(R.string.notification_slot_progress, 2, 4, 1)
        )
        assertEquals(
            "还剩 03:07，是否放弃本次计时？",
            context.getString(R.string.dialog_discard_countdown_remaining, 3, 7)
        )
        assertEquals(
            "已计时 03:07，未达目标 5分钟，是否放弃本次计时？",
            context.getString(R.string.dialog_discard_countup_elapsed, 3, 7, 5)
        )
        assertEquals(
            "还剩 03:07 / 5 分钟",
            context.getString(R.string.timer_remaining_format, 3, 7, 5)
        )
        assertEquals(
            "已计时 03:07 / 5 分钟",
            context.getString(R.string.timer_elapsed_format, 3, 7, 5)
        )
        assertEquals(
            "还剩 03:07 / 5 分钟 (已暂停)",
            context.getString(R.string.timer_remaining_paused_format, 3, 7, 5)
        )
        assertEquals(
            "已计时 03:07 / 5 分钟 (已暂停)",
            context.getString(R.string.timer_elapsed_paused_format, 3, 7, 5)
        )
    }

    private fun localizedContext(languageTag: String): Context {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(baseContext.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(languageTag))
        }
        return baseContext.createConfigurationContext(configuration)
    }
}
