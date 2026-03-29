package com.pixeldweller.instantpear.debug

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
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
import com.pixeldweller.instantpear.protocol.DebugVariable
import com.pixeldweller.instantpear.protocol.PearMessage
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
            }

            override fun processTerminated(
                executorId: String,
                env: ExecutionEnvironment,
                handler: ProcessHandler,
                exitCode: Int
            ) {
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

        if (inSharedFile) {
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

    override fun dispose() {
        cachedVariables.clear()
        currentDebugSession = null
    }
}
