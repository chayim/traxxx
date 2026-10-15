package com.kirshen.traxxx.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kirshen.traxxx.data.Prefs
import com.kirshen.traxxx.net.NetModule
import com.kirshen.traxxx.net.UserDto
import com.kirshen.traxxx.sync.SyncScheduler
import com.kirshen.traxxx.data.Repository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Prefs(ctx) }
    val snap by prefs.flow.collectAsState(initial = null)
    val pending by Repository.get(ctx).pendingCountFlow().collectAsState(initial = 0)

    var apiBase by remember { mutableStateOf("") }
    var users by remember { mutableStateOf<List<UserDto>>(emptyList()) }
    var pickerOpen by remember { mutableStateOf(false) }
    var pickedName by remember { mutableStateOf("") }

    LaunchedEffect(snap) {
        snap?.let {
            apiBase = it.apiBase
            pickedName = it.userName.orEmpty()
        }
    }
    LaunchedEffect(apiBase) {
        if (apiBase.isNotBlank()) {
            users = runCatching { NetModule.build(apiBase).listUsers() }.getOrDefault(emptyList())
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = apiBase, onValueChange = { apiBase = it },
            label = { Text("Server URL") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        ExposedDropdownMenuBox(expanded = pickerOpen, onExpandedChange = { pickerOpen = !pickerOpen }) {
            OutlinedTextField(
                value = pickedName,
                onValueChange = {},
                readOnly = true,
                label = { Text("User") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = pickerOpen) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
            )
            androidx.compose.material3.ExposedDropdownMenu(
                expanded = pickerOpen,
                onDismissRequest = { pickerOpen = false },
            ) {
                users.forEach { u ->
                    DropdownMenuItem(
                        text = { Text(u.name) },
                        onClick = {
                            pickedName = u.name
                            pickerOpen = false
                            scope.launch { prefs.setUser(u.id, u.name) }
                        },
                    )
                }
            }
        }

        Button(onClick = { scope.launch { prefs.setApiBase(apiBase.trim()) } }, modifier = Modifier.fillMaxWidth()) {
            Text("Save server URL")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { SyncScheduler.enqueueOneShot(ctx) }, modifier = Modifier.weight(1f)) {
                Text("Flush queue now")
            }
            OutlinedButton(onClick = { scope.launch { prefs.clearUser() } }, modifier = Modifier.weight(1f)) {
                Text("Switch user…")
            }
        }

        Text("Pending uploads: $pending", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Text(
            "Pending activities are stored on-device and uploaded whenever the server is reachable.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}
