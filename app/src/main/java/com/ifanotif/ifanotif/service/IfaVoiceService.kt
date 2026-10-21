package com.ifanotif.ifanotif.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ifanotif.ifanotif.MainActivity
import com.ifanotif.ifanotif.R
import com.ifanotif.ifanotif.ifa.IfaConnection
import com.ifanotif.ifanotif.ifa.IfaDiscovery
import com.ifanotif.ifanotif.ifa.IfaSettings
import com.ifanotif.ifanotif.ifa.IfaState
import com.ifanotif.ifanotif.ifa.Phase
import com.ifanotif.ifanotif.utils.AppLogger
import com.ifanotif.ifanotif.voice.Endpointer
import com.ifanotif.ifanotif.voice.MicGain
import com.ifanotif.ifanotif.voice.MicRecorder
import com.ifanotif.ifanotif.voice.SpeechPlayer
import com.ifanotif.ifanotif.voice.WakeWordEngine
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Keeps the phone connected to Ifa whenever the PC is reachable, and runs the voice loop:
 *
 *   IDLE --wake word / mic button--> LISTENING --PC says end_of_speech--> THINKING
 *     ^                                                                     |
 *     +----- turn_end, playback done (or a follow-up LISTENING) <--- SPEAKING
 *
 * The wake word runs on the phone; the PC does speech-to-text, the agent, and the voice.
 * All state changes happen on one worker thread; the mic thread only reads [phase].
 */
class IfaVoiceService : Service(), IfaConnection.Listener {

    private val worker = Executors.newSingleThreadScheduledExecutor()
    private lateinit var mic: MicRecorder
    private lateinit var player: SpeechPlayer
    @Volatile private var engine: WakeWordEngine? = null
    private var connection: IfaConnection? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private var stopped = false
    private var retryDelayMs = 2_000L
    private var pendingConnect: ScheduledFuture<*>? = null

    @Volatile private var phase = Phase.SEARCHING
    @Volatile private var streaming = false
    @Volatile private var wakeFired = false
    @Volatile private var resetEngine = false
    private var consecutive = 0

