package com.ifanotif.ifanotif.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import com.ifanotif.ifanotif.ifa.IfaSettings
import com.ifanotif.ifanotif.utils.AppLogger

class MicRecorder(private val onChunk: (ShortArray) -> Unit) {

    @Volatile private var running = false
    @Volatile private var thread: Thread? = null

    val isRunning: Boolean get() = running

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start() {
        if (running && thread != null) return
        running = true
        val recording = Thread({ record() }, "ifa-mic")
        thread = recording
        recording.start()
    }

    @Synchronized
    fun stop() {
        running = false
        val old = thread
        thread = null
        old?.join(500)
    }

    @SuppressLint("MissingPermission")
    private fun record() {
        // Chosen in Ifa's launcher. VOICE_COMMUNICATION is the calls mode, with the phone's own noise
        // suppression, echo cancelling and auto gain (often on dedicated hardware); VOICE_RECOGNITION is
        // near raw on most phones; CAMCORDER is tuned for distant sound; UNPROCESSED is raw where supported.
        val config = IfaSettings.audio
        val source = when (config.source) {
            "voice_recognition" -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            "mic" -> MediaRecorder.AudioSource.MIC
            "unprocessed" -> MediaRecorder.AudioSource.UNPROCESSED
            "camcorder" -> MediaRecorder.AudioSource.CAMCORDER
            else -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
        }
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = try {
            AudioRecord(
                source,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                // 1 s of slack (was ~0.3 s): if this thread stalls longer than the buffer, Android drops
                // samples and the clip comes out choppy. Not urgent-audio priority: the wake-word model
                // runs on this thread too and then starved playback and the network.
                maxOf(minBuffer, SAMPLE_RATE * 2)
            )
        } catch (e: Exception) {
            AppLogger.log("Mic unavailable: ${e.message}")
            if (Thread.currentThread() === thread) running = false
            return
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            AppLogger.log("Mic unavailable (is another app recording?)")
            recorder.release()
            if (Thread.currentThread() === thread) running = false
            return
        }

        val effects = attachEffects(recorder.audioSessionId, config)
        recorder.startRecording()
        try {
            while (running && Thread.currentThread() === thread) {
                val chunk = ShortArray(CHUNK_SAMPLES)
                var filled = 0
                while (running && filled < CHUNK_SAMPLES) {
                    val read = recorder.read(chunk, filled, CHUNK_SAMPLES - filled)
                    if (read < 0) throw IllegalStateException("AudioRecord.read returned $read")
                    filled += read
                }
                if (filled == CHUNK_SAMPLES) {
                    try {
                        onChunk(chunk)
                    } catch (e: Exception) {
                        AppLogger.log("Mic chunk error: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.log("Mic stopped: ${e.message}")
        } finally {
            effects.forEach { runCatching { it.release() } }
            runCatching { recorder.stop() }
            recorder.release()
            if (Thread.currentThread() === thread) running = false
        }
    }

    private fun attachEffects(session: Int, config: com.ifanotif.ifanotif.ifa.AudioConfig): List<AudioEffect> {
        val found = mutableListOf<AudioEffect>()
        val report = listOf(
            Triple("noise suppressor", config.noiseSuppression, { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(session) else null }),
            Triple("echo canceller", config.echoCancel, { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(session) else null }),
            Triple("auto gain", config.autoGain, { if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(session) else null }),
        ).joinToString(", ") { (name, wanted, create) ->
            val effect: AudioEffect? = runCatching { create() }.getOrNull()
            when {
                effect == null -> "$name not on this phone"
                runCatching { effect.enabled = wanted }.isFailure -> { effect.release(); "$name failed" }
                else -> { found += effect; "$name ${if (wanted) "on" else "off"}" }
            }
        }
        AppLogger.log("Mic: ${config.source} ($report, boost ${if (config.boost > 0) "%.1fx".format(config.boost) else "auto"})")
        return found
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK_SAMPLES = WakeWordEngine.CHUNK_SAMPLES
    }
}
