package com.vesper.flipper

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.vesper.flipper.ble.FlipperBleService
import com.vesper.flipper.data.SettingsStore
import com.vesper.flipper.ui.screen.*
import com.vesper.flipper.ui.theme.VesperBackdropBrush
import com.vesper.flipper.ui.theme.VesperTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsStore: SettingsStore

    private val essentialPermissions = com.vesper.flipper.ble.BlePermissionPolicy.required(Build.VERSION.SDK_INT)
    private val optionalPermissions = if (Build.VERSION.SDK_INT >= 33) {
        listOf(Manifest.permission.POST_NOTIFICATIONS)
    } else emptyList()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (com.vesper.flipper.ble.BlePermissionPolicy.hasPermissions(this)) {
            startBleService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkAndRequestPermissions()

        setContent {
            val darkMode by settingsStore.darkMode.collectAsState(initial = true)
            VesperTheme(darkTheme = darkMode) {
                VesperApp()
            }
        }
    }

    private fun checkAndRequestPermissions() {
        if (com.vesper.flipper.ble.BlePermissionPolicy.hasPermissions(this)) startBleService()
        val missingPermissions = (essentialPermissions + optionalPermissions).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun startBleService() {
        FlipperBleService.startService(this)
    }
}

sealed class Screen(
    val route: String,
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    object Chat : Screen("chat", "Chat", Icons.Filled.Chat, Icons.Outlined.Chat)
    object Oracle : Screen("oracle", "Oracle", Icons.Filled.Visibility, Icons.Outlined.Visibility)
    object Arsenal : Screen("arsenal", "Arsenal", Icons.Filled.Sensors, Icons.Outlined.Sensors)
    object Alchemy : Screen("alchemy", "Alchemy", Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome)
    object OpsCenter : Screen("ops_center", "Ops", Icons.Filled.BluetoothSearching, Icons.Outlined.BluetoothSearching)
    object PayloadLab : Screen("payload_lab", "Payloads", Icons.Filled.Code, Icons.Outlined.Code)
    object FapHub : Screen("faphub", "FapHub", Icons.Filled.Apps, Icons.Outlined.Apps)
    object Files : Screen("files", "Files", Icons.Filled.Folder, Icons.Outlined.Folder)
    object Audit : Screen("audit", "Audit", Icons.Filled.History, Icons.Outlined.History)
    object Device : Screen("device", "Device", Icons.Filled.Bluetooth, Icons.Outlined.Bluetooth)
    object Settings : Screen("settings", "Settings", Icons.Filled.Settings, Icons.Outlined.Settings)
}

val screens = listOf(
    Screen.Chat,
    Screen.Alchemy,
    Screen.FapHub,
    Screen.Device,
    Screen.Settings
)

@Composable
fun VesperApp() {
    val navController = rememberNavController()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VesperBackdropBrush)
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    tonalElevation = 0.dp
                ) {
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentDestination = navBackStackEntry?.destination

                    // Map sub-screens to their parent bottom-nav tab
                    val subScreenParents = mapOf(
                        Screen.Audit.route to Screen.Chat.route,
                        Screen.Files.route to Screen.Device.route
                    )
                    val currentRoute = currentDestination?.route
                    val effectiveRoute = subScreenParents[currentRoute] ?: currentRoute

                    screens.forEach { screen ->
                        val selected = if (screen.route == effectiveRoute) {
                            true
                        } else {
                            currentDestination?.hierarchy?.any { it.route == screen.route } == true
                        }

                        NavigationBarItem(
                            icon = {
                                Icon(
                                    if (selected) screen.selectedIcon else screen.unselectedIcon,
                                    contentDescription = screen.title
                                )
                            },
                            label = { Text(screen.title) },
                            selected = selected,
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            onClick = {
                                val activeRoute = navController.currentBackStackEntry?.destination?.route
                                // If already on this tab, do nothing
                                if (activeRoute == screen.route) return@NavigationBarItem
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    // Only restore state when switching between bottom-nav tabs,
                                    // not when returning from sub-screens (Files, Audit)
                                    restoreState = activeRoute in screens.map { it.route }
                                }
                            }
                        )
                    }
                }
            }
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = Screen.Chat.route,
                modifier = Modifier.padding(innerPadding)
            ) {
                composable(Screen.Chat.route) {
                    ChatScreen(
                        onNavigateToAudit = {
                            navController.navigate(Screen.Audit.route)
                        }
                    )
                }
                composable(Screen.Alchemy.route) {
                    AlchemyLabScreen()
                }
                composable(Screen.OpsCenter.route) {
                    OpsCenterScreen()
                }
                composable(Screen.FapHub.route) {
                    FapHubScreen()
                }
                composable(Screen.Files.route) {
                    FileBrowserScreen()
                }
                composable(Screen.Audit.route) {
                    AuditScreen()
                }
                composable(Screen.Device.route) {
                    DeviceScreen(
                        onNavigateToFiles = {
                            navController.navigate(Screen.Files.route)
                        }
                    )
                }
                composable(Screen.Settings.route) {
                    SettingsScreen()
                }
            }
        }
    }
}
