package com.pixeldweller.instantpear.editor

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoFilter
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiFile

/**
 * Suppresses inspection warnings and errors on collaborative editing files.
 * Keeps syntax highlighting intact but filters out inspections like
 * "Class is public, should be declared in a file named ..." since
 * collab files are in-memory LightVirtualFiles, not real project files.
 */
class CollabHighlightFilter : HighlightInfoFilter {
    override fun accept(highlightInfo: HighlightInfo, file: PsiFile?): Boolean {
        val virtualFile = file?.virtualFile ?: return true
        if (virtualFile.name.startsWith("Collab: ")) {
            // Keep only syntax highlighting, filter all inspections/warnings/errors
            return highlightInfo.severity.compareTo(HighlightSeverity.INFORMATION) <= 0
        }
        return true
    }
}
