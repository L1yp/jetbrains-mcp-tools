package com.l1yp.mcp

import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import com.l1yp.tool.GetGitRepositoriesTool
import com.l1yp.tool.GetGitRemotesTool
import com.l1yp.tool.GetRestartableRunConfigurationsTool
import com.l1yp.tool.GitFetchTool
import com.l1yp.tool.GitPullTool
import com.l1yp.tool.GitPushTool
import com.l1yp.tool.RestartRunConfigurationTool

internal data class McpToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val outputSchema: JsonObject,
    val annotations: JsonObject,
)

internal data class McpToolCallResult(
    val text: String,
    val structuredContent: JsonObject? = null,
    val isError: Boolean = false,
) {
    companion object {
        fun success(content: JsonObject): McpToolCallResult = McpToolCallResult(
            text = content.toString(),
            structuredContent = content,
        )

        fun error(message: String): McpToolCallResult = McpToolCallResult(
            text = message,
            isError = true,
        )
    }
}

internal interface McpTool {
    val definition: McpToolDefinition

    fun call(project: Project, arguments: JsonObject): McpToolCallResult
}

internal class ToolRegistry(
    tools: List<McpTool> = listOf(
        GetRestartableRunConfigurationsTool(),
        RestartRunConfigurationTool(),
        GetGitRepositoriesTool(),
        GetGitRemotesTool(),
        GitFetchTool(),
        GitPullTool(),
        GitPushTool(),
    ),
) {
    private val toolsByName = tools.associateBy { it.definition.name }

    val definitions: List<McpToolDefinition> = tools.map(McpTool::definition)

    fun find(name: String): McpTool? = toolsByName[name]

    companion object {
        val DEFAULT = ToolRegistry()
    }
}
