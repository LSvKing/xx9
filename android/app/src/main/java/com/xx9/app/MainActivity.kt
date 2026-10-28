package com.xx9.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.xx9.app.ui.screens.BrowseScreen
import com.xx9.app.ui.screens.HomeScreen
import com.xx9.app.ui.screens.PlayerScreen
import com.xx9.app.ui.screens.SettingsScreen
import com.xx9.app.ui.screens.SetupScreen
import com.xx9.app.ui.theme.Acc
import com.xx9.app.ui.theme.Bg
import com.xx9.app.ui.theme.Mut
import com.xx9.app.ui.theme.Xx9Theme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as Xx9App
        setContent {
            Xx9Theme {
                Xx9Root(app)
            }
        }
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

@Composable
private fun Xx9Root(app: Xx9App) {
    val nav = rememberNavController()
    var setupDone by remember { mutableStateOf(Prefs.setupDone(app)) }
    val tabs = listOf(
        Tab("home", "首页", Icons.Filled.Home),
        Tab("all", "全部", Icons.Filled.VideoLibrary),
        Tab("fav", "收藏", Icons.Filled.Favorite),
        Tab("hist", "历史", Icons.Filled.History),
        Tab("settings", "设置", Icons.Filled.Settings),
    )
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route.orEmpty()
    val hideBar = !setupDone || route.startsWith("player") || route == "setup" || route.startsWith("theme") || route == "short"

    if (!setupDone) {
        SetupScreen(initialUrl = Prefs.baseUrl(app)) { url ->
            Prefs.setBaseUrl(app, url)
            setupDone = true
            nav.navigate("home") { popUpTo(0) }
        }
        return
    }

    Scaffold(
        containerColor = Bg,
        bottomBar = {
            if (!hideBar) {
                NavigationBar(containerColor = Bg) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo("home") { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Acc,
                                selectedTextColor = Acc,
                                unselectedIconColor = Mut,
                                unselectedTextColor = Mut,
                                indicatorColor = Acc.copy(alpha = 0.15f),
                            ),
                        )
                    }
                }
            }
        },
    ) { pad ->
        NavHost(
            navController = nav,
            startDestination = "home",
            modifier = Modifier.padding(pad),
        ) {
            composable("home") {
                HomeScreen(
                    api = app.api,
                    onOpenVideo = { nav.navigate("player/$it") },
                    onOpenTheme = { id, title, source ->
                        when {
                            source == "short" -> nav.navigate("short")
                            id != null && id.toIntOrNull() != null ->
                                nav.navigate("theme/${id.toInt()}/${android.net.Uri.encode(title)}")
                            else -> nav.navigate("all")
                        }
                    },
                )
            }
            composable("all") {
                BrowseScreen(api = app.api, onOpenVideo = { nav.navigate("player/$it") })
            }
            composable("short") {
                BrowseScreen(
                    api = app.api,
                    source = "short",
                    initialTitle = "短视频",
                    onOpenVideo = { nav.navigate("player/$it") },
                )
            }
            composable("fav") {
                BrowseScreen(
                    api = app.api,
                    source = "fav",
                    initialTitle = "收藏",
                    onOpenVideo = { nav.navigate("player/$it") },
                )
            }
            composable("hist") {
                BrowseScreen(
                    api = app.api,
                    source = "hist",
                    initialTitle = "历史",
                    onOpenVideo = { nav.navigate("player/$it") },
                )
            }
            composable("settings") {
                SettingsScreen(currentUrl = Prefs.baseUrl(app)) { url ->
                    Prefs.setBaseUrl(app, url)
                }
            }
            composable(
                "theme/{id}/{title}",
                arguments = listOf(
                    navArgument("id") { type = NavType.IntType },
                    navArgument("title") { type = NavType.StringType },
                ),
            ) { entry ->
                val id = entry.arguments?.getInt("id") ?: 0
                val title = entry.arguments?.getString("title") ?: "专题"
                BrowseScreen(
                    api = app.api,
                    initialTheme = id,
                    initialTitle = title,
                    onOpenVideo = { nav.navigate("player/$it") },
                )
            }
            composable(
                "player/{id}",
                arguments = listOf(navArgument("id") { type = NavType.IntType }),
            ) { entry ->
                val id = entry.arguments?.getInt("id") ?: return@composable
                PlayerScreen(videoId = id, api = app.api, onBack = { nav.popBackStack() })
            }
        }
    }
}
