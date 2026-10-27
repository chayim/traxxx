package com.kirshen.traxxx.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kirshen.traxxx.data.Prefs
import com.kirshen.traxxx.data.Repository
import com.kirshen.traxxx.data.entities.WorkoutEntity
import com.kirshen.traxxx.data.entities.WorkoutStatus
import com.kirshen.traxxx.sync.SyncWorker
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutsListScreen(
    onNewBlank: () -> Unit,
    onPickTemplate: () -> Unit,
    onManageTemplates: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Prefs(ctx) }
    val repo = remember { Repository.get(ctx) }
    val snap by prefs.flow.collectAsState(initial = null)
    val userId = snap?.userId

    val list = if (userId != null) repo.observeWorkouts(userId).collectAsState(initial = emptyList()).value
               else emptyList()

    var refreshing by remember { mutableStateOf(false) }
    var showSheet by remember { mutableStateOf(false) }
    val sheet = rememberModalBottomSheetState()

    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                scope.launch {
                    runCatching { SyncWorker.refreshWorkouts(ctx) }
                    refreshing = false
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (list.isEmpty()) {
                    Text(
                        "No workouts yet. Pull to refresh.",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(list, key = { it.clientUuid }) { WorkoutRow(it, onOpen) }
                    }
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = { showSheet = true },
            icon = { Icon(Icons.Outlined.Add, null) },
            text = { Text("Log workout") },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        )

        if (showSheet) {
            ModalBottomSheet(onDismissRequest = { showSheet = false }, sheetState = sheet) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Log workout", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { showSheet = false; onNewBlank() }) { Text("Blank workout") }
                    TextButton(onClick = { showSheet = false; onPickTemplate() }) { Text("From template…") }
                    TextButton(onClick = { showSheet = false; onManageTemplates() }) { Text("Manage templates") }
                }
            }
        }
    }
}

@Composable
private fun WorkoutRow(w: WorkoutEntity, onOpen: (String) -> Unit) {
    Card(onClick = { onOpen(w.clientUuid) }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(w.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (w.status == WorkoutStatus.PENDING) {
                    AssistChip(
                        onClick = {},
                        label = { Text("pending", style = MaterialTheme.typography.labelSmall) },
                        colors = AssistChipDefaults.assistChipColors(
                            labelColor = MaterialTheme.colorScheme.error,
                        ),
                    )
                }
            }
            Text(
                w.workoutDate,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            if (!w.notes.isNullOrBlank()) {
                Text(
                    w.notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }
}
