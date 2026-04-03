package com.pixeldweller.instantpear.server

import com.google.gson.Gson
import com.pixeldweller.instantpear.protocol.PearMessage
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 9275
    val server = SockJsHttpTestServer(port)
    server.start()
    println("===========================================")
    println("  InstantPear SockJS Test Server")
    println("  Endpoint : http://localhost:$port/ws")
    println("  Plugin URL: http://localhost:$port/ws")
    println("===========================================")
    println("Press Ctrl+C to stop")

    Runtime.getRuntime().addShutdownHook(Thread {
        println("\nShutting down...")
        server.stop()
    })

    Thread.currentThread().join()
}

// ── HTTP server ────────────────────────────────────────────────────────────────

class SockJsHttpTestServer(port: Int) {
    private val httpServer = HttpServer.create(InetSocketAddress(port), 100)
    private val executor   = Executors.newCachedThreadPool()
    private val broker     = SockJsBroker()

    fun start() {
        httpServer.createContext("/") { exchange -> handleRequest(exchange) }
        httpServer.executor = executor
        httpServer.start()
        println("[*] SockJS server started")
    }

    fun stop() {
        httpServer.stop(1)
        executor.shutdownNow()
    }

    private fun handleRequest(exchange: HttpExchange) {
        exchange.responseHeaders.apply {
            add("Access-Control-Allow-Origin", "*")
            add("Access-Control-Allow-Methods", "POST, GET, OPTIONS")
            add("Access-Control-Allow-Headers", "Content-Type, Origin, X-Requested-With")
        }

        if (exchange.requestMethod == "OPTIONS") {
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
            return
        }

        val path = exchange.requestURI.path
        when {
            path.endsWith("/info")           -> handleInfo(exchange)
            path.endsWith("/xhr_streaming")  -> handleXhrStreaming(exchange, extractSessionId(path))
            path.endsWith("/xhr_send")       -> handleXhrSend(exchange, extractSessionId(path))
            else                             -> { exchange.sendResponseHeaders(404, -1); exchange.close() }
        }
    }

    // GET /ws/info — SockJS capability advertisement
    private fun handleInfo(exchange: HttpExchange) {
        val json  = """{"websocket":false,"origins":["*:*"],"cookie_needed":false,"entropy":${(Math.random() * 1_000_000).toInt()}}"""
        val bytes = json.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json;charset=UTF-8")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    // POST /ws/{server}/{session}/xhr_streaming — long-poll streaming
    private fun handleXhrStreaming(exchange: HttpExchange, sessionId: String) {
        val session = SockJsSession(sessionId)
        broker.sessionOpened(session)

        exchange.responseHeaders.add("Content-Type", "application/javascript;charset=UTF-8")
        exchange.responseHeaders.add("Cache-Control", "no-store, no-cache, must-revalidate")
        exchange.sendResponseHeaders(200, 0)   // 0 = unknown length = keep streaming

        val writer = exchange.responseBody.bufferedWriter(Charsets.UTF_8)
        try {
            // SockJS prelude: 2048 'h' bytes to flush proxy buffers
            writer.write("h".repeat(2048) + "\n")
            writer.flush()

            // SockJS open frame
            writer.write("o\n")
            writer.flush()

            println("[+] Stream opened: $sessionId")

            while (!session.closed) {
                val frame = session.outQueue.poll(25, TimeUnit.SECONDS)
                when {
                    frame == null            -> { writer.write("h\n"); writer.flush() }  // heartbeat
                    frame == SESSION_CLOSE   -> break
                    else                     -> { writer.write("$frame\n"); writer.flush() }
                }
            }
        } catch (_: Exception) {
            // client disconnected
        } finally {
            session.closed = true
            broker.sessionClosed(sessionId)
            println("[-] Stream closed: $sessionId")
            try { exchange.close() } catch (_: Exception) {}
        }
    }

    // POST /ws/{server}/{session}/xhr_send — client → server messages
    private fun handleXhrSend(exchange: HttpExchange, sessionId: String) {
        val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
        try {
            // Body is a JSON array: ["<message-json>", ...]
            val messages = Gson().fromJson(body, Array<String>::class.java)
            for (msgJson in messages) broker.onMessage(sessionId, msgJson)
        } catch (e: Exception) {
            println("[!] Bad xhr_send from $sessionId: ${e.message}")
        }
        exchange.responseHeaders.add("Content-Type", "text/plain")
        exchange.sendResponseHeaders(204, -1)
        exchange.close()
    }

    /** Extracts the session id from /base/{serverId}/{sessionId}/xhr_streaming */
    private fun extractSessionId(path: String): String {
        val parts = path.trimEnd('/').split("/")
        return if (parts.size >= 2) parts[parts.size - 2] else path
    }

    companion object {
        const val SESSION_CLOSE = "\u0000CLOSE"
    }
}

// ── Session ────────────────────────────────────────────────────────────────────

class SockJsSession(val id: String) {
    val outQueue = LinkedBlockingQueue<String>()
    @Volatile var closed = false

    /** Enqueue a SockJS frame (e.g. a["..."]) to be written to the stream */
    fun send(frame: String) {
        if (!closed) outQueue.offer(frame)
    }

    fun close() {
        closed = true
        outQueue.offer(SockJsHttpTestServer.SESSION_CLOSE)
    }
}

// ── Lobby broker ───────────────────────────────────────────────────────────────

class SockJsBroker {
    private val gson = Gson()

    data class Member(val session: SockJsSession, val userId: String, val userName: String)
    data class Lobby(val key: String, val members: CopyOnWriteArrayList<Member> = CopyOnWriteArrayList())

