package com.pixeldweller.instantpear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.pixeldweller.instantpear.history.EditOp
import com.pixeldweller.instantpear.history.HistoryService
import com.pixeldweller.instantpear.protocol.InviteLink
import com.pixeldweller.instantpear.protocol.DebugVariable
import com.pixeldweller.instantpear.overlay.ScreenshareService
import com.pixeldweller.instantpear.session.RemoteUser
import com.pixeldweller.instantpear.session.SessionService
import com.pixeldweller.instantpear.session.SessionState
import com.pixeldweller.instantpear.session.UserFocus
import com.pixeldweller.instantpear.settings.PearSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.jetbrains.jewel.bridge.addComposeTab
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
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
    val screenshare = remember { ScreenshareService.getInstance(project) }
    val settings = remember { PearSettings.getInstance() }

    val state by session.state
    val isHost by session.isHost
    val lobbyCode by session.lobbyCode
    val statusMessage by session.statusMessage
    val connectedUsers = session.connectedUsers
    val sharedFiles = session.sharedFiles
    val userFocusMap = session.userFocusMap
    val hostRunState by session.hostRunState
    val hostProcessName by session.hostProcessName
    val debugFileName by session.debugFileName
    val debugLine by session.debugLine
    val debugVariables = session.debugVariables
    val debugVariableChildren = session.debugVariableChildren
    val consoleViewport by session.consoleViewport
    val undoHints = session.undoHints

    val selectedTab = remember { mutableStateOf("coding") }

    val scrollState = rememberScrollState()
    VerticallyScrollableContainer(
        scrollState = scrollState,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Shared connection header — same name + server URL for both flows.
            ConnectionHeader(settings)

            Spacer(Modifier.height(8.dp))
            TabBar(selectedTab)
            Spacer(Modifier.height(8.dp))

            when (selectedTab.value) {
                "coding" -> CodingTabContent(
                    session = session,
                    settings = settings,
                    state = state,
                    isHost = isHost,
                    lobbyCode = lobbyCode,
                    connectedUsers = connectedUsers.toList(),
                    sharedFiles = sharedFiles.toList(),
                    closedCollabFiles = session.closedCollabFiles.toList(),
                    userFocusMap = userFocusMap.toMap(),
                    hostRunState = hostRunState,
                    hostProcessName = hostProcessName,
                    debugFileName = debugFileName,
                    debugLine = debugLine,
                    debugVariables = debugVariables.toList(),
                    debugVariableChildren = debugVariableChildren.toMap(),
                    consoleViewport = consoleViewport,
                    undoHints = undoHints.toMap(),
                )

                "screenshare" -> ScreensharePanel(
                    screenshare = screenshare,
                    defaultUserName = settings.state.userName,
                )
            }

            if (statusMessage.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(statusMessage)
            }
        }
    }
}

@Composable
private fun ConnectionHeader(settings: PearSettings) {
    val nameState = rememberTextFieldState(settings.state.userName)
    val urlState = rememberTextFieldState(settings.state.serverUrl)
    val sockJs = remember { mutableStateOf(settings.state.useSockJS) }

    GroupHeader("Connection")

    Text("Your Name")
    TextField(
        state = nameState,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Developer") }
    )

    Text("Server URL")
    TextField(
        state = urlState,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("ws://host:9274/ws") }
    )

    CheckboxRow(
        text = "Use SockJS endpoint",
        checked = sockJs.value,
        onCheckedChange = {
            sockJs.value = it
            settings.state.useSockJS = it
        }
    )

    // Persist on every recomposition so the current field values are always
    // available to Start Screenshare / Create / Join without requiring an
    // explicit commit button.
    settings.state.userName = nameState.text.toString()
    settings.state.serverUrl = urlState.text.toString()
}

@Composable
private fun TabBar(selected: androidx.compose.runtime.MutableState<String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TabButton("Coding Lobby", selected.value == "coding") { selected.value = "coding" }
        TabButton("Screenshare Lobby", selected.value == "screenshare") { selected.value = "screenshare" }
    }
}

