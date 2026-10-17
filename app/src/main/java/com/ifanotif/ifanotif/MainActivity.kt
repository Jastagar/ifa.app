package com.ifanotif.ifanotif

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ifanotif.ifanotif.api.RetrofitClient
import com.ifanotif.ifanotif.ui.theme.IfanotifTheme
import com.ifanotif.ifanotif.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        RetrofitClient.initialize(applicationContext)

        setContent {
            IfanotifTheme {
                Scaffold { padding ->
                    HomeScreen(
                        modifier = Modifier.padding(padding),
                        onOpenSettings = {
                            startActivity(
                                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit
) {
    val logs by AppLogger.logs.collectAsState()
    val scope = rememberCoroutineScope()

    var backendUrl by remember {
        mutableStateOf(RetrofitClient.currentBaseUrl())
    }
    var backendStatus by remember { mutableStateOf("Not Tested") }
    var urlMessage by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "Notification Bridge",
            style = MaterialTheme.typography.headlineMedium
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Notification Access: Check in Settings")
                Text("Backend: $backendStatus")

                OutlinedTextField(
                    value = backendUrl,
                    onValueChange = {
                        backendUrl = it
                        urlMessage = ""
                    },
                    label = { Text("Backend Base URL") },
                    placeholder = { Text("http://192.168.1.11:8080/") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = {
                        try {
                            RetrofitClient.saveBaseUrl(backendUrl)
                            backendUrl = RetrofitClient.currentBaseUrl()
                            urlMessage = "Backend URL saved"
                            backendStatus = "Not Tested"
                        } catch (e: IllegalArgumentException) {
                            urlMessage = e.message ?: "Invalid URL"
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save Backend URL")
                }

                if (urlMessage.isNotBlank()) {
                    Text(urlMessage, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Button(
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Grant Notification Access")
        }

        Button(
            onClick = {
                backendStatus = "Testing..."

                scope.launch {
                    try {
                        val response = withContext(Dispatchers.IO) {
                            RetrofitClient.api.health()
                        }

                        backendStatus = if (response.isSuccessful) {
                            "Connected ✅"
                        } else {
                            "Failed (${response.code()})"
                        }
                    } catch (e: Exception) {
                        backendStatus = e.message ?: "Unknown Error"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Test Backend")
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth()
        ) {
            items(logs) { log ->
                Text(
                    text = "[${log.timestamp}] ${log.message}",
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}