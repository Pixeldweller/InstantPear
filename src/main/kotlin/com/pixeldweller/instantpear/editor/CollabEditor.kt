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
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.pixeldweller.instantpear.protocol.PearMessage
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.swing.Icon

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

    private var debugLineHighlighter: RangeHighlighter? = null

    private val debugLineColor = JBColor(
        Color(200, 230, 255),  // Light blue for light theme
        Color(40, 60, 90)      // Dark blue for dark theme
    )

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

    fun setDebugLine(line: Int?) {
        ApplicationManager.getApplication().invokeLater {
            debugLineHighlighter?.let {
                try {
                    editor.markupModel.removeHighlighter(it)
                } catch (_: Exception) {
                }
            }
            debugLineHighlighter = null

            if (line == null || line < 0 || line >= editor.document.lineCount) return@invokeLater

            val startOffset = editor.document.getLineStartOffset(line)
            val endOffset = editor.document.getLineEndOffset(line)
            val attrs = TextAttributes().apply {
                backgroundColor = debugLineColor
            }
            debugLineHighlighter = editor.markupModel.addRangeHighlighter(
                startOffset, endOffset,
                HighlighterLayer.LAST + 5,
                attrs,
                HighlighterTargetArea.LINES_IN_RANGE
            ).also {
                it.gutterIconRenderer = DebugArrowGutterIcon()
            }
        }
    }

    fun getCurrentContent(): String = editor.document.text

    override fun dispose() {
        // documentListener is auto-removed via Disposable
        editor.caretModel.removeCaretListener(caretListener)
        editor.removeEditorMouseMotionListener(mouseMotionListener)
        editor.scrollingModel.removeVisibleAreaListener(scrollListener)
        debugLineHighlighter?.let {
            try {
                editor.markupModel.removeHighlighter(it)
            } catch (_: Exception) {
            }
        }
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

private class DebugArrowGutterIcon : GutterIconRenderer() {
    override fun getIcon(): Icon = DebugArrowIcon
    override fun getTooltipText(): String = "Host debug position"
    override fun equals(other: Any?): Boolean = other is DebugArrowGutterIcon
    override fun hashCode(): Int = 0
}

private object DebugArrowIcon : Icon {
    private val arrowColor = JBColor(Color(70, 130, 200), Color(100, 170, 240))

    override fun getIconWidth() = 12
    override fun getIconHeight() = 12
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = arrowColor
        // Right-pointing arrow (debug execution indicator)
        val xPoints = intArrayOf(x + 2, x + 10, x + 2)
        val yPoints = intArrayOf(y + 1, y + 6, y + 11)
        g2.fillPolygon(xPoints, yPoints, 3)
        g2.dispose()
    }
}
