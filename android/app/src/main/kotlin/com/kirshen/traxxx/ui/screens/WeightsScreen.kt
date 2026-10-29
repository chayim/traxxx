package com.kirshen.traxxx.ui.screens

import android.app.DatePickerDialog
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import com.kirshen.traxxx.data.Prefs
import com.kirshen.traxxx.net.CreateWeightDto
import com.kirshen.traxxx.net.NetModule
import com.kirshen.traxxx.net.UpdateWeightDto
import com.kirshen.traxxx.net.WeightDto
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val DateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

@Composable
fun WeightsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Prefs(ctx) }
    val snap by prefs.flow.collectAsState(initial = null)
    val userId = snap?.userId

    var entries by remember { mutableStateOf<List<WeightDto>>(emptyList()) }
    var kgInput by remember { mutableStateOf("") }
    var dateInput by remember { mutableStateOf(DateFmt.format(Date())) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadTick by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<WeightDto?>(null) }
    val pageSize = 25

    LaunchedEffect(userId, reloadTick) {
        if (userId != null) {
            entries = runCatching { NetModule.api(ctx).listWeights(userId).items }
                .getOrDefault(emptyList())
                .sortedBy { it.date }
        }
    }

    val reversed = remember(entries) { entries.reversed() }
    val totalPages = ((reversed.size + pageSize - 1) / pageSize).coerceAtLeast(1)
    val safePage = page.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
    val pageEntries = remember(reversed, safePage) {
        val start = safePage * pageSize
        val end = (start + pageSize).coerceAtMost(reversed.size)
        if (start < end) reversed.subList(start, end) else emptyList()
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Weight", style = MaterialTheme.typography.titleLarge)

        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Trend", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f))
                    val last = entries.lastOrNull()
                    if (last != null) {
                        Text(
                            "Latest: ${"%.1f".format(last.weightKg)} kg · ${last.date}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                }
                WeightChart(
                    entries,
                    modifier = Modifier.fillMaxWidth().height(220.dp).padding(top = 8.dp),
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Log entry", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = kgInput,
                    onValueChange = { kgInput = it; error = null },
                    label = { Text("Weight (kg)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = {
                        val cal = Calendar.getInstance()
                        runCatching { cal.time = DateFmt.parse(dateInput)!! }
                        DatePickerDialog(
                            ctx,
                            { _, y, m, d ->
                                cal.set(y, m, d)
                                dateInput = DateFmt.format(cal.time)
                            },
                            cal.get(Calendar.YEAR),
                            cal.get(Calendar.MONTH),
                            cal.get(Calendar.DAY_OF_MONTH),
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Date: $dateInput") }

                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = {
                        val kg = kgInput.replace(',', '.').toDoubleOrNull()
                        if (kg == null || kg <= 0) {
                            error = "Enter a valid weight"
                            return@Button
                        }
                        if (userId == null) {
                            error = "No user selected"
                            return@Button
                        }
                        scope.launch {
                            val ok = runCatching {
                                NetModule.api(ctx).createWeight(
                                    CreateWeightDto(userId = userId, weightKg = kg, date = dateInput),
                                )
                            }.isSuccess
                            if (ok) {
                                kgInput = ""
                                reloadTick++
                            } else {
                                error = "Failed to save — check connection"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Add entry") }
            }
        }

        if (entries.isEmpty()) {
            Text(
                "No weight entries yet.",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        } else {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val start = safePage * pageSize + 1
                val end = (safePage * pageSize + pageEntries.size)
                Text(
                    "Showing $start–$end of ${reversed.size}",
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
                items(pageEntries, key = { it.id }) { w ->
                    WeightRow(
                        w,
                        onEdit = { editing = w },
                        onDelete = {
                            scope.launch {
                                runCatching { NetModule.api(ctx).deleteWeight(w.id) }
                                reloadTick++
                            }
                        },
                    )
                }
            }
        }
    }

    editing?.let { target ->
        EditWeightDialog(
            initial = target,
            onDismiss = { editing = null },
            onSave = { kg, date ->
                scope.launch {
                    val ok = runCatching {
                        NetModule.api(ctx).updateWeight(
                            target.id,
                            UpdateWeightDto(weightKg = kg, date = date),
                        )
                    }.isSuccess
                    editing = null
                    if (ok) reloadTick++
                }
            },
        )
    }
}

@Composable
private fun EditWeightDialog(
    initial: WeightDto,
    onDismiss: () -> Unit,
    onSave: (Double, String) -> Unit,
) {
    val ctx = LocalContext.current
    var kg by remember { mutableStateOf("%.1f".format(initial.weightKg)) }
    var date by remember { mutableStateOf(initial.date) }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit entry") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = kg,
                    onValueChange = { kg = it; err = null },
                    label = { Text("Weight (kg)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedButton(onClick = {
                    val cal = Calendar.getInstance()
                    runCatching { cal.time = DateFmt.parse(date)!! }
                    DatePickerDialog(
                        ctx,
                        { _, y, m, d ->
                            cal.set(y, m, d)
                            date = DateFmt.format(cal.time)
                        },
                        cal.get(Calendar.YEAR),
                        cal.get(Calendar.MONTH),
                        cal.get(Calendar.DAY_OF_MONTH),
                    ).show()
                }) { Text("Date: $date") }
                err?.let {
                    Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val v = kg.replace(',', '.').toDoubleOrNull()
                if (v == null || v <= 0) { err = "Enter a valid weight"; return@Button }
                onSave(v, date)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun WeightRow(w: WeightDto, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(w.date, modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium)
            Text(
                "${"%.1f".format(w.weightKg)} kg",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
            )
            IconButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, contentDescription = "Edit")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun WeightChart(entries: List<WeightDto>, modifier: Modifier = Modifier) {
    val accent = Color(0xFF7CF3C8)
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val label = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)

    Box(modifier) {
        if (entries.isEmpty()) {
            Text(
                "No entries yet.",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.align(Alignment.Center),
            )
            return@Box
        }
        Canvas(Modifier.fillMaxSize()) {
            val padL = 44f
            val padR = 12f
            val padT = 12f
            val padB = 28f
            val w = size.width - padL - padR
            val h = size.height - padT - padB

            val vals = entries.map { it.weightKg }
            var mn = vals.min()
            var mx = vals.max()
            if (mn == mx) { mn -= 1.0; mx += 1.0 }
            val range = mx - mn
            val pMin = mn - range * 0.1
            val pMax = mx + range * 0.1

            val ts = entries.map {
                runCatching { DateFmt.parse(it.date)!!.time }.getOrDefault(0L)
            }
            val tMin = ts.first()
            val tMax = ts.last()
            val tRange = (tMax - tMin).coerceAtLeast(1L)

            fun x(i: Int): Float =
                if (entries.size == 1) padL + w / 2f
                else padL + ((ts[i] - tMin).toDouble() / tRange * w).toFloat()
            fun y(v: Double): Float =
                padT + ((1.0 - (v - pMin) / (pMax - pMin)) * h).toFloat()

            // Grid + y labels
            val ticks = 4
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(
                    (label.alpha * 255).toInt(),
                    (label.red * 255).toInt(),
                    (label.green * 255).toInt(),
                    (label.blue * 255).toInt(),
                )
                textSize = 11.sp.toPx()
                isAntiAlias = true
            }
            for (i in 0..ticks) {
                val v = pMin + (pMax - pMin) * (i.toDouble() / ticks)
                val yy = y(v)
                drawLine(
                    color = grid,
                    start = Offset(padL, yy),
                    end = Offset(size.width - padR, yy),
                    strokeWidth = 1f,
                )
                drawContext.canvas.nativeCanvas.drawText(
                    "%.1f".format(v), padL - 6f, yy + 4f,
                    paint.apply { textAlign = android.graphics.Paint.Align.RIGHT },
                )
            }

            // X labels
            val xIdx = when (entries.size) {
                1 -> listOf(0)
                2 -> listOf(0, 1)
                else -> listOf(0, entries.size / 2, entries.size - 1)
            }
            paint.textAlign = android.graphics.Paint.Align.CENTER
            xIdx.forEach { i ->
                drawContext.canvas.nativeCanvas.drawText(
                    entries[i].date.substring(5),
                    x(i), size.height - padB + 18f, paint,
                )
            }

            // Area + line
            if (entries.size > 1) {
                val linePath = Path().apply {
                    moveTo(x(0), y(entries[0].weightKg))
                    for (i in 1 until entries.size) lineTo(x(i), y(entries[i].weightKg))
                }
                val areaPath = Path().apply {
                    moveTo(x(0), y(entries[0].weightKg))
                    for (i in 1 until entries.size) lineTo(x(i), y(entries[i].weightKg))
                    lineTo(x(entries.size - 1), padT + h)
                    lineTo(x(0), padT + h)
                    close()
                }
                drawPath(
                    path = areaPath,
                    brush = Brush.verticalGradient(
                        0f to accent.copy(alpha = 0.35f),
                        1f to accent.copy(alpha = 0f),
                        startY = padT,
                        endY = padT + h,
                    ),
                )
                drawPath(
                    path = linePath,
                    color = accent,
                    style = Stroke(width = 2.5f),
                )
            }

            // Points
            entries.forEachIndexed { i, e ->
                drawCircle(
                    color = Color(0xFF0A0D12),
                    radius = 4f,
                    center = Offset(x(i), y(e.weightKg)),
                )
                drawCircle(
                    color = accent,
                    radius = 4f,
                    center = Offset(x(i), y(e.weightKg)),
                    style = Stroke(width = 2f),
                )
            }

            // suppress unused warning
            @Suppress("UNUSED_EXPRESSION") Size(w, h)
        }
    }
}
