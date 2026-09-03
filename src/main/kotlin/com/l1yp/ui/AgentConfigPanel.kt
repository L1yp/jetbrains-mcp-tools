package com.l1yp.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.l1yp.agentconfig.AgentConfigCoordinator
import com.l1yp.agentconfig.AgentConfigView
import com.l1yp.agentconfig.AgentSyncStatus
import com.l1yp.agentconfig.ApplyResult
import com.l1yp.agentconfig.ConfigChange
import com.l1yp.agentconfig.adapters.ManualAgentCatalog
import com.l1yp.agentconfig.adapters.ManualAgentDefinition
import com.l1yp.agentconfig.adapters.ManualConfigurationFormatter
import com.l1yp.logging.McpToolboxLogService
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

internal class AgentConfigPanel(private val project: Project) {
    val component: JComponent

    private val coordinator = project.service<AgentConfigCoordinator>()
    private val model = AgentTableModel()
    private val table = JBTable(model)
    private val statusHint = JBLabel(" ")
    private val actionButtons = mutableListOf<JButton>()
    private var baselineSelectedIds = emptySet<String>()

    init {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.autoResizeMode = javax.swing.JTable.AUTO_RESIZE_LAST_COLUMN
        table.columnModel.getColumn(0).preferredWidth = JBUI.scale(55)
        table.columnModel.getColumn(1).preferredWidth = JBUI.scale(140)
        table.columnModel.getColumn(2).preferredWidth = JBUI.scale(85)
        table.columnModel.getColumn(3).preferredWidth = JBUI.scale(90)
        table.columnModel.getColumn(4).preferredWidth = JBUI.scale(220)
        table.columnModel.getColumn(5).preferredWidth = JBUI.scale(120)

        val primaryActions = actionRow().apply {
            add(actionButton("自动检测") { refresh(preserveSelection = true) })
            add(actionButton("预览变更", ::previewSelected))
            add(actionButton("同步选中 Agent", ::persistAndSync))
            add(actionButton("覆盖当前节点", ::overwriteSelected))
            add(actionButton("移除本插件配置", ::removeSelected))
        }
        val fileAndCopyActions = actionRow().apply {
            add(actionButton("打开配置文件", ::openSelectedConfig))
            add(actionButton("打开官方文档", ::openSelectedDocumentation))
            add(actionButton("复制当前配置", ::copySelectedConfiguration))
            add(actionButton("复制 URL", ::copyEndpointUrl))
            add(actionButton("复制 Header JSON", ::copyHeaderJson))
        }
        val diagnosticActions = actionRow().apply {
            add(actionButton("复制 tools/list 命令", ::copyToolsListCommand))
            add(actionButton("测试 MCP Endpoint", ::testEndpoint))
            add(actionButton("轮换 Token", ::rotateToken))
            add(actionButton("打开 MCP 日志", ::openLogWindow))
        }
        val statusPanel = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            border = JBUI.Borders.empty(6, 4, 0, 4)
            add(JBLabel("操作状态："), BorderLayout.WEST)
            add(statusHint, BorderLayout.CENTER)
        }
        val actions = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(primaryActions)
            add(fileAndCopyActions)
            add(diagnosticActions)
            add(statusPanel)
        }

        component = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            border = JBUI.Borders.empty(12)
            preferredSize = Dimension(JBUI.scale(980), JBUI.scale(560))
            add(
                JBLabel(
                    """
                    <html>
                    <p>选择项目实际使用的 Coding Agent。自动配置只合并本插件拥有的 MCP Server 节点；</p>
                    <p>动态端口和 Token 会加入当前 clone 的 Git exclude，已被 Git 跟踪的文件不会自动修改。</p>
                    </html>
                    """.trimIndent(),
                ),
                BorderLayout.NORTH,
            )
            add(JBScrollPane(table), BorderLayout.CENTER)
            add(
                actions,
                BorderLayout.SOUTH,
            )
        }
        refresh(preserveSelection = false)
    }

    fun isModified(): Boolean = model.selectedIds() != baselineSelectedIds

    fun apply() {
        persistAndSync()
    }

    fun reset() {
        refresh(preserveSelection = false)
    }

    private fun refresh(preserveSelection: Boolean, announce: Boolean = true) {
        val selected = if (preserveSelection) model.selectedIds() else coordinator.selectedAgentIds()
        val automaticRows = coordinator.views().map { view -> row(view, view.adapter.id in selected) }
        val manualRows = ManualAgentCatalog.agents.map { definition -> row(definition, definition.id in selected) }
        model.replace(automaticRows + manualRows)
        if (!preserveSelection) baselineSelectedIds = selected
        if (announce) showFeedback(ActionFeedback.success("已刷新 Agent 检测与配置状态"))
    }

    private fun persistAndSync() {
        val selected = model.selectedIds()
        coordinator.updateSelectedAgentIds(selected)
        baselineSelectedIds = coordinator.selectedAgentIds()
        runBackground("正在自检并同步…") {
            summarize(coordinator.syncSelected(requireSelfTest = true))
        }
    }

    private fun previewSelected() {
        val row = selectedRow() ?: return showHint("请先选择一个 Agent 行")
        val manual = row.manualDefinition
        if (manual != null) {
            showText("${manual.displayName} 人工配置预览", manual.preview(coordinator.endpointForManualConfiguration()))
            showFeedback(ActionFeedback.success("已打开 ${manual.displayName} 人工配置预览"))
            return
        }
        val modalityState = ModalityState.stateForComponent(component)
        runBackground("正在生成预览…") {
            val change = coordinator.preview(row.id)
            ApplicationManager.getApplication().invokeLater(
                {
                    when (change) {
                        is ConfigChange.Ready -> showText(
                            "${row.displayName} 配置变更预览",
                            "变更前：\n${change.beforePreview.ifBlank { "（文件不存在）" }}\n\n变更后：\n${change.afterPreview}",
                        )
                        is ConfigChange.Unchanged ->
                            showText("${row.displayName} 配置变更预览", "配置已同步，无需修改。")
                        is ConfigChange.Blocked -> showText("${row.displayName} 配置变更预览", change.reason)
                    }
                },
                modalityState,
            )
            ActionFeedback.success("预览已生成")
        }
    }

    private fun overwriteSelected() {
        val row = selectedRow() ?: return showHint("请先选择一个自动配置 Agent")
        if (row.manualDefinition != null) return showHint("人工配置 Agent 不会写入文件")
        val answer = JOptionPane.showConfirmDialog(
            component,
            "仅当你确认当前本插件 MCP 节点应被覆盖时继续。其他配置不会被修改。",
            "确认覆盖本插件节点",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE,
        )
        if (answer != JOptionPane.YES_OPTION) return showHint("已取消覆盖")
        runBackground("正在覆盖并同步…") {
            summarize(mapOf(row.id to coordinator.sync(row.id, confirmUserChanges = true)))
        }
    }

    private fun removeSelected() {
        val row = selectedRow() ?: return showHint("请先选择一个自动配置 Agent")
        if (row.manualDefinition != null) return showHint("人工配置 Agent 没有可移除的插件节点")
        val answer = JOptionPane.showConfirmDialog(
            component,
            "只会删除本插件记录的 MCP Server 节点。是否继续？",
            "移除 MCP 配置",
            JOptionPane.YES_NO_OPTION,
        )
        if (answer != JOptionPane.YES_OPTION) return showHint("已取消移除")
        runBackground("正在移除…") { summarize(mapOf(row.id to coordinator.remove(row.id))) }
    }

    private fun openSelectedConfig() {
        val path = selectedRow()?.configPath ?: return showHint("此 Agent 没有自动配置文件")
        if (!Files.exists(path)) return showHint("配置文件尚不存在")
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(
            FileUtil.toSystemIndependentName(path.toString()),
        ) ?: return showHint("无法打开配置文件")
        FileEditorManager.getInstance(project).openFile(virtualFile, true)
        showFeedback(ActionFeedback.success("已打开配置文件"))
    }

    private fun openSelectedDocumentation() {
        val documentationUrl = selectedRow()?.manualDefinition?.documentationUrl
            ?: return showHint("该 Agent 未提供稳定的官方配置文档链接")
        BrowserUtil.browse(documentationUrl)
        showFeedback(ActionFeedback.success("已在浏览器打开官方文档"))
    }

    private fun copySelectedConfiguration() {
        val row = selectedRow() ?: return showHint("请先选择一个 Agent")
        val endpoint = coordinator.endpointForManualConfiguration()
        val configuration = row.manualDefinition?.configuration?.invoke(endpoint)
            ?: ManualConfigurationFormatter.genericMcpServersJson(endpoint)
        copy(configuration, "已复制配置；内容可能含项目 Token，请勿提交 Git")
    }

    private fun copyEndpointUrl() {
        copy(coordinator.endpointForManualConfiguration().url, "已复制 URL")
    }

    private fun copyHeaderJson() {
        copy(
            ManualConfigurationFormatter.headerJson(coordinator.endpointForManualConfiguration()),
            "已复制 Header JSON；内容含项目 Token，请勿提交 Git",
        )
    }

    private fun copyToolsListCommand() {
        copy(
            ManualConfigurationFormatter.toolsListCommand(coordinator.endpointForManualConfiguration()),
            "已复制 tools/list 自检命令；内容含项目 Token，请勿提交 Git",
        )
    }

    private fun testEndpoint() {
        runBackground("正在测试 Endpoint…") {
            val failure = com.l1yp.agentconfig.McpEndpointSelfTest.test(project)
            if (failure == null) ActionFeedback.success("Endpoint 自检通过") else ActionFeedback.failure(failure)
        }
    }

    private fun rotateToken() {
        val answer = JOptionPane.showConfirmDialog(
            component,
            "轮换后旧 Token 立即失效，并会重新同步所有选中的自动配置 Agent。是否继续？",
            "轮换项目 Token",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE,
        )
        if (answer != JOptionPane.YES_OPTION) return showHint("已取消轮换 Token")
        runBackground("正在轮换 Token 并同步…") { summarize(coordinator.rotateToken()) }
    }

    private fun openLogWindow() {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(MCP_LOG_TOOL_WINDOW_ID)
            ?: return showHint("MCP Toolbox 日志窗口尚未注册")
        toolWindow.show()
        showFeedback(ActionFeedback.success("已打开 MCP Toolbox 日志窗口"))
    }

    private fun runBackground(startMessage: String, action: () -> ActionFeedback) {
        val modalityState = ModalityState.stateForComponent(component)
        setBusy(true)
        showFeedback(ActionFeedback.inProgress(startMessage))
        AppExecutorUtil.getAppExecutorService().execute {
            val feedback = runCatching(action).getOrElse { error ->
                ActionFeedback.failure(error.message ?: error.javaClass.simpleName)
            }
            ApplicationManager.getApplication().invokeLater(
                {
                    if (!project.isDisposed) {
                        val refreshFailure = runCatching {
                            refresh(preserveSelection = true, announce = false)
                        }.exceptionOrNull()
                        setBusy(false)
                        showFeedback(
                            refreshFailure?.let { error ->
                                ActionFeedback.failure(
                                    "操作已结束，但刷新状态失败：${error.message ?: error.javaClass.simpleName}",
                                )
                            } ?: feedback,
                        )
                    }
                },
                modalityState,
            )
        }
    }

    private fun summarize(results: Map<String, ApplyResult>): ActionFeedback {
        if (results.isEmpty()) return ActionFeedback.warning("没有选中的自动配置 Agent")
        val failures = results.filterValues { it is ApplyResult.Failed }
        if (failures.isEmpty()) {
            val reloads = results.values
                .filterIsInstance<ApplyResult.Applied>()
                .filter(ApplyResult.Applied::changed)
                .map(ApplyResult.Applied::reloadInstruction)
                .distinct()
            return ActionFeedback.success(buildString {
                append("已处理 ${results.size} 个 Agent")
                if (reloads.isNotEmpty()) append("；${reloads.joinToString("；")}")
            })
        }
        return ActionFeedback.failure(failures.entries.joinToString("；") { (id, result) ->
            "$id: ${(result as ApplyResult.Failed).reason}"
        })
    }

    private fun selectedRow(): AgentRow? {
        val viewRow = table.selectedRow
        if (viewRow < 0) return null
        return model.row(table.convertRowIndexToModel(viewRow))
    }

    private fun copy(text: String, message: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        showFeedback(ActionFeedback.success(message))
    }

    private fun showText(title: String, text: String) {
        val area = JTextArea(text, 28, 100).apply {
            isEditable = false
            caretPosition = 0
        }
        JOptionPane.showMessageDialog(component, JBScrollPane(area), title, JOptionPane.INFORMATION_MESSAGE)
    }

    private fun showHint(message: String) {
        showFeedback(ActionFeedback.warning(message))
    }

    private fun showFeedback(feedback: ActionFeedback) {
        val prefix = when (feedback.kind) {
            FeedbackKind.SUCCESS -> "成功："
            FeedbackKind.FAILURE -> "失败："
            FeedbackKind.WARNING -> "提示："
            FeedbackKind.IN_PROGRESS -> "处理中："
        }
        val message = "$prefix${feedback.message.removePrefix(prefix)}"
        statusHint.text = message
        statusHint.toolTipText = message
        statusHint.foreground = when (feedback.kind) {
            FeedbackKind.SUCCESS -> JBColor(0x2E7D32, 0x59A869)
            FeedbackKind.FAILURE -> JBColor(0xC62828, 0xFF6B68)
            FeedbackKind.WARNING -> JBColor(0x9A6700, 0xD7BA7D)
            FeedbackKind.IN_PROGRESS -> JBColor.GRAY
        }
        runCatching {
            val log = project.service<McpToolboxLogService>()
            when (feedback.kind) {
                FeedbackKind.SUCCESS -> log.success("Coding Agent", message)
                FeedbackKind.FAILURE -> log.error("Coding Agent", message)
                FeedbackKind.WARNING -> log.warning("Coding Agent", message)
                FeedbackKind.IN_PROGRESS -> log.info("Coding Agent", message)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        actionButtons.forEach { it.isEnabled = !busy }
    }

    private fun actionButton(text: String, action: () -> Unit): JButton = JButton(text).also { button ->
        button.addActionListener {
            runCatching(action).onFailure { error ->
                showFeedback(ActionFeedback.failure(error.message ?: error.javaClass.simpleName))
            }
        }
        actionButtons.add(button)
    }

    private fun actionRow(): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
        alignmentX = JComponent.LEFT_ALIGNMENT
    }

    private fun row(view: AgentConfigView, selected: Boolean): AgentRow = AgentRow(
        id = view.adapter.id,
        displayName = view.adapter.displayName,
        selected = selected,
        detection = if (view.detection.detected) "已检测" else "未检测",
        support = "自动配置",
        configPath = view.location.path,
        configDescription = view.location.description,
        status = statusText(view.status),
        detail = view.detail,
    )

    private fun row(definition: ManualAgentDefinition, selected: Boolean): AgentRow = AgentRow(
        id = definition.id,
        displayName = definition.displayName,
        selected = selected,
        detection = if (definition.id == "pi") "未验证" else "人工确认",
        support = "人工配置",
        configPath = null,
        configDescription = "—",
        status = if (selected) "人工配置" else "未选择",
        detail = definition.reason,
        manualDefinition = definition,
    )

    private fun statusText(status: AgentSyncStatus): String = when (status) {
        AgentSyncStatus.NOT_DETECTED -> "未检测"
        AgentSyncStatus.NOT_SELECTED -> "未选择"
        AgentSyncStatus.CONFIG_MISSING -> "配置不存在"
        AgentSyncStatus.PENDING -> "待同步"
        AgentSyncStatus.SYNCED -> "已同步"
        AgentSyncStatus.GIT_TRACKED -> "配置被 Git 跟踪"
        AgentSyncStatus.PARSE_FAILED -> "配置解析失败"
        AgentSyncStatus.USER_MODIFIED -> "配置被用户修改"
        AgentSyncStatus.LEASE_HELD -> "被另一个 IDEA 实例占用"
        AgentSyncStatus.NEEDS_NEW_SESSION -> "需要新会话"
        AgentSyncStatus.CLIENT_KNOWN_ISSUE -> "已同步/有版本警告"
    }
}

