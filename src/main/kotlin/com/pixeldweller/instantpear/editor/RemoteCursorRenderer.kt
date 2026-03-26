package com.pixeldweller.instantpear.editor

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.geom.GeneralPath
import javax.swing.Icon

class RemoteCursorRenderer(private val editor: Editor) {

    data class CursorInfo(
        val userId: String,
        val userName: String,
        var offset: Int = 0,
        var selectionStart: Int = 0,
        var selectionEnd: Int = 0,
        var line: Int = 0,
        val highlighters: MutableList<RangeHighlighter> = mutableListOf(),
        val indicatorHighlighters: MutableList<RangeHighlighter> = mutableListOf()
    )

    private val cursors = mutableMapOf<String, CursorInfo>()

    companion object {
        private val cursorColors = listOf(
            JBColor(Color(0, 120, 215), Color(80, 160, 255)),
            JBColor(Color(215, 95, 0), Color(255, 150, 60)),
            JBColor(Color(0, 160, 80), Color(60, 210, 130)),
            JBColor(Color(160, 40, 180), Color(200, 100, 220)),
            JBColor(Color(200, 30, 30), Color(240, 80, 80)),
        )

        fun getColorForUser(userId: String): Color {
            return cursorColors[(userId.hashCode() and 0x7FFFFFFF) % cursorColors.size]
        }
    }

    fun updateCursor(userId: String, userName: String, offset: Int, selStart: Int, selEnd: Int, line: Int) {
        val cursor = cursors.getOrPut(userId) { CursorInfo(userId, userName) }
        cursor.offset = offset
        cursor.selectionStart = selStart
        cursor.selectionEnd = selEnd
        cursor.line = line

        // Clear old highlighters
        cursor.highlighters.forEach {
            try {
                editor.markupModel.removeHighlighter(it)
            } catch (_: Exception) {
            }
        }
        cursor.highlighters.clear()

        val color = getColorForUser(userId)
        val docLength = editor.document.textLength

        // Cursor position highlight (boxed character)
        if (offset in 0..docLength) {
            val end = minOf(offset + 1, docLength)
            if (offset < end) {
                val cursorAttrs = TextAttributes().apply {
                    effectType = EffectType.BOXED
                    effectColor = color
                    backgroundColor = ColorUtil.withAlpha(color, 0.15)
                }
                val h = editor.markupModel.addRangeHighlighter(
                    offset, end,
                    HighlighterLayer.LAST + 2,
                    cursorAttrs,
                    HighlighterTargetArea.EXACT_RANGE
                )
                h.gutterIconRenderer = UserGutterIcon(userName, color)
                cursor.highlighters.add(h)
            }
        }

        // Selection highlight
        if (selStart != selEnd && selStart >= 0 && selEnd <= docLength && selStart < selEnd) {
            val selAttrs = TextAttributes().apply {
                backgroundColor = ColorUtil.withAlpha(color, 0.25)
            }
            val h = editor.markupModel.addRangeHighlighter(
                selStart, selEnd,
                HighlighterLayer.SELECTION + 1,
                selAttrs,
                HighlighterTargetArea.EXACT_RANGE
            )
            cursor.highlighters.add(h)
        }

        updateScrollIndicators()
    }

    fun updateScrollIndicators() {
        // Clear old indicators
        cursors.values.forEach { cursor ->
            cursor.indicatorHighlighters.forEach {
                try {
                    editor.markupModel.removeHighlighter(it)
                } catch (_: Exception) {
                }
            }
            cursor.indicatorHighlighters.clear()
        }

        val visibleArea = editor.scrollingModel.visibleArea
        if (visibleArea.height == 0) return

        val firstVisibleLine = editor.xyToLogicalPosition(Point(0, visibleArea.y)).line
        val lastVisibleLine = editor.xyToLogicalPosition(Point(0, visibleArea.y + visibleArea.height)).line

        for (cursor in cursors.values) {
            val color = getColorForUser(cursor.userId)

            if (cursor.line < firstVisibleLine) {
                addIndicator(cursor, firstVisibleLine, color, isAbove = true)
            } else if (cursor.line > lastVisibleLine) {
                addIndicator(cursor, lastVisibleLine.coerceAtMost(editor.document.lineCount - 1), color, isAbove = false)
            }
        }
    }

