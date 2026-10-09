package com.dayforge.widget.timer

import com.dayforge.domain.service.WidgetConfigurationKind
import com.dayforge.widget.configuration.WidgetConfigurationActivity

/** Existing launcher entrypoint and timer layout, with shared account-safe configuration. */
class TimerWidgetConfigActivity : WidgetConfigurationActivity() {
    internal override val kind = WidgetConfigurationKind.TIMER
}
