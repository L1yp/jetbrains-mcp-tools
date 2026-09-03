package com.l1yp.agentconfig.adapters

import com.l1yp.agentconfig.McpEndpoint
import com.l1yp.agentconfig.SupportLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManualAgentCatalogTest {
    @Test
    fun `catalog contains four information-only clients`() {
        assertEquals(
            listOf("minimax_code", "deepseek_harness", "workbuddy", "pi"),
            ManualAgentCatalog.agents.map { it.id },
        )
        assertTrue(ManualAgentCatalog.agents.all { it.supportLevel == SupportLevel.MANUAL_INFORMATION_ONLY })
    }

    @Test
    fun `DeepSeek row uses the official streamable HTTP Cordis client shape`() {
        val row = ManualConfigurationFormatter.deepSeekCordisRow(endpoint())

        assertTrue(row.contains("name: '@deepseek-ai/dsh-mcp-client'"))
        assertTrue(row.contains("transport: streamable-http"))
        assertTrue(row.contains("Authorization: 'Bearer secret-token'"))
    }

    @Test
    fun `WorkBuddy connector references an environment variable instead of embedding the token`() {
        val config = ManualConfigurationFormatter.workBuddyConnectorJson(endpoint())

        assertTrue(config.contains("\"type\": \"streamableHttp\""))
        assertTrue(config.contains($$"Bearer ${MCP_SERVICE_RESTART_TOKEN}"))
        assertFalse(config.contains("secret-token"))
    }

    @Test
    fun `manual preview always redacts an embedded project token`() {
        val definition = ManualAgentCatalog.agents.first { it.id == "deepseek_harness" }

        assertTrue(definition.preview(endpoint()).contains("Bearer <redacted>"))
        assertFalse(definition.preview(endpoint()).contains("secret-token"))
    }

    private fun endpoint() = McpEndpoint(
        serverName = "jetbrains_tools",
        url = "http://127.0.0.1:63342/api/jetbrains-mcp-tools",
        authorizationHeader = "Bearer secret-token",
        protocolVersion = "2025-11-25",
        startupTimeoutMillis = 10_000,
        toolTimeoutMillis = 120_000,
        enabledTools = listOf("get_restartable_run_configurations", "restart_run_configuration"),
    )
}
