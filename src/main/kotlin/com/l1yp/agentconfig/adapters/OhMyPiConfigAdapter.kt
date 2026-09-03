package com.l1yp.agentconfig.adapters

import com.google.gson.JsonObject
import com.l1yp.agentconfig.JsonFileAgentConfigAdapter
import com.l1yp.agentconfig.McpEndpoint

internal class OhMyPiConfigAdapter : JsonFileAgentConfigAdapter(
    id = ID,
    displayName = "Oh My Pi",
    relativePaths = listOf(".omp/mcp.json"),
    objectPath = listOf("mcpServers"),
) {
    override fun serverNode(endpoint: McpEndpoint): JsonObject = createServerNode(endpoint)

    override fun reloadInstruction(): String = "请在 Oh My Pi 中运行 /mcp reload。"

    companion object {
        const val ID = "oh_my_pi"

        internal fun createServerNode(endpoint: McpEndpoint): JsonObject = JsonObject().apply {
            addProperty("type", "http")
            addProperty("url", endpoint.url)
            add("headers", endpoint.headers())
        }
    }
}
