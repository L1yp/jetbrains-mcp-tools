package com.l1yp.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.l1yp.configuration.RunConfigurationExposureCatalog
import com.l1yp.configuration.RunConfigurationExposureSettings
import com.l1yp.configuration.RunConfigurationTypeDescriptor
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

internal class RunConfigurationExposurePanel(private val project: Project) {
    val component: JComponent

    private val typesPanel = JPanel()
    private val checkboxes = linkedMapOf<String, JBCheckBox>()
    private var baselineSelectedTypeIds = emptySet<String>()

    init {
        val actions = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(JButton("全部公开").apply { addActionListener { setAllSelected(true) } })
            add(JButton("全部隐藏").apply { addActionListener { setAllSelected(false) } })
            add(JButton("刷新类型").apply { addActionListener { reset() } })
        }
        component = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            border = JBUI.Borders.empty(12)
            add(
                JBLabel(
                    """
                    <html>
                    <p>选择允许 MCP 列出和重启的 Run Configuration 类型。只处理已保存的配置，不公开临时配置。</p>
                    <p>首次使用默认隐藏 Maven 和 Gradle；保存后严格按此处的勾选结果执行。</p>
                    <p>客户端应调用 <code>get_restartable_run_configurations</code>，不要调用 JetBrains 内置的
                    <code>get_run_configurations</code> 或 <code>run_configuration</code>。</p>
                    </html>
                    """.trimIndent(),
                ),
                BorderLayout.NORTH,
            )
            add(JBScrollPane(typesPanel), BorderLayout.CENTER)
            add(actions, BorderLayout.SOUTH)
        }
        reset()
    }

    fun isModified(): Boolean = selectedTypeIds() != baselineSelectedTypeIds

    fun apply() {
        project.service<RunConfigurationExposureSettings>().updateExposedTypeIds(selectedTypeIds())
        baselineSelectedTypeIds = selectedTypeIds()
    }

    fun reset() {
        val settings = project.service<RunConfigurationExposureSettings>()
        val selection = settings.selection()
        val availableTypes = RunConfigurationExposureCatalog.availableTypes(project)
        val descriptorsById = availableTypes.associateBy(RunConfigurationTypeDescriptor::id)
        val descriptors = buildList {
            addAll(availableTypes)
            selection.exposedTypeIds
                .filterNot(descriptorsById::containsKey)
                .sorted()
                .mapTo(this) { id -> RunConfigurationTypeDescriptor(id, id, 0) }
        }
        val selectedTypeIds = descriptors
            .filter { selection.isExposed(it.id) }
            .mapTo(mutableSetOf(), RunConfigurationTypeDescriptor::id)

        rebuildCheckboxes(descriptors, selectedTypeIds)
        baselineSelectedTypeIds = selectedTypeIds
    }

    private fun rebuildCheckboxes(
        descriptors: List<RunConfigurationTypeDescriptor>,
        selectedTypeIds: Set<String>,
    ) {
        checkboxes.clear()
        typesPanel.removeAll()
        typesPanel.layout = javax.swing.BoxLayout(typesPanel, javax.swing.BoxLayout.Y_AXIS)

        if (descriptors.isEmpty()) {
            typesPanel.add(JBLabel("当前项目没有已保存的 Run Configuration。"))
        } else {
            descriptors.forEach { descriptor ->
                val countText = if (descriptor.configurationCount == 0) "当前无配置" else "${descriptor.configurationCount} 个配置"
                val checkbox = JBCheckBox("${descriptor.displayName}（$countText）", descriptor.id in selectedTypeIds).apply {
                    toolTipText = descriptor.id
                    alignmentX = JComponent.LEFT_ALIGNMENT
                }
                checkboxes[descriptor.id] = checkbox
                typesPanel.add(checkbox)
            }
        }
        typesPanel.revalidate()
        typesPanel.repaint()
    }

    private fun selectedTypeIds(): Set<String> =
        checkboxes.filterValues(JBCheckBox::isSelected).keys

    private fun setAllSelected(selected: Boolean) {
        checkboxes.values.forEach { it.isSelected = selected }
    }
}
