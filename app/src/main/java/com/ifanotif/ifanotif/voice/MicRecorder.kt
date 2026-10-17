package com.ifanotif.ifanotif.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.ifanotif.ifanotif.utils.AppLogger

class MicRecorder(private val onChunk: (ShortArray) -> Unit) {

    @Volatile private var running = false
    private var thread: Thread? = null

    val isRunning: Boolean get() = running

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start() {
        if (running) return
        running = true
        thread = Thread({ record() }, "ifa-mic").also { it.start() }
    }

    @Synchronized
    fun stop() {
        running = false
        thread?.join(500)
        thread = null
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
            running = false
            return
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            AppLogger.log("Mic unavailable (is another app recording?)")
            recorder.release()
            running = false
            return
        }

        recorder.startRecording()
        try {
            while (running) {
                val chunk = ShortArray(CHUNK_SAMPLES)
                var filled = 0
                while (running && filled < CHUNK_SAMPLES) {
                    val read = recorder.read(chunk, filled, CHUNK_SAMPLES - filled)
                    if (read < 0) throw IllegalStateException("AudioRecord.read returned $read")
                    filled += read
                }
                if (filled == CHUNK_SAMPLES) onChunk(chunk)
            }
        } catch (e: Exception) {
            AppLogger.log("Mic stopped: ${e.message}")
        } finally {
            recorder.stop()
            recorder.release()
            running = false
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK_SAMPLES = WakeWordEngine.CHUNK_SAMPLES
    }
}
