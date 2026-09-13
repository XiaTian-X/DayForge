package com.dayforge

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.dayforge.R
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.TokenManager
import com.dayforge.domain.service.ThemeManager
import com.dayforge.ui.navigation.HabitNavGraph
import com.dayforge.ui.navigation.Screen
import com.dayforge.ui.theme.DayForgeTheme
import com.dayforge.widget.WidgetUpdateReceiver
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var tokenManager: TokenManager

    @Inject
    lateinit var preferencesManager: PreferencesManager

    @Inject
    lateinit var themeManager: ThemeManager

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize ThemeManager (load themes from assets + files)
        lifecycleScope.launch {
            themeManager.initialize()
        }

        setContent {
            // Collect all theme preferences for reactive theme switching
            val themeMode by preferencesManager.themeMode.collectAsState(initial = null)
            val lightColorThemeId by preferencesManager.lightColorThemeId.collectAsState(initial = "ocean")
            val darkColorThemeId by preferencesManager.darkColorThemeId.collectAsState(initial = "dusk")

            // Calculate WindowSizeClass - auto-updates on resize/rotation
            val windowSizeClass: WindowSizeClass = calculateWindowSizeClass(this)

            // Pass all theme parameters to DayForgeTheme
            // Theme will automatically update when any preference changes
            DayForgeTheme(
                themeMode = themeMode,
                lightColorThemeId = lightColorThemeId,
                darkColorThemeId = darkColorThemeId,
                themeManager = themeManager
            ) {
                val navController = rememberNavController()
                MainScreen(
                    navController = navController,
                    tokenManager = tokenManager,
                    preferencesManager = preferencesManager,
                    windowSizeClass = windowSizeClass
                )
            }
        }
    }
}

@Composable
fun MainScreen(
    navController: NavHostController,
    tokenManager: TokenManager,
    preferencesManager: PreferencesManager,
    windowSizeClass: WindowSizeClass
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Track date change on resume - triggers Flow refresh when date changes
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // Check if date has changed and update trigger
                val todayEpochDays = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
                scope.launch {
                    val dateChanged = preferencesManager.updateLastSeenDate(todayEpochDays)
                    if (dateChanged) {
                        // Notify widgets to refresh when date changes
                        // Use LocalBroadcastManager since WidgetUpdateReceiver is registered with it
                        val intent = Intent(WidgetUpdateReceiver.ACTION_DATA_CHANGED)
                        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Double-back-to-exit state
    var lastBackPressTime by remember { mutableLongStateOf(0L) }

    // Check current route to determine if we're on main tabs or detail screens
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Main tab routes
    val mainTabRoutes = listOf(
        Screen.Dashboard.route,
        Screen.Nested.route,
        Screen.Metrics.route,
        Screen.Profile.route
    )
    val isOnMainTab = currentRoute in mainTabRoutes

    // Back handler for double-back-to-exit (on Dashboard tab)
    BackHandler(enabled = isOnMainTab && selectedTab == 0) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastBackPressTime < 2000) {
            (context as? Activity)?.finish()
        } else {
            lastBackPressTime = currentTime
            Toast.makeText(context, context.getString(R.string.toast_press_again_exit), Toast.LENGTH_SHORT).show()
        }
    }

    // Back handler for non-Dashboard tabs - return to Dashboard
    BackHandler(enabled = isOnMainTab && selectedTab != 0) {
        selectedTab = 0
    }

    Scaffold(
        bottomBar = {
            // Hide bottom bar on detail screens
            if (isOnMainTab) {
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.dashboard_tab_habits)) },
                        label = { Text(stringResource(R.string.dashboard_tab_habits)) }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.AccountTree, contentDescription = stringResource(R.string.tab_nested)) },
                        label = { Text(stringResource(R.string.tab_nested)) }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(Icons.AutoMirrored.Filled.TrendingUp, contentDescription = stringResource(R.string.dashboard_tab_metrics)) },
                        label = { Text(stringResource(R.string.dashboard_tab_metrics)) }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 },
                        icon = { Icon(Icons.Default.Person, contentDescription = stringResource(R.string.tab_profile)) },
                        label = { Text(stringResource(R.string.tab_profile)) }
                    )
                }
            }
        }
    ) { padding ->
        HabitNavGraph(
            navController = navController,
            tokenManager = tokenManager,
            selectedTab = selectedTab,
            onTabSelected = { selectedTab = it },
            windowSizeClass = windowSizeClass,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        )
    }
}
