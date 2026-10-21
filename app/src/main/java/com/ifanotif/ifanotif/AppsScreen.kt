package com.ifanotif.ifanotif

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.ifanotif.ifanotif.ifa.AppRule
import com.ifanotif.ifanotif.ifa.IfaSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledApp(val packageName: String, val label: String)

@Composable
fun AppsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var rules by remember { mutableStateOf(IfaSettings.appRules()) }
    var query by rememberSaveable { mutableStateOf("") }
    var allowedOnly by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val installed = installedApps(context)
            val (on, off) = installed.partition { IfaSettings.appRule(it.packageName).allowed }
            on + off
        }
    }

    fun ruleFor(pkg: String) = rules[pkg] ?: IfaSettings.defaultRule(pkg)
    fun update(pkg: String, rule: AppRule) {
        IfaSettings.setAppRule(pkg, rule)
        rules = IfaSettings.appRules()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.size(8.dp))
        Text("Apps", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Turn on the apps whose notifications should reach Ifa. For each, tell Ifa what matters " +
                "(who or what is important). Ifa reads your note when it decides whether a notification is worth interrupting you.",
            style = MaterialTheme.typography.bodySmall
        )
        if (!IfaSettings.forwardNotifications) {
            Text(
                "Sending notifications to Ifa is off in Settings, so nothing is sent right now.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(Modifier.size(8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search apps") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = allowedOnly, onClick = { allowedOnly = !allowedOnly }, label = { Text("Only turned on") })
            val count = apps?.count { ruleFor(it.packageName).allowed } ?: 0
            Text("$count on", style = MaterialTheme.typography.bodySmall)
        }

        val list = apps
        if (list == null) {
            Text("Loading your apps…", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        val needle = query.trim().lowercase()
        val shown = list.filter { app ->
            (needle.isEmpty() || needle in app.label.lowercase() || needle in app.packageName.lowercase()) &&
                (!allowedOnly || ruleFor(app.packageName).allowed)
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(shown, key = { it.packageName }) { app ->
                AppRow(app, ruleFor(app.packageName)) { update(app.packageName, it) }
            }
            if (shown.isEmpty()) item { Text("No apps match.", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun AppRow(app: InstalledApp, rule: AppRule, onChange: (AppRule) -> Unit) {
    val context = LocalContext.current
    val icon = remember(app.packageName) {
        runCatching { context.packageManager.getApplicationIcon(app.packageName).toBitmap(96, 96).asImageBitmap() }.getOrNull()
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(36.dp))
            else Spacer(Modifier.size(36.dp))
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Switch(checked = rule.allowed, onCheckedChange = { onChange(rule.copy(allowed = it)) })
        }
        if (rule.allowed) {
            OutlinedTextField(
                value = rule.note,
                onValueChange = { onChange(rule.copy(note = it.take(IfaSettings.MAX_NOTE_CHARS))) },
                label = { Text("What matters from ${app.label}?") },
                placeholder = { Text("e.g. Messages from Mom or my manager are important; group chats aren't.") },
                maxLines = 4,
                modifier = Modifier.fillMaxWidth().padding(start = 48.dp)
            )
        }
        HorizontalDivider()
    }
}

private fun installedApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(launcher, 0)
    }
    return activities
        .map { InstalledApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
        .filter { it.packageName != context.packageName }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}
