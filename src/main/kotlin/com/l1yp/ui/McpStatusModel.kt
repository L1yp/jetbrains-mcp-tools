package com.l1yp.ui

import com.google.gson.GsonBuilder
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.l1yp.mcp.McpProtocol
import com.l1yp.mcp.McpToolDefinition
import com.l1yp.mcp.McpToolSettings
import com.l1yp.mcp.ToolRegistry
import org.jetbrains.ide.BuiltInServerManager

internal object McpStatusProvider {
    private val gson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()

    fun snapshot(project: Project): McpStatusSnapshot {
        val port = BuiltInServerManager.getInstance().port
        val endpoint = "http://127.0.0.1:$port${McpProtocol.ENDPOINT_PATH}"
        val registry = ToolRegistry.DEFAULT
        val enabledNames = project.service<McpToolSettings>().enabledToolNames(registry).toSet()
        val definitions = registry.definitions.filter { it.name in enabledNames }
        return McpStatusSnapshot(
            endpoint = endpoint,
            projectName = project.name,
            projectPath = project.basePath,
            processId = ProcessHandle.current().pid(),
            pluginVersion = PluginManagerCore.getPlugin(PLUGIN_ID)?.version ?: "未知",
            codexConfiguration = McpConfigurationFormatter.codexConfiguration(project.name, endpoint, definitions),
            httpDetails = McpConfigurationFormatter.httpDetails(endpoint, definitions),
            toolDefinitions = gson.toJson(definitions),
            toolCount = definitions.size,
            supportedToolCount = registry.definitions.size,
        )
    }

    private val PLUGIN_ID = PluginId.getId("com.l1yp.mcpTools")
}

internal object McpConfigurationFormatter {
    fun codexConfiguration(projectName: String, endpoint: String): String =
        codexConfiguration(projectName, endpoint, ToolRegistry.DEFAULT.definitions)

    fun codexConfiguration(
        projectName: String,
        endpoint: String,
        definitions: List<McpToolDefinition>,
    ): String {
        val enabledTools = definitions.joinToString(", ") { "\"${it.name}\"" }
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

    fun httpDetails(endpoint: String): String = httpDetails(endpoint, ToolRegistry.DEFAULT.definitions)

    fun httpDetails(endpoint: String, definitions: List<McpToolDefinition>): String {
        val tools = definitions.joinToString("\n") { "  - ${it.name}" }.ifBlank { "  （全部已禁用）" }
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
    val supportedToolCount: Int,
)
