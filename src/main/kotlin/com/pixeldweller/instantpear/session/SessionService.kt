package com.pixeldweller.instantpear.session

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.messages.MessageBusConnection
import com.pixeldweller.instantpear.debug.DebugSyncService
import com.pixeldweller.instantpear.editor.CollabEditor
import com.pixeldweller.instantpear.network.PearClient
import com.pixeldweller.instantpear.protocol.DebugVariable
import com.pixeldweller.instantpear.protocol.PearMessage
import com.pixeldweller.instantpear.settings.PearSettings

enum class SessionState {
    DISCONNECTED, CONNECTING, CONNECTED
}

data class RemoteUser(val userId: String, val userName: String)

data class UserFocus(
    val userId: String,
    val userName: String,
    val activeFileName: String,
    val cursorLine: Int = 0,
    val cursorOffset: Int = 0
)

@Service(Service.Level.PROJECT)
class SessionService(private val project: Project) {
    private val log = Logger.getInstance(SessionService::class.java)

    // Observable state for Compose UI
    val state = mutableStateOf(SessionState.DISCONNECTED)
    val isHost = mutableStateOf(false)
    val lobbyCode = mutableStateOf("")
    val statusMessage = mutableStateOf("")
    val connectedUsers = mutableStateListOf<RemoteUser>()
    val sharedFiles = mutableStateListOf<String>()
    val userFocusMap = mutableStateMapOf<String, UserFocus>()

    // Run/Debug state (observable for UI)
    val hostRunState = mutableStateOf("idle") // "idle", "running", "debugging"
    val hostProcessName = mutableStateOf("")
    val debugFileName = mutableStateOf<String?>(null)
    val debugLine = mutableStateOf<Int?>(null)
    val debugVariables = mutableStateListOf<DebugVariable>()
    // Expanded children: parentPath -> list of children
    val debugVariableChildren = mutableStateMapOf<String, List<DebugVariable>>()

    // Stored for invite link generation
    private var currentServerUrl: String = ""
    private var currentLobbyKey: String = ""

    private var client: PearClient? = null
    private val collabEditors = mutableMapOf<String, CollabEditor>()
    private val guestVirtualFiles = mutableMapOf<String, LightVirtualFile>()
    // Chunk reassembly buffer: fileName -> array of chunks
    private val chunkBuffer = mutableMapOf<String, Array<String?>>()
    private var myUserId: String? = null
    private var myUserName: String = "Developer"
    private var messageBusConnection: MessageBusConnection? = null
    private var debugSyncService: DebugSyncService? = null

    fun createLobby(serverUrl: String, code: String, key: String, userName: String) {
        if (state.value != SessionState.DISCONNECTED) return

        val editor = getActiveEditor()
        if (editor == null) {
            statusMessage.value = "No active editor. Open a file first."
            return
        }

        state.value = SessionState.CONNECTING
        isHost.value = true
        myUserName = userName
        currentServerUrl = serverUrl
        currentLobbyKey = key
        statusMessage.value = "Connecting to $serverUrl..."

        val fileId = getFileId(editor)
        attachEditor(fileId, editor)
        registerEditorListener()

        connectClient(serverUrl, userName) {
            client?.send(
                PearMessage(
                    type = PearMessage.CREATE_LOBBY,
                    lobbyCode = code,
                    lobbyKey = key,
                    userName = userName
                )
            )
        }

        // Host: start debug/run sync
        debugSyncService = DebugSyncService(
            project = project,
            sendMessage = { msg ->
                val enriched = msg.copy(userId = myUserId, userName = myUserName)
                client?.send(enriched)
            },
            getSharedFileIds = { sharedFiles.toSet() }
        )
        debugSyncService?.start()
    }

    fun joinLobby(serverUrl: String, code: String, key: String, userName: String) {
        if (state.value != SessionState.DISCONNECTED) return

        state.value = SessionState.CONNECTING
        isHost.value = false
        myUserName = userName
        currentServerUrl = serverUrl
        currentLobbyKey = key
        statusMessage.value = "Connecting to $serverUrl..."

        registerEditorListener()

        connectClient(serverUrl, userName) {
            client?.send(
                PearMessage(
                    type = PearMessage.JOIN_LOBBY,
                    lobbyCode = code,
                    lobbyKey = key,
                    userName = userName
                )
            )
        }
    }

