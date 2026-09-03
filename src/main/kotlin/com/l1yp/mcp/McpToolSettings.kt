package com.l1yp.mcp

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros

@Service(Service.Level.PROJECT)
@State(
    name = "McpToolboxToolSettings",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)],
)
internal class McpToolSettings : PersistentStateComponent<McpToolSettingsState> {
    @Volatile
    private var currentState = McpToolSettingsState()

    override fun getState(): McpToolSettingsState = currentState

    override fun loadState(state: McpToolSettingsState) {
        currentState = state
    }

    fun enabledToolNames(registry: ToolRegistry = ToolRegistry.DEFAULT): List<String> {
        val disabledNames = currentState.disabledToolNames.toSet()
        return registry.definitions.map(McpToolDefinition::name).filterNot(disabledNames::contains)
    }

    fun updateEnabledToolNames(
        enabledNames: Set<String>,
        registry: ToolRegistry = ToolRegistry.DEFAULT,
    ) {
        val knownNames = registry.definitions.mapTo(linkedSetOf(), McpToolDefinition::name)
        currentState = McpToolSettingsState(
            disabledToolNames = (knownNames - enabledNames).sorted().toMutableList(),
        )
    }
}

internal data class McpToolSettingsState(
    var disabledToolNames: MutableList<String> = mutableListOf(),
)
