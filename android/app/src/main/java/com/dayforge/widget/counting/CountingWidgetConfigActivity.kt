package com.dayforge.widget.counting

import com.dayforge.domain.service.WidgetConfigurationKind
import com.dayforge.widget.configuration.WidgetConfigurationActivity

/** Existing launcher entrypoint and counting layout, with shared account-safe configuration. */
class CountingWidgetConfigActivity : WidgetConfigurationActivity() {
    internal override val kind = WidgetConfigurationKind.COUNTING
}
