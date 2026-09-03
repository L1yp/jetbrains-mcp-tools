package com.l1yp.agentconfig.adapters

import com.google.gson.JsonParser
import com.l1yp.agentconfig.McpEndpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class KimiCodeConfigAdapterTest {
    @Test
    fun `writes Kimi HTTP schema without a transport field`() {
        val node = KimiCodeConfigAdapter.createServerNode(endpoint())

        assertEquals("http://127.0.0.1:63342/api/jetbrains-mcp-tools", node.get("url").asString)
        assertEquals("Bearer token", node.getAsJsonObject("headers").get("Authorization").asString)
        assertFalse(node.has("transport"))
        assertEquals(10_000, node.get("startupTimeoutMs").asInt)
        assertEquals(120_000, node.get("toolTimeoutMs").asInt)
        assertEquals(
            listOf("get_restartable_run_configurations", "restart_run_configuration"),
            node.getAsJsonArray("enabledTools").map { it.asString },
        )
        JsonParser.parseString(node.toString())
    }

    private fun endpoint() = McpEndpoint(
        serverName = "jetbrains_tools",
        url = "http://127.0.0.1:63342/api/jetbrains-mcp-tools",
        authorizationHeader = "Bearer token",
        protocolVersion = "2025-11-25",
        startupTimeoutMillis = 10_000,
        toolTimeoutMillis = 120_000,
        enabledTools = listOf("get_restartable_run_configurations", "restart_run_configuration"),
    )
}
