package com.pixeldweller.instantpear.network

import com.google.gson.Gson
import com.pixeldweller.instantpear.protocol.PearMessage
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
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
    // WebSocket transport
    @Volatile private var webSocket: WebSocket? = null

    // SockJS XHR transport
    @Volatile private var sockJsSendUrl: String? = null
    @Volatile private var streamReader: java.io.BufferedReader? = null

    private val messageBuffer = StringBuilder()
    private val gson = Gson()
    private val httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .build()

    fun connect() {
        if (useSockJS) connectSockJs(serverUrl) else connectWebSocket(serverUrl)
    }

    // ── WebSocket transport ────────────────────────────────────────────────

    private fun connectWebSocket(url: String) {
        httpClient.newWebSocketBuilder()
            .buildAsync(URI.create(url), WsListener())
            .exceptionally { e -> onError(e); null }
    }

    // ── SockJS XHR-streaming transport ────────────────────────────────────

    private fun connectSockJs(baseUrl: String) {
        val serverId  = (0..999).random().toString().padStart(3, '0')
        val sessionId = java.util.UUID.randomUUID().toString().replace("-", "")
        val base      = baseUrl.trimEnd('/')

        sockJsSendUrl = "$base/$serverId/$sessionId/xhr_send"
        val streamUrl = "$base/$serverId/$sessionId/xhr_streaming"

        Thread {
            try {
                val request = HttpRequest.newBuilder()
                    .uri(URI.create(streamUrl))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build()

                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
                val reader   = response.body().bufferedReader(Charsets.UTF_8)
                streamReader = reader

                onConnected()

                reader.use { r ->
                    var line: String?
                    while (r.readLine().also { line = it } != null) {
                        val frame = line!!
                        if (frame.isNotEmpty()) handleSockJsFrame(frame)
                    }
                }

                if (sockJsSendUrl != null) onDisconnected("stream closed")

            } catch (_: java.io.IOException) {
                // closed intentionally via disconnect() — suppress
            } catch (e: Exception) {
                if (sockJsSendUrl != null) onError(e)
            }
        }.apply {
            isDaemon = true
            name = "PearClient-SockJS-Stream"
            start()
        }
    }

    private fun handleSockJsFrame(frame: String) {
        when {
            // SockJS prelude is 2048 'h' chars; heartbeat is single 'h' — ignore both
            frame.all { it == 'h' } -> Unit
            frame == "o"            -> Unit   // open confirmation — already connected via onConnected()
            frame.startsWith("a")   -> {
                try {
                    val msgs = gson.fromJson(frame.substring(1), Array<String>::class.java)
                    for (msgJson in msgs) onMessage(gson.fromJson(msgJson, PearMessage::class.java))
                } catch (e: Exception) { onError(e) }
            }
            frame.startsWith("c") -> {
                val reason = try {
                    gson.fromJson(frame.substring(1), Array<Any>::class.java).let {
                        if (it.size > 1) it[1].toString() else "closed"
                    }
                } catch (_: Exception) { "closed" }
                onDisconnected(reason)
            }
        }
    }

    // ── Sending ───────────────────────────────────────────────────────────

    fun send(message: PearMessage) {
        if (message.type == PearMessage.DOCUMENT_SYNC &&
            message.content != null &&
            message.content.length > CHUNK_SIZE
        ) {
            sendChunked(message)
        } else if (useSockJS) {
            sendSockJs(gson.toJson(message))
        } else {
            webSocket?.sendText(gson.toJson(message), true)
        }
    }

    private fun sendSockJs(msgJson: String) {
        val url = sockJsSendUrl ?: return
        // SockJS xhr_send body: a JSON array containing the message as a string
        val body = gson.toJson(listOf(msgJson))
        httpClient.sendAsync(
            HttpRequest.newBuilder()
                .uri(URI.create(url))
                .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
                .header("Content-Type", "text/plain;charset=UTF-8")
                .build(),
            HttpResponse.BodyHandlers.discarding()
        ).exceptionally { null }
    }

    private fun sendChunked(message: PearMessage) {
        val content = message.content ?: return
        val chunks  = content.chunked(CHUNK_SIZE)
        for ((index, chunk) in chunks.withIndex()) {
            val chunkMsg = PearMessage(
                type         = PearMessage.DOCUMENT_SYNC_CHUNK,
                fileName     = message.fileName,
                targetUserId = message.targetUserId,
                userId       = message.userId,
                userName     = message.userName,
                content      = chunk,
                chunkIndex   = index,
                totalChunks  = chunks.size
            )
            if (useSockJS) sendSockJs(gson.toJson(chunkMsg))
            else           webSocket?.sendText(gson.toJson(chunkMsg), true)
        }
    }

    companion object {
        const val CHUNK_SIZE = 256 * 1024 // 256 KB per chunk
    }

    // ── Disconnect ────────────────────────────────────────────────────────

    fun disconnect() {
        sockJsSendUrl = null   // signal intentional close before touching the reader
        val reader = streamReader
        streamReader = null
        if (reader != null) {
            // Close on a background thread — closing an HTTP InputStream can block on TCP teardown
            // and would freeze the EDT if called directly.
            Thread {
                try { reader.close() } catch (_: Exception) {}
            }.apply { isDaemon = true; start() }
        }
        try {
            webSocket?.sendClose(WebSocket.NORMAL_CLOSURE, "leaving")
        } catch (_: Exception) {}
        webSocket = null
    }

    // ── WebSocket listener ────────────────────────────────────────────────

    private inner class WsListener : WebSocket.Listener {
        override fun onOpen(webSocket: WebSocket) {
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
                    onMessage(gson.fromJson(raw, PearMessage::class.java))
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
