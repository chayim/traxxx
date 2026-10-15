package com.kirshen.traxxx.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kirshen.traxxx.data.ActivityType
import com.kirshen.traxxx.data.Prefs
import com.kirshen.traxxx.data.Repository
import com.kirshen.traxxx.service.RecordingService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.wrapContentHeight

@Composable
fun RecordScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Start an activity", style = MaterialTheme.typography.titleLarge)
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(ActivityType.entries) { t ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .clickable {
                            scope.launch {
                                val userId = Prefs(ctx).flow.first().userId ?: return@launch
                                Repository.get(ctx).startRecording(userId, t)
                                RecordingService.start(ctx)
                            }
                        },
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(t.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            if (t.gps) Badge { Text("GPS") }
                        }
                        Text(
                            if (t.gps) "Records a GPS track." else "Records time only.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }
    }
}
