package com.l1yp.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpRequestLogFormatterTest {
    @Test
    fun `describes initialize client info diagnostic header and user agent`() {
        val description = McpRequestLogFormatter.describe(
            requestBody =
                $$"""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"Codex","version":"1.2.3"}}}""",
            diagnosticClientName = "codex",
            userAgent = "Codex-MCP/1.2.3",
        )

        assertTrue(description.startsWith("initialize"))
        assertTrue(description.contains("客户端标记=codex"))
        assertTrue(description.contains("clientInfo=Codex/1.2.3"))
        assertTrue(description.contains("User-Agent=Codex-MCP/1.2.3"))
    }

    @Test
    fun `uses diagnostic header on subsequent tool calls`() {
        val description = McpRequestLogFormatter.describe(
            requestBody =
                """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"git_fetch","arguments":{}}}""",
            diagnosticClientName = "trae",
            userAgent = null,
        )

        assertEquals("tools/call(git_fetch) · 客户端标记=trae", description)
    }

    @Test
    fun `handles malformed identity fields without affecting request processing`() {
        val description = McpRequestLogFormatter.describe(
            requestBody = """{"jsonrpc":"2.0","method":"initialize","params":{"clientInfo":true}}""",
            diagnosticClientName = "  ",
            userAgent = null,
        )

        assertEquals("initialize · 客户端=未知", description)
    }
}
