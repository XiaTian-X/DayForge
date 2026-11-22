package com.dayforge.ui.navigation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EntryNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rapidNavigationAdmitsOneAndOldBackCallbackCannotPopParent() {
        lateinit var controller: NavHostController
        compose.setContent {
            controller = rememberNavController()
            NavHost(controller, "dashboard") {
                composable("dashboard") {}
                composable("create") {}
                composable("detail") {}
            }
        }
        compose.runOnIdle {
            val root = requireNotNull(controller.currentBackStackEntry)
            val fromRoot = EntryNavigation(controller, root)
            repeat(20) { fromRoot.navigate("create") }
            assertEquals("create", controller.currentDestination?.route)
            val creation = requireNotNull(controller.currentBackStackEntry)
            val fromCreation = EntryNavigation(controller, creation)
            repeat(20) { fromCreation.navigate("detail") }
            assertEquals("detail", controller.currentDestination?.route)
            val fromDetail = EntryNavigation(controller, requireNotNull(controller.currentBackStackEntry))
            repeat(20) { fromDetail.back() }
            assertSame(creation, controller.currentBackStackEntry)
            fromCreation.back()
            assertSame(root, controller.currentBackStackEntry)
            fromRoot.navigate("detail") // Returning to the source permits a new intentional action.
            assertEquals("detail", controller.currentDestination?.route)
        }
    }

    @Test fun competingDestinationsFromSamePageCannotBothOpen() {
        lateinit var controller: NavHostController
        compose.setContent {
            controller = rememberNavController()
            NavHost(controller, "dashboard") {
                composable("dashboard") {}
                composable("habit") {}
                composable("metric") {}
            }
        }
        compose.runOnIdle {
            val source = EntryNavigation(controller, requireNotNull(controller.currentBackStackEntry))
            source.navigate("habit")
            source.navigate("metric")
            assertEquals("habit", controller.currentDestination?.route)
            EntryNavigation(controller, requireNotNull(controller.currentBackStackEntry)).backToDashboard()
            assertEquals("dashboard", controller.currentDestination?.route)
        }
    }
}
