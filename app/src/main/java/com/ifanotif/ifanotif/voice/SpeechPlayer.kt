package com.ifanotif.ifanotif.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.ifanotif.ifanotif.utils.AppLogger
import java.util.concurrent.LinkedBlockingQueue

/**
 * Plays Ifa's voice as it streams in from the PC. Audio arrives faster than real time,
 * so chunks queue here and a playback thread feeds AudioTrack. [endOfTurn] queues a
 * marker; [onFinished] fires once everything before it has actually been heard.
 */
class SpeechPlayer(private val onFinished: () -> Unit) {

    private sealed interface Item {
        class Format(val sampleRate: Int) : Item
        class Pcm(val data: ByteArray) : Item
        object End : Item
    }

    private val queue = LinkedBlockingQueue<Item>()
    @Volatile private var generation = 0
    @Volatile var isPlaying = false
        private set

    private val thread = Thread({ loop() }, "ifa-speech").apply {
        isDaemon = true
        start()
    }

    fun setSampleRate(sampleRate: Int) = queue.put(Item.Format(sampleRate))

    fun write(pcm16: ByteArray) {
        isPlaying = true
        queue.put(Item.Pcm(pcm16))
    }

    fun endOfTurn() = queue.put(Item.End)

    fun stop() {
        generation++
        queue.clear()
        isPlaying = false
    }

    private fun loop() {
        var track: AudioTrack? = null
        var sampleRate = 24_000
        var framesWritten = 0L
        var gen = generation

        fun closeTrack() {
            val t = track ?: return
            track = null
            framesWritten = 0
            runCatching { t.pause() }
            runCatching { t.flush() }
            t.release()
        }

        while (true) {
            val item = queue.take()
            try {
                if (gen != generation) {
                    closeTrack()
                    gen = generation
                }
                when (item) {
                    is Item.Format -> if (item.sampleRate != sampleRate) {
                        closeTrack()
                        sampleRate = item.sampleRate
                    }
                    is Item.Pcm -> {
                        val t = track ?: buildTrack(sampleRate).also {
                            it.play()
                            track = it
                            bufferFrames = it.bufferSizeInFrames
                        }
                        var offset = 0
                        while (offset < item.data.size && gen == generation) {
                            val n = t.write(item.data, offset, item.data.size - offset)
                            if (n <= 0) break
                            offset += n
                        }
                        framesWritten += item.data.size / 2
                    }
                    Item.End -> {
                        track?.let { drain(it, framesWritten, sampleRate, gen) }
                        closeTrack()
                        if (gen == generation) finish()
                    }
                }
            } catch (e: Exception) {
                AppLogger.log("Playback error: ${e.javaClass.simpleName}: ${e.message}")
                runCatching { closeTrack() }
                track = null
                framesWritten = 0
                if (item == Item.End && gen == generation) finish()
            }
        }
    }

    private var bufferFrames = 0

    private fun finish() {
        isPlaying = false
        onFinished()
    }

    /**
     * Waits until everything written has been heard. Pads with silence first: a stream
     * shorter than the track's buffer may not start playing until the buffer fills.
     * Polls before stop(), because stop() resets the playback position on some phones.
     */
    private fun drain(track: AudioTrack, frames: Long, sampleRate: Int, gen: Int) {
        val padding = ByteArray(maxOf(bufferFrames, sampleRate / 10) * 2)
        track.write(padding, 0, padding.size)
        val deadline = System.currentTimeMillis() + frames * 1000 / sampleRate + 2000
        while (
            gen == generation &&
            (track.playbackHeadPosition.toLong() and 0xFFFFFFFFL) < frames &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(20)
        }
    }

    private fun buildTrack(sampleRate: Int): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer, sampleRate / 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }
}
