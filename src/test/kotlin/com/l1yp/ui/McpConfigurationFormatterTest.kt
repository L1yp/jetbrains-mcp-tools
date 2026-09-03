package com.l1yp.ui

import com.l1yp.mcp.ToolRegistry
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
        assertTrue(configuration.contains("\"X-MCP-Client\" = \"codex\""))
        assertTrue(configuration.contains("restart_run_configuration"))
        assertTrue(configuration.contains("get_git_repositories"))
        assertTrue(configuration.contains("get_git_remotes"))
        assertTrue(configuration.contains("git_fetch"))
        assertTrue(configuration.contains("git_pull"))
        assertTrue(configuration.contains("git_push"))
        assertFalse(configuration.contains("command = \"node\""))
        assertFalse(configuration.contains("C:/Users/"))
    }

    @Test
    fun `generates an allowlist from enabled tool definitions`() {
        val enabledDefinition = ToolRegistry.DEFAULT.definitions.single { it.name == "git_fetch" }

        val configuration = McpConfigurationFormatter.codexConfiguration(
            "Order Service",
            "http://127.0.0.1:63342/api/jetbrains-mcp-tools",
            listOf(enabledDefinition),
        )

        assertTrue(configuration.contains("enabled_tools = [\"git_fetch\"]"))
        assertFalse(configuration.contains("restart_run_configuration"))
    }

    @Test
    fun `shows the protocol compatibility matrix and legacy SSE endpoint`() {
        val details = McpConfigurationFormatter.httpDetails(
            "http://127.0.0.1:63342/api/jetbrains-mcp-tools",
        )

        assertTrue(details.contains("2025-11-25, 2025-06-18, 2025-03-26"))
        assertTrue(details.contains("/api/jetbrains-mcp-tools/sse"))
        assertTrue(details.contains("2024-11-05"))
    }
}
