package com.l1yp.ui

import com.google.gson.GsonBuilder
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.l1yp.mcp.ToolRegistry
import com.l1yp.mcp.McpProtocol
import org.jetbrains.ide.BuiltInServerManager

internal object McpStatusProvider {
    private val gson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()

    fun snapshot(project: Project): McpStatusSnapshot {
        val port = BuiltInServerManager.getInstance().port
        val endpoint = "http://127.0.0.1:$port${McpProtocol.ENDPOINT_PATH}"
        val definitions = ToolRegistry.DEFAULT.definitions
        return McpStatusSnapshot(
            endpoint = endpoint,
            projectName = project.name,
            projectPath = project.basePath,
            processId = ProcessHandle.current().pid(),
            pluginVersion = PluginManagerCore.getPlugin(PLUGIN_ID)?.version ?: "未知",
            codexConfiguration = McpConfigurationFormatter.codexConfiguration(project.name, endpoint),
            httpDetails = McpConfigurationFormatter.httpDetails(endpoint),
            toolDefinitions = gson.toJson(definitions),
            toolCount = definitions.size,
        )
    }

    private val PLUGIN_ID = PluginId.getId("com.l1yp.mcpTools")
}

internal object McpConfigurationFormatter {
    fun codexConfiguration(projectName: String, endpoint: String): String {
        val enabledTools = ToolRegistry.DEFAULT.definitions.joinToString(", ") { "\"${it.name}\"" }
        return """
        [mcp_servers.jetbrains_tools]
        url = "$endpoint"
        http_headers = { Authorization = "Bearer <project-token>" }
        enabled_tools = [$enabledTools]
        startup_timeout_sec = 10
        tool_timeout_sec = 120

        # Project: $projectName
        # 本机端口和 Token 不应提交到 Git。
    """.trimIndent()
    }

    fun httpDetails(endpoint: String): String {
        val tools = ToolRegistry.DEFAULT.definitions.joinToString("\n") { "  - ${it.name}" }
        return """
            Endpoint: $endpoint
            Protocol: MCP 2025-11-25
            Transport: Streamable HTTP
            Authorization: Bearer <project-token>
            Tools:
            $tools
        """.trimIndent()
    }
}

internal data class McpStatusSnapshot(
    val endpoint: String,
    val projectName: String,
    val projectPath: String?,
    val processId: Long,
    val pluginVersion: String,
    val codexConfiguration: String,
    val httpDetails: String,
    val toolDefinitions: String,
    val toolCount: Int,
)
