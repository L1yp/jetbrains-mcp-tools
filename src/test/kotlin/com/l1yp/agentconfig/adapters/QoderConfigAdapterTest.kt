package com.l1yp.agentconfig.adapters

import com.l1yp.agentconfig.McpEndpoint
import kotlin.test.Test
import kotlin.test.assertEquals

class QoderConfigAdapterTest {
    @Test
    fun `writes Qoder local HTTP schema with a strict tool allowlist`() {
        val node = QoderConfigAdapter.createServerNode(endpoint())

        assertEquals("http", node.get("type").asString)
        assertEquals("Bearer token", node.getAsJsonObject("headers").get("Authorization").asString)
        assertEquals(
            listOf("get_restartable_run_configurations", "restart_run_configuration"),
            node.getAsJsonArray("includeTools").map { it.asString },
        )
        assertEquals(120_000, node.get("timeout").asInt)
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
