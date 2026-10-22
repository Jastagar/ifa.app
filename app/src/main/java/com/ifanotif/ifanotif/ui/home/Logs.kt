package com.ifanotif.ifanotif.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.ifanotif.ifanotif.utils.AppLogger

@Composable
fun Logs() {
    val logs by AppLogger.logs.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        logs.take(100).forEach { log ->
            Text("[${log.timestamp}] ${log.message}", style = MaterialTheme.typography.bodySmall)
        }
    }
}
