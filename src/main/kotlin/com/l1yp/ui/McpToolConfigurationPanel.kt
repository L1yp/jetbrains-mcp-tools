package com.l1yp.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.l1yp.logging.McpToolboxLogService
import com.l1yp.mcp.McpToolDefinition
import com.l1yp.mcp.McpToolSettings
import com.l1yp.mcp.ToolRegistry
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

internal class McpToolConfigurationPanel(private val project: Project) {
    val component: JComponent

    private val registry = ToolRegistry.DEFAULT
    private val statusHint = JBLabel(" ")
    private val model = McpToolTableModel(::selectionChanged)
    private val table = JBTable(model)
    private var baselineEnabledNames = emptySet<String>()

    init {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.autoResizeMode = javax.swing.JTable.AUTO_RESIZE_LAST_COLUMN
        table.columnModel.getColumn(0).preferredWidth = JBUI.scale(55)
        table.columnModel.getColumn(0).maxWidth = JBUI.scale(70)
        table.columnModel.getColumn(1).preferredWidth = JBUI.scale(250)
        table.columnModel.getColumn(2).preferredWidth = JBUI.scale(90)
        table.columnModel.getColumn(3).preferredWidth = JBUI.scale(520)
        table.rowHeight = JBUI.scale(24)

        val actions = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            add(JButton("全部启用").apply { addActionListener { setAllEnabled(true) } })
            add(JButton("全部禁用").apply { addActionListener { setAllEnabled(false) } })
            add(JButton("恢复已保存设置").apply { addActionListener { reset() } })
        }
        val statusPanel = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            border = JBUI.Borders.emptyTop(6)
            add(JBLabel("配置状态："), BorderLayout.WEST)
            add(statusHint, BorderLayout.CENTER)
        }

        component = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            border = JBUI.Borders.empty(12)
            preferredSize = Dimension(JBUI.scale(980), JBUI.scale(560))
            add(
                JBLabel(
                    """
                    <html>
                    <p>选择当前项目允许 MCP 客户端发现和调用的工具。未启用的工具不会出现在 <code>tools/list</code> 中，也无法通过 <code>tools/call</code> 调用。</p>
                    <p>点击“应用”后，选中 Coding Agent 的工具 allowlist 会一并同步；新会话或重新加载 MCP 后即可看到最新列表。</p>
                    </html>
                    """.trimIndent(),
                ),
                BorderLayout.NORTH,
            )
            add(JBScrollPane(table), BorderLayout.CENTER)
            add(
                JPanel(BorderLayout()).apply {
                    add(actions, BorderLayout.NORTH)
                    add(statusPanel, BorderLayout.SOUTH)
                },
                BorderLayout.SOUTH,
            )
        }
        reset(announce = false)
    }

    fun isModified(): Boolean = model.enabledNames() != baselineEnabledNames

    fun apply() {
        val enabledNames = model.enabledNames()
        project.service<McpToolSettings>().updateEnabledToolNames(enabledNames, registry)
        baselineEnabledNames = project.service<McpToolSettings>().enabledToolNames(registry).toSet()
        showStatus(
            "成功：已保存，当前启用 ${baselineEnabledNames.size} / ${registry.definitions.size} 个工具",
            StatusKind.SUCCESS,
        )
    }

    fun reset() {
        reset(announce = true)
    }

    private fun reset(announce: Boolean) {
        val enabledNames = project.service<McpToolSettings>().enabledToolNames(registry).toSet()
        model.replace(registry.definitions.map { definition -> row(definition, definition.name in enabledNames) })
        baselineEnabledNames = enabledNames
        if (announce) {
            showStatus(
                "提示：已恢复已保存设置，当前启用 ${enabledNames.size} / ${registry.definitions.size} 个工具",
                StatusKind.INFO,
            )
        } else {
            selectionChanged()
        }
    }

    private fun setAllEnabled(enabled: Boolean) {
        model.setAllEnabled(enabled)
        showStatus(
            if (enabled) "提示：已选择全部工具，点击“应用”后生效" else "提示：已取消全部工具，点击“应用”后生效",
            StatusKind.INFO,
        )
    }

    private fun selectionChanged() {
        val enabledCount = model.enabledNames().size
        showStatus(
            "提示：已选择 $enabledCount / ${registry.definitions.size} 个工具${if (isModified()) "，有未保存更改" else ""}",
            StatusKind.INFO,
        )
    }

    private fun row(definition: McpToolDefinition, enabled: Boolean): McpToolRow = McpToolRow(
        name = definition.name,
        enabled = enabled,
        access = when {
            definition.annotations.get("readOnlyHint")?.asBoolean == true -> "只读"
            definition.annotations.get("destructiveHint")?.asBoolean == true -> "写操作"
            else -> "有副作用"
        },
        description = definition.description,
    )

    private fun showStatus(message: String, kind: StatusKind) {
        statusHint.text = message
        statusHint.toolTipText = message
        statusHint.foreground = when (kind) {
            StatusKind.SUCCESS -> JBColor(0x2E7D32, 0x59A869)
            StatusKind.INFO -> JBColor.GRAY
        }
        runCatching {
            val log = project.service<McpToolboxLogService>()
            when (kind) {
                StatusKind.SUCCESS -> log.success("工具配置", message)
                StatusKind.INFO -> log.info("工具配置", message)
            }
        }
    }

    private enum class StatusKind {
        SUCCESS,
        INFO,
    }
}

private data class McpToolRow(
    val name: String,
    var enabled: Boolean,
    val access: String,
    val description: String,
)

private class McpToolTableModel(private val onSelectionChanged: () -> Unit) : AbstractTableModel() {
    private val rows = mutableListOf<McpToolRow>()
    private val columns = listOf("启用", "工具名", "访问级别", "说明")

    override fun getRowCount(): Int = rows.size

    override fun getColumnCount(): Int = columns.size

    override fun getColumnName(column: Int): String = columns[column]

    override fun getColumnClass(columnIndex: Int): Class<*> = if (columnIndex == 0) {
        java.lang.Boolean::class.java
    } else {
        String::class.java
    }

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = columnIndex == 0

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = when (columnIndex) {
        0 -> rows[rowIndex].enabled
        1 -> rows[rowIndex].name
        2 -> rows[rowIndex].access
        3 -> rows[rowIndex].description
        else -> ""
    }

    override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
        if (columnIndex != 0) return
        rows[rowIndex].enabled = value == true
        fireTableCellUpdated(rowIndex, columnIndex)
        onSelectionChanged()
    }

    fun replace(newRows: List<McpToolRow>) {
        rows.clear()
        rows.addAll(newRows)
        fireTableDataChanged()
    }

    fun enabledNames(): Set<String> = rows.filter(McpToolRow::enabled).mapTo(linkedSetOf(), McpToolRow::name)

    fun setAllEnabled(enabled: Boolean) {
        rows.forEach { it.enabled = enabled }
        fireTableRowsUpdated(0, (rows.size - 1).coerceAtLeast(0))
        onSelectionChanged()
    }
}
