package com.l1yp.mcp

import kotlin.test.Test
import kotlin.test.assertEquals

class McpLegacySseCodecTest {
    @Test
    fun `encodes the legacy endpoint event`() {
        assertEquals(
            "event: endpoint\n" +
                "data: http://127.0.0.1:63342/api/jetbrains-mcp-tools/message?sessionId=session\n\n",
            McpLegacySseCodec.endpointEvent(
                "http://127.0.0.1:63342/api/jetbrains-mcp-tools/message?sessionId=session",
            ),
        )
    }

    @Test
    fun `encodes every JSON RPC line as SSE data`() {
        assertEquals(
            "event: message\ndata: {\ndata:   \"jsonrpc\": \"2.0\"\ndata: }\n\n",
            McpLegacySseCodec.messageEvent("{\n  \"jsonrpc\": \"2.0\"\n}"),
        )
        assertEquals(": keepalive\n\n", McpLegacySseCodec.heartbeat())
    }
}
