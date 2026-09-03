package com.l1yp.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.l1yp.logging.McpLogEntry
import com.l1yp.logging.McpLogLevel
import com.l1yp.logging.McpLogUpdate
import com.l1yp.logging.McpToolboxLogService
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

internal const val MCP_LOG_TOOL_WINDOW_ID = "MCP Toolbox"

internal class McpLogToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = McpLogPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.component, "日志", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
        project.service<McpToolboxLogService>().info("日志窗口", "MCP Toolbox 日志窗口已打开")
    }
}

private class McpLogPanel(private val project: Project) : Disposable {
    val component: JComponent

    private val logArea = JBTextArea()
    private val status = JBLabel(" ")
    private val renderedEntries = ArrayDeque<McpLogEntry>()
    private var entryCount = 0

    init {
        logArea.apply {
            isEditable = false
            lineWrap = false
            font = mcpTextAreaFont()
            border = JBUI.Borders.empty(8)
        }
        val actions = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            add(JButton("清空日志").apply {
                addActionListener { project.service<McpToolboxLogService>().clear() }
            })
            add(JButton("复制全部").apply {
                addActionListener {
                    CopyPasteManager.getInstance().setContents(StringSelection(logArea.text))
                    status.text = "已复制 $entryCount 条日志"
                }
            })
            add(JButton("滚动到底部").apply {
                addActionListener { logArea.caretPosition = logArea.document.length }
            })
            add(status)
        }
        component = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            border = JBUI.Borders.empty(6)
            add(JBScrollPane(logArea), BorderLayout.CENTER)
            add(actions, BorderLayout.SOUTH)
        }

        val snapshot = project.service<McpToolboxLogService>().addListener(this) { update ->
            ApplicationManager.getApplication().invokeLater(
                { applyUpdate(update) },
                ModalityState.any(),
            )
        }
        renderedEntries.addAll(snapshot)
        renderAll()
        updateStatus()
        logArea.caretPosition = logArea.document.length
    }

    override fun dispose() = Unit

    private fun applyUpdate(update: McpLogUpdate) {
        if (project.isDisposed) return
        when (update) {
            is McpLogUpdate.EntryAdded -> {
                renderedEntries.addLast(update.entry)
                if (renderedEntries.size > MAX_ENTRIES) {
                    renderedEntries.removeFirst()
                    renderAll()
                } else {
                    logArea.append(format(update.entry))
                    entryCount = renderedEntries.size
                }
                logArea.caretPosition = logArea.document.length
            }
            McpLogUpdate.Cleared -> {
                renderedEntries.clear()
                logArea.text = ""
                entryCount = 0
            }
        }
        updateStatus()
    }

    private fun updateStatus() {
        status.text = "$entryCount 条日志 · 最多保留 $MAX_ENTRIES 条 · 敏感信息已脱敏"
    }

    private fun renderAll() {
        logArea.text = renderedEntries.joinToString(separator = "", transform = ::format)
        entryCount = renderedEntries.size
    }

    private fun format(entry: McpLogEntry): String = buildString {
        append(TIMESTAMP_FORMATTER.format(entry.timestamp.atZone(ZoneId.systemDefault())))
        append(" [")
        append(
            when (entry.level) {
                McpLogLevel.INFO -> "信息"
                McpLogLevel.SUCCESS -> "成功"
                McpLogLevel.WARNING -> "警告"
                McpLogLevel.ERROR -> "错误"
            },
        )
        append("] [")
        append(entry.source)
        append("] ")
        append(entry.message)
        append('\n')
    }

    private companion object {
        const val MAX_ENTRIES = 2_000
        val TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    }
}
