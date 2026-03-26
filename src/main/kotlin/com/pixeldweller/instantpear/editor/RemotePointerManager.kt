package com.pixeldweller.instantpear.editor

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.event.VisibleAreaListener
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.geom.GeneralPath
import javax.swing.JPanel

/**
 * Renders remote users' mouse pointers as colored arrow cursors
 * with name labels, overlaid on the editor content.
 */
class RemotePointerManager(private val editor: Editor) {

    data class PointerData(
        val userName: String,
        var line: Int,
        var column: Int,
        val color: Color
    )

    private val pointers = mutableMapOf<String, PointerData>()
    private val overlay = PointerOverlay()

    private val resizeListener = object : ComponentAdapter() {
        override fun componentResized(e: ComponentEvent) {
            val cc = editor.contentComponent
            overlay.setBounds(0, 0, cc.width, cc.height)
        }
    }

    private val scrollListener = VisibleAreaListener { overlay.repaint() }

    init {
        val cc = editor.contentComponent
        overlay.setBounds(0, 0, cc.width, cc.height)
        cc.add(overlay, 0)
        cc.addComponentListener(resizeListener)
        editor.scrollingModel.addVisibleAreaListener(scrollListener)
    }

    fun updatePointer(userId: String, userName: String, line: Int, column: Int, color: Color) {
        pointers[userId] = PointerData(userName, line, column, color)
        overlay.repaint()
    }

    fun removePointer(userId: String) {
        if (pointers.remove(userId) != null) {
            overlay.repaint()
        }
    }

    fun dispose() {
        editor.contentComponent.remove(overlay)
        editor.contentComponent.removeComponentListener(resizeListener)
        editor.scrollingModel.removeVisibleAreaListener(scrollListener)
        pointers.clear()
    }

    private inner class PointerOverlay : JPanel() {
        init {
            isOpaque = false
            layout = null
        }

        // Pass-through all mouse events to the editor underneath
        override fun contains(x: Int, y: Int) = false

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            for ((_, data) in pointers) {
                val pos = LogicalPosition(data.line, data.column)
                val point = editor.logicalPositionToXY(pos)
                drawPointer(g2, point.x, point.y, data.color, data.userName)
            }

            g2.dispose()
        }

        private fun drawPointer(g2: Graphics2D, x: Int, y: Int, color: Color, name: String) {
            // Arrow cursor shape
            val path = GeneralPath()
            path.moveTo(x.toFloat(), y.toFloat())
            path.lineTo(x.toFloat(), (y + 16).toFloat())
            path.lineTo((x + 4).toFloat(), (y + 12).toFloat())
            path.lineTo((x + 8).toFloat(), (y + 18).toFloat())
            path.lineTo((x + 10).toFloat(), (y + 17).toFloat())
            path.lineTo((x + 7).toFloat(), (y + 11).toFloat())
            path.lineTo((x + 11).toFloat(), (y + 11).toFloat())
            path.closePath()

            g2.color = color
            g2.fill(path)
            g2.color = Color(0, 0, 0, 180)
            g2.stroke = BasicStroke(0.8f)
            g2.draw(path)

            // Name label
            val origFont = g2.font
            g2.font = origFont.deriveFont(10f)
            val fm = g2.fontMetrics
            val labelX = x + 13
            val labelY = y + 18
            val textWidth = fm.stringWidth(name)

            g2.color = Color(color.red, color.green, color.blue, 210)
            g2.fillRoundRect(labelX - 3, labelY - fm.ascent - 1, textWidth + 6, fm.height + 2, 6, 6)
            g2.color = Color.WHITE
            g2.drawString(name, labelX, labelY)
            g2.font = origFont
        }
    }
}