@Composable
private fun TabButton(label: String, active: Boolean, onClick: () -> Unit) {
    if (active) DefaultButton(onClick = onClick) { Text(label) }
    else OutlinedButton(onClick = onClick) { Text(label) }
}

@Composable
private fun CodingTabContent(
    session: SessionService,
    settings: PearSettings,
    state: SessionState,
    isHost: Boolean,
    lobbyCode: String,
    connectedUsers: List<RemoteUser>,
    sharedFiles: List<String>,
    closedCollabFiles: List<String>,
    userFocusMap: Map<String, UserFocus>,
    hostRunState: String,
    hostProcessName: String,
    debugFileName: String?,
    debugLine: Int?,
    debugVariables: List<DebugVariable>,
    debugVariableChildren: Map<String, List<DebugVariable>>,
    consoleViewport: String,
    undoHints: Map<String, String>,
) {
    when (state) {
        SessionState.DISCONNECTED -> DisconnectedView(
            settings = settings,
            onCreateLobby = { url, code, key, name -> session.createLobby(url, code, key, name) },
            onJoinLobby = { url, code, key, name -> session.joinLobby(url, code, key, name) },
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
        SessionState.CONNECTING -> Text("Connecting...")
        SessionState.CONNECTED -> ConnectedView(
            isHost = isHost,
            lobbyCode = lobbyCode,
            sharedFiles = sharedFiles,
            closedCollabFiles = closedCollabFiles,
            connectedUsers = connectedUsers,
            userFocusMap = userFocusMap,
            hostRunState = hostRunState,
            hostProcessName = hostProcessName,
            debugFileName = debugFileName,
            debugLine = debugLine,
            debugVariables = debugVariables,
            debugVariableChildren = debugVariableChildren,
            consoleViewport = consoleViewport,
            history = session.history,
            undoHints = undoHints,
            onLeave = { session.leaveLobby() },
            onJumpToUser = { session.jumpToUser(it) },
            onReopenFile = { session.reopenFile(it) },
            onCopyInviteLink = {
                val link = session.getInviteLink()
                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                clipboard.setContents(StringSelection(link), null)
                session.statusMessage.value = "Invite link copied to clipboard"
            },
            onInspectVariable = { session.requestInspectVariable(it) },
            onRestoreState = { fileId, opId -> session.restoreToState(fileId, opId) },
            onRestoreBaseline = { fileId -> session.restoreToBaseline(fileId) }
        )
    }
}

@Composable
private fun DisconnectedView(
    settings: PearSettings,
    onCreateLobby: (serverUrl: String, code: String, key: String, userName: String) -> Unit,
    onJoinLobby: (serverUrl: String, code: String, key: String, userName: String) -> Unit,
    onJoinFromLink: (link: String, userName: String) -> Unit
) {
    val codeState = rememberTextFieldState()
    val keyState = rememberTextFieldState()
    val inviteLinkState = rememberTextFieldState()

    GroupHeader("Quick Join")

    Text("Invite Link")
    TextField(
        state = inviteLinkState,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("instantpear://join?...") }
    )

    val linkText = inviteLinkState.text.toString()

    DefaultButton(
        onClick = { onJoinFromLink(linkText, settings.state.userName) },
        enabled = linkText.isNotBlank()
    ) { Text("Join from Link") }

    Spacer(Modifier.height(8.dp))
    GroupHeader("Manual")

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
                onCreateLobby(
                    settings.state.serverUrl, codeText, keyText, settings.state.userName,
                )
            },
            enabled = codeText.isNotBlank() && keyText.isNotBlank()
        ) { Text("Create Lobby") }

        OutlinedButton(
            onClick = {
                onJoinLobby(
                    settings.state.serverUrl, codeText, keyText, settings.state.userName,
                )
            },
            enabled = codeText.isNotBlank() && keyText.isNotBlank()
        ) { Text("Join Lobby") }
    }

    Spacer(Modifier.height(8.dp))
    GroupHeader("Coding Settings")

    val focusNewTabs = remember { mutableStateOf(settings.state.focusNewCollabTabs) }
    CheckboxRow(
        text = "Focus new collab tabs when opened",
        checked = focusNewTabs.value,
        onCheckedChange = {
            focusNewTabs.value = it
            settings.state.focusNewCollabTabs = it
        }
    )

    val sendDebugVars = remember { mutableStateOf(settings.state.sendDebugVariables) }
    CheckboxRow(
        text = "Send debug variables to client when debugging",
        checked = sendDebugVars.value,
        onCheckedChange = {
            sendDebugVars.value = it
            settings.state.sendDebugVariables = it
        }
    )
}

