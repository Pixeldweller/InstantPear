package com.pixeldweller.instantpear.server

import com.google.gson.Gson
import com.pixeldweller.instantpear.protocol.PearMessage
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 9275
    val server = SockJsPearServer(port)
    server.start()
    println("===========================================")
    println("  InstantPear SockJS Test Server")
    println("  Endpoint: http://localhost:$port/ws")
    println("  Plugin URL: http://localhost:$port/ws")
    println("===========================================")
    println("Press Ctrl+C to stop")

    Runtime.getRuntime().addShutdownHook(Thread {
        println("\nShutting down...")
        server.stop(1000)
    })
}

/**
 * SockJS WebSocket transport server.
 *
 * Accepts connections at:  ws://host:<port>/{serverId}/{sessionId}/websocket
 * SockJS framing:
 *   open:  server sends  "o"
 *   data:  server sends  a["<json-encoded message>"]
 *   close: server sends  c[3000,"reason"]
 *   heartbeat: server sends "h"  (not implemented, not needed for tests)
 *
 * Client → server: plain JSON strings, no SockJS framing.
 */
class SockJsPearServer(port: Int) : WebSocketServer(InetSocketAddress(port)) {
    init {
        connectionLostTimeout = 60
    }

    private val gson = Gson()

    data class LobbyMember(val conn: WebSocket, val userId: String, val userName: String)
    data class Lobby(val key: String, val members: MutableList<LobbyMember> = mutableListOf())

    private val lobbies = ConcurrentHashMap<String, Lobby>()
    private val connToLobby = ConcurrentHashMap<WebSocket, String>()
    private val connToUserId = ConcurrentHashMap<WebSocket, String>()

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        println("[+] SockJS connection from ${conn.remoteSocketAddress} (path: ${conn.resourceDescriptor})")
        // SockJS open frame — must be sent before any data frames
        conn.send("o")
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        println("[-] Disconnected: ${conn.remoteSocketAddress} (reason: $reason)")
        handleDisconnect(conn)
    }

    override fun onMessage(conn: WebSocket, message: String) {
        // Client sends plain JSON (no SockJS framing client→server for websocket transport)
        val msg = try {
            gson.fromJson(message, PearMessage::class.java)
        } catch (e: Exception) {
            println("[!] Invalid message from ${conn.remoteSocketAddress}: $message")
            return
        }

        println("[>] ${msg.type} from ${connToUserId[conn] ?: conn.remoteSocketAddress}")

        when (msg.type) {
            PearMessage.CREATE_LOBBY -> handleCreateLobby(conn, msg)
            PearMessage.JOIN_LOBBY   -> handleJoinLobby(conn, msg)
            PearMessage.LEAVE_LOBBY  -> handleDisconnect(conn)
            else                     -> handleBroadcast(conn, message, msg)
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        println("[!] Error${conn?.let { " from ${it.remoteSocketAddress}" } ?: ""}: ${ex.message}")
        conn?.let { handleDisconnect(it) }
    }

    override fun onStart() {
        println("[*] SockJS server started successfully")
    }

    /** Wrap a JSON string in a SockJS data frame: a["<escaped-json>"] */
    private fun sockJsSend(conn: WebSocket, json: String) {
        conn.send("a${gson.toJson(listOf(json))}")
    }

    private fun handleCreateLobby(conn: WebSocket, msg: PearMessage) {
        val code = msg.lobbyCode ?: return sendError(conn, "Missing lobby code")
        val key  = msg.lobbyKey  ?: return sendError(conn, "Missing lobby key")

        if (lobbies.containsKey(code)) {
            return sendError(conn, "Lobby '$code' already exists")
        }

        val userId   = java.util.UUID.randomUUID().toString().take(8)
        val userName = msg.userName ?: "Host"
        val lobby    = Lobby(key)
        lobby.members.add(LobbyMember(conn, userId, userName))
        lobbies[code]      = lobby
        connToLobby[conn]  = code
        connToUserId[conn] = userId

        sockJsSend(conn, gson.toJson(PearMessage(type = PearMessage.LOBBY_CREATED, lobbyCode = code, userId = userId)))
        println("[*] Lobby created: '$code' by $userName ($userId)")
    }

    private fun handleJoinLobby(conn: WebSocket, msg: PearMessage) {
        val code = msg.lobbyCode ?: return sendError(conn, "Missing lobby code")
        val key  = msg.lobbyKey  ?: return sendError(conn, "Missing lobby key")

        val lobby = lobbies[code] ?: return sendError(conn, "Lobby '$code' not found")
        if (lobby.key != key) return sendError(conn, "Invalid lobby key")

        val userId   = java.util.UUID.randomUUID().toString().take(8)
        val userName = msg.userName ?: "Guest"
        lobby.members.add(LobbyMember(conn, userId, userName))
        connToLobby[conn]  = code
        connToUserId[conn] = userId

        // Confirm join to the new member
        sockJsSend(conn, gson.toJson(PearMessage(type = PearMessage.LOBBY_JOINED, lobbyCode = code, userId = userId)))

        // Tell the joiner about all existing members
        for (existing in lobby.members) {
            if (existing.conn != conn) {
                sockJsSend(conn, gson.toJson(PearMessage(type = PearMessage.USER_JOINED, userId = existing.userId, userName = existing.userName)))
            }
        }

        // Notify everyone else
        broadcastToOthers(conn, code, gson.toJson(PearMessage(type = PearMessage.USER_JOINED, userId = userId, userName = userName)))
        println("[*] $userName ($userId) joined lobby: '$code' (${lobby.members.size} members)")
    }

    private fun handleBroadcast(conn: WebSocket, rawMessage: String, msg: PearMessage) {
        val code = connToLobby[conn] ?: return

        val targetId = msg.targetUserId
        if (targetId != null) {
            val lobby  = lobbies[code] ?: return
            val target = lobby.members.find { it.userId == targetId }
            if (target != null && target.conn.isOpen) {
                sockJsSend(target.conn, rawMessage)
            }
        } else {
            broadcastToOthers(conn, code, rawMessage)
        }
    }

    private fun handleDisconnect(conn: WebSocket) {
        val code   = connToLobby.remove(conn)  ?: return
        val userId = connToUserId.remove(conn) ?: return
        val lobby  = lobbies[code]             ?: return

        val member = lobby.members.find { it.conn == conn }
        lobby.members.removeAll { it.conn == conn }

        if (lobby.members.isEmpty()) {
            lobbies.remove(code)
            println("[*] Lobby removed: '$code' (empty)")
        } else {
            broadcastToOthers(conn, code, gson.toJson(PearMessage(type = PearMessage.USER_LEFT, userId = userId, userName = member?.userName)))
            println("[*] ${member?.userName} ($userId) left lobby: '$code' (${lobby.members.size} remaining)")
        }
    }

    private fun broadcastToOthers(sender: WebSocket, lobbyCode: String, json: String) {
        lobbies[lobbyCode]?.members
            ?.filter { it.conn != sender && it.conn.isOpen }
            ?.forEach { member ->
                try { sockJsSend(member.conn, json) } catch (_: Exception) {}
            }
    }

    private fun sendError(conn: WebSocket, message: String) {
        println("[!] Error sent to ${conn.remoteSocketAddress}: $message")
        sockJsSend(conn, gson.toJson(PearMessage(type = PearMessage.ERROR, message = message)))
    }
}