    private var pcThreshold = 0.5f
    private var pcConsecutive = 2
    private var followUp = false
    private var followUpSec = 5.0
    private var followUpCue = false
    @Volatile private var endpointer: Endpointer? = null
    @Volatile private var peakLevel = 0f
    @Volatile private var gain = 1f
    @Volatile private var rawPeak = 0f
    private var askedByIfa = false
    private var ignoreUntilTurnEnd = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannels()
        ServiceCompat.startForeground(
            this, SERVICE_NOTIFICATION_ID, serviceNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        )
        mic = MicRecorder(::onMicChunk)
        player = SpeechPlayer { post { onPlaybackFinished() } }
        setPhase(Phase.SEARCHING)
        post {
            engine = try {
                WakeWordEngine(this)
            } catch (e: Exception) {
                AppLogger.log("Wake word unavailable: ${e.message}")
                null
            }
            tryConnect()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_TALK -> post { onTalkPressed() }
            ACTION_RECONNECT -> post { reconnect() }
            ACTION_SETTINGS_CHANGED -> post { updateMic() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        instance = null
        stopped = true
        worker.execute {
            connection?.close()
            connection = null
            mic.stop()
            player.stop()
            engine?.close()
            releaseWifiLock()
            abandonFocus()
        }
        worker.shutdown()
        IfaState.setPhase(Phase.STOPPED)
        IfaState.setPcName(null)
        super.onDestroy()
    }

    // region connection

    private fun tryConnect() {
        if (stopped || connection != null) return
        val code = IfaSettings.pairingCode
        if (code.isBlank()) {
            IfaState.setProblem(CODE_HINT)
            setPhase(Phase.SEARCHING)
            scheduleConnect(3_000)
            return
        }
        setPhase(Phase.SEARCHING)
        val host = IfaSettings.manualHost.takeIf { it.isNotBlank() }?.let(IfaDiscovery::parseManual)
            ?: runCatching { IfaDiscovery.discover(this) }.getOrNull()
        if (host == null) {
            scheduleConnect(retryDelayMs)
            retryDelayMs = (retryDelayMs * 2).coerceAtMost(10_000)
            return
        }
        setPhase(Phase.CONNECTING)
        connection = IfaConnection(host, code, this).also { it.connect() }
    }

    private fun scheduleConnect(delayMs: Long) {
        pendingConnect?.cancel(false)
        pendingConnect = if (worker.isShutdown) null else runCatching {
            worker.schedule(guarded { tryConnect() }, delayMs, TimeUnit.MILLISECONDS)
        }.getOrNull()
    }

    private fun reconnect() {
        pendingConnect?.cancel(false)
        connection?.close()
        connection = null
        retryDelayMs = 2_000
        IfaState.setProblem(null)
        resetTurn()
        IfaState.setPcName(null)
        setPhase(Phase.SEARCHING)
        updateMic()
        tryConnect()
    }

    override fun onMessage(connection: IfaConnection, message: JSONObject) = post {
        if (connection === this.connection) handle(message)
    }

    override fun onAudio(connection: IfaConnection, pcm16: ByteArray) = post {
        if (connection !== this.connection || ignoreUntilTurnEnd) return@post
        if (phase == Phase.THINKING || phase == Phase.SPEAKING) {
            if (phase == Phase.THINKING) setPhase(Phase.SPEAKING)
            player.write(pcm16)
        }
    }

    override fun onClosed(connection: IfaConnection, reason: String, httpCode: Int?) = post {
        if (connection !== this.connection) return@post
        this.connection = null
        releaseWifiLock()
        resetTurn()
        IfaState.setPcName(null)
        when (httpCode) {
            401 -> {
                IfaState.setProblem("Wrong pairing code \"${IfaSettings.pairingCode}\". $CODE_HINT")
                scheduleConnect(30_000)
            }
            429 -> {
                IfaState.setProblem("Too many wrong codes, the PC is ignoring this phone for a minute. $CODE_HINT")
                scheduleConnect(65_000)
            }
            else -> {
                AppLogger.log("Ifa disconnected: $reason")
                scheduleConnect(1_000)
            }
        }
        setPhase(Phase.SEARCHING)
        updateMic()
    }

    // endregion

    // region protocol

    private fun handle(message: JSONObject) {
        when (message.optString("type")) {
            "ready" -> {
                pcThreshold = message.optDouble("wakeThreshold", 0.5).toFloat()
                pcConsecutive = message.optInt("wakeConsecutive", 2).coerceAtLeast(1)
                followUpSec = message.optDouble("followupSec", 5.0)
                val name = message.optString("name", connection?.host?.name ?: "PC")
                retryDelayMs = 2_000
                IfaState.setPcName(name)
                IfaState.setProblem(null)
                AppLogger.log("Connected to Ifa on $name")
                acquireWifiLock()
                enterIdle()
                pushNotificationSettings()
            }
            "notification_settings" -> AppLogger.log("Ifa is using your notification settings")
            "end_of_speech" -> {
                if (streaming) learnMicGain()
                streaming = false
                if (phase == Phase.LISTENING) setPhase(Phase.THINKING)
                updateMic()
            }
            "transcript" -> {
                IfaState.setProblem(null)
                IfaState.addChat(true, message.optString("text"))
            }
            "reply" -> if (!ignoreUntilTurnEnd) IfaState.addChat(false, message.optString("text"))
            "audio" -> if (!ignoreUntilTurnEnd) {
                player.setSampleRate(message.optInt("sampleRate", 24_000))
                requestFocus()
            }
            "nudge" -> {
                val urgent = message.optString("level") == "urgent"
                IfaState.addChat(false, if (urgent) "🔔 Urgent notification. Ask \"what is it?\"" else "🔔 New notification. Ask \"what is it?\"")
                if (urgent && IfaSettings.wakeScreen) wakeScreen()
                if (phase == Phase.IDLE) {
                    setPhase(Phase.THINKING)
                    updateMic()
                }
            }
            "no_speech" -> {
                followUp = false
                val reason = message.optString("reason")
                AppLogger.log("Ifa didn't catch that: $reason (loudest mic level on the phone: %.3f)".format(peakLevel))
                if (reason != "cancelled" && !askedByIfa) IfaState.setProblem("Ifa didn't catch that: $reason")
            }
            "turn_end" -> {
                if (ignoreUntilTurnEnd) {
                    ignoreUntilTurnEnd = false
                    return
                }
                followUp = message.optBoolean("followup", false)
                followUpSec = message.optDouble("followupSec", followUpSec)
                followUpCue = message.optBoolean("cue", false)
                player.endOfTurn()
            }
            "error" -> {
                val text = message.optString("message")
                AppLogger.log("Ifa: $text")
                if (text == "busy") {
                    streaming = false
                    IfaState.setProblem("Ifa is still finishing the last reply.")
                    enterIdle()
                }
            }
        }
    }

    private fun learnMicGain() {
        if (IfaSettings.micBoost.isNotBlank()) return
        val learned = MicGain.learn(IfaSettings.learnedMicGain, rawPeak)
        if (learned != IfaSettings.learnedMicGain) {
            AppLogger.log("Mic boost %.1fx → %.1fx (your loudest raw level was %.3f)".format(IfaSettings.learnedMicGain, learned, rawPeak))
            IfaSettings.learnedMicGain = learned
        }
    }

    private fun onPlaybackFinished() {
        abandonFocus()
        if (phase != Phase.THINKING && phase != Phase.SPEAKING) return
        if (followUp) startListening(timeoutMs = (followUpSec * 1000).toLong(), cue = followUpCue, askedByIfa = true) else enterIdle()
    }

    private fun startListening(timeoutMs: Long, cue: Boolean, askedByIfa: Boolean = false) {
        val conn = connection ?: return
        streaming = false
        setPhase(Phase.LISTENING)
        IfaState.setProblem(null)
        val begin = {
            if (phase == Phase.LISTENING && connection === conn) {
                val silenceMs = (IfaSettings.silenceSec * 1000).toInt()
                conn.send(
                    "start",
                    "startTimeoutMs" to timeoutMs,
                    "silenceMs" to silenceMs,
                    "vadThreshold" to IfaSettings.speechThreshold.toDouble()
                )
                endpointer = Endpointer(silenceMs = silenceMs + ENDPOINT_GRACE_MS)
                peakLevel = 0f
                rawPeak = 0f
                gain = IfaSettings.micGain()
                IfaState.setMicGain(gain)
                this.askedByIfa = askedByIfa
                streaming = true
                updateMic()
            }
        }
        if (cue) playCue { post(begin) } else begin()
    }

    private fun enterIdle() {
        streaming = false
        wakeFired = false
        resetEngine = true
        consecutive = 0
        setPhase(if (connection != null) Phase.IDLE else Phase.SEARCHING)
        updateMic()
        cancelWakeScreenNotification()
    }

    private fun resetTurn() {
        streaming = false
        followUp = false
        ignoreUntilTurnEnd = false
        player.stop()
        abandonFocus()
    }

    private fun onTalkPressed() {
        val conn = connection
        if (conn == null) {
            IfaState.setProblem("Not connected to Ifa yet.")
            return
        }
        when (phase) {
            Phase.IDLE -> startListening(timeoutMs = 8_000, cue = true)
            Phase.LISTENING -> conn.send("stop", "reason" to "button")
            Phase.THINKING, Phase.SPEAKING -> {
                player.stop()
                conn.send("cancel")
                ignoreUntilTurnEnd = true
                enterIdle()
            }
            else -> Unit
        }
    }

    fun sendText(text: String) = post {
        val conn = connection ?: return@post IfaState.setProblem("Not connected to Ifa yet.")
        if (phase != Phase.IDLE) return@post IfaState.setProblem("Wait for Ifa to finish.")
        IfaState.setProblem(null)
        IfaState.addChat(true, text)
        conn.send("text", "text" to text)
        setPhase(Phase.THINKING)
        updateMic()
    }

    fun pushNotificationSettings(): Boolean =
        connection?.send(IfaSettings.notificationSettingsMessage()) ?: false

    fun forwardNotification(payload: JSONObject): Boolean {
        val conn = connection ?: return false
        return conn.send(JSONObject(payload.toString()).put("type", "notification"))
    }

    // endregion

    // region mic + wake word

    private fun onMicChunk(chunk: ShortArray) {
        when (phase) {
            Phase.LISTENING -> if (streaming) stream(chunk)
            Phase.IDLE -> detectWake(chunk)
            else -> Unit
        }
    }

    private fun stream(raw: ShortArray) {
        val conn = connection ?: return
        rawPeak = maxOf(rawPeak, MicGain.peak(raw))
        val chunk = MicGain.apply(raw, gain)
        conn.sendAudio(chunk)
        val ep = endpointer ?: return
        val done = ep.process(chunk)
        IfaState.setMicLevel(ep.level)
        if (ep.level > peakLevel) peakLevel = ep.level
        if (done) {
            endpointer = null
            val peak = peakLevel
            post {
                if (phase == Phase.LISTENING && connection === conn) {
                    AppLogger.log("Stopped listening: you went quiet (loudest mic level %.3f)".format(peak))
                    conn.send("stop", "reason" to "silence")
                }
            }
        }
    }

    private fun detectWake(chunk: ShortArray) {
        val engine = engine ?: return
        if (wakeFired) return
        if (resetEngine) {
            resetEngine = false
            engine.reset()
            consecutive = 0
        }
        val score = try {
            engine.process(chunk)
        } catch (e: Exception) {
            AppLogger.log("Wake word error: ${e.message}")
            return
        }
        IfaState.setWakeScore(score)
        if (score >= threshold()) {
            consecutive++
            if (consecutive >= pcConsecutive) {
                wakeFired = true
                consecutive = 0
                post { onWake(score) }
            }
        } else {
            consecutive = 0
        }
    }

    private fun threshold(): Float = IfaSettings.wakeThreshold.toFloatOrNull()?.coerceIn(0.05f, 0.99f) ?: pcThreshold

    private fun onWake(score: Float) {
        if (phase != Phase.IDLE) return
        AppLogger.log("Wake word (%.2f)".format(score))
        if (IfaSettings.wakeScreen) wakeScreen()
        startListening(timeoutMs = 8_000, cue = true)
    }

    private fun updateMic() {
        val wanted = connection != null && when (phase) {
            Phase.LISTENING -> true
            Phase.IDLE -> IfaSettings.wakeWordEnabled && engine != null
            else -> false
        }
        if (wanted) mic.start() else mic.stop()
    }

    private fun playCue(onDone: () -> Unit) {
        val cue = runCatching { MediaPlayer.create(this, R.raw.wakesound) }.getOrNull()
        if (cue == null) {
            onDone()
            return
        }
        var finished = false
        val finish = {
            if (!finished) {
                finished = true
                cue.release()
                onDone()
            }
        }
        cue.setOnCompletionListener { post(finish) }
        cue.start()
        schedule(1_500) { finish() }
    }

    // endregion

    // region screen, audio focus, wifi

    /** Turns the screen on and shows Ifa over the lock screen when the wake word fires. */
    private fun wakeScreen() {
        val power = getSystemService(PowerManager::class.java)
        @Suppress("DEPRECATION")
        power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "ifa:wake"
        ).acquire(5_000)

        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_FROM_WAKE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, WAKE_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Ifa is listening")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(30_000)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(WAKE_NOTIFICATION_ID, notification) }
    }

