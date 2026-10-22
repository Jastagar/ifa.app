package com.ifanotif.ifanotif.ui.settings

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ifanotif.ifanotif.ifa.IfaSettings
import com.ifanotif.ifanotif.ifa.IfaState
import com.ifanotif.ifanotif.ifa.Phase
import com.ifanotif.ifanotif.legacy.RetrofitClient
import com.ifanotif.ifanotif.service.IfaVoiceService
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsCard() {
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
            YourVoice()
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
            var silence by remember { mutableStateOf(IfaSettings.silenceSec) }
            Text("Pause before Ifa stops listening: %.1f s".format(silence), style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = silence,
                onValueChange = { silence = (it * 10).roundToInt() / 10f },
                onValueChangeFinished = { IfaSettings.silenceSec = silence },
                valueRange = 0.5f..4f
            )
            Text("Raise it if Ifa cuts you off mid-sentence; lower it for quicker replies.", style = MaterialTheme.typography.bodySmall)
            var speech by remember { mutableStateOf(IfaSettings.speechThreshold) }
            Text("Speech detection threshold: %.2f".format(speech), style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = speech,
                onValueChange = { speech = (it * 20).roundToInt() / 20f },
                onValueChangeFinished = { IfaSettings.speechThreshold = speech },
                valueRange = 0.2f..0.9f
            )
            Text("Lower it if soft words get treated as silence; raise it if background noise keeps Ifa listening.", style = MaterialTheme.typography.bodySmall)
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
            if (forward) NotificationRules()

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
private fun YourVoice() {
    val sources by IfaState.voiceSources.collectAsState()
    val enrollment by IfaState.enrollment.collectAsState()
    val phase by IfaState.phase.collectAsState()
    val connected = phase !in listOf(Phase.STOPPED, Phase.SEARCHING, Phase.CONNECTING)
    val where = sources.joinToString(" and ") { if (it == "pc") "the PC mic" else "this phone" }

    Text("Your voice", style = MaterialTheme.typography.bodyMedium)
    Text(
        if (sources.isEmpty()) "Not set up: Ifa listens to everyone, including the TV."
        else "Ifa only listens to you and ignores other voices. Learned from $where.",
        style = MaterialTheme.typography.bodySmall
    )
    val current = enrollment
    if (current == null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { IfaVoiceService.instance?.startEnrollment() }, enabled = connected && phase == Phase.IDLE) {
                Text(if ("phone" in sources) "Teach it again" else "Teach Ifa my voice")
            }
            if (sources.isNotEmpty()) {
                TextButton(onClick = { IfaVoiceService.instance?.forgetVoice() }, enabled = connected) { Text("Forget my voice") }
            }
        }
        if (!connected) Text("Connect to Ifa first.", style = MaterialTheme.typography.bodySmall)
        return
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val phrase = current.phrases.getOrNull(current.index)
            if (!current.finished && phrase != null) {
                Text("Phrase ${current.index + 1} of ${current.phrases.size}", style = MaterialTheme.typography.labelMedium)
                Text("\u201C$phrase\u201D", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                current.status,
                style = MaterialTheme.typography.bodySmall,
                color = if (current.finished && !current.ok) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (current.finished) {
                    Button(onClick = { IfaVoiceService.instance?.cancelEnrollment() }) { Text("Done") }
                } else {
                    Button(
                        onClick = { IfaVoiceService.instance?.recordEnrollPhrase() },
                        enabled = phrase != null && phase == Phase.IDLE
                    ) { Text(if (phase == Phase.LISTENING) "Listening…" else "Record") }
                    TextButton(onClick = { IfaVoiceService.instance?.cancelEnrollment() }) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun NotificationRules() {
    val sync = { IfaVoiceService.instance?.pushNotificationSettings() }

    Text(
        "Choose which apps go to Ifa, and what matters in each, in the Apps tab.",
        style = MaterialTheme.typography.bodySmall
    )
    SettingSlider("Group a sender's messages for", IfaSettings.notifSettleSec, 1f..30f, { "%.0f s".format(it) },
        "Wait this long after someone's last message before Ifa judges the whole group. Raise it for people who send many short messages.") {
        IfaSettings.notifSettleSec = it.roundToInt().toFloat(); sync()
    }
    SettingSlider("Keep as new for", IfaSettings.notifTtlSec, 30f..600f, { "%.0f s".format(it) },
        "After this, a notification moves to the older list. Ask Ifa for older notifications to hear those.") {
        IfaSettings.notifTtlSec = (it / 10).roundToInt() * 10f; sync()
    }
    SettingSlider("New notifications kept", IfaSettings.notifMaxRecent.toFloat(), 1f..20f, { it.roundToInt().toString() },
        "When more arrive, the oldest move to the older list.") {
        IfaSettings.notifMaxRecent = it.roundToInt(); sync()
    }
    SettingSlider("Older notifications kept", IfaSettings.notifMaxDiscarded.toFloat(), 10f..200f, { it.roundToInt().toString() }, "") {
        IfaSettings.notifMaxDiscarded = it.roundToInt(); sync()
    }
    SettingSlider("Listen after a nudge for", IfaSettings.notifListenSec, 2f..30f, { "%.0f s".format(it) },
        "How long Ifa waits for you to say \"what is it?\" without the wake word.") {
        IfaSettings.notifListenSec = it.roundToInt().toFloat(); sync()
    }
}

@Composable
private fun SettingSlider(
    label: String,
    initial: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    help: String,
    onDone: (Float) -> Unit,
) {
    var value by remember { mutableStateOf(initial.coerceIn(range)) }
    Text("$label: ${format(value)}", style = MaterialTheme.typography.bodyMedium)
    Slider(value = value, onValueChange = { value = it }, valueRange = range, onValueChangeFinished = { onDone(value) })
    if (help.isNotEmpty()) Text(help, style = MaterialTheme.typography.bodySmall)
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
