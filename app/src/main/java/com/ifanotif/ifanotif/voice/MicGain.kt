package com.ifanotif.ifanotif.voice

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Digital gain for the audio streamed to the PC. Phone mics in voice-recognition mode
 * often record far quieter than a desk mic, which leaves Ifa's VAD and Whisper guessing.
 * The gain is fixed for a whole utterance and learned from how loud the previous ones were.
 */
object MicGain {
    const val DEFAULT = 4f
    const val MIN = 1f
    const val MAX = 16f
    private const val TARGET_PEAK = 0.5f
    private const val MIN_SPEECH_PEAK = 0.002f

    fun apply(chunk: ShortArray, gain: Float): ShortArray {
        if (gain <= 1.001f) return chunk
        return ShortArray(chunk.size) { i -> (chunk[i] * gain).roundToInt().coerceIn(-32768, 32767).toShort() }
    }

    fun peak(chunk: ShortArray): Float {
        var max = 0
        for (sample in chunk) max = maxOf(max, abs(sample.toInt()))
        return max / 32768f
    }

    /** The gain to use next time, given the raw (un-boosted) peak of the last utterance. */
    fun learn(previous: Float, rawPeak: Float): Float {
        if (rawPeak < MIN_SPEECH_PEAK) return previous
        val ideal = (TARGET_PEAK / rawPeak).coerceIn(MIN, MAX)
        return ((previous + ideal) / 2f).coerceIn(MIN, MAX)
    }
}
