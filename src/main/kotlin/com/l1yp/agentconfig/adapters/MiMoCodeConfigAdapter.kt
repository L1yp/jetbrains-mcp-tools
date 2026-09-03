package com.l1yp.agentconfig.adapters

import com.google.gson.JsonObject
import com.l1yp.agentconfig.JsonFileAgentConfigAdapter
import com.l1yp.agentconfig.McpEndpoint

internal class MiMoCodeConfigAdapter : JsonFileAgentConfigAdapter(
    id = ID,
    displayName = "MiMoCode",
    relativePaths = listOf(
        ".mimocode/mimocode.jsonc",
        ".mimocode/mimocode.json",
    ),
    defaultRelativePath = ".mimocode/mimocode.json",
    objectPath = listOf("mcp"),
) {
    override fun serverNode(endpoint: McpEndpoint): JsonObject = createServerNode(endpoint)

    override fun reloadInstruction(): String =
        "请在 MiMoCode 中重新加载 MCP 或新建会话；mimo serve/attach 的 MCP 初始化能力需按客户端版本确认。"

    companion object {
        const val ID = "mimocode"

        internal fun createServerNode(endpoint: McpEndpoint): JsonObject = JsonObject().apply {
            addProperty("type", "remote")
            addProperty("url", endpoint.url)
            add("headers", endpoint.headers())
            addProperty("oauth", false)
            addProperty("enabled", true)
            addProperty("timeout", endpoint.toolTimeoutMillis)
        }
    }
}
