package com.dayforge.widget.checkin

import com.dayforge.domain.service.WidgetConfigurationKind
import com.dayforge.widget.configuration.WidgetConfigurationActivity

/** Existing launcher entrypoint and check-in layout, with shared account-safe configuration. */
class CheckInWidgetConfigActivity : WidgetConfigurationActivity() {
    internal override val kind = WidgetConfigurationKind.CHECK_IN
}
