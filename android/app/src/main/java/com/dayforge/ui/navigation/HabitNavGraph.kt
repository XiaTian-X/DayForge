package com.dayforge.ui.navigation

import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import androidx.navigation.navigation
import androidx.navigation.NavDestination.Companion.hierarchy
import com.dayforge.data.local.TokenManager
import com.dayforge.reminder.ReminderDetailRequest
import com.dayforge.ui.screens.createhabit.CreateHabitScreen
import com.dayforge.ui.screens.createtemptask.CreateTempTaskScreen
import com.dayforge.ui.screens.creategoal.CreateGoalScreen
import com.dayforge.ui.screens.creategoal.CreateGoalViewModel
import com.dayforge.ui.screens.createmetric.CreateMetricScreen
import com.dayforge.ui.screens.dashboard.DashboardScreen
import com.dayforge.ui.screens.dashboard.DashboardViewModel
import com.dayforge.ui.screens.edithabit.EditHabitScreen
import com.dayforge.ui.screens.editgoal.EditGoalScreen
import com.dayforge.ui.screens.editmetric.EditMetricScreen
import com.dayforge.ui.screens.habitdetail.HabitDetailScreen
import com.dayforge.ui.screens.login.LoginScreen
import com.dayforge.ui.screens.metricdetail.MetricDetailScreen
import com.dayforge.ui.screens.metrics.MetricsScreen
import com.dayforge.ui.screens.metrics.MetricsViewModel
import com.dayforge.ui.screens.nested.NestedScreen
import com.dayforge.ui.screens.nested.NestedViewModel
import com.dayforge.ui.screens.profile.ProfileScreen
import com.dayforge.ui.screens.profile.ProfileViewModel
import com.dayforge.ui.screens.settings.SettingsScreen
import com.dayforge.ui.screens.settings.IconLibraryScreen
import com.dayforge.ui.screens.admin.AdminScreen
import com.dayforge.ui.screens.permission.PermissionScreen
import com.dayforge.ui.screens.token.TokenScreen
import kotlinx.coroutines.flow.first

sealed class Screen(val route: String) {
    object Dashboard : Screen("dashboard")
    object Nested : Screen("nested")
    object Metrics : Screen("metrics")
    object Profile : Screen("profile")
    object CreateHabit : Screen("create_habit?parentUuid={parentUuid}") {
        fun createRoute(parentUuid: String? = null): String {
            return if (parentUuid != null) {
                "create_habit?parentUuid=$parentUuid"
            } else {
                "create_habit"
            }
        }
    }
    object CreateTempTask : Screen("create_temp_task?parentUuid={parentUuid}") {
        fun createRoute(parentUuid: String? = null): String {
            return if (parentUuid != null) {
                "create_temp_task?parentUuid=$parentUuid"
            } else {
                "create_temp_task"
            }
        }
    }
    object CreateMetric : Screen("create_metric")
    object CreateGoal : Screen("create_goal")
    object CreateGoalChild : Screen("create_goal_child")
    object EditHabit : Screen("edit_habit/{habitId}") {
        fun createRoute(habitId: Long) = "edit_habit/$habitId"
    }
    object EditMetric : Screen("edit_metric/{metricId}") {
        fun createRoute(metricId: Long) = "edit_metric/$metricId"
    }
    object EditGoal : Screen("edit_goal/{goalId}") {
        fun createRoute(goalId: Long) = "edit_goal/$goalId"
    }
    object HabitDetail : Screen("habit_detail/{habitId}") {
        fun createRoute(habitId: Long) = "habit_detail/$habitId"
    }
    object MetricDetail : Screen("metric_detail/{metricId}") {
        fun createRoute(metricId: Long) = "metric_detail/$metricId"
    }
    object Settings : Screen("settings")
    object IconLibrary : Screen("icon-library")
    object Token : Screen("token")
    object Permission : Screen("permission")
    object Admin : Screen("admin")
    object Login : Screen("login")
}

internal const val BUSINESS_GRAPH = "authenticated-business"

/**
 * Main navigation graph with state-switch for 4 main tabs.
 *
 * Main tabs (Dashboard, Nested, Metrics, Profile) use state switching
 * for instant tab changes. Detail screens use Navigation for proper
 * back stack management.
 */