    fun getInviteLink(): String {
        val code = lobbyCode.value
        val encodedServer = java.net.URLEncoder.encode(currentServerUrl, "UTF-8")
        val encodedKey = java.net.URLEncoder.encode(currentLobbyKey, "UTF-8")
        val encodedCode = java.net.URLEncoder.encode(code, "UTF-8")
        return "instantpear://join?server=$encodedServer&code=$encodedCode&key=$encodedKey"
    }

    fun leaveLobby() {
        try {
            client?.send(PearMessage(type = PearMessage.LEAVE_LOBBY))
        } catch (_: Exception) {
        }
        client?.disconnect()
        cleanup()
        statusMessage.value = "Left lobby"
    }

    fun jumpToUser(userId: String) {
        val focus = userFocusMap[userId] ?: return
        val fileId = focus.activeFileName
        val ce = collabEditors[fileId] ?: return

        if (isHost.value) {
            val virtualFile = FileDocumentManager.getInstance().getFile(ce.editor.document) ?: return
            FileEditorManager.getInstance(project).openFile(virtualFile, true)
        } else {
            val virtualFile = guestVirtualFiles[fileId] ?: return
            FileEditorManager.getInstance(project).openFile(virtualFile, true)
        }

        val offset = focus.cursorOffset.coerceIn(0, ce.editor.document.textLength)
        ce.editor.caretModel.moveToOffset(offset)
        ce.editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
    }

    fun requestFile(relativePath: String) {
        if (state.value != SessionState.CONNECTED || isHost.value) return
        client?.send(
            PearMessage(
                type = PearMessage.FILE_REQUEST,
                filePath = relativePath,
                userId = myUserId,
                userName = myUserName
            )
        )
        statusMessage.value = "Requested host to open: $relativePath"
    }

    private fun handleFileRequest(message: PearMessage) {
        val requesterId = message.userId ?: return
        val requesterName = message.userName ?: "Someone"
        val filePath = message.filePath ?: return

        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("InstantPear")
            .createNotification(
                "File Request",
                "$requesterName wants you to open: $filePath",
                NotificationType.INFORMATION
            )

        notification.addAction(NotificationAction.createSimpleExpiring("Accept") {
            acceptFileRequest(filePath, requesterId)
        })
        notification.addAction(NotificationAction.createSimpleExpiring("Deny") {
            statusMessage.value = "Denied file request: $filePath"
        })

        notification.notify(project)
    }

    private fun acceptFileRequest(relativePath: String, requesterId: String) {
        val basePath = project.basePath
        if (basePath == null) {
            statusMessage.value = "Cannot resolve project path"
            return
        }

        val fullPath = "$basePath/$relativePath"
        val vf = LocalFileSystem.getInstance().findFileByPath(fullPath)
        if (vf == null) {
            statusMessage.value = "File not found: $relativePath"
            return
        }

        FileEditorManager.getInstance(project).openFile(vf, true)

        // The FileEditorManagerListener will pick up the new editor and share it,
        // but only if it's not already shared. Give it a moment to initialize.
        ApplicationManager.getApplication().invokeLater {
            val editors = FileEditorManager.getInstance(project).getEditors(vf)
            val editor = editors.filterIsInstance<TextEditor>().firstOrNull()?.editor
            if (editor != null) {
                val fileId = getFileId(editor)
                if (!collabEditors.containsKey(fileId)) {
                    attachEditor(fileId, editor)
                    for (user in connectedUsers) {
                        client?.send(
                            PearMessage(
                                type = PearMessage.DOCUMENT_SYNC,
                                content = editor.document.text,
                                fileName = fileId,
                                targetUserId = user.userId
                            )
                        )
                    }
                }
                statusMessage.value = "Shared: $fileId"
            }
        }
    }

