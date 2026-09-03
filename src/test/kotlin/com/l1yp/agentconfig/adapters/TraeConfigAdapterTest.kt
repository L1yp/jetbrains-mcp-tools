package com.l1yp.agentconfig.adapters

import com.l1yp.agentconfig.McpEndpoint
import kotlin.test.Test
import kotlin.test.assertEquals

class TraeConfigAdapterTest {
    @Test
    fun `writes Trae Streamable HTTP schema`() {
        val node = TraeConfigAdapter.createServerNode(endpoint())

        assertEquals("http", node.get("type").asString)
        assertEquals("http://127.0.0.1:63342/api/jetbrains-mcp-tools", node.get("url").asString)
        assertEquals("Bearer token", node.getAsJsonObject("headers").get("Authorization").asString)
        assertEquals(setOf("type", "url", "headers"), node.keySet())
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
