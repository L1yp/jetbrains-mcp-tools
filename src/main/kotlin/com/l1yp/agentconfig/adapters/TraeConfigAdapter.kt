package com.l1yp.agentconfig.adapters

import com.google.gson.JsonObject
import com.l1yp.agentconfig.JsonFileAgentConfigAdapter
import com.l1yp.agentconfig.McpEndpoint

internal class TraeConfigAdapter : JsonFileAgentConfigAdapter(
    id = ID,
    displayName = "Trae / TraeCode",
    relativePaths = listOf(".trae/mcp.json"),
    objectPath = listOf("mcpServers"),
) {
    override fun serverNode(endpoint: McpEndpoint): JsonObject = createServerNode(endpoint)

    override fun reloadInstruction(): String = "请在 Trae 中重新加载 MCP，或新建会话。"

    companion object {
        const val ID = "trae"

        internal fun createServerNode(endpoint: McpEndpoint): JsonObject = JsonObject().apply {
            addProperty("type", "http")
            addProperty("url", endpoint.url)
            add("headers", endpoint.headers())
        }
    }
}
