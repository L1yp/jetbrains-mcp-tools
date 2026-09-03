package com.l1yp.ui

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTabbedPane
import javax.swing.JComponent

internal class McpSettingsConfigurable(private val project: Project) : Configurable, Configurable.NoScroll {
    private var statusPanel: McpStatusPanel? = null
    private var agentConfigPanel: AgentConfigPanel? = null
    private var exposurePanel: RunConfigurationExposurePanel? = null

    override fun getDisplayName(): String = "MCP Toolbox"

    override fun createComponent(): JComponent {
        val newAgentConfigPanel = AgentConfigPanel(project).also { agentConfigPanel = it }
        val newExposurePanel = RunConfigurationExposurePanel(project).also { exposurePanel = it }
        val newStatusPanel = McpStatusPanel(project) {
            McpGuideEditorProvider.open(project)
        }.also { statusPanel = it }
        return JBTabbedPane().apply {
            addTab("Coding Agent", newAgentConfigPanel.component)
            addTab("可重启的 Run Configuration", newExposurePanel.component)
            addTab("连接与工具状态", newStatusPanel.component)
        }
    }

    override fun isModified(): Boolean =
        agentConfigPanel?.isModified() == true || exposurePanel?.isModified() == true

    override fun apply() {
        agentConfigPanel?.apply()
        exposurePanel?.apply()
        statusPanel?.refresh()
    }

    override fun reset() {
        agentConfigPanel?.reset()
        exposurePanel?.reset()
        statusPanel?.refresh()
    }

    override fun disposeUIResources() {
        agentConfigPanel = null
        exposurePanel = null
        statusPanel = null
    }
}
