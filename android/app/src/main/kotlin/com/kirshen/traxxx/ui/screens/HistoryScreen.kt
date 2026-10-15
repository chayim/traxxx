package com.kirshen.traxxx.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import com.kirshen.traxxx.data.ActivityType
import com.kirshen.traxxx.data.Prefs
import com.kirshen.traxxx.data.Repository
import com.kirshen.traxxx.data.entities.ActivityEntity
import com.kirshen.traxxx.data.entities.ActivityStatus
import com.kirshen.traxxx.sync.SyncWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Prefs(ctx) }
    val repo = remember { Repository.get(ctx) }
    val snap by prefs.flow.collectAsState(initial = null)

    val userId = snap?.userId
    val list = if (userId != null) {
        repo.observeForUser(userId).collectAsState(initial = emptyList()).value
    } else emptyList()

    var status by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                scope.launch {
                    status = "loading…"
                    runCatching { SyncWorker.refreshHistory(ctx) }
                        .onSuccess { status = "updated" }
                        .onFailure { status = "offline — showing cached" }
                }
            }) { Text("Refresh from server") }
            status?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }

        if (list.isEmpty()) {
            Text("No activities yet.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(list, key = { it.clientUuid }) { ActivityRow(it) }
            }
        }
    }
}

@Composable
private fun ActivityRow(a: ActivityEntity) {
    val label = ActivityType.fromName(a.typeName)?.label ?: a.typeName
    val date = DATE_FMT.format(Date(a.startedAt))
    val dur = a.totalSeconds?.let { s -> "${s / 60}m ${s % 60}s" } ?: "in progress"
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(a.name ?: "(unnamed)", style = MaterialTheme.typography.titleMedium,
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
