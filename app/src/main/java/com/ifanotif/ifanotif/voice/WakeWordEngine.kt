package com.ifanotif.ifanotif.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.Closeable
import java.nio.FloatBuffer

/**
 * Kotlin port of openWakeWord's streaming pipeline, the same one Ifa runs on the PC:
 * 80 ms of audio -> melspectrogram.onnx -> embedding_model.onnx (one 96-dim vector)
 * -> the last 16 vectors -> ifa.onnx -> score 0..1.
 */
class WakeWordEngine(context: Context) : Closeable {

    private val env = OrtEnvironment.getEnvironment()
    private val melSession = env.createSession(asset(context, "melspectrogram.onnx"), OrtSession.SessionOptions())
    private val embeddingSession = env.createSession(asset(context, "embedding_model.onnx"), OrtSession.SessionOptions())
    private val classifierSession = env.createSession(asset(context, "ifa.onnx"), OrtSession.SessionOptions())
    private val classifierInput = classifierSession.inputNames.first()

    private val previous = FloatArray(CONTEXT_SAMPLES)
    private val melFrames = ArrayDeque<FloatArray>()
    private val features = ArrayDeque<FloatArray>()

    init {
        reset()
    }

    fun reset() {
        previous.fill(0f)
        melFrames.clear()
        repeat(MEL_WINDOW) { melFrames.addLast(FloatArray(MEL_BINS) { 1f }) }
        features.clear()
    }

    /** [chunk] must be exactly [CHUNK_SAMPLES] samples of 16 kHz mono PCM16. */
    fun process(chunk: ShortArray): Float {
        require(chunk.size == CHUNK_SAMPLES) { "expected $CHUNK_SAMPLES samples" }

        val input = FloatArray(CONTEXT_SAMPLES + CHUNK_SAMPLES)
        previous.copyInto(input)
        for (i in chunk.indices) input[CONTEXT_SAMPLES + i] = chunk[i].toFloat()
        input.copyInto(previous, 0, input.size - CONTEXT_SAMPLES)

        val mel = run(melSession, "input", input, longArrayOf(1, input.size.toLong()))
        for (frame in 0 until mel.size / MEL_BINS) {
            melFrames.addLast(FloatArray(MEL_BINS) { mel[frame * MEL_BINS + it] / 10f + 2f })
        }
        while (melFrames.size > MEL_WINDOW) melFrames.removeFirst()

        val window = FloatArray(MEL_WINDOW * MEL_BINS)
        melFrames.forEachIndexed { i, frame -> frame.copyInto(window, i * MEL_BINS) }
        features.addLast(run(embeddingSession, "input_1", window, longArrayOf(1, MEL_WINDOW.toLong(), MEL_BINS.toLong(), 1)))
        while (features.size > FEATURE_WINDOW) features.removeFirst()
        if (features.size < FEATURE_WINDOW) return 0f

        val stacked = FloatArray(FEATURE_WINDOW * EMBEDDING_SIZE)
        features.forEachIndexed { i, vector -> vector.copyInto(stacked, i * EMBEDDING_SIZE) }
        return run(classifierSession, classifierInput, stacked, longArrayOf(1, FEATURE_WINDOW.toLong(), EMBEDDING_SIZE.toLong()))[0]
    }

    private fun run(session: OrtSession, inputName: String, data: FloatArray, shape: LongArray): FloatArray =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val buffer = (result[0] as OnnxTensor).floatBuffer
                FloatArray(buffer.remaining()).also { buffer.get(it) }
            }
        }

    override fun close() {
        melSession.close()
        embeddingSession.close()
        classifierSession.close()
    }

    companion object {
        const val CHUNK_SAMPLES = 1280
        private const val CONTEXT_SAMPLES = 480
        private const val MEL_BINS = 32
        private const val MEL_WINDOW = 76
        private const val FEATURE_WINDOW = 16
        private const val EMBEDDING_SIZE = 96

        private fun asset(context: Context, name: String): ByteArray =
            context.assets.open("wakeword/$name").use { it.readBytes() }
    }
}
