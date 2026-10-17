package com.ifanotif.ifanotif.voice

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Decides on the phone when you've stopped talking, as a backstop to Ifa's VAD on the PC.
 * It follows the room's noise floor (the quietest recent level) and counts a chunk as
 * speech when it is clearly louder than that, so it adapts to fans, TVs and mic gain.
 */
class Endpointer(private val silenceMs: Int, private val maxMs: Int = 20_000) {

    private var floor = -1f
    private var speechChunks = 0
    private var silentMs = 0
    private var elapsedMs = 0

    var level = 0f
        private set

    /** Returns true once the utterance looks finished. */
    fun process(chunk: ShortArray): Boolean {
        var sum = 0.0
        for (sample in chunk) {
            val v = sample / 32768.0
            sum += v * v
        }
        val rms = sqrt(sum / chunk.size).toFloat()
        level = rms
        elapsedMs += CHUNK_MS

        floor = when {
            floor < 0 -> rms
            rms < floor -> rms
            else -> floor * FLOOR_RISE
        }.coerceIn(MIN_FLOOR, MAX_FLOOR)

        if (rms > max(floor * SPEECH_RATIO, MIN_SPEECH_RMS)) {
            speechChunks++
            silentMs = 0
        } else if (speechChunks >= MIN_SPEECH_CHUNKS) {
            silentMs += CHUNK_MS
        }
        return (speechChunks >= MIN_SPEECH_CHUNKS && silentMs >= silenceMs) || elapsedMs >= maxMs
    }

    companion object {
        private const val CHUNK_MS = MicRecorder.CHUNK_SAMPLES * 1000 / MicRecorder.SAMPLE_RATE
        private const val FLOOR_RISE = 1.01f
        private const val MIN_FLOOR = 0.0005f
        private const val MAX_FLOOR = 0.05f
        private const val SPEECH_RATIO = 3.5f
        private const val MIN_SPEECH_RMS = 0.006f
        private const val MIN_SPEECH_CHUNKS = 3
    }
}
