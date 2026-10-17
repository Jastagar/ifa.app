package com.ifanotif.ifanotif

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ifanotif.ifanotif.api.RetrofitClient
import com.ifanotif.ifanotif.ifa.ChatLine
import com.ifanotif.ifanotif.ifa.IfaSettings
import com.ifanotif.ifanotif.ifa.IfaState
import com.ifanotif.ifanotif.ifa.Phase
import com.ifanotif.ifanotif.service.IfaVoiceService
import com.ifanotif.ifanotif.ui.theme.IfanotifTheme
import com.ifanotif.ifanotif.utils.AppLogger
import com.ifanotif.ifanotif.utils.CrashLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyLockScreenMode(intent)

        setContent {
            IfanotifTheme {
                Scaffold { padding ->
                    IfaScreen(modifier = Modifier.padding(padding))
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyLockScreenMode(intent)
    }

    /** Opened by the wake word: turn the screen on and show over the lock screen. */
    private fun applyLockScreenMode(intent: Intent?) {
        val fromWake = intent?.getBooleanExtra(EXTRA_FROM_WAKE, false) == true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(fromWake)
            setTurnScreenOn(fromWake)
        } else {
            @Suppress("DEPRECATION")
            val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (fromWake) window.addFlags(flags) else window.clearFlags(flags)
        }
    }

    companion object {
        const val EXTRA_FROM_WAKE = "from_wake"
    }
}

@Composable
fun IfaScreen(modifier: Modifier = Modifier) {
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

@Composable
private fun SettingsCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf(IfaSettings.pairingCode) }
    var host by remember { mutableStateOf(IfaSettings.manualHost) }
    var threshold by remember { mutableStateOf(IfaSettings.wakeThreshold) }
    var wakeWord by remember { mutableStateOf(IfaSettings.wakeWordEnabled) }
    var wakeScreen by remember { mutableStateOf(IfaSettings.wakeScreen) }
    var forward by remember { mutableStateOf(IfaSettings.forwardNotifications) }
    var legacy by remember { mutableStateOf(IfaSettings.legacyBackendEnabled) }
    var backendUrl by remember { mutableStateOf(RetrofitClient.currentBaseUrl()) }
    var message by remember { mutableStateOf("") }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Connection", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text("Pairing code") },
                supportingText = { Text("Shown in the Ifa launcher under \"Phone app\", and printed in Ifa's console at startup. Ifa makes it for you.") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("PC address (optional)") },
                placeholder = { Text("Found automatically") },
                supportingText = { Text("Only if auto-discovery fails, e.g. 192.168.1.97 or 192.168.1.97:8789") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    IfaSettings.pairingCode = code
                    IfaSettings.manualHost = host
                    IfaSettings.wakeThreshold = threshold
                    message = "Saved"
                    IfaVoiceService.send(context, IfaVoiceService.ACTION_RECONNECT)
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save and reconnect") }
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)

            HorizontalDivider()
            Text("Voice", style = MaterialTheme.typography.titleSmall)
            SettingSwitch("Wake word \"Ifa\"", "Listens on the phone; nothing is sent until it hears the wake word.", wakeWord) {
                wakeWord = it
                IfaSettings.wakeWordEnabled = it
                IfaVoiceService.send(context, IfaVoiceService.ACTION_SETTINGS_CHANGED)
            }
            OutlinedTextField(
                value = threshold,
                onValueChange = {
                    threshold = it
                    IfaSettings.wakeThreshold = it
                },
                label = { Text("Wake sensitivity threshold (optional)") },
                placeholder = { Text("Same as the PC") },
                supportingText = { Text("0–1. Lower wakes more easily. Watch the wake score while saying \"Ifa\".") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            var boost by remember { mutableStateOf(IfaSettings.micBoost) }
            OutlinedTextField(
                value = boost,
                onValueChange = {
                    boost = it
                    IfaSettings.micBoost = it
                },
                label = { Text("Mic boost (optional)") },
                placeholder = { Text("Auto (now %.1fx)".format(IfaSettings.learnedMicGain)) },
                supportingText = { Text("1–16. Leave empty to learn it from how loud you speak. Raise it if Ifa misses what you say.") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            SettingSwitch("Turn the screen on", "When the wake word fires, wake the screen and show Ifa, even over the lock screen.", wakeScreen) {
                wakeScreen = it
                IfaSettings.wakeScreen = it
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val manager = context.getSystemService(NotificationManager::class.java)
                if (!manager.canUseFullScreenIntent()) {
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}"))
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Allow full-screen alerts (needed to show over the lock screen)") }
                }
            }

            val power = context.getSystemService(PowerManager::class.java)
            if (!power.isIgnoringBatteryOptimizations(context.packageName)) {
                OutlinedButton(
                    onClick = {
                        @SuppressLint("BatteryLife")
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Keep Ifa running in the background (battery: unrestricted)") }
            }

            HorizontalDivider()
            Text("Notifications", style = MaterialTheme.typography.titleSmall)
            SettingSwitch("Send notifications to Ifa", "Your phone's notifications go straight to Ifa on the PC.", forward) {
                forward = it
                IfaSettings.forwardNotifications = it
            }
            OutlinedButton(
                onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Grant notification access") }

            SettingSwitch("Also send to the backend URL", "The old Spring Boot bridge. Not needed when Ifa is connected.", legacy) {
                legacy = it
                IfaSettings.legacyBackendEnabled = it
            }
            if (legacy) {
                OutlinedTextField(
                    value = backendUrl,
                    onValueChange = { backendUrl = it },
                    label = { Text("Backend base URL") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        message = try {
                            RetrofitClient.saveBaseUrl(backendUrl)
                            backendUrl = RetrofitClient.currentBaseUrl()
                            "Backend URL saved"
                        } catch (e: IllegalArgumentException) {
                            e.message ?: "Invalid URL"
                        }
                    }) { Text("Save URL") }
                    OutlinedButton(onClick = {
                        message = "Testing…"
                        scope.launch {
                            message = try {
                                val response = withContext(Dispatchers.IO) { RetrofitClient.api.health() }
                                if (response.isSuccessful) "Backend connected ✅" else "Backend failed (${response.code()})"
                            } catch (e: Exception) {
                                e.message ?: "Unknown error"
                            }
                        }
                    }) { Text("Test") }
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Logs() {
    val logs by AppLogger.logs.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        logs.take(100).forEach { log ->
            Text("[${log.timestamp}] ${log.message}", style = MaterialTheme.typography.bodySmall)
        }
    }
}