@Composable
fun HabitNavGraph(
    navController: NavHostController,
    tokenManager: TokenManager,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    windowSizeClass: WindowSizeClass,
    modifier: Modifier = Modifier,
    reminderRequest: ReminderDetailRequest? = null,
    onReminderConsumed: (ReminderDetailRequest) -> Unit = {}
) {
    // Determine start destination based on login state
    var startDestination by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val accessToken = tokenManager.accessToken.first()
        startDestination = if (accessToken != null) BUSINESS_GRAPH else Screen.Login.route
    }

    // Show loading while determining start destination
    if (startDestination == null) {
        return
    }

    val navigationEntry by navController.currentBackStackEntryAsState()
    val inBusiness = navigationEntry?.destination?.hierarchy?.any { it.route == BUSINESS_GRAPH } == true
    val reminderViewModel: ReminderNavigationViewModel? = if (inBusiness) {
        val owner = remember(navigationEntry) { navController.getBackStackEntry(BUSINESS_GRAPH) }
        hiltViewModel(owner)
    } else null
    ReminderNavigationEffect(reminderRequest, navigationEntry != null,
        !inBusiness, { request, navigate -> reminderViewModel?.open(request, navigate) ?: false }, onReminderConsumed) { id ->
        navController.navigate(Screen.HabitDetail.createRoute(id)) { launchSingleTop = true }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination!!,
        modifier = modifier
    ) {
        // Login screen
        composable(Screen.Login.route) { entry ->
            val navigation = remember(entry) { EntryNavigation(navController, entry) }
            LoginScreen(
                onLoginSuccess = {
                    navigation.navigate(BUSINESS_GRAPH) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                }
            )
        }

        navigation(startDestination = Screen.Dashboard.route, route = BUSINESS_GRAPH) {
            listOf(Screen.Dashboard, Screen.Nested, Screen.Metrics, Screen.Profile).forEach { screen ->
                composable(screen.route) { entry ->
                    val owner = businessGraphOwner(navController, entry)
                    MainTabContent(navController, owner, entry, selectedTab, windowSizeClass)
                }
            }

            // Detail screens - normal navigation
            composable(
                route = Screen.CreateHabit.route,
                arguments = listOf(
                    navArgument("parentUuid") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { backStackEntry ->
                val navigation = remember(backStackEntry) { EntryNavigation(navController, backStackEntry) }
                val parentUuid = backStackEntry.arguments?.getString("parentUuid")
                ObjectCreationEntry(onBack = navigation::back) { authority ->
                    CreateHabitScreen(
                        creationAuthority = authority,
                        parentUuid = parentUuid,
                        onNavigateBack = navigation::back
                    )
                }
            }

            composable(
                route = Screen.CreateTempTask.route,
                arguments = listOf(
                    navArgument("parentUuid") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { backStackEntry ->
                val navigation = remember(backStackEntry) { EntryNavigation(navController, backStackEntry) }
                val parentUuid = backStackEntry.arguments?.getString("parentUuid")
                ObjectCreationEntry(onBack = navigation::back) { authority ->
                    CreateTempTaskScreen(
                        creationAuthority = authority,
                        parentUuid = parentUuid,
                        onNavigateBack = navigation::back
                    )
                }
            }

            composable(Screen.CreateGoal.route) { entry ->
                val navigation = remember(entry) { EntryNavigation(navController, entry) }
                ObjectCreationEntry(onBack = navigation::back) { authority ->
                    CreateGoalScreen(
                        creationAuthority = authority,
                        onNavigateBack = navigation::back,
                        onCreateChildHabit = { _ -> navigation.navigate(Screen.CreateGoalChild.route) }
                    )
                }
            }

            composable(Screen.CreateGoalChild.route) { childEntry ->
                val navigation = remember(childEntry) { EntryNavigation(navController, childEntry) }
                val goalEntry = remember(childEntry) { navController.getBackStackEntry(Screen.CreateGoal.route) }
                val goalViewModel: CreateGoalViewModel = hiltViewModel(goalEntry)
                val goalState by goalViewModel.uiState.collectAsStateWithLifecycle()
                // After process death the parent form can be below this route and not composed.
                // Recover its entry admission before exposing a child, never a new independent ticket.
                ObjectCreationEntry(onBack = navigation::back, viewModel = hiltViewModel(goalEntry)) { authority ->
                    LaunchedEffect(authority) { authority?.let(goalViewModel::beginCreation) }
                    if (authority == null || goalState.creationAuthority === authority) {
                        CreateHabitScreen(
                            parentUuid = goalState.parentUuid,
                            creationAuthority = goalState.creationAuthority,
                            onSaveDraft = goalViewModel::addChildHabit,
                            onNavigateBack = navigation::back
                        )
                    } else ObjectCreationPending(goalState.errorMessage, onBack = navigation::back)
                }
            }

            composable(Screen.CreateMetric.route) { entry ->
                val navigation = remember(entry) { EntryNavigation(navController, entry) }
                ObjectCreationEntry(onBack = navigation::back) { authority ->
                    CreateMetricScreen(
                        creationAuthority = authority,
                        onNavigateBack = navigation::back
                    )
                }
            }

            composable(
                route = Screen.EditHabit.route,
                arguments = listOf(
                    navArgument("habitId") {
                        type = NavType.LongType
                    }
                )
            ) { backStackEntry ->
                val habitId = backStackEntry.arguments?.getLong("habitId") ?: return@composable
                val navigation = remember(backStackEntry) { EntryNavigation(navController, backStackEntry) }
                EditHabitScreen(
                    habitId = habitId,
                    onNavigateBack = {
                        navigation.back()
                    },
                    onHabitDeleted = {
                        // Pop back to Dashboard (the only tab route in the backstack).
                        // selectedTab is preserved, so the correct tab content is shown.
                        navigation.backToDashboard()
                    }
                )
            }

            composable(
                route = Screen.EditGoal.route,
                arguments = listOf(
                    navArgument("goalId") {
                        type = NavType.LongType
                    }
                )
            ) { backStackEntry ->
                val goalId = backStackEntry.arguments?.getLong("goalId") ?: return@composable
                val navigation = remember(backStackEntry) { EntryNavigation(navController, backStackEntry) }
                EditGoalScreen(
                    goalId = goalId,
                    onNavigateBack = {
                        // Pop back to Dashboard (the only tab route in the backstack).
                        // selectedTab is preserved, so the correct tab content is shown.
                        navigation.backToDashboard()
                    },
                    onCreateChildHabit = { parentUuid ->
                        navigation.navigate(Screen.CreateHabit.createRoute(parentUuid))
                    },
                    onEditChildHabit = { habitId ->
                        navigation.navigate(Screen.EditHabit.createRoute(habitId))
                    }
                )
            }

            composable(
                route = Screen.HabitDetail.route,
                arguments = listOf(
                    navArgument("habitId") {
                        type = NavType.LongType
                    }
                )
            ) { backStackEntry ->
                val habitId = backStackEntry.arguments?.getLong("habitId") ?: return@composable
                val navigation = remember(backStackEntry) { EntryNavigation(navController, backStackEntry) }
                HabitDetailScreen(
                    habitId = habitId,
                    onNavigateBack = {
                        navigation.back()
                    },
                    onEditClick = { id ->
                        navigation.navigate(Screen.EditHabit.createRoute(id))
                    },
                    onEditGoalClick = { id ->
                        navigation.navigate(Screen.EditGoal.createRoute(id))
                    }
                )
            }

            composable(
                route = Screen.MetricDetail.route,
                arguments = listOf(
                    navArgument("metricId") {
                        type = NavType.LongType
                    }
                )
            ) { backStackEntry ->
                val metricId = backStackEntry.arguments?.getLong("metricId") ?: return@composable
                val navigation = remember(backStackEntry) { EntryNavigation(navController, backStackEntry) }
                MetricDetailScreen(
                    metricId = metricId,
                    onNavigateBack = {
                        navigation.back()
                    },
                    onEditClick = { id ->
                        navigation.navigate(Screen.EditMetric.createRoute(id))
                    },
                    onDeleted = {
                        navigation.backToDashboard()
                    }
                )
            }

            composable(
                route = Screen.EditMetric.route,
                arguments = listOf(
                    navArgument("metricId") {
                        type = NavType.LongType
                    }
                )
            ) { backStackEntry ->
                val metricId = backStackEntry.arguments?.getLong("metricId") ?: return@composable
                val navigation = remember(backStackEntry) { EntryNavigation(navController, backStackEntry) }
                EditMetricScreen(
                    metricId = metricId,
                    onNavigateBack = {
                        navigation.back()
                    },
                    onMetricDeleted = {
                        navigation.backToDashboard()
                    }
                )
            }

            composable(Screen.Settings.route) { entry ->
                val navigation = remember(entry) { EntryNavigation(navController, entry) }
                SettingsScreen(
                    onNavigateBack = {
                        navigation.back()
                    },
                    onNavigateToLogin = {
                        navigation.navigate(Screen.Login.route) {
                            popUpTo(BUSINESS_GRAPH) { inclusive = true }
                        }
                    },
                    onNavigateToPermission = {
                        navigation.navigate(Screen.Permission.route)
                    },
                    onNavigateToAdmin = {
                        navigation.navigate(Screen.Admin.route)
                    },
                    onNavigateToToken = {
                        navigation.navigate(Screen.Token.route)
                    },
                    onNavigateToIcons = {
                        navigation.navigate(Screen.IconLibrary.route)
                    }
                )
            }

            composable(Screen.IconLibrary.route) { entry ->
                val navigation = remember(entry) { EntryNavigation(navController, entry) }
                IconLibraryScreen(onNavigateBack = navigation::back)
            }

            composable(Screen.Token.route) { entry ->
                val navigation = remember(entry) { EntryNavigation(navController, entry) }
                TokenScreen(
                    onNavigateBack = {
                        navigation.back()
                    }
                )
            }

            composable(Screen.Permission.route) { entry ->
                val navigation = remember(entry) { EntryNavigation(navController, entry) }
                PermissionScreen(
                    onNavigateBack = navigation::back
                )
            }

            composable(Screen.Admin.route) { entry ->
                val navigation = remember(entry) { EntryNavigation(navController, entry) }
                AdminScreen(
                    onNavigateBack = navigation::back
                )
            }
        }
    }
}

/** Shared across tab switches, never Activity-owned or created on Login. */
@Composable
internal fun businessGraphOwner(navController: NavHostController, entry: androidx.navigation.NavBackStackEntry) =
    remember(entry) { navController.getBackStackEntry(BUSINESS_GRAPH) }

@Composable
private fun MainTabContent(navController: NavHostController, owner: androidx.navigation.NavBackStackEntry,
    entry: androidx.navigation.NavBackStackEntry, selectedTab: Int, windowSizeClass: WindowSizeClass) {
    val navigation = remember(entry) { EntryNavigation(navController, entry) }
    val dashboardViewModel: DashboardViewModel = hiltViewModel(owner)
    val nestedViewModel: NestedViewModel = hiltViewModel(owner)
    val metricsViewModel: MetricsViewModel = hiltViewModel(owner)
    val profileViewModel: ProfileViewModel = hiltViewModel(owner)
    when (selectedTab) {
        0 -> DashboardScreen(
            viewModel = dashboardViewModel,
            windowSizeClass = windowSizeClass,
            onHabitClick = { navigation.navigate(Screen.HabitDetail.createRoute(it)) },
            onCreateHabitClick = { navigation.navigate(Screen.CreateHabit.createRoute()) },
            onCreateTempTaskClick = { navigation.navigate(Screen.CreateTempTask.createRoute()) },
            onEditHabitClick = { navigation.navigate(Screen.EditHabit.createRoute(it)) },
            onMetricClick = { navigation.navigate(Screen.MetricDetail.createRoute(it)) }
        )
        1 -> NestedScreen(
            viewModel = nestedViewModel,
            windowSizeClass = windowSizeClass,
            onHabitClick = { navigation.navigate(Screen.HabitDetail.createRoute(it)) },
            onEditHabitClick = { navigation.navigate(Screen.EditHabit.createRoute(it)) },
            onEditGoalClick = { navigation.navigate(Screen.HabitDetail.createRoute(it)) },
            onCreateGoalClick = { navigation.navigate(Screen.CreateGoal.route) },
            onMetricClick = { navigation.navigate(Screen.MetricDetail.createRoute(it)) }
        )
        2 -> MetricsScreen(
            viewModel = metricsViewModel,
            windowSizeClass = windowSizeClass,
            onCreateMetricClick = { navigation.navigate(Screen.CreateMetric.route) },
            onMetricClick = { navigation.navigate(Screen.MetricDetail.createRoute(it)) },
            onEditMetricClick = { navigation.navigate(Screen.EditMetric.createRoute(it)) }
        )
        3 -> ProfileScreen(
            viewModel = profileViewModel,
            onSettingsClick = { navigation.navigate(Screen.Settings.route) }
        )
    }
}