@Composable
private fun ConnectedView(
    isHost: Boolean,
    lobbyCode: String,
    sharedFiles: List<String>,
    closedCollabFiles: List<String>,
    connectedUsers: List<RemoteUser>,
    userFocusMap: Map<String, UserFocus>,
    hostRunState: String,
    hostProcessName: String,
    debugFileName: String?,
    debugLine: Int?,
    debugVariables: List<DebugVariable>,
    debugVariableChildren: Map<String, List<DebugVariable>>,
    consoleViewport: String,
    history: HistoryService,
    undoHints: Map<String, String>,
    onLeave: () -> Unit,
    onJumpToUser: (userId: String) -> Unit,
    onReopenFile: (fileId: String) -> Unit,
    onCopyInviteLink: () -> Unit,
    onInspectVariable: (variablePath: String) -> Unit,
    onRestoreState: (fileId: String, opId: Long) -> Unit,
    onRestoreBaseline: (fileId: String) -> Unit
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

    // Run/Debug state indicator
    if (hostRunState != "idle") {
        Spacer(Modifier.height(4.dp))
        val stateLabel = when (hostRunState) {
            "running" -> "Running"
            "debugging" -> "Debugging"
            else -> hostRunState
        }
        val processLabel = if (hostProcessName.isNotEmpty()) ": $hostProcessName" else ""
        Text(
            text = "$stateLabel$processLabel",
            fontWeight = FontWeight.Bold
        )
    }

    // Debug position indicator
    if (hostRunState == "debugging" && debugFileName != null && debugLine != null) {
        Text("Paused at $debugFileName:${debugLine + 1}")
    }

    if (sharedFiles.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text("Shared files (${sharedFiles.size}):")
        sharedFiles.forEach { file ->
            val isClosed = !isHost && file in closedCollabFiles
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("  $file")
                if (isClosed) {
                    OutlinedButton(onClick = { onReopenFile(file) }) {
                        Text("Reopen")
                    }
                }
            }
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

    // Debug variables panel
    if (hostRunState == "debugging" && debugVariables.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        GroupHeader("Debug Variables")
        DebugVariableList(
            variables = debugVariables,
            childrenMap = debugVariableChildren,
            depth = 0,
            onInspect = onInspectVariable
        )
    }

    // Console viewport
    if (hostRunState != "idle" && consoleViewport.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Host Console", fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = {
                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                clipboard.setContents(StringSelection(consoleViewport), null)
            }) {
                Text("Copy", fontSize = 10.sp)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E1E1E))
                .padding(6.dp)
                .horizontalScroll(rememberScrollState())
        ) {
            Text(
                text = consoleViewport,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = Color(0xFFCCCCCC)
            )
        }
    }

    if (undoHints.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        GroupHeader("Next Ctrl-Z")
        undoHints.forEach { (fileId, preview) ->
            Text(
                text = "$fileId: $preview",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
        }
    }

    if (isHost) {
        Spacer(Modifier.height(8.dp))
        HistoryPanel(
            history = history,
            sharedFiles = sharedFiles,
            onRestore = onRestoreState,
            onRestoreBaseline = onRestoreBaseline
        )
    }

    Spacer(Modifier.height(8.dp))

    OutlinedButton(onClick = onLeave) {
        Text("Leave Lobby")
    }
}

private val historyTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

