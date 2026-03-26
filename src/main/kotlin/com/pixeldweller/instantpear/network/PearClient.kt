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
    private val onError: (Throwable) -> Unit
) {
    private var webSocket: WebSocket? = null
    private val messageBuffer = StringBuilder()
    private val gson = Gson()

    fun connect() {
        val client = HttpClient.newHttpClient()
        client.newWebSocketBuilder()
            .buildAsync(URI.create(serverUrl), WsListener())
            .thenAccept { ws ->
                webSocket = ws
                onConnected()
            }
            .exceptionally { e ->
                onError(e)
                null
            }
    }

    fun send(message: PearMessage) {
        val json = gson.toJson(message)
        webSocket?.sendText(json, true)
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
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            messageBuffer.append(data)
            if (last) {
                val json = messageBuffer.toString()
                messageBuffer.clear()
                try {
                    val message = gson.fromJson(json, PearMessage::class.java)
                    onMessage(message)
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
