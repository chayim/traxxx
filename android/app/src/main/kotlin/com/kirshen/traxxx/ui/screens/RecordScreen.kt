package com.kirshen.traxxx.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirshen.traxxx.data.ActivityType
import com.kirshen.traxxx.data.Prefs
import com.kirshen.traxxx.data.Repository
import com.kirshen.traxxx.service.RecordingService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun RecordScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Prefs(ctx) }
    val snap by prefs.flow.collectAsState(initial = null)

    var selected by remember { mutableStateOf<ActivityType?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(snap?.lastActivityType) {
        if (selected == null) {
            selected = ActivityType.fromName(snap?.lastActivityType) ?: ActivityType.WALK
        }
    }

    val current = selected ?: ActivityType.WALK

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Record", style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.Start))

        Spacer(Modifier.weight(1f))

        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { menuOpen = true },
                modifier = Modifier.fillMaxWidth().height(64.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(current.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (current.gps) "GPS tracked" else "No GPS",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    )
                }
                if (current.gps) {
                    Badge { Text("GPS") }
                    Spacer(Modifier.size(8.dp))
                }
                Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose activity")
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                modifier = Modifier.fillMaxWidth(0.9f),
            ) {
                ActivityType.entries.forEach { t ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(t.label, modifier = Modifier.weight(1f))
                                if (t.gps) Badge { Text("GPS") }
                            }
                        },
                        leadingIcon = if (t == current) {
                            { Icon(Icons.Default.Check, contentDescription = null) }
                        } else null,
                        onClick = {
                            selected = t
                            menuOpen = false
                            scope.launch { prefs.setLastActivityType(t.typeName) }
                        },
                    )
                }
            }
        }

        Button(
            onClick = {
                scope.launch {
                    val userId = prefs.flow.first().userId ?: return@launch
                    prefs.setLastActivityType(current.typeName)
                    Repository.get(ctx).startRecording(userId, current)
                    RecordingService.start(ctx)
                }
            },
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            modifier = Modifier.size(180.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Start", modifier = Modifier.size(72.dp))
                Text("START", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.weight(1f))
    }
}
