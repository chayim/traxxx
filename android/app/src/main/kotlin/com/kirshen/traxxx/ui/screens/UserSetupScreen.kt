package com.kirshen.traxxx.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kirshen.traxxx.data.Prefs
import com.kirshen.traxxx.net.CreateUserDto
import com.kirshen.traxxx.net.NetModule
import com.kirshen.traxxx.net.UserDto
import kotlinx.coroutines.launch

@Composable
fun UserSetupScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    val snap by prefs.flow.collectAsState(initial = null)

    var apiBase by remember { mutableStateOf(Prefs.DEFAULT_API_BASE) }
    var newName by remember { mutableStateOf("") }
    var users by remember { mutableStateOf<List<UserDto>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(snap) { snap?.apiBase?.let { apiBase = it } }
    LaunchedEffect(apiBase) {
        error = null
        users = runCatching { NetModule.build(apiBase).listUsers() }
            .onFailure { error = "Server unreachable: ${it.message}" }
            .getOrDefault(emptyList())
    }

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Pick a user", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = apiBase,
            onValueChange = { apiBase = it },
            label = { Text("Server URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = { scope.launch { prefs.setApiBase(apiBase.trim()) } }) {
            Text("Save server & reload users")
        }
        error?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(users) { u ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(u.name, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            scope.launch {
                                prefs.setApiBase(apiBase.trim())
                                prefs.setUser(u.id, u.name)
                                onDone()
                            }
                        }) { Text("Use") }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = newName,
            onValueChange = { newName = it },
            label = { Text("New user name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                scope.launch {
                    try {
                        val created = NetModule.build(apiBase.trim())
                            .createUser(CreateUserDto(newName.trim()))
                        prefs.setApiBase(apiBase.trim())
                        prefs.setUser(created.id, created.name)
                        onDone()
                    } catch (t: Throwable) {
                        error = "Create failed: ${t.message}"
                    }
                }
            },
            enabled = newName.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Create & use") }
    }
}
