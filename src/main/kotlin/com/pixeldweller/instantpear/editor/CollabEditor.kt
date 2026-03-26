package com.pixeldweller.instantpear.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.pixeldweller.instantpear.protocol.PearMessage
import java.awt.Color
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CollabEditor(
    private val project: Project,
    val editor: Editor,
    val fileId: String,
    private val sendMessage: (PearMessage) -> Unit
) : Disposable {
    @Volatile
    private var isApplyingRemoteChange = false

    val remoteCursors = RemoteCursorRenderer(editor)
    val remotePointers = RemotePointerManager(editor)

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "InstantPear-highlight-cleanup").apply { isDaemon = true }
    }
    private val changeHighlighters = mutableListOf<RangeHighlighter>()

    private val remoteChangeColor = JBColor(
        Color(255, 255, 180),
        Color(60, 60, 30)
    )

    private val documentListener = object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
            if (isApplyingRemoteChange) return
            sendMessage(
                PearMessage(
                    type = PearMessage.DOCUMENT_CHANGE,
                    fileName = fileId,
                    offset = event.offset,
                    oldLength = event.oldLength,
                    newText = event.newFragment.toString()
                )
            )
        }
    }

    private val caretListener = object : CaretListener {
        override fun caretPositionChanged(event: CaretEvent) {
            val caret = editor.caretModel.primaryCaret
            sendMessage(
                PearMessage(
                    type = PearMessage.CURSOR_UPDATE,
                    fileName = fileId,
                    cursorOffset = caret.offset,
                    selectionStart = caret.selectionStart,
                    selectionEnd = caret.selectionEnd,
                    line = caret.logicalPosition.line
                )
            )
        }
    }

    private var lastMouseSendTime = 0L

    private val mouseMotionListener = object : EditorMouseMotionListener {
        override fun mouseMoved(e: EditorMouseEvent) {
            val now = System.currentTimeMillis()
            if (now - lastMouseSendTime < 50) return
            lastMouseSendTime = now

            val pos = e.logicalPosition
            sendMessage(
                PearMessage(
                    type = PearMessage.MOUSE_MOVE,
                    fileName = fileId,
                    line = pos.line,
                    column = pos.column
                )
            )
        }
    }

    private val scrollListener = VisibleAreaListener {
        ApplicationManager.getApplication().invokeLater {
            remoteCursors.updateScrollIndicators()
        }
    }

    init {
        editor.document.addDocumentListener(documentListener, this)
        editor.caretModel.addCaretListener(caretListener)
        editor.addEditorMouseMotionListener(mouseMotionListener)
        editor.scrollingModel.addVisibleAreaListener(scrollListener)
    }

    fun applyRemoteChange(message: PearMessage) {
        ApplicationManager.getApplication().invokeLater {
            WriteCommandAction.runWriteCommandAction(project) {
                isApplyingRemoteChange = true
                try {
                    val doc = editor.document
                    val offset = message.offset ?: return@runWriteCommandAction
                    val oldLength = message.oldLength ?: 0
                    val newText = message.newText ?: ""

                    if (offset < 0 || offset + oldLength > doc.textLength) return@runWriteCommandAction

                    doc.replaceString(offset, offset + oldLength, newText)

                    if (newText.isNotEmpty()) {
                        highlightChange(offset, offset + newText.length)
                    }
                } finally {
                    isApplyingRemoteChange = false
                }
            }
        }
    }

    private fun highlightChange(start: Int, end: Int) {
        if (start >= end || end > editor.document.textLength) return
        val attrs = TextAttributes().apply {
            backgroundColor = remoteChangeColor
        }
        val h = editor.markupModel.addRangeHighlighter(
            start, end,
            HighlighterLayer.LAST,
            attrs,
            HighlighterTargetArea.EXACT_RANGE
        )
        changeHighlighters.add(h)

        scheduler.schedule({
            ApplicationManager.getApplication().invokeLater {
                try {
                    editor.markupModel.removeHighlighter(h)
                    changeHighlighters.remove(h)
                } catch (_: Exception) {
                }
            }
        }, 3, TimeUnit.SECONDS)
    }

    fun updateRemoteCursor(userId: String, userName: String, message: PearMessage) {
        ApplicationManager.getApplication().invokeLater {
            remoteCursors.updateCursor(
                userId, userName,
                message.cursorOffset ?: 0,
                message.selectionStart ?: 0,
                message.selectionEnd ?: 0,
                message.line ?: 0
            )
        }
    }

    fun updateRemotePointer(userId: String, userName: String, line: Int, column: Int) {
        ApplicationManager.getApplication().invokeLater {
            val color = RemoteCursorRenderer.getColorForUser(userId)
            remotePointers.updatePointer(userId, userName, line, column, color)
        }
    }

    fun removeRemoteUser(userId: String) {
        ApplicationManager.getApplication().invokeLater {
            remoteCursors.removeCursor(userId)
            remotePointers.removePointer(userId)
        }
    }

    fun getCurrentContent(): String = editor.document.text

    override fun dispose() {
        // documentListener is auto-removed via Disposable
        editor.caretModel.removeCaretListener(caretListener)
        editor.removeEditorMouseMotionListener(mouseMotionListener)
        editor.scrollingModel.removeVisibleAreaListener(scrollListener)
        changeHighlighters.forEach {
            try {
                editor.markupModel.removeHighlighter(it)
            } catch (_: Exception) {
            }
        }
        changeHighlighters.clear()
        remoteCursors.dispose()
        remotePointers.dispose()
        scheduler.shutdownNow()
    }
}
