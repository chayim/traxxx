package com.kirshen.traxxx.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.kirshen.traxxx.data.Prefs
import com.kirshen.traxxx.data.Repository
import com.kirshen.traxxx.data.entities.WorkoutEntity
import com.kirshen.traxxx.data.entities.WorkoutExerciseEntity
import com.kirshen.traxxx.data.entities.WorkoutSetEntity
import com.kirshen.traxxx.data.entities.WorkoutStatus
import com.kirshen.traxxx.sync.SyncScheduler
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private data class ExerciseEdit(
    val clientUuid: String,
    var name: String,
    var targetSets: String,
    var targetReps: String,
    var targetSeconds: String,
    val sets: SnapshotStateList<SetEdit>,
)

private data class SetEdit(
    val clientUuid: String,
    var reps: String,
    var seconds: String,
    var mode: SetMode,
)

private enum class SetMode { REPS, SECONDS }

@Composable
fun WorkoutEditScreen(
    editingUuid: String?,
    seedFromTemplateId: Long?,
    onDone: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { Repository.get(ctx) }
    val prefs = remember { Prefs(ctx) }
    val snap by prefs.flow.collectAsState(initial = null)

    var name by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(todayStr()) }
    var notes by remember { mutableStateOf("") }
    val exercises = remember { mutableStateListOf<ExerciseEdit>() }
    var workoutUuid by remember { mutableStateOf(editingUuid ?: UUID.randomUUID().toString()) }
    var templateServerId by remember { mutableStateOf<Long?>(null) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(editingUuid, seedFromTemplateId) {
        if (loaded) return@LaunchedEffect
        if (editingUuid != null) {
            val detail = repo.workoutDetail(editingUuid)
            if (detail != null) {
                name = detail.workout.name
                date = detail.workout.workoutDate
                notes = detail.workout.notes.orEmpty()
                templateServerId = detail.workout.templateServerId
                exercises.clear()
                for ((ex, sets) in detail.exercises) {
                    exercises.add(ex.toEdit(sets))
                }
                workoutUuid = detail.workout.clientUuid
            }
        } else if (seedFromTemplateId != null) {
            val tpl = repo.templateDetail(seedFromTemplateId)
            if (tpl != null) {
                name = tpl.first.name
                templateServerId = tpl.first.serverId
                exercises.clear()
                for (e in tpl.second) {
                    exercises.add(
                        ExerciseEdit(
                            clientUuid = UUID.randomUUID().toString(),
                            name = e.name,
                            targetSets = e.targetSets?.toString().orEmpty(),
                            targetReps = e.targetReps?.toString().orEmpty(),
                            targetSeconds = e.targetSeconds?.toString().orEmpty(),
                            sets = mutableStateListOf(),
                        )
                    )
                }
            }
        }
        loaded = true
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (editingUuid != null) "Edit workout" else "New workout",
            style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") })
        OutlinedTextField(date, { date = it }, Modifier.fillMaxWidth(), label = { Text("Date (yyyy-MM-dd)") })
        OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = { Text("Notes") })

        Text("Exercises", style = MaterialTheme.typography.titleMedium)
        for ((i, e) in exercises.withIndex()) {
            ExerciseCard(
                index = i + 1,
                e = e,
                onRemove = { exercises.removeAt(i) },
                onAddSet = { e.sets.add(SetEdit(UUID.randomUUID().toString(), "", "", SetMode.REPS)) },
                onRemoveSet = { s -> e.sets.remove(s) },
            )
        }
        OutlinedButton(onClick = {
            exercises.add(ExerciseEdit(
                clientUuid = UUID.randomUUID().toString(),
                name = "", targetSets = "", targetReps = "", targetSeconds = "",
                sets = mutableStateListOf(),
            ))
        }) { Icon(Icons.Outlined.Add, null); Text("  Add exercise") }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = name.isNotBlank() && date.isNotBlank() && snap?.userId != null,
                onClick = {
                    val userId = snap?.userId ?: return@Button
                    scope.launch {
                        val draft = Repository.WorkoutDraft(
                            workout = WorkoutEntity(
                                clientUuid = workoutUuid,
                                userId = userId,
                                templateServerId = templateServerId,
                                name = name.trim(),
                                workoutDate = date.trim(),
                                notes = notes.trim().ifBlank { null },
                                status = WorkoutStatus.PENDING,
                            ),
                            exercises = exercises.mapIndexed { idx, e ->
                                val ex = WorkoutExerciseEntity(
                                    clientUuid = e.clientUuid,
                                    workoutClientUuid = workoutUuid,
                                    position = idx + 1,
                                    name = e.name.trim(),
                                    targetSets = e.targetSets.toIntOrNull(),
                                    targetReps = e.targetReps.toIntOrNull(),
                                    targetSeconds = e.targetSeconds.toIntOrNull(),
                                )
                                val sets = e.sets.mapIndexed { j, s ->
                                    WorkoutSetEntity(
                                        clientUuid = s.clientUuid,
                                        exerciseClientUuid = e.clientUuid,
                                        setNumber = j + 1,
                                        reps = if (s.mode == SetMode.REPS) s.reps.toIntOrNull() else null,
                                        seconds = if (s.mode == SetMode.SECONDS) s.seconds.toIntOrNull() else null,
                                    )
                                }
                                ex to sets
                            },
                        )
                        repo.saveWorkout(draft, markPending = true)
                        SyncScheduler.enqueueOneShot(ctx)
                        onDone()
                    }
                },
            ) { Text("Save") }
            OutlinedButton(onClick = onDone) { Text("Cancel") }
        }
    }
}

