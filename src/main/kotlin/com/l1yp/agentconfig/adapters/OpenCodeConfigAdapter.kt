package com.l1yp.agentconfig.adapters

import com.google.gson.JsonObject
import com.l1yp.agentconfig.JsonFileAgentConfigAdapter
import com.l1yp.agentconfig.McpEndpoint

internal class OpenCodeConfigAdapter : JsonFileAgentConfigAdapter(
    id = ID,
    displayName = "OpenCode",
    relativePaths = listOf(
        "opencode.jsonc",
        "opencode.json",
        ".opencode/opencode.jsonc",
        ".opencode/opencode.json",
    ),
    defaultRelativePath = ".opencode/opencode.json",
    objectPath = listOf("mcp", "servers"),
) {
    override fun serverNode(endpoint: McpEndpoint): JsonObject = createServerNode(endpoint)

    override fun reloadInstruction(): String = "请在 OpenCode 中重新加载 MCP，或新建会话。"

    companion object {
        const val ID = "opencode"

        internal fun createServerNode(endpoint: McpEndpoint): JsonObject = JsonObject().apply {
            addProperty("type", "remote")
            addProperty("url", endpoint.url)
            add("headers", endpoint.headers())
            addProperty("oauth", false)
            addProperty("codemode", false)
            addProperty("disabled", false)
            add("timeout", JsonObject().apply {
                addProperty("startup", endpoint.startupTimeoutMillis)
                addProperty("catalog", endpoint.startupTimeoutMillis)
                addProperty("execution", endpoint.toolTimeoutMillis)
            })
        }
    }
}
