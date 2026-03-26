package com.pixeldweller.instantpear.server

import com.google.gson.Gson
import com.pixeldweller.instantpear.protocol.PearMessage
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 9274
    val server = TestPearServer(port)
    server.start()
    println("===========================================")
    println("  InstantPear Test Server")
    println("  Running on ws://localhost:$port")
    println("===========================================")
    println("Press Ctrl+C to stop")

    Runtime.getRuntime().addShutdownHook(Thread {
        println("\nShutting down...")
        server.stop(1000)
    })
}

class TestPearServer(port: Int) : WebSocketServer(InetSocketAddress(port)) {
    private val gson = Gson()

    data class LobbyMember(val conn: WebSocket, val userId: String, val userName: String)
    data class Lobby(val key: String, val members: MutableList<LobbyMember> = mutableListOf())

    private val lobbies = ConcurrentHashMap<String, Lobby>()
    private val connToLobby = ConcurrentHashMap<WebSocket, String>()
    private val connToUserId = ConcurrentHashMap<WebSocket, String>()

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        println("[+] Connection from ${conn.remoteSocketAddress}")
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        println("[-] Disconnected: ${conn.remoteSocketAddress} (reason: $reason)")
        handleDisconnect(conn)
    }

    override fun onMessage(conn: WebSocket, message: String) {
        val msg = try {
            gson.fromJson(message, PearMessage::class.java)
        } catch (e: Exception) {
            println("[!] Invalid message from ${conn.remoteSocketAddress}: $message")
            return
        }

        println("[>] ${msg.type} from ${connToUserId[conn] ?: conn.remoteSocketAddress}")

        when (msg.type) {
            PearMessage.CREATE_LOBBY -> handleCreateLobby(conn, msg)
            PearMessage.JOIN_LOBBY -> handleJoinLobby(conn, msg)
            PearMessage.LEAVE_LOBBY -> handleDisconnect(conn)
            else -> handleBroadcast(conn, message, msg)
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        println("[!] Error${conn?.let { " from ${it.remoteSocketAddress}" } ?: ""}: ${ex.message}")
        conn?.let { handleDisconnect(it) }
    }

    override fun onStart() {
        println("[*] Server started successfully")
    }

    private fun handleCreateLobby(conn: WebSocket, msg: PearMessage) {
        val code = msg.lobbyCode ?: return sendError(conn, "Missing lobby code")
        val key = msg.lobbyKey ?: return sendError(conn, "Missing lobby key")

        if (lobbies.containsKey(code)) {
            return sendError(conn, "Lobby '$code' already exists")
        }

        val userId = java.util.UUID.randomUUID().toString().take(8)
        val userName = msg.userName ?: "Host"
        val lobby = Lobby(key)
        lobby.members.add(LobbyMember(conn, userId, userName))
        lobbies[code] = lobby
        connToLobby[conn] = code
        connToUserId[conn] = userId

        conn.send(
            gson.toJson(
                PearMessage(
                    type = PearMessage.LOBBY_CREATED,
                    lobbyCode = code,
                    userId = userId
                )
            )
        )

        println("[*] Lobby created: '$code' by $userName ($userId)")
    }

    private fun handleJoinLobby(conn: WebSocket, msg: PearMessage) {
        val code = msg.lobbyCode ?: return sendError(conn, "Missing lobby code")
        val key = msg.lobbyKey ?: return sendError(conn, "Missing lobby key")

        val lobby = lobbies[code] ?: return sendError(conn, "Lobby '$code' not found")

        if (lobby.key != key) {
            return sendError(conn, "Invalid lobby key")
        }

        val userId = java.util.UUID.randomUUID().toString().take(8)
        val userName = msg.userName ?: "Guest"
        lobby.members.add(LobbyMember(conn, userId, userName))
        connToLobby[conn] = code
        connToUserId[conn] = userId

        // Confirm to the joiner
        conn.send(
            gson.toJson(
                PearMessage(
                    type = PearMessage.LOBBY_JOINED,
                    lobbyCode = code,
                    userId = userId
                )
            )
        )

        // Tell the joiner about existing members
        for (existing in lobby.members) {
            if (existing.conn != conn) {
                conn.send(
                    gson.toJson(
                        PearMessage(
                            type = PearMessage.USER_JOINED,
                            userId = existing.userId,
                            userName = existing.userName
                        )
                    )
                )
            }
        }

        // Notify others in the lobby
        broadcastToOthers(
            conn, code, gson.toJson(
                PearMessage(
                    type = PearMessage.USER_JOINED,
                    userId = userId,
                    userName = userName
                )
            )
        )

        println("[*] $userName ($userId) joined lobby: '$code' (${lobby.members.size} members)")
    }

    private fun handleBroadcast(conn: WebSocket, rawMessage: String, msg: PearMessage) {
        val code = connToLobby[conn] ?: return

        val targetId = msg.targetUserId
        if (targetId != null) {
            // Targeted message - send only to specific user
            val lobby = lobbies[code] ?: return
            val target = lobby.members.find { it.userId == targetId }
            if (target != null && target.conn.isOpen) {
                target.conn.send(rawMessage)
            }
        } else {
            // Broadcast to all others
            broadcastToOthers(conn, code, rawMessage)
        }
    }

    private fun handleDisconnect(conn: WebSocket) {
        val code = connToLobby.remove(conn) ?: return
        val userId = connToUserId.remove(conn) ?: return
        val lobby = lobbies[code] ?: return

        val member = lobby.members.find { it.conn == conn }
        lobby.members.removeAll { it.conn == conn }

        if (lobby.members.isEmpty()) {
            lobbies.remove(code)
            println("[*] Lobby removed: '$code' (empty)")
        } else {
            broadcastToOthers(
                conn, code, gson.toJson(
                    PearMessage(
                        type = PearMessage.USER_LEFT,
                        userId = userId,
                        userName = member?.userName
                    )
                )
            )
            println("[*] ${member?.userName} ($userId) left lobby: '$code' (${lobby.members.size} remaining)")
        }
    }

    private fun broadcastToOthers(sender: WebSocket, lobbyCode: String, message: String) {
        val lobby = lobbies[lobbyCode] ?: return
        lobby.members
            .filter { it.conn != sender && it.conn.isOpen }
            .forEach {
                try {
                    it.conn.send(message)
                } catch (_: Exception) {
                }
            }
    }

    private fun sendError(conn: WebSocket, message: String) {
        println("[!] Error sent to ${conn.remoteSocketAddress}: $message")
        conn.send(gson.toJson(PearMessage(type = PearMessage.ERROR, message = message)))
    }
}
