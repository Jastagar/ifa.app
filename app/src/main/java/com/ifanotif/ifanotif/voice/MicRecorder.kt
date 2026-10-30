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
        // VOICE_RECOGNITION gives near-raw audio on most phones (no noise suppression), which the gain then
        // amplified along with the room. VOICE_COMMUNICATION is the calls mode: the phone's own noise
        // suppression, echo cancelling and auto gain, often on dedicated hardware.
        val clean = IfaSettings.noiseReduction
        val source = if (clean) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION
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

        val effects = if (clean) attachEffects(recorder.audioSessionId) else emptyList()
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

    private fun attachEffects(session: Int): List<AudioEffect> {
        val found = mutableListOf<AudioEffect>()
        val report = listOf(
            "noise suppressor" to (if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(session) }.getOrNull() else null),
            "echo canceller" to (if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(session) }.getOrNull() else null),
            "auto gain" to (if (AutomaticGainControl.isAvailable()) runCatching { AutomaticGainControl.create(session) }.getOrNull() else null),
        ).joinToString(", ") { (name, effect) ->
            if (effect != null && runCatching { effect.enabled = true }.isSuccess) {
                found += effect
                "$name on"
            } else "$name not on this phone"
        }
        AppLogger.log("Mic: noise reduction mode ($report)")
        return found
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK_SAMPLES = WakeWordEngine.CHUNK_SAMPLES
    }
}
