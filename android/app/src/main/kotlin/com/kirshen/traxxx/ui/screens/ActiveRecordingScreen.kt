package com.kirshen.traxxx.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirshen.traxxx.data.ActivityType
import com.kirshen.traxxx.data.Repository
import com.kirshen.traxxx.service.RecordingService
import com.kirshen.traxxx.service.RecordingState
import com.kirshen.traxxx.sync.SyncScheduler
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun ActiveRecordingScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { Repository.get(ctx) }
    val inProgress by repo.inProgressFlow().collectAsState(initial = null)
    val live by RecordingState.state.collectAsState()

    var name by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var confirmDiscard by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    // Load name/notes once when the row appears, so the user's edits aren't clobbered on every tick.
    LaunchedEffect(inProgress?.clientUuid) {
        inProgress?.let {
            name = it.name.orEmpty()
            notes = it.notes.orEmpty()
        }
    }

    // Persist edits (debounced).
    LaunchedEffect(name, notes, inProgress?.clientUuid) {
        val uuid = inProgress?.clientUuid ?: return@LaunchedEffect
        delay(400)
        repo.updateNameAndNotes(uuid, name, notes)
    }

    // Ticker for the elapsed display.
    LaunchedEffect(inProgress?.clientUuid) {
        while (inProgress != null) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }

    val row = inProgress ?: return
    val label = ActivityType.fromName(row.typeName)?.label ?: row.typeName
    val elapsed = (now - row.startedAt).coerceAtLeast(0) / 1000

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("$label · recording", style = MaterialTheme.typography.titleLarge)
        Text(
            formatElapsed(elapsed),
            fontSize = 56.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            StatCard("Points", (live?.pointCount ?: 0).toString(), Modifier.weight(1f))
            StatCard(
                "Accuracy",
                live?.lastAccuracyM?.let { "${it.toInt()} m" } ?: "—",
                Modifier.weight(1f),
            )
            StatCard(
                "Last fix",
                live?.lastFixAt?.let { formatTime(it) } ?: "—",
                Modifier.weight(1f),
            )
        }

        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Name") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = notes, onValueChange = { notes = it },
            label = { Text("Notes") },
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = {
                scope.launch {
                    repo.finish(row.clientUuid, name, notes)
                    RecordingService.stop(ctx)
                    SyncScheduler.enqueueOneShot(ctx)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Stop & save") }

        OutlinedButton(
            onClick = { confirmDiscard = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text("Discard") }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            confirmButton = {
                Button(onClick = {
                    confirmDiscard = false
                    scope.launch {
                        repo.discard(row.clientUuid)
                        RecordingService.stop(ctx)
                    }
                }) { Text("Discard") }
            },
            dismissButton = { OutlinedButton(onClick = { confirmDiscard = false }) { Text("Cancel") } },
            title = { Text("Discard this recording?") },
            text  = { Text("All GPS points for this activity will be deleted.") },
        )
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}

private fun formatElapsed(secs: Long): String {
    val h = secs / 3600; val m = (secs % 3600) / 60; val s = secs % 60
    return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
}
private fun formatTime(ms: Long): String {
    val fmt = java.text.SimpleDateFormat("HH:mm:ss", Locale.US)
    return fmt.format(java.util.Date(ms))
}
