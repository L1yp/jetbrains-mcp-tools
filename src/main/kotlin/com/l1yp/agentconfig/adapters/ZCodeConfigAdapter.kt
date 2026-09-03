package com.l1yp.agentconfig.adapters

import com.google.gson.JsonObject
import com.l1yp.agentconfig.JsonFileAgentConfigAdapter
import com.l1yp.agentconfig.McpEndpoint

internal class ZCodeConfigAdapter : JsonFileAgentConfigAdapter(
    id = ID,
    displayName = "ZCode",
    relativePaths = listOf(".zcode/config.json"),
    objectPath = listOf("mcp", "servers"),
) {
    override fun serverNode(endpoint: McpEndpoint): JsonObject = createServerNode(endpoint)

    override fun reloadInstruction(): String = "请在 ZCode 中重新加载 MCP，或新建会话。"

    companion object {
        const val ID = "zcode"

        internal fun createServerNode(endpoint: McpEndpoint): JsonObject = JsonObject().apply {
            addProperty("type", "http")
            addProperty("url", endpoint.url)
            add("headers", endpoint.headers())
        }
    }
}
