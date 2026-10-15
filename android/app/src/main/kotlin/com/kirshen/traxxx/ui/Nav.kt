package com.kirshen.traxxx.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kirshen.traxxx.data.Prefs
import com.kirshen.traxxx.service.RecordingState
import com.kirshen.traxxx.ui.screens.ActiveRecordingScreen
import com.kirshen.traxxx.ui.screens.HistoryScreen
import com.kirshen.traxxx.ui.screens.RecordScreen
import com.kirshen.traxxx.ui.screens.SettingsScreen
import com.kirshen.traxxx.ui.screens.UserSetupScreen

private sealed class Dest(val route: String, val label: String, val icon: @Composable () -> Unit) {
    object Record  : Dest("record",  "Record",   { Icon(Icons.Outlined.PlayArrow, null) })
    object History : Dest("history", "History",  { Icon(Icons.Outlined.History, null) })
    object Settings: Dest("settings","Settings", { Icon(Icons.Outlined.Settings, null) })
}

@Composable
fun Nav() {
    val ctx = LocalContext.current
    val prefs = Prefs(ctx).flow.collectAsState(initial = null).value

    if (prefs == null) return
    if (prefs.userId == null) {
        UserSetupScreen(onDone = { /* flow re-emits */ })
        return
    }

    val nav = rememberNavController()
    val stack = nav.currentBackStackEntryAsState().value
    val currentRoute = stack?.destination?.route ?: Dest.Record.route
    val active = RecordingState.state.collectAsState().value

    Scaffold(
        bottomBar = {
            NavigationBar {
                listOf(Dest.Record, Dest.History, Dest.Settings).forEach { d ->
                    NavigationBarItem(
                        icon = d.icon,
                        label = { Text(d.label) },
                        selected = currentRoute == d.route ||
                            (d == Dest.Record && currentRoute == "active"),
                        onClick = {
                            nav.navigate(d.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = Dest.Record.route, modifier = Modifier.fillMaxSize().padding(pad)) {
            composable(Dest.Record.route) {
                if (active != null) ActiveRecordingScreen() else RecordScreen()
            }
            composable("active") { ActiveRecordingScreen() }
            composable(Dest.History.route)  { HistoryScreen() }
            composable(Dest.Settings.route) { SettingsScreen() }
        }
    }
}
