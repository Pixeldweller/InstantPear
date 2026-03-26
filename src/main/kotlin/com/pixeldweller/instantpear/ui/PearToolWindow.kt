package com.pixeldweller.instantpear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.pixeldweller.instantpear.protocol.InviteLink
import com.pixeldweller.instantpear.session.RemoteUser
import com.pixeldweller.instantpear.session.SessionService
import com.pixeldweller.instantpear.session.SessionState
import com.pixeldweller.instantpear.session.UserFocus
import com.pixeldweller.instantpear.settings.PearSettings
import org.jetbrains.jewel.bridge.addComposeTab
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

class PearToolWindowFactory : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.addComposeTab("InstantPear", focusOnClickInside = true) {
            PearToolWindowContent(project)
        }
    }
}

@Composable
private fun PearToolWindowContent(project: Project) {
    val session = remember { SessionService.getInstance(project) }
    val settings = remember { PearSettings.getInstance() }

    val state by session.state
    val isHost by session.isHost
    val lobbyCode by session.lobbyCode
    val statusMessage by session.statusMessage
    val connectedUsers = session.connectedUsers
    val sharedFiles = session.sharedFiles
    val userFocusMap = session.userFocusMap

    Column(
        modifier = Modifier.padding(12.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        when (state) {
            SessionState.DISCONNECTED -> {
                DisconnectedView(
                    settings = settings,
                    onCreateLobby = { url, code, key, name ->
                        session.createLobby(url, code, key, name)
                    },
                    onJoinLobby = { url, code, key, name ->
                        session.joinLobby(url, code, key, name)
                    },
                    onJoinFromLink = { link, name ->
                        val parsed = InviteLink.parse(link)
                        if (parsed != null) {
                            settings.state.userName = name
                            session.joinLobby(parsed.serverUrl, parsed.code, parsed.key, name)
                        } else {
                            session.statusMessage.value = "Invalid invite link"
                        }
                    }
                )
            }

            SessionState.CONNECTING -> {
                Text("Connecting...")
            }

            SessionState.CONNECTED -> {
                ConnectedView(
                    isHost = isHost,
                    lobbyCode = lobbyCode,
                    sharedFiles = sharedFiles.toList(),
                    connectedUsers = connectedUsers.toList(),
                    userFocusMap = userFocusMap.toMap(),
                    onLeave = { session.leaveLobby() },
                    onJumpToUser = { session.jumpToUser(it) },
                    onCopyInviteLink = {
                        val link = session.getInviteLink()
                        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                        clipboard.setContents(StringSelection(link), null)
                        session.statusMessage.value = "Invite link copied to clipboard"
                    }
                )
            }
        }

        if (statusMessage.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(statusMessage)
        }
    }
}

@Composable
private fun DisconnectedView(
    settings: PearSettings,
    onCreateLobby: (serverUrl: String, code: String, key: String, userName: String) -> Unit,
    onJoinLobby: (serverUrl: String, code: String, key: String, userName: String) -> Unit,
    onJoinFromLink: (link: String, userName: String) -> Unit
) {
    val serverUrlState = rememberTextFieldState(settings.state.serverUrl)
    val userNameState = rememberTextFieldState(settings.state.userName)
    val codeState = rememberTextFieldState()
    val keyState = rememberTextFieldState()
    val inviteLinkState = rememberTextFieldState()

    GroupHeader("Connection")

    Text("Your Name")
    TextField(
        state = userNameState,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Developer") }
    )

    Spacer(Modifier.height(8.dp))
    GroupHeader("Quick Join")

    Text("Invite Link")
    TextField(
        state = inviteLinkState,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("instantpear://join?...") }
    )

    val linkText = inviteLinkState.text.toString()

    DefaultButton(
        onClick = {
            val name = userNameState.text.toString()
            settings.state.userName = name
            onJoinFromLink(linkText, name)
        },
        enabled = linkText.isNotBlank()
    ) { Text("Join from Link") }

    Spacer(Modifier.height(8.dp))
    GroupHeader("Manual")

    Text("Server URL")
    TextField(
        state = serverUrlState,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("ws://localhost:9274") }
    )

    Text("Lobby Code")
    TextField(
        state = codeState,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("e.g. PEAR-1234") }
    )

    Text("Lobby Key")
    TextField(
        state = keyState,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("shared secret") }
    )

    Spacer(Modifier.height(4.dp))

    val codeText = codeState.text.toString()
    val keyText = keyState.text.toString()

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DefaultButton(
            onClick = {
                val url = serverUrlState.text.toString()
                val name = userNameState.text.toString()
                settings.state.serverUrl = url
                settings.state.userName = name
                onCreateLobby(url, codeText, keyText, name)
            },
            enabled = codeText.isNotBlank() && keyText.isNotBlank()
        ) { Text("Create Lobby") }

        OutlinedButton(
            onClick = {
                val url = serverUrlState.text.toString()
                val name = userNameState.text.toString()
                settings.state.serverUrl = url
                settings.state.userName = name
                onJoinLobby(url, codeText, keyText, name)
            },
            enabled = codeText.isNotBlank() && keyText.isNotBlank()
        ) { Text("Join Lobby") }
    }

    Spacer(Modifier.height(8.dp))
    GroupHeader("Settings")

    val focusNewTabs = remember { mutableStateOf(settings.state.focusNewCollabTabs) }
    CheckboxRow(
        text = "Focus new collab tabs when opened",
        checked = focusNewTabs.value,
        onCheckedChange = {
            focusNewTabs.value = it
            settings.state.focusNewCollabTabs = it
        }
    )
}

@Composable
private fun ConnectedView(
    isHost: Boolean,
    lobbyCode: String,
    sharedFiles: List<String>,
    connectedUsers: List<RemoteUser>,
    userFocusMap: Map<String, UserFocus>,
    onLeave: () -> Unit,
    onJumpToUser: (userId: String) -> Unit,
    onCopyInviteLink: () -> Unit
) {
    GroupHeader(if (isHost) "Hosting Session" else "Collaborative Session")

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Lobby: $lobbyCode")
        OutlinedButton(onClick = onCopyInviteLink) {
            Text("Copy Invite Link")
        }
    }

    if (sharedFiles.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text("Shared files (${sharedFiles.size}):")
        sharedFiles.forEach { file ->
            Text("  $file")
        }
    }

    Spacer(Modifier.height(8.dp))

    if (connectedUsers.isNotEmpty()) {
        GroupHeader("Collaborators")
        connectedUsers.forEach { user ->
            val focus = userFocusMap[user.userId]
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (focus != null) {
                    Text("${user.userName}  ${focus.activeFileName}:${focus.cursorLine + 1}")
                    OutlinedButton(onClick = { onJumpToUser(user.userId) }) {
                        Text("Go to")
                    }
                } else {
                    Text(user.userName)
                }
            }
        }
    } else {
        Text("Waiting for collaborators to join...")
    }

    Spacer(Modifier.height(8.dp))

    OutlinedButton(onClick = onLeave) {
        Text("Leave Lobby")
    }
}
