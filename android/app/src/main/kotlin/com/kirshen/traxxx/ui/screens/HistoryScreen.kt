package com.kirshen.traxxx.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kirshen.traxxx.data.ActivityType
import com.kirshen.traxxx.data.Prefs
import com.kirshen.traxxx.data.Repository
import com.kirshen.traxxx.data.entities.ActivityEntity
import com.kirshen.traxxx.data.entities.ActivityStatus
import com.kirshen.traxxx.service.RecordingService
import com.kirshen.traxxx.sync.SyncWorker
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onOpen: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Prefs(ctx) }
    val repo = remember { Repository.get(ctx) }
    val snap by prefs.flow.collectAsState(initial = null)

    val userId = snap?.userId
    val list = if (userId != null) {
        repo.observeForUser(userId).collectAsState(initial = emptyList()).value
    } else emptyList()

    var refreshing by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<ActivityEntity?>(null) }
    var page by remember { mutableIntStateOf(0) }
    val pageSize = 25
    val totalPages = ((list.size + pageSize - 1) / pageSize).coerceAtLeast(1)
    val safePage = page.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
    val pageList = remember(list, safePage) {
        val start = safePage * pageSize
        val end = (start + pageSize).coerceAtMost(list.size)
        if (start < end) list.subList(start, end) else emptyList()
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            scope.launch {
                runCatching { SyncWorker.refreshHistory(ctx) }
                refreshing = false
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (list.isEmpty()) {
                Text(
                    "No activities yet. Pull to refresh.",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val start = safePage * pageSize + 1
                    val end = safePage * pageSize + pageList.size
                    Text(
                        "Showing $start–$end of ${list.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = { if (safePage > 0) page = safePage - 1 },
                        enabled = safePage > 0,
                    ) { Text("Prev") }
                    OutlinedButton(
                        onClick = { if (safePage < totalPages - 1) page = safePage + 1 },
                        enabled = safePage < totalPages - 1,
                        modifier = Modifier.padding(start = 6.dp),
                    ) { Text("Next") }
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(pageList, key = { it.clientUuid }) { a ->
                        ActivityRow(
                            a,
                            onClick = { onOpen(a.clientUuid) },
                            onLongPress = { toDelete = a },
                        )
                    }
                }
            }
        }
    }

    toDelete?.let { a ->
        val isInProgress = a.stoppedAt == null
        AlertDialog(
            onDismissRequest = { toDelete = null },
            confirmButton = {
                Button(
                    onClick = {
                        toDelete = null
                        scope.launch {
                            if (isInProgress) RecordingService.stop(ctx)
                            repo.deleteActivity(ctx, a.clientUuid)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("Delete") }
            },
            dismissButton = { OutlinedButton(onClick = { toDelete = null }) { Text("Cancel") } },
            title = { Text(if (isInProgress) "Delete in-progress activity?" else "Delete this activity?") },
            text = {
                Text(
                    if (isInProgress) "Recording will stop and all GPS points will be deleted."
                    else "This will remove it from the server and this device."
                )
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActivityRow(a: ActivityEntity, onClick: () -> Unit, onLongPress: () -> Unit) {
    val label = ActivityType.fromName(a.typeName)?.label ?: a.typeName
    val date = DATE_FMT.format(Date(a.startedAt))
    val dur = a.totalSeconds?.let { s -> "${s / 60}m ${s % 60}s" } ?: "in progress"
    Card(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongPress)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(a.name ?: ActivityType.defaultName(a.typeName, a.startedAt),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
                if (a.status == ActivityStatus.PENDING) {
                    AssistChip(
                        onClick = {},
                        label = { Text("pending", style = MaterialTheme.typography.labelSmall) },
                        colors = AssistChipDefaults.assistChipColors(
                            labelColor = MaterialTheme.colorScheme.error,
                        ),
                    )
                }
            }
            Row {
                Text(
                    "$label · $date",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    dur,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }
}

private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
