package com.l1yp.agentconfig.adapters

import com.l1yp.agentconfig.JsoncConfigEditor
import com.l1yp.agentconfig.McpEndpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MiMoCodeConfigAdapterTest {
    @Test
    fun `writes MiMoCode direct mcp schema instead of OpenCode V2 nesting`() {
        val endpoint = endpoint()
        val node = MiMoCodeConfigAdapter.createServerNode(endpoint)
        val source = $$"""{
          "$schema": "https://mimo.xiaomi.com/mimocode/config.json",
          // Existing settings must remain.
          "model": "mimo/default",
        }""".trimIndent()
        val updated = JsoncConfigEditor.upsert(source, listOf("mcp"), endpoint.serverName, node)

        JsoncConfigEditor.validate(updated)
        assertEquals(setOf(endpoint.serverName), JsoncConfigEditor.serverNames(updated, listOf("mcp")))
        assertFalse(updated.contains("\"servers\""))
        assertEquals("remote", node.get("type").asString)
        assertFalse(node.get("oauth").asBoolean)
        assertTrue(node.get("enabled").asBoolean)
        assertEquals(120_000, node.get("timeout").asInt)
        assertFalse(node.has("codemode"))
        assertFalse(node.has("disabled"))
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
