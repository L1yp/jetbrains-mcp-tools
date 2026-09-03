package com.l1yp.agentconfig.adapters

import com.l1yp.agentconfig.McpEndpoint
import com.l1yp.agentconfig.TomlConfigEditor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodexConfigAdapterTest {
    @Test
    fun `writes Codex URL headers tools and second based timeouts`() {
        val body = CodexConfigAdapter.tableBody(endpoint())

        assertTrue(body.contains("url = \"http://127.0.0.1:63342/api/jetbrains-mcp-tools\""))
        assertTrue(body.contains("http_headers = { Authorization = \"Bearer token\" }"))
        assertTrue(body.contains("enabled_tools = [\"get_restartable_run_configurations\", \"restart_run_configuration\"]"))
        assertTrue(body.contains("startup_timeout_sec = 10"))
        assertTrue(body.contains("tool_timeout_sec = 120"))
    }

    @Test
    fun `managed Codex table preserves unrelated TOML`() {
        val source = """
            model = "gpt-5"

            [mcp_servers.other]
            command = "other"
        """.trimIndent()
        val updated = TomlConfigEditor.upsertManagedTable(
            source,
            "mcp_servers.jetbrains_tools",
            CodexConfigAdapter.tableBody(endpoint()),
        )

        TomlConfigEditor.validate(updated)
        assertEquals(
            setOf("other", "jetbrains_tools"),
            TomlConfigEditor.tableNames(updated, "mcp_servers."),
        )
        assertTrue(updated.contains("model = \"gpt-5\""))
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
