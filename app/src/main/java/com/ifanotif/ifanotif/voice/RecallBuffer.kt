package com.ifanotif.ifanotif.voice

/**
 * The last few seconds the mic heard, kept only in memory, so Sir can ask "is that true?" about something
 * he just heard. Nothing is saved or sent unless Ifa asks for it (the recall_recent_audio tool).
 */
class RecallBuffer(sampleRate: Int = MicRecorder.SAMPLE_RATE) {
    private val rate = sampleRate
    private var ring = ShortArray(0)
    private var write = 0
    private var filled = 0

    @Synchronized
    fun resize(seconds: Int) {
        val size = seconds.coerceIn(0, 120) * rate
        if (size == ring.size) return
        ring = ShortArray(size)
        write = 0
        filled = 0
    }

    @Synchronized
    fun add(chunk: ShortArray) {
        if (ring.isEmpty()) return
        for (sample in chunk) {
            ring[write] = sample
            write = (write + 1) % ring.size
        }
        filled = minOf(ring.size, filled + chunk.size)
    }

    /** Oldest first. */
    @Synchronized
    fun snapshot(): ShortArray {
        if (filled == 0) return ShortArray(0)
        val out = ShortArray(filled)
        val start = (write - filled + ring.size) % ring.size
        for (i in 0 until filled) out[i] = ring[(start + i) % ring.size]
        return out
    }
}
