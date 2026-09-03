package com.l1yp.mcp

internal object McpProtocol {
    const val VERSION = "2025-11-25"
    const val ENDPOINT_PATH = "/api/jetbrains-mcp-tools"
    const val SERVER_NAME = "jetbrains-mcp-tools"
    const val INSTRUCTIONS =
        "Call get_restartable_run_configurations before restart_run_configuration, and get_git_repositories before " +
            "get_git_remotes, git_fetch, git_pull, or git_push. Use exact returned names and repository roots. Git " +
            "remote tools use IDE-managed authentication; never request or expose credentials."
}

internal data class McpDispatchResult(
    val httpStatus: Int,
    val responseBody: String? = null,
)

internal object JsonRpcErrorCode {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
}
