package com.pixeldweller.instantpear.debug

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.VisibleAreaEvent
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.openapi.project.Project
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerManagerListener
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XDebuggerTreeNodeHyperlink
import com.intellij.xdebugger.frame.XFullValueEvaluator
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import com.pixeldweller.instantpear.protocol.DebugVariable
import com.pixeldweller.instantpear.protocol.PearMessage
import com.pixeldweller.instantpear.settings.PearSettings
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.Icon

class DebugSyncService(
    private val project: Project,
    private val sendMessage: (PearMessage) -> Unit,
    private val getSharedFileIds: () -> Set<String>
) : Disposable {

    private val log = Logger.getInstance(DebugSyncService::class.java)
    private val cachedVariables = ConcurrentHashMap<String, XValue>()
    private var currentDebugSession: XDebugSession? = null
    private var consoleEditor: Editor? = null
    private var consoleVisibleAreaListener: VisibleAreaListener? = null
    private var consoleFocusListener: java.awt.event.FocusListener? = null
    private var lastSentConsoleText: String? = null

    fun start() {
        val connection = project.messageBus.connect(this)

        connection.subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            override fun processStarted(
                executorId: String,
                env: ExecutionEnvironment,
                handler: ProcessHandler
            ) {
                val isDebug = executorId == "Debug"
                sendMessage(
                    PearMessage(
                        type = PearMessage.RUN_STATE,
                        runState = if (isDebug) "debugging" else "running",
                        processName = env.runProfile.name
                    )
                )
                // Attach to console after it becomes available
                ApplicationManager.getApplication().invokeLater {
                    attachToConsole()
                }
            }

            override fun processTerminated(
                executorId: String,
                env: ExecutionEnvironment,
                handler: ProcessHandler,
                exitCode: Int
            ) {
                detachFromConsole()
                sendMessage(
                    PearMessage(
                        type = PearMessage.RUN_STATE,
                        runState = "idle"
                    )
                )
                cachedVariables.clear()
            }
        })

        connection.subscribe(XDebuggerManager.TOPIC, object : XDebuggerManagerListener {
            override fun currentSessionChanged(
                previousSession: XDebugSession?,
                currentSession: XDebugSession?
            ) {
                if (currentSession != null) {
                    attachToDebugSession(currentSession)
                }
            }
        })

        // Attach to any already-running debug session
        XDebuggerManager.getInstance(project).currentSession?.let { attachToDebugSession(it) }
    }

    private fun attachToDebugSession(session: XDebugSession) {
        if (session == currentDebugSession) return
        currentDebugSession = session

        session.addSessionListener(object : XDebugSessionListener {
            override fun sessionPaused() {
                onDebugPaused(session)
            }

            override fun sessionResumed() {
                sendMessage(
                    PearMessage(
                        type = PearMessage.DEBUG_POSITION,
                        fileName = null,
                        line = null
                    )
                )
            }

            override fun sessionStopped() {
                currentDebugSession = null
                cachedVariables.clear()
            }

            override fun stackFrameChanged() {
                onDebugPaused(session)
            }
        })
    }

    private fun onDebugPaused(session: XDebugSession) {
        val frame = session.currentStackFrame ?: return
        val position = session.currentPosition ?: return
        val fileId = position.file.name
        val line = position.line
        val sharedFiles = getSharedFileIds()
        val inSharedFile = fileId in sharedFiles

        sendMessage(
            PearMessage(
                type = PearMessage.DEBUG_POSITION,
                fileName = if (inSharedFile) fileId else null,
                line = line
            )
        )

        if (inSharedFile && PearSettings.getInstance().state.sendDebugVariables) {
            collectVariables(frame, fileId)
        }
    }

    private fun collectVariables(frame: XStackFrame, fileId: String) {
        cachedVariables.clear()

        frame.computeChildren(object : XCompositeNode {
            override fun addChildren(children: XValueChildrenList, last: Boolean) {
                val count = children.size()
                if (count == 0) {
                    sendMessage(
                        PearMessage(
                            type = PearMessage.DEBUG_VARIABLES,
                            fileName = fileId,
                            variables = emptyList()
                        )
                    )
                    return
                }

                val vars = mutableListOf<DebugVariable>()
                val remaining = AtomicInteger(count)

                for (i in 0 until count) {
                    val name = children.getName(i)
                    val value = children.getValue(i)
                    cachedVariables[name] = value
                    computeValuePresentation(value) { type, displayValue, hasChildren ->
                        synchronized(vars) {
                            vars.add(
                                DebugVariable(
                                    name = name,
                                    type = type,
                                    value = displayValue,
                                    hasChildren = hasChildren,
                                    path = name
                                )
                            )
                        }
                        if (remaining.decrementAndGet() == 0) {
                            sendMessage(
                                PearMessage(
                                    type = PearMessage.DEBUG_VARIABLES,
                                    fileName = fileId,
                                    variables = synchronized(vars) { vars.toList() }
                                )
                            )
                        }
                    }
                }
            }

            override fun tooManyChildren(remaining: Int) {}
            override fun setAlreadySorted(alreadySorted: Boolean) {}
            override fun setErrorMessage(errorMessage: String) {}
            override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) {}
            override fun isObsolete(): Boolean = false
            override fun setMessage(
                message: String,
                icon: Icon?,
                attributes: SimpleTextAttributes,
                link: XDebuggerTreeNodeHyperlink?
            ) {
            }
        })
    }

    fun handleInspectVariable(variablePath: String, fileId: String) {
        if (!PearSettings.getInstance().state.sendDebugVariables) return
        val value = cachedVariables[variablePath] ?: return
        expandValue(value, variablePath, fileId)
    }

    private fun expandValue(value: XValue, parentPath: String, fileId: String) {
        value.computeChildren(object : XCompositeNode {
            override fun addChildren(children: XValueChildrenList, last: Boolean) {
                val count = children.size()
                if (count == 0) {
                    sendMessage(
                        PearMessage(
                            type = PearMessage.DEBUG_VARIABLE_CHILDREN,
                            fileName = fileId,
                            variablePath = parentPath,
                            variables = emptyList()
                        )
                    )
                    return
                }

                val vars = mutableListOf<DebugVariable>()
                val remaining = AtomicInteger(count)

                for (i in 0 until count) {
                    val name = children.getName(i)
                    val childValue = children.getValue(i)
                    val childPath = "$parentPath.$name"
                    cachedVariables[childPath] = childValue
                    computeValuePresentation(childValue) { type, displayValue, hasChildren ->
                        synchronized(vars) {
                            vars.add(
                                DebugVariable(
                                    name = name,
                                    type = type,
                                    value = displayValue,
                                    hasChildren = hasChildren,
                                    path = childPath
                                )
                            )
                        }
                        if (remaining.decrementAndGet() == 0) {
                            sendMessage(
                                PearMessage(
                                    type = PearMessage.DEBUG_VARIABLE_CHILDREN,
                                    fileName = fileId,
                                    variablePath = parentPath,
                                    variables = synchronized(vars) { vars.toList() }
                                )
                            )
                        }
                    }
                }
            }

            override fun tooManyChildren(remaining: Int) {}
            override fun setAlreadySorted(alreadySorted: Boolean) {}
            override fun setErrorMessage(errorMessage: String) {}
            override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) {}
            override fun isObsolete(): Boolean = false
            override fun setMessage(
                message: String,
                icon: Icon?,
                attributes: SimpleTextAttributes,
                link: XDebuggerTreeNodeHyperlink?
            ) {
            }
        })
    }

    private fun computeValuePresentation(
        value: XValue,
        callback: (type: String?, displayValue: String?, hasChildren: Boolean) -> Unit
    ) {
        try {
            value.computePresentation(object : XValueNode {
                override fun setPresentation(
                    icon: Icon?,
                    type: String?,
                    value: String,
                    hasChildren: Boolean
                ) {
                    callback(type, value, hasChildren)
                }

                override fun setPresentation(
                    icon: Icon?,
                    presentation: XValuePresentation,
                    hasChildren: Boolean
                ) {
                    val sb = StringBuilder()
                    presentation.renderValue(object : XValuePresentation.XValueTextRenderer {
                        override fun renderValue(value: String) { sb.append(value) }
                        override fun renderStringValue(value: String) { sb.append(value) }
                        override fun renderNumericValue(value: String) { sb.append(value) }
                        override fun renderKeywordValue(value: String) { sb.append(value) }
                        override fun renderValue(value: String, key: TextAttributesKey) { sb.append(value) }
                        override fun renderStringValue(value: String, additionalSpecialCharsToHighlight: String?, maxLength: Int) { sb.append(value) }
                        override fun renderComment(comment: String) {}
                        override fun renderSpecialSymbol(symbol: String) { sb.append(symbol) }
                        override fun renderError(error: String) { sb.append(error) }
                    })
                    callback(presentation.type, sb.toString(), hasChildren)
                }

                override fun setFullValueEvaluator(fullValueEvaluator: XFullValueEvaluator) {
                    // We already got the short presentation via setPresentation
                }

                override fun isObsolete(): Boolean = false
            }, XValuePlace.TREE)
        } catch (e: Exception) {
            log.debug("Failed to compute value presentation", e)
            callback(null, "<error: ${e.message ?: "evaluation failed"}>", false)
        }
    }

    private fun attachToConsole() {
        if (consoleEditor != null) return
        val descriptor = RunContentManager.getInstance(project).selectedContent ?: return
        val editor = findConsoleEditor(descriptor.executionConsole) ?: return
        consoleEditor = editor

        consoleVisibleAreaListener = VisibleAreaListener { _: VisibleAreaEvent ->
            sendConsoleViewport(editor)
        }
        editor.scrollingModel.addVisibleAreaListener(consoleVisibleAreaListener!!)

        consoleFocusListener = object : java.awt.event.FocusAdapter() {
            override fun focusGained(e: java.awt.event.FocusEvent?) {
                sendConsoleViewport(editor)
            }
        }
        editor.contentComponent.addFocusListener(consoleFocusListener!!)
    }

    private fun detachFromConsole() {
        val editor = consoleEditor ?: return
        consoleVisibleAreaListener?.let { editor.scrollingModel.removeVisibleAreaListener(it) }
        consoleFocusListener?.let { editor.contentComponent.removeFocusListener(it) }
        consoleEditor = null
        consoleVisibleAreaListener = null
        consoleFocusListener = null
        lastSentConsoleText = null
    }

    private fun findConsoleEditor(console: ExecutionConsole?): Editor? {
        if (console == null) return null
        // ConsoleViewImpl and similar have an "editor" property via the ConsoleView interface
        try {
            val method = console.javaClass.getMethod("getEditor")
            return method.invoke(console) as? Editor
        } catch (_: Exception) {
            return null
        }
    }

    private fun sendConsoleViewport(editor: Editor) {
        if (!PearSettings.getInstance().state.sendDebugVariables) return
        val doc = editor.document
        if (doc.lineCount == 0) return
        val visibleArea = editor.scrollingModel.visibleArea
        val firstLine = editor.xyToLogicalPosition(java.awt.Point(0, visibleArea.y)).line
            .coerceIn(0, doc.lineCount - 1)
        val lastLine = editor.xyToLogicalPosition(java.awt.Point(0, visibleArea.y + visibleArea.height)).line
            .coerceIn(0, doc.lineCount - 1)
        val startOffset = doc.getLineStartOffset(firstLine)
        val endOffset = doc.getLineEndOffset(lastLine)
        val visibleText = doc.getText(com.intellij.openapi.util.TextRange(startOffset, endOffset))

        if (visibleText == lastSentConsoleText) return
        lastSentConsoleText = visibleText

        sendMessage(
            PearMessage(
                type = PearMessage.CONSOLE_VIEWPORT,
                consoleText = visibleText
            )
        )
    }

    override fun dispose() {
        detachFromConsole()
        cachedVariables.clear()
        currentDebugSession = null
    }
}
