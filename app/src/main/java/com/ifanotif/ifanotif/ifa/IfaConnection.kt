package com.ifanotif.ifanotif.ifa

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * One WebSocket to Ifa. Text frames are JSON events, binary frames are audio:
 * phone -> PC is 16 kHz mono PCM16 mic audio, PC -> phone is Ifa's voice (PCM16 at
 * the rate announced in the preceding "audio" event).
 */
class IfaConnection(
    val host: IfaHost,
    private val pairingCode: String,
    private val listener: Listener
) {
    interface Listener {
        fun onMessage(connection: IfaConnection, message: JSONObject)
        fun onAudio(connection: IfaConnection, pcm16: ByteArray)
        fun onClosed(connection: IfaConnection, reason: String, httpCode: Int?)
    }

    private var socket: WebSocket? = null

    fun connect() {
        val request = Request.Builder()
            .url(host.url)
            .header("X-IFA-Token", pairingCode)
            .build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = runCatching { JSONObject(text) }.getOrNull() ?: return
                listener.onMessage(this@IfaConnection, message)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                listener.onAudio(this@IfaConnection, bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onClosed(this@IfaConnection, reason.ifBlank { "closed" }, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code
                listener.onClosed(
                    this@IfaConnection,
                    if (code != null) "HTTP $code" else (t.message ?: t.javaClass.simpleName),
                    code
                )
            }
        })
    }

    fun send(message: JSONObject): Boolean = socket?.send(message.toString()) ?: false

    fun send(type: String, vararg fields: Pair<String, Any?>): Boolean =
        send(JSONObject().put("type", type).apply { fields.forEach { (k, v) -> put(k, v) } })

    fun sendAudio(chunk: ShortArray): Boolean {
        val buffer = ByteBuffer.allocate(chunk.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asShortBuffer().put(chunk)
        return socket?.send(buffer.array().toByteString()) ?: false
    }

    fun close() {
        socket?.close(1000, "bye")
        socket = null
    }

    companion object {
        private val client = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(10, TimeUnit.SECONDS)
            .build()
    }
}
