package com.pixeldweller.instantpear.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.pixeldweller.instantpear.session.SessionService
import com.pixeldweller.instantpear.session.SessionState

class AcceptFileRequestAction : AnAction("Accept File Request") {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val session = SessionService.getInstance(project)
        e.presentation.isEnabledAndVisible =
            session.state.value == SessionState.CONNECTED &&
            session.isHost.value &&
            session.hasPendingFileRequest()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        SessionService.getInstance(project).acceptLastFileRequest()
    }
}