@Composable
private fun HistoryPanel(
    history: HistoryService,
    sharedFiles: List<String>,
    onRestore: (fileId: String, opId: Long) -> Unit,
    onRestoreBaseline: (fileId: String) -> Unit
) {
    GroupHeader("Edit History")

    if (sharedFiles.isEmpty()) {
        Text("No shared files yet.")
        return
    }

    val selectedFile = remember { mutableStateOf(sharedFiles.first()) }
    if (selectedFile.value !in sharedFiles) selectedFile.value = sharedFiles.first()

    // File picker row
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        sharedFiles.forEach { f ->
            val isSel = f == selectedFile.value
            if (isSel) {
                DefaultButton(onClick = { selectedFile.value = f }) { Text(f, fontSize = 10.sp) }
            } else {
                OutlinedButton(onClick = { selectedFile.value = f }) { Text(f, fontSize = 10.sp) }
            }
        }
    }

    val fileId = selectedFile.value
    val fh = history.timelines[fileId]

    val selectedTab = remember { mutableStateOf("applied") } // "applied" | "displaced"
    val displaced = fh?.displaced?.value
    val altAvailable = !displaced.isNullOrEmpty()

    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (selectedTab.value == "applied") {
            DefaultButton(onClick = { selectedTab.value = "applied" }) { Text("Main", fontSize = 10.sp) }
        } else {
            OutlinedButton(onClick = { selectedTab.value = "applied" }) { Text("Main", fontSize = 10.sp) }
        }
        if (altAvailable) {
            if (selectedTab.value == "displaced") {
                DefaultButton(onClick = { selectedTab.value = "displaced" }) { Text("Alternate", fontSize = 10.sp) }
            } else {
                OutlinedButton(onClick = { selectedTab.value = "displaced" }) { Text("Alternate", fontSize = 10.sp) }
            }
        } else {
            Text("(no alt branch)", fontSize = 10.sp)
        }
    }

    Spacer(Modifier.height(4.dp))

    val ops: List<EditOp> = when {
        fh == null -> emptyList()
        selectedTab.value == "displaced" -> displaced ?: emptyList()
        else -> fh.applied.toList()
    }

    val baseline = fh?.baseline?.value
    val baselineTs = fh?.baselineTimestamp?.value ?: 0L
    val showBaselineRow = selectedTab.value == "applied" && baseline != null

    if (ops.isEmpty() && !showBaselineRow) {
        Text("No edits recorded.", fontSize = 10.sp)
        return
    }

    // Show newest first
    ops.asReversed().forEach { op ->
        val time = historyTimeFormat.format(Date(op.timestamp))
        val preview = buildPreview(op)
        val restorable = !op.undone // both tabs: clicking restores to that state
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 1.dp)
                .then(
                    if (restorable) Modifier.clickable { onRestore(fileId, op.id) }
                    else Modifier
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val marker = if (op.undone) "[undone] " else ""
            Text(
                text = "$time  ${op.userName}  L${op.line + 1}  $marker$preview",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier.weight(1f)
            )
            if (restorable) {
                Text("restore", fontSize = 9.sp)
            }
        }
    }

    if (showBaselineRow) {
        val time = historyTimeFormat.format(Date(baselineTs))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 1.dp)
                .clickable { onRestoreBaseline(fileId) },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$time  ---  Initial (pre-session) state",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier.weight(1f)
            )
            Text("restore", fontSize = 9.sp)
        }
    }
}

private fun buildPreview(op: EditOp): String {
    val maxLen = 30
    fun clip(s: String) = s.replace('\n', '\u23CE').let {
        if (it.length > maxLen) it.take(maxLen) + "..." else it
    }
    return when {
        op.oldText.isEmpty() && op.newText.isNotEmpty() -> "ins@${op.offset}: \"${clip(op.newText)}\""
        op.newText.isEmpty() && op.oldText.isNotEmpty() -> "del@${op.offset}: \"${clip(op.oldText)}\""
        else -> "rep@${op.offset}: \"${clip(op.oldText)}\" -> \"${clip(op.newText)}\""
    }
}

