package com.l1yp.agentconfig.adapters

import com.l1yp.agentconfig.JsonConfigEditor
import com.l1yp.agentconfig.McpEndpoint
import kotlin.test.Test
import kotlin.test.assertEquals

class ZCodeConfigAdapterTest {
    @Test
    fun `writes ZCode server below mcp servers`() {
        val endpoint = endpoint()
        val node = ZCodeConfigAdapter.createServerNode(endpoint)
        val config = JsonConfigEditor.upsert(
            """{"theme":"dark"}""",
            listOf("mcp", "servers"),
            endpoint.serverName,
            node,
        )

        assertEquals(setOf(endpoint.serverName), JsonConfigEditor.serverNames(config, listOf("mcp", "servers")))
        assertEquals("http", node.get("type").asString)
        assertEquals("Bearer token", node.getAsJsonObject("headers").get("Authorization").asString)
    }

    private fun endpoint() = McpEndpoint(
        serverName = "jetbrains_tools",
        url = "http://127.0.0.1:63342/api/jetbrains-mcp-tools",
        authorizationHeader = "Bearer token",
        protocolVersion = "2025-11-25",
        startupTimeoutMillis = 10_000,
        toolTimeoutMillis = 120_000,
        enabledTools = emptyList(),
    )
}
