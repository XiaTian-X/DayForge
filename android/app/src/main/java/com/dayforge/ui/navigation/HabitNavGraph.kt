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
import androidx.navigation.navArgument
import com.dayforge.data.local.TokenManager
import com.dayforge.ui.screens.createhabit.CreateHabitScreen
import com.dayforge.ui.screens.createtemptask.CreateTempTaskScreen
import com.dayforge.ui.screens.creatgoal.CreateGoalScreen
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
    object Token : Screen("token")
    object Permission : Screen("permission")
    object Admin : Screen("admin")
    object Login : Screen("login")
}

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
    modifier: Modifier = Modifier
) {
    // Determine start destination based on login state
    var startDestination by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val accessToken = tokenManager.accessToken.first()
        startDestination = if (accessToken != null) Screen.Dashboard.route else Screen.Login.route
    }

    // Show loading while determining start destination
    if (startDestination == null) {
        return
    }

    // Pre-initialize ViewModels for all main tabs (kept alive across switches)
    val dashboardViewModel: DashboardViewModel = hiltViewModel()
    val nestedViewModel: NestedViewModel = hiltViewModel()
    val metricsViewModel: MetricsViewModel = hiltViewModel()
    val profileViewModel: ProfileViewModel = hiltViewModel()

    NavHost(
        navController = navController,
        startDestination = startDestination!!,
        modifier = modifier
    ) {
        // Login screen
        composable(Screen.Login.route) {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(Screen.Dashboard.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                }
            )
        }

        // Main tabs - state switch based on selectedTab
        composable(Screen.Dashboard.route) {
            when (selectedTab) {
                0 -> DashboardScreen(
                    viewModel = dashboardViewModel,
                    windowSizeClass = windowSizeClass,
                    onHabitClick = { habitId ->
                        navController.navigate(Screen.HabitDetail.createRoute(habitId))
                    },
                    onCreateHabitClick = {
                        navController.navigate(Screen.CreateHabit.createRoute())
                    },
                    onCreateTempTaskClick = {
                        navController.navigate(Screen.CreateTempTask.createRoute())
                    },
                    onEditHabitClick = { habitId ->
                        navController.navigate(Screen.EditHabit.createRoute(habitId))
                    },
                    onMetricClick = { metricId ->
                        navController.navigate(Screen.MetricDetail.createRoute(metricId))
                    }
                )
                1 -> NestedScreen(
                    viewModel = nestedViewModel,
                    windowSizeClass = windowSizeClass,
                    onHabitClick = { habitId ->
                        navController.navigate(Screen.HabitDetail.createRoute(habitId))
                    },
                    onEditHabitClick = { habitId ->
                        navController.navigate(Screen.EditHabit.createRoute(habitId))
                    },
                    onEditGoalClick = { goalId ->
                        navController.navigate(Screen.HabitDetail.createRoute(goalId))
                    },
                    onCreateGoalClick = {
                        navController.navigate(Screen.CreateGoal.route)
                    },
                    onMetricClick = { metricId ->
                        navController.navigate(Screen.MetricDetail.createRoute(metricId))
                    }
                )
                2 -> MetricsScreen(
                    viewModel = metricsViewModel,
                    windowSizeClass = windowSizeClass,
                    onCreateMetricClick = {
                        navController.navigate(Screen.CreateMetric.route)
                    },
                    onMetricClick = { metricId ->
                        navController.navigate(Screen.MetricDetail.createRoute(metricId))
                    },
                    onEditMetricClick = { metricId ->
                        navController.navigate(Screen.EditMetric.createRoute(metricId))
                    }
                )
                3 -> ProfileScreen(
                    viewModel = profileViewModel,
                    onSettingsClick = {
                        navController.navigate(Screen.Settings.route)
                    }
                )
            }
        }

        // Other main tab routes - redirect to Dashboard with state switch
        composable(Screen.Nested.route) {
            when (selectedTab) {
                0 -> DashboardScreen(
                    viewModel = dashboardViewModel,
                    windowSizeClass = windowSizeClass,
                    onHabitClick = { navController.navigate(Screen.HabitDetail.createRoute(it)) },
                    onCreateHabitClick = { navController.navigate(Screen.CreateHabit.createRoute()) },
                    onCreateTempTaskClick = { navController.navigate(Screen.CreateTempTask.createRoute()) },
                    onEditHabitClick = { navController.navigate(Screen.EditHabit.createRoute(it)) },
                    onMetricClick = { navController.navigate(Screen.MetricDetail.createRoute(it)) }
                )
                1 -> NestedScreen(
                    viewModel = nestedViewModel,
                    windowSizeClass = windowSizeClass,
                    onHabitClick = { navController.navigate(Screen.HabitDetail.createRoute(it)) },
                    onEditHabitClick = { navController.navigate(Screen.EditHabit.createRoute(it)) },
                    onEditGoalClick = { navController.navigate(Screen.HabitDetail.createRoute(it)) },
                    onCreateGoalClick = { navController.navigate(Screen.CreateGoal.route) },
                    onMetricClick = { navController.navigate(Screen.MetricDetail.createRoute(it)) }
                )
                2 -> MetricsScreen(
                    viewModel = metricsViewModel,
                    windowSizeClass = windowSizeClass,
                    onCreateMetricClick = { navController.navigate(Screen.CreateMetric.route) },
                    onMetricClick = { navController.navigate(Screen.MetricDetail.createRoute(it)) },
                    onEditMetricClick = { navController.navigate(Screen.EditMetric.createRoute(it)) }
                )
                3 -> ProfileScreen(
                    viewModel = profileViewModel,
                    onSettingsClick = { navController.navigate(Screen.Settings.route) }
                )
            }
        }

        composable(Screen.Metrics.route) {
            when (selectedTab) {
                0 -> DashboardScreen(
                    viewModel = dashboardViewModel,
                    windowSizeClass = windowSizeClass,
                    onHabitClick = { navController.navigate(Screen.HabitDetail.createRoute(it)) },
                    onCreateHabitClick = { navController.navigate(Screen.CreateHabit.createRoute()) },
                    onCreateTempTaskClick = { navController.navigate(Screen.CreateTempTask.createRoute()) },
                    onEditHabitClick = { navController.navigate(Screen.EditHabit.createRoute(it)) },
                    onMetricClick = { navController.navigate(Screen.MetricDetail.createRoute(it)) }
                )
                1 -> NestedScreen(
                    viewModel = nestedViewModel,
                    windowSizeClass = windowSizeClass,
                    onHabitClick = { navController.navigate(Screen.HabitDetail.createRoute(it)) },
                    onEditHabitClick = { navController.navigate(Screen.EditHabit.createRoute(it)) },
                    onEditGoalClick = { navController.navigate(Screen.HabitDetail.createRoute(it)) },
                    onCreateGoalClick = { navController.navigate(Screen.CreateGoal.route) },
                    onMetricClick = { navController.navigate(Screen.MetricDetail.createRoute(it)) }
                )
                2 -> MetricsScreen(
                    viewModel = metricsViewModel,
                    windowSizeClass = windowSizeClass,
                    onCreateMetricClick = { navController.navigate(Screen.CreateMetric.route) },
                    onMetricClick = { navController.navigate(Screen.MetricDetail.createRoute(it)) },
                    onEditMetricClick = { navController.navigate(Screen.EditMetric.createRoute(it)) }
                )
                3 -> ProfileScreen(
                    viewModel = profileViewModel,
                    onSettingsClick = { navController.navigate(Screen.Settings.route) }
                )
            }
        }

        composable(Screen.Profile.route) {
            when (selectedTab) {
                0 -> DashboardScreen(
                    viewModel = dashboardViewModel,
                    windowSizeClass = windowSizeClass,
                    onHabitClick = { navController.navigate(Screen.HabitDetail.createRoute(it)) },
                    onCreateHabitClick = { navController.navigate(Screen.CreateHabit.createRoute()) },
                    onCreateTempTaskClick = { navController.navigate(Screen.CreateTempTask.createRoute()) },
                    onEditHabitClick = { navController.navigate(Screen.EditHabit.createRoute(it)) },
                    onMetricClick = { navController.navigate(Screen.MetricDetail.createRoute(it)) }
                )
                1 -> NestedScreen(
                    viewModel = nestedViewModel,
                    windowSizeClass = windowSizeClass,
                    onHabitClick = { navController.navigate(Screen.HabitDetail.createRoute(it)) },
                    onEditHabitClick = { navController.navigate(Screen.EditHabit.createRoute(it)) },
                    onEditGoalClick = { navController.navigate(Screen.HabitDetail.createRoute(it)) },
                    onCreateGoalClick = { navController.navigate(Screen.CreateGoal.route) },
                    onMetricClick = { navController.navigate(Screen.MetricDetail.createRoute(it)) }
                )
                2 -> MetricsScreen(
                    viewModel = metricsViewModel,
                    windowSizeClass = windowSizeClass,
                    onCreateMetricClick = { navController.navigate(Screen.CreateMetric.route) },
                    onMetricClick = { navController.navigate(Screen.MetricDetail.createRoute(it)) },
                    onEditMetricClick = { navController.navigate(Screen.EditMetric.createRoute(it)) }
                )
                3 -> ProfileScreen(
                    viewModel = profileViewModel,
                    onSettingsClick = { navController.navigate(Screen.Settings.route) }
                )
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
            val parentUuid = backStackEntry.arguments?.getString("parentUuid")
            CreateHabitScreen(
                parentUuid = parentUuid,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
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
            val parentUuid = backStackEntry.arguments?.getString("parentUuid")
            CreateTempTaskScreen(
                parentUuid = parentUuid,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Screen.CreateGoal.route) {
            CreateGoalScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onCreateChildHabit = { parentUuid ->
                    navController.navigate(Screen.CreateHabit.createRoute(parentUuid))
                }
            )
        }

        composable(Screen.CreateMetric.route) {
            CreateMetricScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
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
            EditHabitScreen(
                habitId = habitId,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onHabitDeleted = {
                    // Pop back to Dashboard (the only tab route in the backstack).
                    // selectedTab is preserved, so the correct tab content is shown.
                    navController.popBackStack(Screen.Dashboard.route, inclusive = false)
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
            EditGoalScreen(
                goalId = goalId,
                onNavigateBack = {
                    // Pop back to Dashboard (the only tab route in the backstack).
                    // selectedTab is preserved, so the correct tab content is shown.
                    navController.popBackStack(Screen.Dashboard.route, inclusive = false)
                },
                onCreateChildHabit = { parentUuid ->
                    navController.navigate(Screen.CreateHabit.createRoute(parentUuid))
                },
                onEditChildHabit = { habitId ->
                    navController.navigate(Screen.EditHabit.createRoute(habitId))
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
            HabitDetailScreen(
                habitId = habitId,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onEditClick = { id ->
                    navController.navigate(Screen.EditHabit.createRoute(id))
                },
                onEditGoalClick = { id ->
                    navController.navigate(Screen.EditGoal.createRoute(id))
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
            MetricDetailScreen(
                metricId = metricId,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onEditClick = { id ->
                    navController.navigate(Screen.EditMetric.createRoute(id))
                },
                onDeleted = {
                    navController.popBackStack(Screen.Dashboard.route, inclusive = false)
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
            EditMetricScreen(
                metricId = metricId,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onMetricDeleted = {
                    navController.popBackStack(Screen.Dashboard.route, inclusive = false)
                }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToLogin = {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(Screen.Dashboard.route) { inclusive = false }
                    }
                },
                onNavigateToPermission = {
                    navController.navigate(Screen.Permission.route)
                },
                onNavigateToAdmin = {
                    navController.navigate(Screen.Admin.route)
                },
                onNavigateToToken = {
                    navController.navigate(Screen.Token.route)
                }
            )
        }

        composable(Screen.Token.route) {
            TokenScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Screen.Permission.route) {
            PermissionScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Admin.route) {
            AdminScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
