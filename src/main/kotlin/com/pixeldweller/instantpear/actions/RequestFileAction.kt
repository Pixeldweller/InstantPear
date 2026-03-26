package com.pixeldweller.instantpear.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.pixeldweller.instantpear.session.SessionService
import com.pixeldweller.instantpear.session.SessionState

class RequestFileAction : AnAction("Request Host to Open This File") {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val vf = e.getData(CommonDataKeys.VIRTUAL_FILE)
        if (project == null || vf == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val session = SessionService.getInstance(project)
        // Only show for connected guests, on real project files (not Collab: tabs)
        val isGuest = session.state.value == SessionState.CONNECTED && !session.isHost.value
        val isProjectFile = !vf.name.startsWith("Collab: ")
        e.presentation.isEnabledAndVisible = isGuest && isProjectFile
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val vf = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val basePath = project.basePath ?: return

        val absolutePath = vf.path
        val relativePath = if (absolutePath.startsWith(basePath)) {
            absolutePath.removePrefix(basePath).removePrefix("/")
        } else {
            vf.name
        }

        SessionService.getInstance(project).requestFile(relativePath)
    }
}