@Composable
private fun ExerciseCard(
    index: Int,
    e: ExerciseEdit,
    onRemove: () -> Unit,
    onAddSet: () -> Unit,
    onRemoveSet: (SetEdit) -> Unit,
) {
    var name by remember(e.clientUuid) { mutableStateOf(e.name) }
    var tSets by remember(e.clientUuid) { mutableStateOf(e.targetSets) }
    var tReps by remember(e.clientUuid) { mutableStateOf(e.targetReps) }
    var tSecs by remember(e.clientUuid) { mutableStateOf(e.targetSeconds) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$index.", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    name, { name = it; e.name = it },
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    label = { Text("Exercise") },
                )
                IconButton(onClick = onRemove) { Icon(Icons.Outlined.Delete, null) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NumField(tSets, "sets") { tSets = it; e.targetSets = it }
                NumField(tReps, "reps") { tReps = it; e.targetReps = it }
                NumField(tSecs, "sec") { tSecs = it; e.targetSeconds = it }
            }
            if (e.sets.isNotEmpty()) {
                Text("Sets logged", style = MaterialTheme.typography.labelMedium)
                for ((idx, s) in e.sets.withIndex()) {
                    SetRow(idx + 1, s, onRemove = { onRemoveSet(s) })
                }
            }
            OutlinedButton(onClick = onAddSet) { Text("+ Add set") }
        }
    }
}

@Composable
private fun SetRow(num: Int, s: SetEdit, onRemove: () -> Unit) {
    var reps by remember(s.clientUuid) { mutableStateOf(s.reps) }
    var seconds by remember(s.clientUuid) { mutableStateOf(s.seconds) }
    var mode by remember(s.clientUuid) { mutableStateOf(s.mode) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("#$num", style = MaterialTheme.typography.bodyMedium)
        FilterChip(selected = mode == SetMode.REPS, onClick = { mode = SetMode.REPS; s.mode = mode },
            label = { Text("reps") })
        FilterChip(selected = mode == SetMode.SECONDS, onClick = { mode = SetMode.SECONDS; s.mode = mode },
            label = { Text("sec") })
        if (mode == SetMode.REPS) {
            NumField(reps, "reps", modifier = Modifier.weight(1f)) { reps = it; s.reps = it }
        } else {
            NumField(seconds, "sec", modifier = Modifier.weight(1f)) { seconds = it; s.seconds = it }
        }
        IconButton(onClick = onRemove) { Icon(Icons.Outlined.Delete, null) }
    }
}

@Composable
private fun NumField(value: String, label: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { c -> c.isDigit() }) },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = modifier,
    )
}

private fun WorkoutExerciseEntity.toEdit(sets: List<WorkoutSetEntity>): ExerciseEdit {
    val setsStates = mutableStateListOf<SetEdit>()
    for (s in sets) {
        setsStates.add(
            SetEdit(
                clientUuid = s.clientUuid,
                reps = s.reps?.toString().orEmpty(),
                seconds = s.seconds?.toString().orEmpty(),
                mode = if (s.seconds != null && s.reps == null) SetMode.SECONDS else SetMode.REPS,
            )
        )
    }
    return ExerciseEdit(
        clientUuid = this.clientUuid,
        name = this.name,
        targetSets = this.targetSets?.toString().orEmpty(),
        targetReps = this.targetReps?.toString().orEmpty(),
        targetSeconds = this.targetSeconds?.toString().orEmpty(),
        sets = setsStates,
    )
}

private fun todayStr(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