private enum class FeedbackKind {
    SUCCESS,
    FAILURE,
    WARNING,
    IN_PROGRESS,
}

private data class ActionFeedback(
    val kind: FeedbackKind,
    val message: String,
) {
    companion object {
        fun success(message: String): ActionFeedback = ActionFeedback(FeedbackKind.SUCCESS, message)
        fun failure(message: String): ActionFeedback = ActionFeedback(FeedbackKind.FAILURE, message)
        fun warning(message: String): ActionFeedback = ActionFeedback(FeedbackKind.WARNING, message)
        fun inProgress(message: String): ActionFeedback = ActionFeedback(FeedbackKind.IN_PROGRESS, message)
    }
}

private data class AgentRow(
    val id: String,
    val displayName: String,
    var selected: Boolean,
    val detection: String,
    val support: String,
    val configPath: Path?,
    val configDescription: String,
    val status: String,
    val detail: String?,
    val manualDefinition: ManualAgentDefinition? = null,
)

private class AgentTableModel : AbstractTableModel() {
    private val rows = mutableListOf<AgentRow>()
    private val columns = listOf("选择", "Agent", "检测", "支持级别", "配置文件", "状态")

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
        0 -> rows[rowIndex].selected
        1 -> rows[rowIndex].displayName
        2 -> rows[rowIndex].detection
        3 -> rows[rowIndex].support
        4 -> rows[rowIndex].configDescription
        5 -> rows[rowIndex].status
        else -> ""
    }

    override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
        if (columnIndex != 0) return
        rows[rowIndex].selected = value == true
        fireTableCellUpdated(rowIndex, columnIndex)
    }

    fun replace(newRows: List<AgentRow>) {
        rows.clear()
        rows.addAll(newRows)
        fireTableDataChanged()
    }

    fun selectedIds(): Set<String> = rows.filter(AgentRow::selected).mapTo(linkedSetOf(), AgentRow::id)

    fun row(index: Int): AgentRow = rows[index]
}
