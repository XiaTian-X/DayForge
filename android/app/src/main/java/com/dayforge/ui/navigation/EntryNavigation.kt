package com.dayforge.ui.navigation

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder

/** A callback belongs to the page that rendered it. After its first navigation, old
 * callbacks cannot push another page or pop the newly exposed page. No time debounce.
 */
internal class EntryNavigation(
    private val controller: NavHostController,
    private val origin: NavBackStackEntry
) {
    fun navigate(route: String, options: NavOptionsBuilder.() -> Unit = {}) {
        if (controller.currentBackStackEntry !== origin) return
        controller.navigate(route) { launchSingleTop = true; options() }
    }

    fun back() {
        if (controller.currentBackStackEntry === origin) controller.popBackStack()
    }

    fun backToDashboard() {
        if (controller.currentBackStackEntry === origin)
            controller.popBackStack(Screen.Dashboard.route, inclusive = false)
    }
}
