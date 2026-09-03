package com.l1yp.ui

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTabbedPane
import javax.swing.JComponent

internal class McpSettingsConfigurable(private val project: Project) : Configurable, Configurable.NoScroll {
    private var statusPanel: McpStatusPanel? = null
    private var agentConfigPanel: AgentConfigPanel? = null
    private var toolConfigurationPanel: McpToolConfigurationPanel? = null
    private var exposurePanel: RunConfigurationExposurePanel? = null

    override fun getDisplayName(): String = "MCP Toolbox"

    override fun createComponent(): JComponent {
        val newAgentConfigPanel = AgentConfigPanel(project).also { agentConfigPanel = it }
        val newToolConfigurationPanel = McpToolConfigurationPanel(project).also { toolConfigurationPanel = it }
        val newExposurePanel = RunConfigurationExposurePanel(project).also { exposurePanel = it }
        val newStatusPanel = McpStatusPanel(project) {
            McpGuideEditorProvider.open(project)
        }.also { statusPanel = it }
        return JBTabbedPane().apply {
            addTab("Coding Agent", newAgentConfigPanel.component)
            addTab("MCP 工具", newToolConfigurationPanel.component)
            addTab("可重启的 Run Configuration", newExposurePanel.component)
            addTab("连接与工具状态", newStatusPanel.component)
        }
    }

    override fun isModified(): Boolean =
        agentConfigPanel?.isModified() == true ||
            toolConfigurationPanel?.isModified() == true ||
            exposurePanel?.isModified() == true

    override fun apply() {
        toolConfigurationPanel?.apply()
        agentConfigPanel?.apply()
        exposurePanel?.apply()
        statusPanel?.refresh()
    }

    override fun reset() {
        agentConfigPanel?.reset()
        toolConfigurationPanel?.reset()
        exposurePanel?.reset()
        statusPanel?.refresh()
    }

    override fun disposeUIResources() {
        agentConfigPanel = null
        toolConfigurationPanel = null
        exposurePanel = null
        statusPanel = null
    }
}