    private val sessions      = ConcurrentHashMap<String, SockJsSession>()
    private val lobbies       = ConcurrentHashMap<String, Lobby>()
    private val sessionLobby  = ConcurrentHashMap<String, String>()  // sessionId → lobbyCode
    private val sessionUser   = ConcurrentHashMap<String, String>()  // sessionId → userId

    fun sessionOpened(session: SockJsSession) {
        sessions[session.id] = session
    }

    fun sessionClosed(sessionId: String) {
        val code   = sessionLobby.remove(sessionId) ?: return
        val userId = sessionUser.remove(sessionId)  ?: return
        val lobby  = lobbies[code]                  ?: return

        val member = lobby.members.find { it.session.id == sessionId }
        lobby.members.removeIf { it.session.id == sessionId }

        if (lobby.members.isEmpty()) {
            lobbies.remove(code)
            println("[*] Lobby removed: '$code'")
        } else {
            broadcastRaw(sessionId, code, gson.toJson(
                PearMessage(type = PearMessage.USER_LEFT, userId = userId, userName = member?.userName)
            ))
            println("[*] ${member?.userName} left '$code' (${lobby.members.size} remaining)")
        }
        sessions.remove(sessionId)
    }

    fun onMessage(sessionId: String, msgJson: String) {
        val session = sessions[sessionId] ?: return
        val msg = try {
            gson.fromJson(msgJson, PearMessage::class.java)
        } catch (e: Exception) {
            println("[!] Invalid message from $sessionId: $msgJson"); return
        }

        println("[>] ${msg.type} from ${sessionUser[sessionId] ?: sessionId}")

        when (msg.type) {
            PearMessage.CREATE_LOBBY -> handleCreate(session, msg)
            PearMessage.JOIN_LOBBY   -> handleJoin(session, msg)
            PearMessage.LEAVE_LOBBY  -> sessionClosed(sessionId)
            else                     -> handleBroadcast(sessionId, msgJson, msg)
        }
    }

    /** Wrap a PearMessage in a SockJS 'a' frame and enqueue it to a session */
    private fun sendTo(session: SockJsSession, msg: PearMessage) {
        session.send("a${gson.toJson(listOf(gson.toJson(msg)))}")
    }

    /** Forward a raw JSON string (already serialised PearMessage) to a session */
    private fun forwardTo(session: SockJsSession, rawJson: String) {
        session.send("a${gson.toJson(listOf(rawJson))}")
    }

    private fun handleCreate(session: SockJsSession, msg: PearMessage) {
        val code = msg.lobbyCode ?: return sendError(session, "Missing lobby code")
        val key  = msg.lobbyKey  ?: return sendError(session, "Missing lobby key")
        if (lobbies.containsKey(code)) return sendError(session, "Lobby '$code' already exists")

        val userId   = java.util.UUID.randomUUID().toString().take(8)
        val userName = msg.userName ?: "Host"

        lobbies[code] = Lobby(key).also { it.members.add(Member(session, userId, userName)) }
        sessionLobby[session.id] = code
        sessionUser[session.id]  = userId

        sendTo(session, PearMessage(type = PearMessage.LOBBY_CREATED, lobbyCode = code, userId = userId))
        println("[*] Lobby '$code' created by $userName ($userId)")
    }

    private fun handleJoin(session: SockJsSession, msg: PearMessage) {
        val code  = msg.lobbyCode ?: return sendError(session, "Missing lobby code")
        val key   = msg.lobbyKey  ?: return sendError(session, "Missing lobby key")
        val lobby = lobbies[code] ?: return sendError(session, "Lobby '$code' not found")
        if (lobby.key != key) return sendError(session, "Invalid lobby key")

        val userId   = java.util.UUID.randomUUID().toString().take(8)
        val userName = msg.userName ?: "Guest"

        lobby.members.add(Member(session, userId, userName))
        sessionLobby[session.id] = code
        sessionUser[session.id]  = userId

        sendTo(session, PearMessage(type = PearMessage.LOBBY_JOINED, lobbyCode = code, userId = userId))

        // Tell the new member about everyone already in the lobby
        lobby.members.filter { it.session.id != session.id }.forEach { existing ->
            sendTo(session, PearMessage(type = PearMessage.USER_JOINED, userId = existing.userId, userName = existing.userName))
        }

        // Tell everyone else a new member joined
        broadcastRaw(session.id, code, gson.toJson(
            PearMessage(type = PearMessage.USER_JOINED, userId = userId, userName = userName)
        ))
        println("[*] $userName ($userId) joined '$code' (${lobby.members.size} members)")
    }

    private fun handleBroadcast(sessionId: String, rawJson: String, msg: PearMessage) {
        val code = sessionLobby[sessionId] ?: return
        val targetId = msg.targetUserId
        if (targetId != null) {
            lobbies[code]?.members?.find { it.userId == targetId }
                ?.let { forwardTo(it.session, rawJson) }
        } else {
            broadcastRaw(sessionId, code, rawJson)
        }
    }

    private fun broadcastRaw(senderSessionId: String, code: String, rawJson: String) {
        lobbies[code]?.members
            ?.filter { it.session.id != senderSessionId && !it.session.closed }
            ?.forEach { try { forwardTo(it.session, rawJson) } catch (_: Exception) {} }
    }

    private fun sendError(session: SockJsSession, message: String) {
        println("[!] Error: $message")
        sendTo(session, PearMessage(type = PearMessage.ERROR, message = message))
    }
}
