package com.pixeldweller.instantpear.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(name = "InstantPearSettings", storages = [Storage("InstantPearSettings.xml")])
class PearSettings : PersistentStateComponent<PearSettings.State> {

    class State {
        var serverUrl: String = "ws://localhost:9274/ws"
        var userName: String = "Developer"
        var focusNewCollabTabs: Boolean = true
        var sendDebugVariables: Boolean = true
        var useSockJS: Boolean = false
        var screenshareHttps: Boolean = true
        var screenshareHttpsPort: Int = 9275
        var turnEnabled: Boolean = false
        var turnUrl: String = ""
        var turnUsername: String = ""
        var turnPassword: String = ""
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        fun getInstance(): PearSettings =
            ApplicationManager.getApplication().getService(PearSettings::class.java)
    }
}
