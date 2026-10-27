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
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.kirshen.traxxx.data.entities.WorkoutTemplateCacheEntity
import com.kirshen.traxxx.net.NetModule
import com.kirshen.traxxx.sync.SyncWorker
import kotlinx.coroutines.launch

@Composable
fun WorkoutTemplatesScreen(
    pickerMode: Boolean,
    onNew: () -> Unit,
    onEdit: (Long) -> Unit,
    onUse: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { Repository.get(ctx) }
    val prefs = remember { Prefs(ctx) }
    val snap by prefs.flow.collectAsState(initial = null)
    val userId = snap?.userId
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(userId) {
        if (userId != null) runCatching { SyncWorker.refreshTemplates(ctx) }
    }

    val list = if (userId != null) repo.observeTemplates(userId).collectAsState(initial = emptyList()).value
               else emptyList()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (pickerMode) "Choose template" else "Templates",
                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onBack) { Text("Back") }
            }
            Row {
                OutlinedButton(onClick = {
                    scope.launch {
                        status = "loading…"
                        runCatching { SyncWorker.refreshTemplates(ctx) }
                            .onSuccess { status = "updated" }
                            .onFailure { status = "offline" }
                    }
                }) { Text("Refresh") }
                status?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 8.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }

            if (list.isEmpty()) {
                Text("No templates.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(list, key = { it.serverId }) { t ->
                        TemplateRow(t, pickerMode, onEdit = onEdit, onUse = onUse, onDelete = {
                            scope.launch {
                                runCatching { NetModule.api(ctx).deleteWorkoutTemplate(t.serverId) }
                                repo.deleteTemplateLocal(t.serverId)
                            }
                        })
                    }
                }
            }
        }

        if (!pickerMode) {
            ExtendedFloatingActionButton(
                onClick = onNew,
                icon = { Icon(Icons.Outlined.Add, null) },
                text = { Text("New template") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }
}

@Composable
private fun TemplateRow(
    t: WorkoutTemplateCacheEntity,
    pickerMode: Boolean,
    onEdit: (Long) -> Unit,
    onUse: (Long) -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(t.name, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onUse(t.serverId) }) { Text("Use") }
                if (!pickerMode) {
                    TextButton(onClick = { onEdit(t.serverId) }) { Text("Edit") }
                    TextButton(onClick = onDelete) { Text("Delete") }
                }
            }
        }
    }
}
