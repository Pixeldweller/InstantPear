package com.pixeldweller.instantpear.protocol

import java.net.URI
import java.net.URLDecoder

data class InviteLink(val serverUrl: String, val code: String, val key: String) {
    companion object {
        fun parse(link: String): InviteLink? {
            return try {
                val uri = URI(link.trim())
                if (uri.scheme != "instantpear" || uri.host != "join") return null
                val params = (uri.rawQuery ?: return null)
                    .split("&")
                    .associate {
                        val (k, v) = it.split("=", limit = 2)
                        k to URLDecoder.decode(v, "UTF-8")
                    }
                val server = params["server"] ?: return null
                val code = params["code"] ?: return null
                val key = params["key"] ?: return null
                if (server.isBlank() || code.isBlank() || key.isBlank()) return null
                InviteLink(server, code, key)
            } catch (_: Exception) {
                null
            }
        }
    }
}