    private fun addIndicator(cursor: CursorInfo, line: Int, color: Color, isAbove: Boolean) {
        val lineCount = editor.document.lineCount
        if (line < 0 || line >= lineCount) return

        val offset = editor.document.getLineStartOffset(line)
        val endOffset = editor.document.getLineEndOffset(line)

        val attrs = TextAttributes()
        val h = editor.markupModel.addRangeHighlighter(
            offset, endOffset,
            HighlighterLayer.LAST + 3,
            attrs,
            HighlighterTargetArea.EXACT_RANGE
        )
        h.gutterIconRenderer = ArrowGutterIcon(cursor.userName, color, isAbove)
        cursor.indicatorHighlighters.add(h)
    }

    fun removeCursor(userId: String) {
        val cursor = cursors.remove(userId) ?: return
        cursor.highlighters.forEach {
            try {
                editor.markupModel.removeHighlighter(it)
            } catch (_: Exception) {
            }
        }
        cursor.indicatorHighlighters.forEach {
            try {
                editor.markupModel.removeHighlighter(it)
            } catch (_: Exception) {
            }
        }
    }

    fun dispose() {
        cursors.values.forEach { cursor ->
            cursor.highlighters.forEach {
                try {
                    editor.markupModel.removeHighlighter(it)
                } catch (_: Exception) {
                }
            }
            cursor.indicatorHighlighters.forEach {
                try {
                    editor.markupModel.removeHighlighter(it)
                } catch (_: Exception) {
                }
            }
        }
        cursors.clear()
    }
}

private class UserGutterIcon(private val userName: String, private val color: Color) : GutterIconRenderer() {
    override fun getIcon(): Icon = ColorDotIcon(color)
    override fun getTooltipText(): String = userName
    override fun equals(other: Any?): Boolean = other is UserGutterIcon && other.userName == userName
    override fun hashCode(): Int = userName.hashCode()
}

private class ArrowGutterIcon(
    private val userName: String,
    private val color: Color,
    private val isAbove: Boolean
) : GutterIconRenderer() {
    override fun getIcon(): Icon = ArrowIcon(color, isAbove)
    override fun getTooltipText(): String = "$userName (${if (isAbove) "above" else "below"})"
    override fun equals(other: Any?): Boolean =
        other is ArrowGutterIcon && other.userName == userName && other.isAbove == isAbove

    override fun hashCode(): Int = userName.hashCode() * 31 + isAbove.hashCode()
}

private class ColorDotIcon(private val color: Color) : Icon {
    override fun getIconWidth() = 10
    override fun getIconHeight() = 10
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = color
        g2.fillOval(x + 1, y + 1, 8, 8)
        g2.dispose()
    }
}

private class ArrowIcon(private val color: Color, private val up: Boolean) : Icon {
    override fun getIconWidth() = 12
    override fun getIconHeight() = 12
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = color
        val path = GeneralPath()
        if (up) {
            path.moveTo((x + 6).toFloat(), (y + 2).toFloat())
            path.lineTo((x + 11).toFloat(), (y + 10).toFloat())
            path.lineTo((x + 1).toFloat(), (y + 10).toFloat())
        } else {
            path.moveTo((x + 6).toFloat(), (y + 10).toFloat())
            path.lineTo((x + 11).toFloat(), (y + 2).toFloat())
            path.lineTo((x + 1).toFloat(), (y + 2).toFloat())
        }
        path.closePath()
        g2.fill(path)
        g2.dispose()
    }
}
