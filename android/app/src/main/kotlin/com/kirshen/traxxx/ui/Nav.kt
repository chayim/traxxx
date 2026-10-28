package com.kirshen.traxxx.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MonitorWeight
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
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kirshen.traxxx.data.Prefs
import com.kirshen.traxxx.data.Repository
import com.kirshen.traxxx.net.NetModule
import com.kirshen.traxxx.service.RecordingState
import com.kirshen.traxxx.ui.screens.ActiveRecordingScreen
import com.kirshen.traxxx.ui.screens.ActivityDetailScreen
import com.kirshen.traxxx.ui.screens.HistoryScreen
import com.kirshen.traxxx.ui.screens.RecordScreen
import com.kirshen.traxxx.ui.screens.SettingsScreen
import com.kirshen.traxxx.ui.screens.TemplateEditScreen
import com.kirshen.traxxx.ui.screens.UserSetupScreen
import com.kirshen.traxxx.ui.screens.WeightsScreen
import com.kirshen.traxxx.ui.screens.WorkoutDetailScreen
import com.kirshen.traxxx.ui.screens.WorkoutEditScreen
import com.kirshen.traxxx.ui.screens.WorkoutTemplatesScreen
import com.kirshen.traxxx.ui.screens.WorkoutsListScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private sealed class Dest(val route: String, val label: String, val icon: @Composable () -> Unit) {
    object Record   : Dest("record",   "Record",   { Icon(Icons.Outlined.PlayArrow, null) })
    object History  : Dest("history",  "History",  { Icon(Icons.Outlined.History, null) })
    object Workouts : Dest("workouts", "Workouts", { Icon(Icons.Outlined.FitnessCenter, null) })
    object Weights  : Dest("weights",  "Weight",   { Icon(Icons.Outlined.MonitorWeight, null) })
    object Settings : Dest("settings", "Settings", { Icon(Icons.Outlined.Settings, null) })
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
                listOf(Dest.Record, Dest.Workouts, Dest.History, Dest.Weights, Dest.Settings).forEach { d ->
                    NavigationBarItem(
                        icon = d.icon,
                        label = { Text(d.label) },
                        selected = currentRoute == d.route ||
                            (d == Dest.Record && currentRoute == "active") ||
                            (d == Dest.Workouts && currentRoute?.startsWith("workouts") == true) ||
                            (d == Dest.Workouts && currentRoute?.startsWith("templates") == true),
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
        NavHost(
            nav,
            startDestination = Dest.Record.route,
            modifier = Modifier.fillMaxSize().padding(pad),
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            composable(Dest.Record.route) {
                if (active != null) ActiveRecordingScreen() else RecordScreen()
            }
            composable("active") { ActiveRecordingScreen() }
            composable(Dest.History.route)  {
                HistoryScreen(onOpen = { uuid -> nav.navigate("activities/$uuid") })
            }
            composable(
                "activities/{uuid}",
                arguments = listOf(navArgument("uuid") { type = NavType.StringType }),
            ) { bs ->
                val uuid = bs.arguments?.getString("uuid") ?: return@composable
                ActivityDetailScreen(
                    uuid = uuid,
                    onDeleted = { nav.popBackStack() },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Dest.Weights.route) { WeightsScreen() }
            composable(Dest.Settings.route) { SettingsScreen() }

            composable(Dest.Workouts.route) {
                WorkoutsListScreen(
                    onNewBlank = { nav.navigate("workouts/edit/new") },
                    onPickTemplate = { nav.navigate("templates/workouts?pick=1") },
                    onManageTemplates = { nav.navigate("templates/workouts?pick=0") },
                    onOpen = { uuid -> nav.navigate("workouts/detail/$uuid") },
                )
            }

            composable("workouts/edit/new") {
                WorkoutEditScreen(editingUuid = null, seedFromTemplateId = null, onDone = { nav.popBackStack() })
            }
            composable(
                "workouts/edit/new/{templateId}",
                arguments = listOf(navArgument("templateId") { type = NavType.LongType }),
            ) { bs ->
                WorkoutEditScreen(
                    editingUuid = null,
                    seedFromTemplateId = bs.arguments?.getLong("templateId"),
                    onDone = { nav.popBackStack() },
                )
            }
            composable(
                "workouts/edit/{uuid}",
                arguments = listOf(navArgument("uuid") { type = NavType.StringType }),
            ) { bs ->
                WorkoutEditScreen(
                    editingUuid = bs.arguments?.getString("uuid"),
                    seedFromTemplateId = null,
                    onDone = { nav.popBackStack() },
                )
            }
            composable(
                "workouts/detail/{uuid}",
                arguments = listOf(navArgument("uuid") { type = NavType.StringType }),
            ) { bs ->
                val uuid = bs.arguments?.getString("uuid") ?: return@composable
                WorkoutDetailScreen(
                    uuid = uuid,
                    onEdit = { nav.navigate("workouts/edit/$uuid") },
                    onDone = { nav.popBackStack() },
                )
            }
            composable(
                "templates/workouts?pick={pick}",
                arguments = listOf(navArgument("pick") { type = NavType.StringType; defaultValue = "0" }),
            ) { bs ->
                val picker = bs.arguments?.getString("pick") == "1"
                WorkoutTemplatesScreen(
                    pickerMode = picker,
                    onNew = { nav.navigate("templates/workouts/new") },
                    onEdit = { id -> nav.navigate("templates/workouts/$id") },
                    onUse = { id ->
                        cloneTemplateAndOpen(ctx, nav, id)
                    },
                    onBack = { nav.popBackStack() },
                )
            }
            composable("templates/workouts/new") {
                TemplateEditScreen(templateId = null, onDone = { nav.popBackStack() })
            }
            composable(
                "templates/workouts/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) { bs ->
                TemplateEditScreen(
                    templateId = bs.arguments?.getLong("id"),
                    onDone = { nav.popBackStack() },
                )
            }
        }
    }
}

private fun cloneTemplateAndOpen(
    ctx: android.content.Context,
    nav: androidx.navigation.NavController,
    templateId: Long,
) {
    CoroutineScope(Dispatchers.Main).launch {
        try {
            val api = NetModule.api(ctx)
            val workout = api.cloneWorkoutTemplate(templateId, com.kirshen.traxxx.net.CloneTemplateDto())
            Repository.get(ctx).upsertWorkoutFromServer(workout)
            val uuid = workout.clientUuid?.takeIf { it.isNotBlank() }
            if (uuid != null) {
                nav.navigate("workouts/detail/$uuid") {
                    popUpTo("workouts") { inclusive = false }
                }
            } else {
                nav.popBackStack()
            }
        } catch (_: Throwable) {
            // Offline: seed a draft workout from the cached template.
            val tpl = Repository.get(ctx).templateDetail(templateId)
            if (tpl != null) {
                nav.navigate("workouts/edit/new/$templateId")
            }
        }
    }
}
