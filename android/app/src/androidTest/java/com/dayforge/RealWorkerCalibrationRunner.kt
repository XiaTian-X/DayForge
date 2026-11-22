package com.dayforge

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import com.dayforge.widget.RealWidgetRefreshRuntime
import dagger.hilt.android.testing.HiltTestApplication

/** Selected only by the two explicit B variants; ordinary HiltTestRunner is unchanged. */
class RealWorkerCalibrationRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(cl, HiltTestApplication::class.java.name, context)

    override fun onCreate(arguments: Bundle) {
        check(arguments.getString(RealWidgetRefreshRuntime.ARGUMENT) == "real") {
            "Real-worker calibration requires dayforge.widgetRefresh=real"
        }
        // The B target overlay removes Startup's WorkManagerInitializer. Install before
        // AndroidJUnitRunner starts discovery/tests; never replace an initialized instance.
        RealWidgetRefreshRuntime.install(targetContext)
        super.onCreate(arguments)
    }
}
