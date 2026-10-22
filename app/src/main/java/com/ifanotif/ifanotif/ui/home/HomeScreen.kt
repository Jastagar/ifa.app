package com.ifanotif.ifanotif.ui.home

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ifanotif.ifanotif.ifa.ChatLine
import com.ifanotif.ifanotif.ifa.IfaSettings
import com.ifanotif.ifanotif.ifa.IfaState
import com.ifanotif.ifanotif.ifa.Phase
import com.ifanotif.ifanotif.service.IfaVoiceService
import com.ifanotif.ifanotif.ui.settings.SettingsCard
import com.ifanotif.ifanotif.utils.CrashLog
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val phase by IfaState.phase.collectAsState()
    val pcName by IfaState.pcName.collectAsState()
    val problem by IfaState.problem.collectAsState()
    val chat by IfaState.chat.collectAsState()
    var showSettings by remember { mutableStateOf(IfaSettings.pairingCode.isBlank()) }
    var showLogs by remember { mutableStateOf(false) }

    val permissions = remember {
        buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) {
            IfaVoiceService.start(context)
        } else {
            IfaState.setProblem("Ifa needs the microphone permission to listen.")
        }
    }
    val startIfa = {
        val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        if (micGranted) IfaVoiceService.start(context) else permissionLauncher.launch(permissions)
    }

    LaunchedEffect(Unit) {
        if (!IfaVoiceService.isRunning && IfaSettings.pairingCode.isNotBlank()) startIfa()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 16.dp)
    ) {
    LazyColumn(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(Modifier.size(8.dp))
            Text("Ifa", style = MaterialTheme.typography.headlineMedium)
        }

        item { LastCrash() }

        item {
            StatusCard(
                phase = phase,
                pcName = pcName,
                problem = problem,
                onStart = startIfa,
                onStop = { IfaVoiceService.stop(context) }
            )
        }

        if (phase != Phase.STOPPED) {
            item {
                TalkButton(phase = phase, enabled = pcName != null) {
                    IfaVoiceService.send(context, IfaVoiceService.ACTION_TALK)
                }
            }
            item { if (phase == Phase.LISTENING) MicMeter() else WakeMeter() }
        }

        item { Conversation(chat) }

        item {
            TextButton(onClick = { showSettings = !showSettings }) {
                Text(if (showSettings) "Hide settings" else "Settings")
            }
        }
        if (showSettings) item { SettingsCard() }

        item {
            TextButton(onClick = { showLogs = !showLogs }) {
                Text(if (showLogs) "Hide logs" else "Logs")
            }
        }
        if (showLogs) item { Logs() }

        item { Spacer(Modifier.size(24.dp)) }
    }
    TextInput(enabled = phase == Phase.IDLE)
    Spacer(Modifier.size(8.dp))
    }
}

@Composable
private fun LastCrash() {
    val context = LocalContext.current
    var crash by remember { mutableStateOf(CrashLog.read()) }
    val text = crash ?: return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Ifa crashed last time", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            Text(text.lines().take(8).joinToString("\n"), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText("Ifa crash", text))
                }) { Text("Copy") }
                OutlinedButton(onClick = {
                    CrashLog.clear()
                    crash = null
                }) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun StatusCard(phase: Phase, pcName: String?, problem: String?, onStart: () -> Unit, onStop: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(phase.label, style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    phase == Phase.STOPPED -> "Start Ifa to connect to your PC when it's on this Wi-Fi."
                    pcName != null -> "Connected to $pcName"
                    else -> "Make sure Ifa is running on the PC and both are on the same Wi-Fi."
                },
                style = MaterialTheme.typography.bodyMedium
            )
            if (problem != null) {
                Text(problem, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (phase == Phase.STOPPED) {
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Start Ifa") }
            } else {
                OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Stop Ifa") }
            }
        }
    }
}

@Composable
private fun TalkButton(phase: Phase, enabled: Boolean, onClick: () -> Unit) {
    val (label, hint) = when (phase) {
        Phase.IDLE -> "🎤" to "Tap to talk"
        Phase.LISTENING -> "●" to "Listening… tap when done"
        Phase.THINKING -> "…" to "Thinking… tap to cancel"
        Phase.SPEAKING -> "■" to "Speaking… tap to stop"
        else -> "🎤" to "Waiting for the PC"
    }
    val color = when (phase) {
        Phase.LISTENING -> MaterialTheme.colorScheme.error
        Phase.IDLE -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.secondary
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(112.dp)
                .clip(CircleShape)
                .background(if (enabled) color else MaterialTheme.colorScheme.surfaceVariant)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(label, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.size(8.dp))
        Text(hint, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MicMeter() {
    val level by IfaState.micLevel.collectAsState()
    val gain by IfaState.micGain.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Mic level %.3f (boost %.1fx%s) — stops about a second after you go quiet"
                .format(level, gain, if (IfaSettings.micBoost.isBlank()) ", auto" else ""),
            style = MaterialTheme.typography.bodySmall
        )
        LinearProgressIndicator(progress = { (level * 8f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun WakeMeter() {
    val score by IfaState.wakeScore.collectAsState()
    val peak by IfaState.wakePeak.collectAsState()
    if (!IfaSettings.wakeWordEnabled) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Say \"Ifa\" — wake score %.2f (peak %.2f)".format(score, peak), style = MaterialTheme.typography.bodySmall)
            Text(
                "reset",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { IfaState.resetWakePeak() }
            )
        }
        LinearProgressIndicator(progress = { score.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Conversation(chat: List<ChatLine>) {
    if (chat.isEmpty()) return
    val listState = rememberLazyListState()
    LaunchedEffect(chat.size) { listState.animateScrollToItem(chat.size - 1) }
    Card(Modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .heightIn(max = 320.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(chat) { line ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = if (line.fromUser) Arrangement.End else Arrangement.Start
                ) {
                    Text(
                        line.text,
                        modifier = Modifier
                            .widthIn(max = 280.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (line.fromUser) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun TextInput(enabled: Boolean) {
    var text by remember { mutableStateOf("") }
    val send = {
        val message = text.trim()
        if (message.isNotEmpty()) {
            IfaVoiceService.instance?.sendText(message)
            text = ""
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Type to Ifa") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            modifier = Modifier.weight(1f)
        )
        Button(onClick = send, enabled = enabled && text.isNotBlank()) { Text("Send") }
    }
}