    private fun attachEditor(fileId: String, editor: Editor) {
        if (collabEditors.containsKey(fileId)) return
        val ce = CollabEditor(project, editor, fileId) { msg ->
            val enriched = msg.copy(userId = myUserId, userName = myUserName)
            client?.send(enriched)
        }
        collabEditors[fileId] = ce
        if (fileId !in sharedFiles) {
            sharedFiles.add(fileId)
        }
    }

    private fun registerEditorListener() {
        messageBusConnection = project.messageBus.connect()
        messageBusConnection?.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    if (state.value != SessionState.CONNECTED) return
                    val editor = (event.newEditor as? TextEditor)?.editor ?: return

                    if (isHost.value) {
                        val fileId = getFileId(editor)

                        if (!collabEditors.containsKey(fileId)) {
                            // Host opened a new file - share it with all guests
                            attachEditor(fileId, editor)
                            for (user in connectedUsers) {
                                client?.send(
                                    PearMessage(
                                        type = PearMessage.DOCUMENT_SYNC,
                                        content = editor.document.text,
                                        fileName = fileId,
                                        targetUserId = user.userId
                                    )
                                )
                            }
                            statusMessage.value = "Sharing: $fileId"
                        }

                        // Notify focus change
                        client?.send(
                            PearMessage(
                                type = PearMessage.FOCUS_CHANGE,
                                fileName = fileId,
                                userId = myUserId,
                                userName = myUserName
                            )
                        )
                    } else {
                        // Guest switched tabs - send focus change if it's a collab file
                        val fileId = findFileIdForEditor(editor)
                        if (fileId != null) {
                            client?.send(
                                PearMessage(
                                    type = PearMessage.FOCUS_CHANGE,
                                    fileName = fileId,
                                    userId = myUserId,
                                    userName = myUserName
                                )
                            )
                        }
                    }
                }
            }
        )
    }

    private fun connectClient(serverUrl: String, userName: String, onConnected: () -> Unit) {
        client = PearClient(
            serverUrl = serverUrl,
            onMessage = ::handleMessage,
            onConnected = onConnected,
            onDisconnected = { reason ->
                ApplicationManager.getApplication().invokeLater {
                    cleanup()
                    statusMessage.value = "Disconnected: $reason"
                }
            },
            onError = { error ->
                ApplicationManager.getApplication().invokeLater {
                    log.warn("WebSocket error", error)
                    cleanup()
                    statusMessage.value =
                        "Connection error: ${error.message ?: error.cause?.message ?: "Unknown error"}"
                }
            }
        )
        client?.connect()
    }

    private fun handleMessage(message: PearMessage) {
        ApplicationManager.getApplication().invokeLater {
            when (message.type) {
                PearMessage.LOBBY_CREATED -> {
                    myUserId = message.userId
                    lobbyCode.value = message.lobbyCode ?: ""
                    state.value = SessionState.CONNECTED
                    statusMessage.value = "Lobby created! Share code: ${message.lobbyCode}"
                }

                PearMessage.LOBBY_JOINED -> {
                    myUserId = message.userId
                    lobbyCode.value = message.lobbyCode ?: ""
                    state.value = SessionState.CONNECTED
                    statusMessage.value = "Joined lobby. Waiting for document sync..."
                }

                PearMessage.USER_JOINED -> {
                    val userId = message.userId ?: return@invokeLater
                    val userName = message.userName ?: "Unknown"
                    connectedUsers.add(RemoteUser(userId, userName))
                    statusMessage.value = "$userName joined the session"

                    // Host sends ALL shared files to the new user
                    if (isHost.value) {
                        for ((fileId, ce) in collabEditors) {
                            client?.send(
                                PearMessage(
                                    type = PearMessage.DOCUMENT_SYNC,
                                    content = ce.getCurrentContent(),
                                    fileName = fileId,
                                    targetUserId = userId
                                )
                            )
                        }
                    }
                }

                PearMessage.USER_LEFT -> {
                    val userId = message.userId ?: return@invokeLater
                    val user = connectedUsers.find { it.userId == userId }
                    connectedUsers.removeAll { it.userId == userId }
                    userFocusMap.remove(userId)
                    collabEditors.values.forEach { it.removeRemoteUser(userId) }

                    if (!isHost.value && connectedUsers.isEmpty()) {
                        cleanup()
                        statusMessage.value = "Host left the session"
                    } else {
                        statusMessage.value = "${user?.userName ?: "Someone"} left the session"
                    }
                }

                PearMessage.DOCUMENT_SYNC -> {
                    if (!isHost.value) {
                        val fileName = message.fileName ?: "collab-file"
                        val content = message.content ?: ""
                        openCollabFile(fileName, content)
                    }
                }

                PearMessage.DOCUMENT_SYNC_CHUNK -> {
                    if (!isHost.value) {
                        val fileName = message.fileName ?: return@invokeLater
                        val chunkIndex = message.chunkIndex ?: return@invokeLater
                        val totalChunks = message.totalChunks ?: return@invokeLater
                        val chunk = message.content ?: return@invokeLater

                        val chunks = chunkBuffer.getOrPut(fileName) { arrayOfNulls(totalChunks) }
                        chunks[chunkIndex] = chunk

                        if (chunks.all { it != null }) {
                            chunkBuffer.remove(fileName)
                            val fullContent = chunks.joinToString("")
                            openCollabFile(fileName, fullContent)
                        }
                    }
                }

                PearMessage.DOCUMENT_CHANGE -> {
                    val fileId = message.fileName ?: return@invokeLater
                    collabEditors[fileId]?.applyRemoteChange(message)
                }

                PearMessage.CURSOR_UPDATE -> {
                    val fileId = message.fileName ?: return@invokeLater
                    val userId = message.userId ?: return@invokeLater
                    val userName = message.userName
                        ?: connectedUsers.find { it.userId == userId }?.userName
                        ?: "Unknown"
                    collabEditors[fileId]?.updateRemoteCursor(userId, userName, message)
                    userFocusMap[userId] = UserFocus(
                        userId = userId,
                        userName = userName,
                        activeFileName = fileId,
                        cursorLine = message.line ?: 0,
                        cursorOffset = message.cursorOffset ?: 0
                    )
                }

                PearMessage.MOUSE_MOVE -> {
                    val fileId = message.fileName ?: return@invokeLater
                    val userId = message.userId ?: return@invokeLater
                    val userName = message.userName
                        ?: connectedUsers.find { it.userId == userId }?.userName
                        ?: "Unknown"
                    collabEditors[fileId]?.updateRemotePointer(
                        userId, userName,
                        message.line ?: 0,
                        message.column ?: 0
                    )
                }

                PearMessage.FILE_REQUEST -> {
                    if (isHost.value) {
                        handleFileRequest(message)
                    }
                }

                PearMessage.FOCUS_CHANGE -> {
                    val userId = message.userId ?: return@invokeLater
                    val userName = message.userName ?: "Unknown"
                    val fileId = message.fileName ?: return@invokeLater
                    val existing = userFocusMap[userId]
                    userFocusMap[userId] = UserFocus(
                        userId = userId,
                        userName = userName,
                        activeFileName = fileId,
                        cursorLine = existing?.cursorLine ?: 0,
                        cursorOffset = existing?.cursorOffset ?: 0
                    )
                }

                PearMessage.RUN_STATE -> {
                    hostRunState.value = message.runState ?: "idle"
                    hostProcessName.value = message.processName ?: ""
                    if (message.runState == "idle") {
                        clearDebugState()
                    }
                }

                PearMessage.DEBUG_POSITION -> {
                    val fileId = message.fileName
                    val line = message.line
                    // Clear previous debug line highlight
                    debugFileName.value?.let { prevFile ->
                        collabEditors[prevFile]?.setDebugLine(null)
                    }
                    debugFileName.value = fileId
                    debugLine.value = line
                    if (fileId != null && line != null) {
                        collabEditors[fileId]?.setDebugLine(line)
                    }
                }

                PearMessage.DEBUG_VARIABLES -> {
                    debugVariables.clear()
                    debugVariableChildren.clear()
                    message.variables?.let { debugVariables.addAll(it) }
                }

                PearMessage.DEBUG_VARIABLE_CHILDREN -> {
                    val parentPath = message.variablePath ?: return@invokeLater
                    debugVariableChildren[parentPath] = message.variables ?: emptyList()
                }

                PearMessage.DEBUG_INSPECT_VARIABLE -> {
                    // Host receives inspect request from client
                    if (isHost.value) {
                        val varPath = message.variablePath ?: return@invokeLater
                        val fileId = debugFileName.value ?: return@invokeLater
                        debugSyncService?.handleInspectVariable(varPath, fileId)
                    }
                }

                PearMessage.ERROR -> {
                    statusMessage.value = "Server error: ${message.message}"
                    if (state.value == SessionState.CONNECTING) {
                        cleanup()
                    }
                }
            }
        }
    }

    fun requestInspectVariable(variablePath: String) {
        if (isHost.value) return // Host doesn't need to request
        client?.send(
            PearMessage(
                type = PearMessage.DEBUG_INSPECT_VARIABLE,
                variablePath = variablePath,
                userId = myUserId,
                userName = myUserName
            )
        )
    }

    private fun clearDebugState() {
        debugFileName.value?.let { collabEditors[it]?.setDebugLine(null) }
        debugFileName.value = null
        debugLine.value = null
        debugVariables.clear()
        debugVariableChildren.clear()
    }

    private fun openCollabFile(fileName: String, content: String) {
        if (collabEditors.containsKey(fileName)) {
            // Already have this file open, skip
            return
        }

        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(fileName)
        val virtualFile = LightVirtualFile("Collab: $fileName", fileType, content)
        guestVirtualFiles[fileName] = virtualFile
        if (fileName !in sharedFiles) {
            sharedFiles.add(fileName)
        }

        val focusNew = PearSettings.getInstance().state.focusNewCollabTabs
        val fem = FileEditorManager.getInstance(project)
        val previousFile = fem.selectedEditor?.file
        fem.openFile(virtualFile, focusNew)

        // Delay to let the editor fully initialize
        ApplicationManager.getApplication().invokeLater {
            val editors = fem.getEditors(virtualFile)
            val editor = editors.filterIsInstance<TextEditor>().firstOrNull()?.editor
            if (editor != null) {
                attachEditor(fileName, editor)
                statusMessage.value = "Opened: $fileName"
            } else {
                log.warn("Could not get editor for collaborative file: $fileName")
            }

            // Restore previous tab if user doesn't want focus on new collab tabs
            if (!focusNew && previousFile != null) {
                fem.openFile(previousFile, true)
            }
        }
    }

    private fun findFileIdForEditor(editor: Editor): String? {
        return collabEditors.entries.find { (_, ce) -> ce.editor === editor }?.key
    }

    private fun getFileId(editor: Editor): String {
        val vf = FileDocumentManager.getInstance().getFile(editor.document)
        return vf?.name ?: "untitled"
    }

    private fun closeGuestTabs() {
        val fem = FileEditorManager.getInstance(project)
        for (vf in guestVirtualFiles.values) {
            fem.closeFile(vf)
        }
        guestVirtualFiles.clear()
    }

    private fun getActiveEditor(): Editor? {
        val fileEditor = FileEditorManager.getInstance(project).selectedEditor
        return (fileEditor as? TextEditor)?.editor
    }

    private fun cleanup() {
        debugSyncService?.let { Disposer.dispose(it) }
        debugSyncService = null
        messageBusConnection?.disconnect()
        messageBusConnection = null
        collabEditors.values.forEach { Disposer.dispose(it) }
        collabEditors.clear()
        closeGuestTabs()
        client?.disconnect()
        client = null
        myUserId = null
        currentServerUrl = ""
        currentLobbyKey = ""
        state.value = SessionState.DISCONNECTED
        isHost.value = false
        connectedUsers.clear()
        sharedFiles.clear()
        userFocusMap.clear()
        chunkBuffer.clear()
        hostRunState.value = "idle"
        hostProcessName.value = ""
        clearDebugState()
    }

    companion object {
        fun getInstance(project: Project): SessionService = project.service()
    }
}
