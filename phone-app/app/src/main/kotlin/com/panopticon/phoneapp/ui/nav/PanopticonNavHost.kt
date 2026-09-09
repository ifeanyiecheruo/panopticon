package com.panopticon.phoneapp.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.panopticon.phoneapp.PanopticonApplication
import com.panopticon.phoneapp.ui.calibrate.CalibrateScreen
import com.panopticon.phoneapp.ui.connect.ConnectScreen
import com.panopticon.phoneapp.ui.gallery.GalleryScreen
import com.panopticon.phoneapp.ui.home.HomeScreen
import com.panopticon.phoneapp.ui.theme.PanopticonColors

private sealed class Dest(val route: String, val label: String) {
    object Home : Dest("home", "Home")
    object Connect : Dest("connect", "Connect")
    object Gallery : Dest("gallery", "Gallery")
    object Calibrate : Dest("calibrate", "Calibrate")
}

/**
 * Navigation shell for this vertical slice's three screens. The full mock uses a left icon rail
 * + top bar with a recording-status pill (see docs/design/decisions/0012-ux-shape.md) - deliberately simplified here
 * to a bottom nav bar to keep this slice's UI effort proportionate; visual language (dark/teal,
 * see ui/theme/Theme.kt) is carried over, exact chrome layout is not. See docs/design/components/phone-ui.md.
 */
@Composable
fun PanopticonNavHost(app: PanopticonApplication) {
    val navController = rememberNavController()
    val destinations = listOf(Dest.Home, Dest.Connect, Dest.Gallery, Dest.Calibrate)

    Scaffold(
        containerColor = PanopticonColors.bg,
        bottomBar = {
            NavigationBar(containerColor = PanopticonColors.surface) {
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = backStackEntry?.destination
                destinations.forEach { dest ->
                    val selected = currentDestination?.hierarchy?.any { it.route == dest.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                when (dest) {
                                    Dest.Home -> Icons.Filled.Home
                                    Dest.Connect -> Icons.Filled.Link
                                    Dest.Gallery -> Icons.Filled.PhotoLibrary
                                    Dest.Calibrate -> Icons.Filled.Tune
                                },
                                contentDescription = dest.label,
                            )
                        },
                        label = { Text(dest.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = PanopticonColors.accentInk,
                            selectedTextColor = PanopticonColors.accent,
                            indicatorColor = PanopticonColors.accent,
                            unselectedIconColor = PanopticonColors.textDim,
                            unselectedTextColor = PanopticonColors.textDim,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Dest.Home.route,
            modifier = Modifier
                .padding(padding)
                .background(PanopticonColors.bg),
        ) {
            composable(Dest.Home.route) { HomeScreen(app) }
            composable(Dest.Connect.route) { ConnectScreen(app) }
            composable(Dest.Gallery.route) { GalleryScreen(app) }
            composable(Dest.Calibrate.route) { CalibrateScreen(app) }
        }
    }
}
