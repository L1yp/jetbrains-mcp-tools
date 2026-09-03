package com.l1yp.mcp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpJsonRpcDispatcherTest {
    private val dispatcher = McpJsonRpcDispatcher(serverVersion = "9.8.7")

    @Test
    fun `negotiates only the 2025-11-25 protocol`() {
        val result = dispatcher.dispatch(
            requestBody = request(1, "initialize", """{"protocolVersion":"2025-11-25"}"""),
            protocolVersionHeader = null,
            project = null,
        )

        assertEquals(200, result.httpStatus)
        val payload = result.payload()
        val initializeResult = payload.getAsJsonObject("result")
        assertEquals("2025-11-25", initializeResult.get("protocolVersion").asString)
        assertEquals("jetbrains-mcp-tools", initializeResult.getAsJsonObject("serverInfo").get("name").asString)
        assertEquals("9.8.7", initializeResult.getAsJsonObject("serverInfo").get("version").asString)
        assertFalse(
            initializeResult.getAsJsonObject("capabilities")
                .getAsJsonObject("tools")
                .get("listChanged")
                .asBoolean,
        )
    }

    @Test
    fun `rejects unsupported initialize versions with HTTP 400`() {
        val result = dispatcher.dispatch(
            requestBody = request("init", "initialize", """{"protocolVersion":"2024-11-05"}"""),
            protocolVersionHeader = null,
            project = null,
        )

        assertEquals(400, result.httpStatus)
        assertEquals(JsonRpcErrorCode.INVALID_PARAMS, result.errorCode())
        assertTrue(result.payload().getAsJsonObject("error").get("message").asString.contains("2025-11-25"))
    }

    @Test
    fun `requires protocol header after initialization`() {
        val missing = dispatcher.dispatch(request(1, "ping"), null, null)
        val wrong = dispatcher.dispatch(request(1, "ping"), "2024-11-05", null)
        val valid = dispatcher.dispatch(request(1, "ping"), McpProtocol.VERSION, null)

        assertEquals(400, missing.httpStatus)
        assertEquals(400, wrong.httpStatus)
        assertEquals(200, valid.httpStatus)
        assertEquals(JsonObject(), valid.payload().getAsJsonObject("result"))
    }

    @Test
    fun `returns parse and invalid request errors`() {
        val malformed = dispatcher.dispatch("{", null, null)
        val batch = dispatcher.dispatch("[]", null, null)
        val booleanId = dispatcher.dispatch(
            """{"jsonrpc":"2.0","id":true,"method":"initialize","params":{"protocolVersion":"2025-11-25"}}""",
            null,
            null,
        )

        assertEquals(JsonRpcErrorCode.PARSE_ERROR, malformed.errorCode())
        assertEquals(JsonRpcErrorCode.INVALID_REQUEST, batch.errorCode())
        assertEquals(JsonRpcErrorCode.INVALID_REQUEST, booleanId.errorCode())
    }

    @Test
    fun `accepts string and numeric ids`() {
        val numeric = dispatcher.dispatch(
            request(42, "initialize", """{"protocolVersion":"2025-11-25"}"""),
            null,
            null,
        )
        val string = dispatcher.dispatch(
            request("abc", "initialize", """{"protocolVersion":"2025-11-25"}"""),
            null,
            null,
        )

        assertEquals(42, numeric.payload().get("id").asInt)
        assertEquals("abc", string.payload().get("id").asString)
    }

    @Test
    fun `notifications return 202 without a body`() {
        val initialized = dispatcher.dispatch(
            """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
            McpProtocol.VERSION,
            null,
        )
        val cancelled = dispatcher.dispatch(
            """{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":1}}""",
            McpProtocol.VERSION,
            null,
        )

        assertEquals(202, initialized.httpStatus)
        assertNull(initialized.responseBody)
        assertEquals(202, cancelled.httpStatus)
        assertNull(cancelled.responseBody)
    }

    @Test
    fun `notifications still require the negotiated protocol header`() {
        val missingHeader = dispatcher.dispatch(
            """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
            null,
            null,
        )

        assertEquals(400, missingHeader.httpStatus)
        assertEquals(JsonRpcErrorCode.INVALID_REQUEST, missingHeader.errorCode())
    }

    @Test
    fun `lists all tools with complete contracts`() {
        val result = dispatcher.dispatch(request(7, "tools/list"), McpProtocol.VERSION, null)

        val tools = result.payload().getAsJsonObject("result").getAsJsonArray("tools")
        assertEquals(
            listOf(
                "get_restartable_run_configurations",
                "restart_run_configuration",
                "get_git_repositories",
                "git_fetch",
                "git_pull",
                "git_push",
            ),
            tools.map { it.asJsonObject.get("name").asString },
        )
        tools.forEach { tool ->
            assertTrue(tool.asJsonObject.has("inputSchema"))
            assertTrue(tool.asJsonObject.has("outputSchema"))
            assertTrue(tool.asJsonObject.has("annotations"))
        }
    }

    @Test
    fun `returns method not found and invalid params as JSON-RPC errors`() {
        val unknown = dispatcher.dispatch(request(1, "resources/list"), McpProtocol.VERSION, null)
        val invalid = dispatcher.dispatch(
            request(1, "tools/list", """{"unexpected":true}"""),
            McpProtocol.VERSION,
            null,
        )

        assertEquals(200, unknown.httpStatus)
        assertEquals(JsonRpcErrorCode.METHOD_NOT_FOUND, unknown.errorCode())
        assertEquals(JsonRpcErrorCode.INVALID_PARAMS, invalid.errorCode())
    }

    @Test
    fun `tool calls return text and structured content`() {
        val output = JsonObject().apply { addProperty("value", "ok") }
        val tool = fakeTool("example") { McpToolCallResult.success(output) }
        val callDispatcher = McpJsonRpcDispatcher("1", ToolRegistry(listOf(tool)))

        val result = callDispatcher.dispatch(
            request(9, "tools/call", """{"name":"example","arguments":{}}"""),
            McpProtocol.VERSION,
            fakeProject(),
        )

        val callResult = result.payload().getAsJsonObject("result")
        assertFalse(callResult.get("isError").asBoolean)
        assertEquals("ok", callResult.getAsJsonObject("structuredContent").get("value").asString)
        assertEquals(
            output.toString(),
            callResult.getAsJsonArray("content").first().asJsonObject.get("text").asString,
        )
    }

    @Test
    fun `tool business failures use isError instead of protocol errors`() {
        val tool = fakeTool("example") { McpToolCallResult.error("configuration was not found") }
        val callDispatcher = McpJsonRpcDispatcher("1", ToolRegistry(listOf(tool)))

        val result = callDispatcher.dispatch(
            request(9, "tools/call", """{"name":"example","arguments":{}}"""),
            McpProtocol.VERSION,
            fakeProject(),
        )

        val payload = result.payload()
        assertFalse(payload.has("error"))
        assertTrue(payload.getAsJsonObject("result").get("isError").asBoolean)
    }

    @Test
    fun `malformed tool arguments return invalid params`() {
        val result = dispatcher.dispatch(
            request(
                9,
                "tools/call",
                """{"name":"restart_run_configuration","arguments":{}}""",
            ),
            McpProtocol.VERSION,
            fakeProject(),
        )

        assertEquals(JsonRpcErrorCode.INVALID_PARAMS, result.errorCode())
    }

    private fun request(id: Any, method: String, params: String? = null): String {
        val encodedId = when (id) {
            is Number -> id.toString()
            else -> "\"$id\""
        }
        return buildString {
            append("{\"jsonrpc\":\"2.0\",\"id\":")
            append(encodedId)
            append(",\"method\":\"")
            append(method)
            append('"')
            params?.let {
                append(",\"params\":")
                append(it)
            }
            append('}')
        }
    }

    private fun McpDispatchResult.payload(): JsonObject =
        JsonParser.parseString(requireNotNull(responseBody)).asJsonObject

    private fun McpDispatchResult.errorCode(): Int = payload().getAsJsonObject("error").get("code").asInt

    private fun fakeTool(name: String, result: () -> McpToolCallResult): McpTool = object : McpTool {
        override val definition = McpToolDefinition(
            name = name,
            description = "test",
            inputSchema = JsonObject(),
            outputSchema = JsonObject(),
            annotations = JsonObject(),
        )

        override fun call(project: Project, arguments: JsonObject): McpToolCallResult = result()
    }

    private fun fakeProject(): Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { _, method, _ -> error("Unexpected Project method: ${method.name}") } as Project
}
