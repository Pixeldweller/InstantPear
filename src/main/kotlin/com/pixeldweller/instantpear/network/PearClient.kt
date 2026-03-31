package com.pixeldweller.instantpear.network

import com.google.gson.Gson
import com.pixeldweller.instantpear.protocol.PearMessage
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.util.concurrent.CompletionStage

class PearClient(
    private val serverUrl: String,
    private val onMessage: (PearMessage) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: (String) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val useSockJS: Boolean = false
) {
    @Volatile private var webSocket: WebSocket? = null
    private val messageBuffer = StringBuilder()
    private val gson = Gson()

    fun connect() {
        val url = if (useSockJS) buildSockJsUrl(serverUrl) else serverUrl
        val client = HttpClient.newHttpClient()
        client.newWebSocketBuilder()
            .buildAsync(URI.create(url), WsListener())
            .exceptionally { e ->
                onError(e)
                null
            }
    }

    private fun buildSockJsUrl(base: String): String {
        val serverId = (0..999).random().toString().padStart(3, '0')
        val sessionId = java.util.UUID.randomUUID().toString().replace("-", "")
        val wsBase = base
            .replace(Regex("^http://"), "ws://")
            .replace(Regex("^https://"), "wss://")
            .trimEnd('/')
        return "$wsBase/$serverId/$sessionId/websocket"
    }

    private fun handleSockJsFrame(frame: String) {
        when {
            frame == "o" -> { /* already connected via onOpen */ }
            frame == "h" -> { /* heartbeat, ignore */ }
            frame.startsWith("a") -> {
                val arrayJson = frame.substring(1)
                val messages = gson.fromJson(arrayJson, Array<String>::class.java)
                for (msgJson in messages) {
                    val message = gson.fromJson(msgJson, PearMessage::class.java)
                    onMessage(message)
                }
            }
            frame.startsWith("c") -> {
                val parts = gson.fromJson(frame.substring(1), Array<Any>::class.java)
                val reason = if (parts.size > 1) parts[1].toString() else "closed"
                onDisconnected(reason)
            }
        }
    }

    fun send(message: PearMessage) {
        if (message.type == PearMessage.DOCUMENT_SYNC && message.content != null && message.content.length > CHUNK_SIZE) {
            sendChunked(message)
        } else {
            val json = gson.toJson(message)
            webSocket?.sendText(json, true)
        }
    }

    private fun sendChunked(message: PearMessage) {
        val content = message.content ?: return
        val chunks = content.chunked(CHUNK_SIZE)
        for ((index, chunk) in chunks.withIndex()) {
            val chunkMsg = PearMessage(
                type = PearMessage.DOCUMENT_SYNC_CHUNK,
                fileName = message.fileName,
                targetUserId = message.targetUserId,
                userId = message.userId,
                userName = message.userName,
                content = chunk,
                chunkIndex = index,
                totalChunks = chunks.size
            )
            webSocket?.sendText(gson.toJson(chunkMsg), true)
        }
    }

    companion object {
        const val CHUNK_SIZE = 256 * 1024 // 256KB per chunk
    }

    fun disconnect() {
        try {
            webSocket?.sendClose(WebSocket.NORMAL_CLOSURE, "leaving")
        } catch (_: Exception) {
        }
        webSocket = null
    }

    private inner class WsListener : WebSocket.Listener {
        override fun onOpen(webSocket: WebSocket) {
            // Set before any messages can arrive so send() is never called with a null socket
            this@PearClient.webSocket = webSocket
            onConnected()
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            messageBuffer.append(data)
            if (last) {
                val raw = messageBuffer.toString()
                messageBuffer.clear()
                try {
                    if (useSockJS) {
                        handleSockJsFrame(raw)
                    } else {
                        val message = gson.fromJson(raw, PearMessage::class.java)
                        onMessage(message)
                    }
                } catch (e: Exception) {
                    onError(e)
                }
            }
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            onDisconnected(reason)
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            this@PearClient.onError(error)
        }
    }
}