    private fun cancelWakeScreenNotification() {
        getSystemService(NotificationManager::class.java).cancel(WAKE_NOTIFICATION_ID)
    }

    private fun requestFocus() {
        if (focusRequest != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
        getSystemService(AudioManager::class.java).requestAudioFocus(request)
        focusRequest = request
    }

    private fun abandonFocus() {
        val request = focusRequest ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(AudioManager::class.java).abandonAudioFocusRequest(request)
        }
        focusRequest = null
    }

    private fun acquireWifiLock() {
        if (wifiLock != null) return
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = applicationContext.getSystemService(WifiManager::class.java)
            ?.createWifiLock(mode, "ifa:link")
            ?.apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseWifiLock() {
        wifiLock?.release()
        wifiLock = null
    }

    // endregion

    // region notifications + plumbing

    private fun setPhase(newPhase: Phase) {
        val changed = phase != newPhase
        phase = newPhase
        IfaState.setPhase(newPhase)
        if (changed && (newPhase == Phase.IDLE || newPhase == Phase.SEARCHING)) {
            getSystemService(NotificationManager::class.java).notify(SERVICE_NOTIFICATION_ID, serviceNotification())
        }
    }

    private fun serviceNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val talk = PendingIntent.getService(
            this, 2, Intent(this, IfaVoiceService::class.java).setAction(ACTION_TALK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 3, Intent(this, IfaVoiceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pc = IfaState.pcName.value
        val text = when {
            pc == null -> "Looking for your PC on this Wi-Fi…"
            IfaSettings.wakeWordEnabled -> "Connected to $pc. Say \"Ifa\"."
            else -> "Connected to $pc."
        }
        return NotificationCompat.Builder(this, SERVICE_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Ifa")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "Talk", talk)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(SERVICE_CHANNEL, "Ifa connection", NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(WAKE_CHANNEL, "Ifa listening", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
            }
        )
    }

    private fun post(block: () -> Unit) {
        if (!worker.isShutdown) runCatching { worker.execute(guarded(block)) }
    }

    private fun schedule(delayMs: Long, block: () -> Unit) {
        if (!worker.isShutdown) runCatching { worker.schedule(guarded(block), delayMs, TimeUnit.MILLISECONDS) }
    }

    private fun guarded(block: () -> Unit): Runnable = Runnable {
        try {
            block()
        } catch (e: Exception) {
            AppLogger.log("Ifa service error: ${e.javaClass.simpleName}: ${e.message} at ${e.stackTrace.firstOrNull()}")
        }
    }

    // endregion

    companion object {
        const val ACTION_STOP = "com.ifanotif.ifanotif.STOP"
        const val ACTION_TALK = "com.ifanotif.ifanotif.TALK"
        const val ACTION_RECONNECT = "com.ifanotif.ifanotif.RECONNECT"
        const val ACTION_SETTINGS_CHANGED = "com.ifanotif.ifanotif.SETTINGS_CHANGED"
        private const val SERVICE_CHANNEL = "ifa_service"
        private const val WAKE_CHANNEL = "ifa_wake"
        private const val SERVICE_NOTIFICATION_ID = 1
        private const val WAKE_NOTIFICATION_ID = 2
        private const val ENDPOINT_GRACE_MS = 500
        private const val CODE_HINT =
            "Enter the pairing code shown in the Ifa launcher (Phone app row) or printed in Ifa's console as \"Pairing code: …\"."

        @Volatile var instance: IfaVoiceService? = null
            private set

        val isRunning: Boolean get() = instance != null

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, IfaVoiceService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, IfaVoiceService::class.java))
        }

        fun send(context: Context, action: String) {
            if (isRunning) context.startService(Intent(context, IfaVoiceService::class.java).setAction(action))
        }
    }
}
