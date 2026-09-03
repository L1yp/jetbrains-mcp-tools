package com.l1yp.mcp

internal object McpProtocol {
    const val VERSION = "2025-11-25"
    const val STREAMABLE_HTTP_2025_06_18 = "2025-06-18"
    const val STREAMABLE_HTTP_2025_03_26 = "2025-03-26"
    const val LEGACY_HTTP_SSE_2024_11_05 = "2024-11-05"
    const val ENDPOINT_PATH = "/api/jetbrains-mcp-tools"
    const val LEGACY_SSE_ENDPOINT_PATH = "$ENDPOINT_PATH/sse"
    const val LEGACY_MESSAGE_ENDPOINT_PATH = "$ENDPOINT_PATH/message"
    const val SERVER_NAME = "jetbrains-mcp-tools"
    const val DIAGNOSTIC_CLIENT_HEADER = "X-MCP-Client"
    const val INSTRUCTIONS =
        "Call get_restartable_run_configurations before restart_run_configuration, and get_git_repositories before " +
            "get_git_remotes, git_fetch, git_pull, or git_push. Use exact returned names and repository roots. Git " +
            "remote tools use IDE-managed authentication; never request or expose credentials."

    val STREAMABLE_HTTP_VERSIONS: Set<String> = linkedSetOf(
        VERSION,
        STREAMABLE_HTTP_2025_06_18,
        STREAMABLE_HTTP_2025_03_26,
    )
    val LEGACY_HTTP_SSE_VERSIONS: Set<String> = setOf(LEGACY_HTTP_SSE_2024_11_05)

    fun versionsFor(transport: McpTransport): Set<String> = when (transport) {
        McpTransport.STREAMABLE_HTTP -> STREAMABLE_HTTP_VERSIONS
        McpTransport.LEGACY_HTTP_SSE -> LEGACY_HTTP_SSE_VERSIONS
    }

    fun supportsToolAnnotations(version: String): Boolean = version != LEGACY_HTTP_SSE_2024_11_05

    fun supportsStructuredToolContent(version: String): Boolean =
        version == VERSION || version == STREAMABLE_HTTP_2025_06_18

    fun supportsServerInstructions(version: String): Boolean = version != LEGACY_HTTP_SSE_2024_11_05
}

internal enum class McpTransport(val displayName: String) {
    STREAMABLE_HTTP("Streamable HTTP"),
    LEGACY_HTTP_SSE("legacy HTTP+SSE"),
}

internal data class McpDispatchResult(
    val httpStatus: Int,
    val responseBody: String? = null,
    val negotiatedProtocolVersion: String? = null,
)

internal object JsonRpcErrorCode {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
}
