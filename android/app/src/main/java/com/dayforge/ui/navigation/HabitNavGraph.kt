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
import com.dayforge.data.local.DatabaseObjectSelection
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
    object CreateHabit : Screen("create_habit?parentUuid={parentUuid}&parentSource={parentSource}") {
        fun createRoute(parentUuid: String? = null, parentSource: String? = null): String {
            return if (parentUuid != null) {
                "create_habit?parentUuid=$parentUuid" + (parentSource?.let { "&parentSource=$it" } ?: "")
            } else {
                "create_habit"
            }
        }
    }
    object CreateTempTask : Screen("create_temp_task?parentUuid={parentUuid}&parentSource={parentSource}") {
        fun createRoute(parentUuid: String? = null, parentSource: String? = null): String {
            return if (parentUuid != null) {
                "create_temp_task?parentUuid=$parentUuid" + (parentSource?.let { "&parentSource=$it" } ?: "")
            } else {
                "create_temp_task"
            }
        }
    }
    object CreateMetric : Screen("create_metric")
    object CreateGoal : Screen("create_goal")
    object CreateGoalChild : Screen("create_goal_child")
    object EditHabit : Screen("edit_habit/{habitId}?source={source}") {
        fun createRoute(habitId: Long, source: String? = null) = "edit_habit/$habitId" +
            (source?.let { "?source=$it" } ?: "")
    }
    object EditMetric : Screen("edit_metric/{metricId}?source={source}") {
        fun createRoute(metricId: Long, source: String? = null) = "edit_metric/$metricId" +
            (source?.let { "?source=$it" } ?: "")
    }
    object EditGoal : Screen("edit_goal/{goalId}?source={source}") {
        fun createRoute(goalId: Long, source: String? = null) = "edit_goal/$goalId" +
            (source?.let { "?source=$it" } ?: "")
    }
    object HabitDetail : Screen("habit_detail/{habitId}?source={source}") {
        fun createRoute(habitId: Long, source: String? = null) = "habit_detail/$habitId" +
            (source?.let { "?source=$it" } ?: "")
    }
    object MetricDetail : Screen("metric_detail/{metricId}?source={source}") {
        fun createRoute(metricId: Long, source: String? = null) = "metric_detail/$metricId" +
            (source?.let { "?source=$it" } ?: "")
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
    val reminderTransfer: DatabaseNavigationViewModel? = if (inBusiness) {
        val owner = remember(navigationEntry) { navController.getBackStackEntry(BUSINESS_GRAPH) }
        hiltViewModel(owner)
    } else null
    ReminderSelectionNavigationEffect(reminderRequest, navigationEntry != null,
        !inBusiness, { request, navigate -> reminderViewModel?.openSelection(request, navigate) ?: false }, onReminderConsumed) { selection ->
        val route = requireNotNull(reminderTransfer).route(selection, Screen.HabitDetail::createRoute)
        navController.navigate(route) { launchSingleTop = true }
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
                    },
                    navArgument("parentSource") { type = NavType.StringType; nullable = true; defaultValue = null }
                )
            ) { backStackEntry ->
                val navigation = remember(backStackEntry) { EntryNavigation(navController, backStackEntry) }
                val parentUuid = backStackEntry.arguments?.getString("parentUuid")
                val transfer: DatabaseNavigationViewModel = hiltViewModel(businessGraphOwner(navController, backStackEntry))
                val parentSelection = remember(backStackEntry) { parentUuid?.let { transfer.parent(backStackEntry, it) } }
                ObjectCreationSourceEntry(onBack = navigation::back, parentSelection = parentSelection,
                    requireParent = parentUuid != null) { authority, input ->
                    CreateHabitScreen(
                        creationAuthority = authority,
                        creationInput = input,
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
                    },
                    navArgument("parentSource") { type = NavType.StringType; nullable = true; defaultValue = null }
                )
            ) { backStackEntry ->
                val navigation = remember(backStackEntry) { EntryNavigation(navController, backStackEntry) }
                val parentUuid = backStackEntry.arguments?.getString("parentUuid")
                val transfer: DatabaseNavigationViewModel = hiltViewModel(businessGraphOwner(navController, backStackEntry))
                val parentSelection = remember(backStackEntry) { parentUuid?.let { transfer.parent(backStackEntry, it) } }
                ObjectCreationSourceEntry(onBack = navigation::back, parentSelection = parentSelection,
                    requireParent = parentUuid != null) { authority, input ->
                    CreateTempTaskScreen(
                        creationAuthority = authority,
                        creationInput = input,
                        parentUuid = parentUuid,
                        onNavigateBack = navigation::back
                    )
                }
            }

            composable(Screen.CreateGoal.route) { entry ->
                val navigation = remember(entry) { EntryNavigation(navController, entry) }
                ObjectCreationSourceEntry(onBack = navigation::back) { authority, input ->
                    CreateGoalScreen(
                        creationAuthority = authority,
                        creationInput = input,
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
                ObjectCreationSourceEntry(onBack = navigation::back, viewModel = hiltViewModel(goalEntry)) { authority, input ->
                    LaunchedEffect(input) { goalViewModel.beginSource(input) }
                    if (authority == null || goalState.creationAuthority === authority) {
                        CreateHabitScreen(
                            parentUuid = goalState.parentUuid,
                            creationAuthority = goalState.creationAuthority,
                            creationInput = input,
                            onSaveDraft = goalViewModel::addChildHabit,
                            onNavigateBack = navigation::back
                        )
                    } else ObjectCreationPending(goalState.errorMessage, onBack = navigation::back)
                }
            }

            composable(Screen.CreateMetric.route) { entry ->
                val navigation = remember(entry) { EntryNavigation(navController, entry) }
                ObjectCreationSourceEntry(onBack = navigation::back) { authority, input ->
                    CreateMetricScreen(
                        creationAuthority = authority,
                        creationInput = input,
                        onNavigateBack = navigation::back
                    )
                }
            }

            composable(
                route = Screen.EditHabit.route,
                arguments = listOf(
                    navArgument("habitId") {
                        type = NavType.LongType
                    },
                    navArgument("source") { type = NavType.StringType; nullable = true; defaultValue = null }
                )
            ) { backStackEntry ->
                val habitId = backStackEntry.arguments?.getLong("habitId") ?: return@composable
                val navigation = remember(backStackEntry) { EntryNavigation(navController, backStackEntry) }
                val transfer: DatabaseNavigationViewModel = hiltViewModel(businessGraphOwner(navController, backStackEntry))
                val selection = remember(backStackEntry) { transfer.target(backStackEntry, DatabaseObjectSelection.Kind.HABIT, habitId) }
                EditHabitScreen(
                    habitId = habitId,
                    sourceSelection = selection,
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
                    },
                    navArgument("source") { type = NavType.StringType; nullable = true; defaultValue = null }
                )
            ) { backStackEntry ->
                val goalId = backStackEntry.arguments?.getLong("goalId") ?: return@composable
                val navigation = remember(backStackEntry) { EntryNavigation(navController, backStackEntry) }
                val transfer: DatabaseNavigationViewModel = hiltViewModel(businessGraphOwner(navController, backStackEntry))
                val selection = remember(backStackEntry) { transfer.target(backStackEntry, DatabaseObjectSelection.Kind.HABIT, goalId) }
                EditGoalScreen(
                    goalId = goalId,
                    sourceSelection = selection,
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
                    },
                    onEditChildSelection = { selected -> transfer.navigate(selected, Screen.EditHabit::createRoute) { route -> navigation.navigate(route) } },
                    onCreateChildSelection = { selected ->
                        transfer.navigateChild(selected) { uuid, key -> navigation.navigate(Screen.CreateHabit.createRoute(uuid, key)) }
                    }
                )
            }

            composable(
                route = Screen.HabitDetail.route,
                arguments = listOf(
                    navArgument("habitId") {
                        type = NavType.LongType
                    },
                    navArgument("source") { type = NavType.StringType; nullable = true; defaultValue = null }
                )
            ) { backStackEntry ->
                val habitId = backStackEntry.arguments?.getLong("habitId") ?: return@composable
                val navigation = remember(backStackEntry) { EntryNavigation(navController, backStackEntry) }
                val transfer: DatabaseNavigationViewModel = hiltViewModel(businessGraphOwner(navController, backStackEntry))
                val selection = remember(backStackEntry) { transfer.target(backStackEntry, DatabaseObjectSelection.Kind.HABIT, habitId) }
                HabitDetailScreen(
                    habitId = habitId,
                    sourceSelection = selection,
                    onNavigateBack = {
                        navigation.back()
                    },
                    onEditClick = { id ->
                        navigation.navigate(Screen.EditHabit.createRoute(id))
                    },
                    onEditGoalClick = { id ->
                        navigation.navigate(Screen.EditGoal.createRoute(id))
                    },
                    onEditHabitSelection = { selected -> transfer.navigate(selected, Screen.EditHabit::createRoute) { route -> navigation.navigate(route) } },
                    onEditGoalSelection = { selected -> transfer.navigate(selected, Screen.EditGoal::createRoute) { route -> navigation.navigate(route) } }
                )
            }

            composable(
                route = Screen.MetricDetail.route,
                arguments = listOf(
                    navArgument("metricId") {
                        type = NavType.LongType
                    },
                    navArgument("source") { type = NavType.StringType; nullable = true; defaultValue = null }
                )
            ) { backStackEntry ->
                val metricId = backStackEntry.arguments?.getLong("metricId") ?: return@composable
                val navigation = remember(backStackEntry) { EntryNavigation(navController, backStackEntry) }
                val transfer: DatabaseNavigationViewModel = hiltViewModel(businessGraphOwner(navController, backStackEntry))
                val selection = remember(backStackEntry) { transfer.target(backStackEntry, DatabaseObjectSelection.Kind.METRIC, metricId) }
                MetricDetailScreen(
                    metricId = metricId,
                    sourceSelection = selection,
                    onNavigateBack = {
                        navigation.back()
                    },
                    onEditClick = { id ->
                        navigation.navigate(Screen.EditMetric.createRoute(id))
                    },
                    onDeleted = {
                        navigation.backToDashboard()
                    },
                    onEditMetricSelection = { selected -> transfer.navigate(selected, Screen.EditMetric::createRoute) { route -> navigation.navigate(route) } }
                )
            }

            composable(
                route = Screen.EditMetric.route,
                arguments = listOf(
                    navArgument("metricId") {
                        type = NavType.LongType
                    },
                    navArgument("source") { type = NavType.StringType; nullable = true; defaultValue = null }
                )
            ) { backStackEntry ->
                val metricId = backStackEntry.arguments?.getLong("metricId") ?: return@composable
                val navigation = remember(backStackEntry) { EntryNavigation(navController, backStackEntry) }
                val transfer: DatabaseNavigationViewModel = hiltViewModel(businessGraphOwner(navController, backStackEntry))
                val selection = remember(backStackEntry) { transfer.target(backStackEntry, DatabaseObjectSelection.Kind.METRIC, metricId) }
                EditMetricScreen(
                    metricId = metricId,
                    sourceSelection = selection,
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
    val transfer: DatabaseNavigationViewModel = hiltViewModel(owner)
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
            onMetricClick = { navigation.navigate(Screen.MetricDetail.createRoute(it)) },
            onHabitSelection = { transfer.navigate(it, Screen.HabitDetail::createRoute) { route -> navigation.navigate(route) } },
            onEditHabitSelection = { transfer.navigate(it, Screen.EditHabit::createRoute) { route -> navigation.navigate(route) } },
            onMetricSelection = { transfer.navigate(it, Screen.MetricDetail::createRoute) { route -> navigation.navigate(route) } }
        )
        1 -> NestedScreen(
            viewModel = nestedViewModel,
            windowSizeClass = windowSizeClass,
            onHabitClick = { navigation.navigate(Screen.HabitDetail.createRoute(it)) },
            onEditHabitClick = { navigation.navigate(Screen.EditHabit.createRoute(it)) },
            onEditGoalClick = { navigation.navigate(Screen.HabitDetail.createRoute(it)) },
            onCreateGoalClick = { navigation.navigate(Screen.CreateGoal.route) },
            onMetricClick = { navigation.navigate(Screen.MetricDetail.createRoute(it)) },
            onHabitSelection = { transfer.navigate(it, Screen.HabitDetail::createRoute) { route -> navigation.navigate(route) } },
            onEditHabitSelection = { transfer.navigate(it, Screen.EditHabit::createRoute) { route -> navigation.navigate(route) } },
            onEditGoalSelection = { transfer.navigate(it, Screen.HabitDetail::createRoute) { route -> navigation.navigate(route) } },
            onMetricSelection = { transfer.navigate(it, Screen.MetricDetail::createRoute) { route -> navigation.navigate(route) } }
        )
        2 -> MetricsScreen(
            viewModel = metricsViewModel,
            windowSizeClass = windowSizeClass,
            onCreateMetricClick = { navigation.navigate(Screen.CreateMetric.route) },
            onMetricClick = { navigation.navigate(Screen.MetricDetail.createRoute(it)) },
            onEditMetricClick = { navigation.navigate(Screen.EditMetric.createRoute(it)) },
            onMetricSelection = { transfer.navigate(it, Screen.MetricDetail::createRoute) { route -> navigation.navigate(route) } },
            onEditMetricSelection = { transfer.navigate(it, Screen.EditMetric::createRoute) { route -> navigation.navigate(route) } }
        )
        3 -> ProfileScreen(
            viewModel = profileViewModel,
            onSettingsClick = { navigation.navigate(Screen.Settings.route) }
        )
    }
}
