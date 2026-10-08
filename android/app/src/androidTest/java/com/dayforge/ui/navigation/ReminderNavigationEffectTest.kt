package com.dayforge.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.reminder.ReminderDetailRequest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Compose/NavHost, isolated from production authentication and the formal application. */
@RunWith(AndroidJUnit4::class)
class ReminderNavigationEffectTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val claim = ReminderDetailRequest(1L + (1L shl 32), "a3110000-0000-4000-8000-000000000001", "scope")

    @Test fun coldGraphWaitsThenOpensFullLongDetailOnceAndDoesNotReopenAfterReturn() {
        var pending by mutableStateOf<ReminderDetailRequest?>(claim)
        var installed by mutableStateOf(false)
        var opens = 0; var consumed = 0
        var back: () -> Unit = {}
        compose.setContent {
            val nav = rememberNavController()
            val entry by nav.currentBackStackEntryAsState()
            back = { nav.popBackStack() }
            ReminderNavigationEffect(pending, entry != null, false, { request, navigate ->
                opens++; navigate(request.habitId); true
            }, { pending = null; consumed++ }) { nav.navigate(Screen.HabitDetail.createRoute(it)) }
            if (installed) NavHost(nav, "home") {
                composable("home") { Text("Home") }
                composable(Screen.HabitDetail.route, arguments = listOf(navArgument("habitId") { type = NavType.LongType })) {
                    Text("Detail: " + it.arguments!!.getLong("habitId"))
                }
            }
        }
        compose.runOnIdle { assertEquals(0, opens); assertEquals(0, consumed); installed = true }
        compose.onNodeWithText("Detail: ${claim.habitId}").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, opens); assertEquals(1, consumed); back() }
        compose.onNodeWithText("Home").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, opens); assertNull(pending) }
    }

    @Test fun warmRequestsNavigateOnlyWhenVerifiedAndEachIsConsumedWithoutChangingOtherRoutes() {
        var pending by mutableStateOf<ReminderDetailRequest?>(null)
        var accepted = true; var opens = 0; var consumed = 0
        compose.setContent {
            val nav = rememberNavController()
            val entry by nav.currentBackStackEntryAsState()
            ReminderNavigationEffect(pending, entry != null, false, { request, navigate ->
                if (accepted) { opens++; navigate(request.habitId) }; accepted
            }, { pending = null; consumed++ }) { nav.navigate(Screen.HabitDetail.createRoute(it)) { launchSingleTop = true } }
            NavHost(nav, "home") {
                composable("home") { Text("Home") }
                composable(Screen.HabitDetail.route, arguments = listOf(navArgument("habitId") { type = NavType.LongType })) {
                    Text("Detail: " + it.arguments!!.getLong("habitId"))
                }
            }
        }
        compose.onNodeWithText("Home").assertIsDisplayed()
        compose.runOnIdle { pending = claim }
        compose.onNodeWithText("Detail: ${claim.habitId}").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, opens); assertEquals(1, consumed); accepted = false; pending = claim.copy(habitId = 2) }
        compose.waitForIdle()
        compose.onNodeWithText("Detail: ${claim.habitId}").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, opens); assertEquals(2, consumed); assertNull(pending) }
    }

    @Test fun loginConsumesClaimWithoutReadingOrOpeningPersonalData() {
        var pending by mutableStateOf<ReminderDetailRequest?>(claim)
        var consumed = 0
        compose.setContent {
            val nav = rememberNavController()
            val entry by nav.currentBackStackEntryAsState()
            ReminderNavigationEffect(pending, entry != null, entry?.destination?.route == "login",
                { _, _ -> fail("Login must not resolve personal data"); false }, { pending = null; consumed++ }) {
                fail("Login must not navigate")
            }
            NavHost(nav, "login") { composable("login") { Text("Login") } }
        }
        compose.onNodeWithText("Login").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, consumed); assertNull(pending) }
    }
}
