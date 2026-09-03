package com.l1yp.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.l1yp.logging.McpToolboxLogService
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.UIManager

internal class McpStatusPanel(
    private val project: Project,
    private val openGuideAction: (() -> Unit)? = null,
) {
    val component: JComponent

    private val httpStatus = JBLabel()
    private val processIdentity = JBLabel()
    private val projectIdentity = JBLabel()
    private val pluginVersion = JBLabel()
    private val toolsStatus = JBLabel()
    private val copyHint = JBLabel(" ")
    private val codexArea = codeArea()
    private val httpArea = codeArea()
    private val toolsArea = codeArea()

    init {
        val statusPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("HTTP 状态：", httpStatus)
            .addLabeledComponent("IDE 进程：", processIdentity)
            .addLabeledComponent("当前项目：", projectIdentity)
            .addLabeledComponent("插件版本：", pluginVersion)
            .addLabeledComponent("可用工具：", toolsStatus)
            .panel

        val actions = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            add(JButton("刷新状态").apply { addActionListener { refresh() } })
            add(JButton("复制 Codex 配置").apply { addActionListener { copy(codexArea.text, "已复制 Codex 配置") } })
            openGuideAction?.let { action ->
                add(JButton("在编辑器打开使用说明").apply { addActionListener { action() } })
            }
            add(copyHint)
        }

        val tabs = JBTabbedPane().apply {
            addTab("快速开始", guidePanel())
            addTab("Codex", textPanel(codexArea, "复制配置") { copy(codexArea.text, "已复制 Codex 配置") })
            addTab("HTTP", textPanel(httpArea, "复制 HTTP 说明") { copy(httpArea.text, "已复制 HTTP 说明") })
            addTab("工具定义", textPanel(toolsArea, "复制工具定义") { copy(toolsArea.text, "已复制工具定义") })
        }

        component = JPanel(BorderLayout(0, JBUI.scale(10))).apply {
            border = JBUI.Borders.empty(12)
            preferredSize = Dimension(JBUI.scale(860), JBUI.scale(600))
            add(
                JPanel(BorderLayout(0, JBUI.scale(8))).apply {
                    add(statusPanel, BorderLayout.CENTER)
                    add(actions, BorderLayout.SOUTH)
                },
                BorderLayout.NORTH,
            )
            add(tabs, BorderLayout.CENTER)
        }
        refresh()
    }

    fun refresh() {
        val snapshot = runCatching { McpStatusProvider.snapshot(project) }.getOrElse { error ->
            httpStatus.text = "读取失败"
            processIdentity.text = error.message ?: error.javaClass.simpleName
            projectIdentity.text = project.name
            pluginVersion.text = "未知"
            toolsStatus.text = "未知"
            project.service<McpToolboxLogService>().error(
                "连接状态",
                "刷新失败：${error.message ?: error.javaClass.simpleName}",
            )
            return
        }
        httpStatus.text = "已注册 · ${snapshot.endpoint}"
        processIdentity.text = "PID ${snapshot.processId}"
        projectIdentity.text = "${snapshot.projectName} · ${snapshot.projectPath.orEmpty()}"
        pluginVersion.text = snapshot.pluginVersion
        toolsStatus.text = "${snapshot.toolCount} / ${snapshot.supportedToolCount} 个已启用"
        codexArea.text = snapshot.codexConfiguration
        httpArea.text = snapshot.httpDetails
        toolsArea.text = snapshot.toolDefinitions
        copyHint.text = "状态已刷新"
        project.service<McpToolboxLogService>().success(
            "连接状态",
            "状态已刷新，Endpoint=${snapshot.endpoint}，已启用 ${snapshot.toolCount}/${snapshot.supportedToolCount} 个工具",
        )
    }

    private fun guidePanel(): JComponent = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(12)
        add(
            JBLabel(
                """
                <html>
                <h2>MCP Toolbox 使用流程</h2>
                <ol>
                  <li>在插件设置中选择允许公开的 Run Configuration 类型。</li>
                  <li>选择 Coding Agent 并同步项目级 MCP 配置；动态 URL 和 Token 只保留在本机。</li>
                  <li>Agent 通过项目 Bearer Token 自动绑定当前项目，无需传递项目路径。</li>
                  <li>先调用 <code>get_restartable_run_configurations</code> 获取准确配置名。</li>
                  <li>再调用 <code>restart_run_configuration</code> 启动或重启该配置。</li>
                  <li>Git 远程操作前调用 <code>get_git_repositories</code>；fetch、pull、push 的认证由 IDEA 接管。</li>
                </ol>
                <p><b>安全原则：</b>端口、Token 和 Agent 本地配置不得进入 Git；MCP 工具不会接收或返回 Git 凭据。</p>
                <p><b>旧版本升级：</b>Node Proxy 和实例注册表已停用；确认没有旧版插件在使用后，可手动删除
                <code>~/.mcp-service-restart/mcp-service-restart-proxy.mjs</code> 与 <code>instances/</code>。</p>
                </html>
                """.trimIndent(),
            ).apply {
                verticalAlignment = JBLabel.TOP
            },
            BorderLayout.NORTH,
        )
    }

    private fun textPanel(area: JBTextArea, buttonText: String, copyAction: () -> Unit): JComponent =
        JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            border = JBUI.Borders.empty(8)
            add(
                JBScrollPane(
                    area,
                    ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                    ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED,
                ),
                BorderLayout.CENTER,
            )
            add(
                JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                    add(JButton(buttonText).apply { addActionListener { copyAction() } })
                },
                BorderLayout.SOUTH,
            )
        }

    private fun copy(text: String, message: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        copyHint.foreground = JBColor.GRAY
        copyHint.text = message
    }

    private companion object {
        fun codeArea(): JBTextArea = JBTextArea().apply {
            isEditable = false
            font = mcpTextAreaFont()
            lineWrap = false
            tabSize = 2
            border = JBUI.Borders.empty(8)
        }
    }
}

internal fun mcpTextAreaFont(): Font {
    val editorFont = EditorFontType.getGlobalPlainFont()
    if (editorFont.canDisplayUpTo(CJK_FONT_SAMPLE) == -1) return editorFont

    return listOfNotNull(
        Font(Font.MONOSPACED, Font.PLAIN, editorFont.size),
        UIManager.getFont("TextArea.font"),
        Font(Font.DIALOG, Font.PLAIN, editorFont.size),
    ).firstOrNull { candidate -> candidate.canDisplayUpTo(CJK_FONT_SAMPLE) == -1 }
        ?: editorFont
}

private const val CJK_FONT_SAMPLE = "中文：返回错误响应"