@Composable
private fun DebugVariableList(
    variables: List<DebugVariable>,
    childrenMap: Map<String, List<DebugVariable>>,
    depth: Int,
    onInspect: (variablePath: String) -> Unit
) {
    val indent = "  ".repeat(depth)
    val maxValueLength = 60
    variables.forEach { variable ->
        val expanded = childrenMap.containsKey(variable.path)
        val prefix = when {
            !variable.hasChildren -> "  "
            expanded -> "- "
            else -> "+ "
        }
        val typeStr = if (variable.type != null) "${variable.type} " else ""
        val fullValue = variable.value ?: "null"
        val displayValue = if (fullValue.length > maxValueLength) fullValue.take(maxValueLength) + "..." else fullValue

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$indent$prefix${variable.name}: $typeStr= $displayValue",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f).then(
                    if (variable.hasChildren) Modifier.clickable { onInspect(variable.path) }
                    else Modifier
                )
            )
            OutlinedButton(onClick = {
                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                clipboard.setContents(StringSelection(fullValue), null)
            }) {
                Text("Copy", fontSize = 10.sp)
            }
        }

        // Show children if expanded
        if (expanded) {
            val children = childrenMap[variable.path] ?: emptyList()
            DebugVariableList(
                variables = children,
                childrenMap = childrenMap,
                depth = depth + 1,
                onInspect = onInspect
            )
        }
    }
}

@Composable
private fun ScreensharePanel(
    screenshare: ScreenshareService,
    defaultUserName: String,
) {
    val running by screenshare.running
    val invite by screenshare.inviteLink
    val status by screenshare.statusMessage
    val keyState = rememberTextFieldState()
    val settings = remember { PearSettings.getInstance() }
    val httpsState = remember { mutableStateOf(settings.state.screenshareHttps) }
    val httpsPortState = rememberTextFieldState(settings.state.screenshareHttpsPort.toString())

    GroupHeader("Screenshare Lobby (OS overlay)")

    if (running) {
        Text("Lobby is live. Share this link with guests:")
        Text(invite, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        Text(
            "Hover a note on the overlay to drag it, ⧉ copies, ✕ removes.",
            fontSize = 10.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                clipboard.setContents(StringSelection(invite), null)
            }) { Text("Copy Link") }
            OutlinedButton(onClick = { screenshare.stop() }) { Text("Stop Screenshare") }
        }
    } else {
        Text("Lobby key (optional)")
        TextField(
            state = keyState,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("leave empty for open lobby") }
        )
        Spacer(Modifier.height(4.dp))
        CheckboxRow(
            text = "Use HTTPS (required if server is not on localhost)",
            checked = httpsState.value,
            onCheckedChange = {
                httpsState.value = it
                settings.state.screenshareHttps = it
            }
        )
        if (httpsState.value) {
            Text("HTTPS port")
            TextField(
                state = httpsPortState,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("9275") }
            )
        }

        Spacer(Modifier.height(8.dp))
        TurnSettings(settings)

        Spacer(Modifier.height(4.dp))
        DefaultButton(onClick = {
            val name = defaultUserName.ifBlank { "Host" }
            settings.state.screenshareHttpsPort =
                httpsPortState.text.toString().toIntOrNull() ?: 9275
            screenshare.start(name, keyState.text.toString())
        }) { Text("Start Screenshare Lobby") }
    }

    if (status.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text(status, fontSize = 10.sp)
    }
}

@Composable
private fun TurnSettings(settings: PearSettings) {
    val enabled = remember { mutableStateOf(settings.state.turnEnabled) }
    val urlState = rememberTextFieldState(settings.state.turnUrl)
    val userState = rememberTextFieldState(settings.state.turnUsername)
    val passState = rememberTextFieldState(settings.state.turnPassword)

    GroupHeader("TURN server (optional)")
    CheckboxRow(
        text = "Enable TURN (relay WebRTC when ICE fails)",
        checked = enabled.value,
        onCheckedChange = {
            enabled.value = it
            settings.state.turnEnabled = it
        }
    )
    if (enabled.value) {
        Text("TURN URL")
        TextField(
            state = urlState,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("turn:host.example.com:3478") }
        )
        Text("Username")
        TextField(
            state = userState,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("pear") }
        )
        Text("Password")
        TextField(
            state = passState,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("pearpass") }
        )
        // Persist on any blur; TextFieldState doesn't expose onFocusChanged here so
        // snapshot into settings right now so the next Start sees current values.
        settings.state.turnUrl = urlState.text.toString()
        settings.state.turnUsername = userState.text.toString()
        settings.state.turnPassword = passState.text.toString()
    }
}
