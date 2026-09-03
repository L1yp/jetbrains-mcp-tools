package com.l1yp.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpConfigurationFormatterTest {
    @Test
    fun `generates direct Streamable HTTP Codex configuration`() {
        val configuration = McpConfigurationFormatter.codexConfiguration(
            "Order Service",
            "http://127.0.0.1:63342/api/jetbrains-mcp-tools",
        )

        assertTrue(configuration.contains("[mcp_servers.jetbrains_tools]"))
        assertTrue(configuration.contains("url = \"http://127.0.0.1:63342/api/jetbrains-mcp-tools\""))
        assertTrue(configuration.contains("Authorization = \"Bearer <project-token>\""))
        assertTrue(configuration.contains("restart_run_configuration"))
        assertFalse(configuration.contains("command = \"node\""))
        assertFalse(configuration.contains("C:/Users/"))
    }
}
