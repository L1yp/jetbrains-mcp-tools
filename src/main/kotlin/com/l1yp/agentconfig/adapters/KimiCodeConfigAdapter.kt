package com.l1yp.agentconfig.adapters

import com.google.gson.JsonObject
import com.l1yp.agentconfig.JsonFileAgentConfigAdapter
import com.l1yp.agentconfig.McpEndpoint

internal class KimiCodeConfigAdapter : JsonFileAgentConfigAdapter(
    id = ID,
    displayName = "Kimi Code",
    relativePaths = listOf(".kimi-code/mcp.json"),
    objectPath = listOf("mcpServers"),
) {
    override fun serverNode(endpoint: McpEndpoint): JsonObject = createServerNode(endpoint)

    override fun reloadInstruction(): String = "新增 MCP Server 只会在新会话中注册，请新建 Kimi Code 会话。"

    companion object {
        const val ID = "kimi_code"

        internal fun createServerNode(endpoint: McpEndpoint): JsonObject = JsonObject().apply {
            addProperty("url", endpoint.url)
            add("headers", endpoint.headers())
            addProperty("startupTimeoutMs", endpoint.startupTimeoutMillis)
            addProperty("toolTimeoutMs", endpoint.toolTimeoutMillis)
            add("enabledTools", endpoint.enabledTools.toJsonArray())
        }
    }
}
