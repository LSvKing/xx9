package com.xx9.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.xx9.app.ui.MainTabs
import com.xx9.app.ui.Xx9BottomBar
import com.xx9.app.ui.Xx9TopBar
import com.xx9.app.ui.screens.BrowseScreen
import com.xx9.app.ui.screens.HomeScreen
import com.xx9.app.ui.screens.PlayerScreen
import com.xx9.app.ui.screens.SettingsScreen
import com.xx9.app.ui.screens.SetupScreen
import com.xx9.app.ui.theme.Bg
import com.xx9.app.ui.theme.Fg
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

@Composable
private fun Xx9Root(app: Xx9App) {
    val nav = rememberNavController()
    var setupDone by remember { mutableStateOf(Prefs.setupDone(app)) }
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route.orEmpty()
    val mainTab = MainTabs.any { it.route == route }
    val hideBar = !setupDone || !mainTab
    val showTopChrome = setupDone && mainTab

    if (!setupDone) {
        SetupScreen(initialUrl = Prefs.baseUrl(app)) { url ->
            Prefs.setBaseUrl(app, url)
            setupDone = true
        }
        return
    }

    Scaffold(
        containerColor = Bg,
        bottomBar = {
            if (!hideBar) {
                Xx9BottomBar(currentRoute = route.ifBlank { "home" }) { tab ->
                    nav.navigate(tab) {
                        popUpTo("home") { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (showTopChrome) {
                Xx9TopBar(
                    onSearch = { nav.navigate("search") },
                    onSettings = { nav.navigate("settings") },
                )
            }
            NavHost(
                navController = nav,
                startDestination = "home",
                modifier = Modifier.fillMaxSize(),
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
                composable("fav") {
                    BrowseScreen(
                        api = app.api,
                        source = "fav",
                        initialTitle = "收藏",
                        emptyTitle = "还没有收藏",
                        emptySubtitle = "在播放页点☆即可收藏",
                        onOpenVideo = { nav.navigate("player/$it") },
                    )
                }
                composable("hist") {
                    BrowseScreen(
                        api = app.api,
                        source = "hist",
                        initialTitle = "历史",
                        emptyTitle = "暂无播放记录",
                        onOpenVideo = { nav.navigate("player/$it") },
                    )
                }
                composable("short") {
                    Column {
                        Xx9TopBar(
                            title = "短视频",
                            showBrand = false,
                            navigationIcon = {
                                IconButton(onClick = { nav.popBackStack() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Fg)
                                }
                            },
                        )
                        BrowseScreen(
                            api = app.api,
                            source = "short",
                            initialTitle = "短视频",
                            onOpenVideo = { nav.navigate("player/$it") },
                        )
                    }
                }
                composable("search") {
                    Column {
                        Xx9TopBar(
                            title = "搜索",
                            showBrand = false,
                            navigationIcon = {
                                IconButton(onClick = { nav.popBackStack() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Fg)
                                }
                            },
                        )
                        BrowseScreen(
                            api = app.api,
                            forceSearch = true,
                            emptyTitle = "没有找到相关内容",
                            onOpenVideo = { nav.navigate("player/$it") },
                        )
                    }
                }
                composable("settings") {
                    Column {
                        Xx9TopBar(
                            title = "设置",
                            showBrand = false,
                            navigationIcon = {
                                IconButton(onClick = { nav.popBackStack() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Fg)
                                }
                            },
                        )
                        SettingsScreen(currentUrl = Prefs.baseUrl(app)) { url ->
                            Prefs.setBaseUrl(app, url)
                            nav.navigate("home") {
                                popUpTo("home") { inclusive = true }
                            }
                        }
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
                    Column {
                        Xx9TopBar(
                            title = title,
                            showBrand = false,
                            navigationIcon = {
                                IconButton(onClick = { nav.popBackStack() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Fg)
                                }
                            },
                        )
                        BrowseScreen(
                            api = app.api,
                            initialTheme = id,
                            initialTitle = title,
                            onOpenVideo = { nav.navigate("player/$it") },
                        )
                    }
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
}
