package com.ifanotif.ifanotif.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, CHUNK_SAMPLES * 2 * 4)
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
            runCatching { recorder.stop() }
            recorder.release()
            if (Thread.currentThread() === thread) running = false
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK_SAMPLES = WakeWordEngine.CHUNK_SAMPLES
    }
}
