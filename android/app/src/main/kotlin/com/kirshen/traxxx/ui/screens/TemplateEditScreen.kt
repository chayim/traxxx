package com.kirshen.traxxx.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kirshen.traxxx.data.Prefs
import com.kirshen.traxxx.data.Repository
import com.kirshen.traxxx.net.CreateWorkoutTemplateDto
import com.kirshen.traxxx.net.NetModule
import com.kirshen.traxxx.net.TemplateExerciseDto
import kotlinx.coroutines.launch

private data class TplExerciseEdit(
    val key: String,
    var name: String,
    var sets: String,
    var reps: String,
    var seconds: String,
)

@Composable
fun TemplateEditScreen(templateId: Long?, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { Repository.get(ctx) }
    val prefs = remember { Prefs(ctx) }
    val snap by prefs.flow.collectAsState(initial = null)

    var name by remember { mutableStateOf("") }
    val exercises = remember { mutableStateListOf<TplExerciseEdit>() }
    var loaded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(templateId) {
        if (loaded) return@LaunchedEffect
        if (templateId != null) {
            val det = repo.templateDetail(templateId)
            if (det != null) {
                name = det.first.name
                exercises.clear()
                for (e in det.second) {
                    exercises.add(
                        TplExerciseEdit(
                            key = e.id.toString(),
                            name = e.name,
                            sets = e.targetSets?.toString().orEmpty(),
                            reps = e.targetReps?.toString().orEmpty(),
                            seconds = e.targetSeconds?.toString().orEmpty(),
                        )
                    )
                }
            }
        }
        loaded = true
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (templateId != null) "Edit template" else "New template",
            style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") })

        Text("Exercises", style = MaterialTheme.typography.titleMedium)
        for ((i, e) in exercises.withIndex()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}.")
                        OutlinedTextField(
                            e.name, { e.name = it; exercises[i] = e.copy(name = it) },
                            modifier = Modifier.weight(1f).padding(start = 8.dp),
                            label = { Text("Exercise") },
                        )
                        IconButton(onClick = { exercises.removeAt(i) }) { Icon(Icons.Outlined.Delete, null) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        NumField(e.sets, "sets") { exercises[i] = e.copy(sets = it) }
                        NumField(e.reps, "reps") { exercises[i] = e.copy(reps = it) }
                        NumField(e.seconds, "sec") { exercises[i] = e.copy(seconds = it) }
                    }
                }
            }
        }
        OutlinedButton(onClick = {
            exercises.add(TplExerciseEdit(
                key = java.util.UUID.randomUUID().toString(),
                name = "", sets = "", reps = "", seconds = "",
            ))
        }) { Icon(Icons.Outlined.Add, null); Text("  Add exercise") }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !saving && name.isNotBlank() && snap?.userId != null,
                onClick = {
                    val userId = snap?.userId ?: return@Button
                    scope.launch {
                        saving = true
                        error = null
                        val body = CreateWorkoutTemplateDto(
                            userId = userId,
                            name = name.trim(),
                            exercises = exercises.mapIndexedNotNull { idx, e ->
                                if (e.name.isBlank()) null
                                else TemplateExerciseDto(
                                    position = idx + 1,
                                    name = e.name.trim(),
                                    targetSets = e.sets.toIntOrNull(),
                                    targetReps = e.reps.toIntOrNull(),
                                    targetSeconds = e.seconds.toIntOrNull(),
                                )
                            },
                        )
                        runCatching {
                            val api = NetModule.api(ctx)
                            val saved = if (templateId != null) api.updateWorkoutTemplate(templateId, body)
                                        else api.createWorkoutTemplate(body)
                            repo.upsertTemplateFromServer(saved)
                        }.onFailure {
                            error = "Save failed (template editing requires network): ${it.message}"
                        }.onSuccess {
                            onDone()
                        }
                        saving = false
                    }
                },
            ) { Text(if (saving) "Saving…" else "Save") }
            OutlinedButton(onClick = onDone) { Text("Cancel") }
        }
    }
}

@Composable
private fun NumField(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { c -> c.isDigit() }) },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
    )
}
