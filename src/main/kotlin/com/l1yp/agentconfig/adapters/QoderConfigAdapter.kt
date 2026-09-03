package com.l1yp.agentconfig.adapters

import com.google.gson.JsonObject
import com.l1yp.agentconfig.JsonFileAgentConfigAdapter
import com.l1yp.agentconfig.McpEndpoint

internal class QoderConfigAdapter : JsonFileAgentConfigAdapter(
    id = ID,
    displayName = "Qoder",
    relativePaths = listOf(".qoder/settings.local.json"),
    objectPath = listOf("mcpServers"),
) {
    override fun serverNode(endpoint: McpEndpoint): JsonObject = createServerNode(endpoint)

    override fun reloadInstruction(): String = "请在 Qoder 中运行 /mcp reload，或新建会话。"

    companion object {
        const val ID = "qoder"

        internal fun createServerNode(endpoint: McpEndpoint): JsonObject = JsonObject().apply {
            addProperty("type", "http")
            addProperty("url", endpoint.url)
            add("headers", endpoint.headers())
            add("includeTools", endpoint.enabledTools.toJsonArray())
            addProperty("timeout", endpoint.toolTimeoutMillis)
        }
    }
}
