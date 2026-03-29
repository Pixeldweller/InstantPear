package com.pixeldweller.instantpear.protocol

data class DebugVariable(
    val name: String,
    val type: String? = null,
    val value: String? = null,
    val hasChildren: Boolean = false,
    val path: String = ""
)

data class PearMessage(
    val type: String = "",
    // Lobby
    val lobbyCode: String? = null,
    val lobbyKey: String? = null,
    // User
    val userName: String? = null,
    val userId: String? = null,
    val targetUserId: String? = null,
    // Document
    val content: String? = null,
    val fileName: String? = null,
    // Change
    val offset: Int? = null,
    val oldLength: Int? = null,
    val newText: String? = null,
    // Cursor
    val cursorOffset: Int? = null,
    val selectionStart: Int? = null,
    val selectionEnd: Int? = null,
    val line: Int? = null,
    val column: Int? = null,
    // Chunking
    val chunkIndex: Int? = null,
    val totalChunks: Int? = null,
    // File request
    val filePath: String? = null,
    // Error
    val message: String? = null,
    // Run/Debug state
    val runState: String? = null,
    val processName: String? = null,
    // Debug variables
    val variables: List<DebugVariable>? = null,
    val variablePath: String? = null,
    // Console viewport
    val consoleText: String? = null
) {
    companion object {
        const val CREATE_LOBBY = "create_lobby"
        const val JOIN_LOBBY = "join_lobby"
        const val LEAVE_LOBBY = "leave_lobby"
        const val LOBBY_CREATED = "lobby_created"
        const val LOBBY_JOINED = "lobby_joined"
        const val DOCUMENT_SYNC = "document_sync"
        const val DOCUMENT_SYNC_CHUNK = "document_sync_chunk"
        const val DOCUMENT_CHANGE = "document_change"
        const val CURSOR_UPDATE = "cursor_update"
        const val MOUSE_MOVE = "mouse_move"
        const val FOCUS_CHANGE = "focus_change"
        const val FILE_CLOSED = "file_closed"
        const val FILE_REQUEST = "file_request"
        const val DOCUMENT_RESYNC_REQUEST = "document_resync_request"
        const val USER_JOINED = "user_joined"
        const val USER_LEFT = "user_left"
        const val ERROR = "error"
        const val RUN_STATE = "run_state"
        const val DEBUG_POSITION = "debug_position"
        const val DEBUG_VARIABLES = "debug_variables"
        const val DEBUG_VARIABLE_CHILDREN = "debug_variable_children"
        const val DEBUG_INSPECT_VARIABLE = "debug_inspect_variable"
        const val CONSOLE_VIEWPORT = "console_viewport"
    }
}
