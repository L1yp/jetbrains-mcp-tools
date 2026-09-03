package com.l1yp.agentconfig

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros

@Service(Service.Level.PROJECT)
@State(
    name = "McpToolboxAgentConfig",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)],
)
internal class AgentConfigSettings : PersistentStateComponent<AgentConfigState> {
    @Volatile
    private var currentState = AgentConfigState()

    override fun getState(): AgentConfigState = currentState

    override fun loadState(state: AgentConfigState) {
        currentState = state
    }

    fun selectedAgentIds(): Set<String> = currentState.selectedAgentIds.toSet()

    fun updateSelectedAgentIds(ids: Set<String>) {
        currentState.selectedAgentIds = ids.sorted().toMutableList()
    }

    fun ownership(adapterId: String): AgentConfigOwnership? = currentState.ownership
        .firstOrNull { it.adapterId == adapterId }
        ?.copy()

    fun recordOwnership(ownership: AgentConfigOwnership) {
        currentState.ownership.removeIf { it.adapterId == ownership.adapterId }
        currentState.ownership.add(ownership.copy())
    }

    fun removeOwnership(adapterId: String) {
        currentState.ownership.removeIf { it.adapterId == adapterId }
    }
}

internal class AgentConfigState {
    var selectedAgentIds: MutableList<String> = mutableListOf()
    var ownership: MutableList<AgentConfigOwnership> = mutableListOf()
}

internal data class AgentConfigOwnership(
    var adapterId: String = "",
    var configPath: String = "",
    var serverName: String = "",
    var lastAppliedEndpointHash: String = "",
    var createdFileByPlugin: Boolean = false,
)
