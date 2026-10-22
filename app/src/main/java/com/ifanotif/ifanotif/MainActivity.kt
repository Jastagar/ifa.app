package com.ifanotif.ifanotif

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.ifanotif.ifanotif.ui.apps.AppsScreen
import com.ifanotif.ifanotif.ui.home.HomeScreen
import com.ifanotif.ifanotif.ui.theme.IfanotifTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyLockScreenMode(intent)

        setContent {
            IfanotifTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                Scaffold(
                    topBar = {
                        TabRow(selectedTabIndex = tab, modifier = Modifier.statusBarsPadding()) {
                            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Ifa") })
                            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Apps") })
                        }
                    }
                ) { padding ->
                    if (tab == 0) HomeScreen(modifier = Modifier.padding(padding))
                    else AppsScreen(modifier = Modifier.padding(padding))
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
