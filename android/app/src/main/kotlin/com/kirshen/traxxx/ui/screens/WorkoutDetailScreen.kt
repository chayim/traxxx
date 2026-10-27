package com.kirshen.traxxx.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kirshen.traxxx.data.Repository
import com.kirshen.traxxx.net.NetModule
import kotlinx.coroutines.launch

@Composable
fun WorkoutDetailScreen(uuid: String, onEdit: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { Repository.get(ctx) }
    var detail by remember { mutableStateOf<Repository.WorkoutDraft?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(uuid) { detail = repo.workoutDetail(uuid) }

    val d = detail
    if (d == null) {
        Column(Modifier.fillMaxSize().padding(16.dp)) { Text("Loading…") }
        return
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(d.workout.name, style = MaterialTheme.typography.titleLarge)
        Text(d.workout.workoutDate, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        if (!d.workout.notes.isNullOrBlank()) Text(d.workout.notes!!)

        for ((idx, pair) in d.exercises.withIndex()) {
            val (ex, sets) = pair
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${idx + 1}. ${ex.name}", style = MaterialTheme.typography.titleMedium)
                    val targets = buildList {
                        ex.targetSets?.let { add("$it sets") }
                        ex.targetReps?.let { add("$it reps") }
                        ex.targetSeconds?.let { add("${it}s") }
                    }.joinToString(" · ")
                    if (targets.isNotEmpty()) {
                        Text("target: $targets", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                    for (s in sets) {
                        val value = s.reps?.let { "$it reps" } ?: s.seconds?.let { "${it}s" } ?: "—"
                        Text("set ${s.setNumber}: $value", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onEdit) { Text("Edit") }
            OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete") }
            OutlinedButton(onClick = onDone) { Text("Back") }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete workout?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        val serverId = d.workout.serverId
                        if (serverId != null) {
                            runCatching {
                                NetModule.api(ctx).deleteWorkout(serverId)
                            }
                        }
                        repo.discardWorkout(uuid)
                        onDone()
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
