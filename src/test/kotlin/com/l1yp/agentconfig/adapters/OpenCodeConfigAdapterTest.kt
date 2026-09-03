package com.l1yp.agentconfig.adapters

import com.l1yp.agentconfig.JsoncConfigEditor
import com.l1yp.agentconfig.McpEndpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class OpenCodeConfigAdapterTest {
    @Test
    fun `writes OpenCode V2 remote schema and preserves JSONC`() {
        val endpoint = endpoint()
        val node = OpenCodeConfigAdapter.createServerNode(endpoint)
        val source = $$"""{
          // keep schema and model
          "$schema": "https://opencode.ai/config.json",
          "model": "openai/gpt-5",
        }""".trimIndent()
        val updated = JsoncConfigEditor.upsert(
            source,
            listOf("mcp", "servers"),
            endpoint.serverName,
            node,
        )

        JsoncConfigEditor.validate(updated)
        assertFalse(node.get("oauth").asBoolean)
        assertFalse(node.get("codemode").asBoolean)
        assertFalse(node.get("disabled").asBoolean)
        assertFalse(node.has("enabled"))
        assertEquals(10_000, node.getAsJsonObject("timeout").get("startup").asInt)
        assertEquals(10_000, node.getAsJsonObject("timeout").get("catalog").asInt)
        assertEquals(120_000, node.getAsJsonObject("timeout").get("execution").asInt)
        assertEquals(setOf(endpoint.serverName), JsoncConfigEditor.serverNames(updated, listOf("mcp", "servers")))
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
