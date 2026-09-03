package com.l1yp.agentconfig.adapters

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.l1yp.agentconfig.McpEndpoint
import com.l1yp.agentconfig.SupportLevel
import com.l1yp.mcp.McpProtocol

internal data class ManualAgentDefinition(
    val id: String,
    val displayName: String,
    val reason: String,
    val documentationUrl: String?,
    val reloadInstruction: String,
    val configuration: (McpEndpoint) -> String,
) {
    val supportLevel: SupportLevel = SupportLevel.MANUAL_INFORMATION_ONLY

    fun preview(endpoint: McpEndpoint): String = configuration(endpoint)
        .replace(endpoint.authorizationHeader, "Bearer <redacted>")
}

internal object ManualAgentCatalog {
    val agents: List<ManualAgentDefinition> = listOf(
        ManualAgentDefinition(
            id = "minimax_code",
            displayName = "MiniMax Code",
            reason = "配置路径和第三方 MCP 工具注入行为仍在变化，第一版不猜测私有配置路径。",
            documentationUrl = null,
            reloadInstruction = "按当前客户端版本手动重载 MCP 或新建会话。",
            configuration = ManualConfigurationFormatter::genericMcpServersJson,
        ),
        ManualAgentDefinition(
            id = "deepseek_harness",
            displayName = "DeepSeek Harness",
            reason = "MCP Client 由进程级 Cordis composition/profile patch 加载，缺少稳定的 workspace 自动配置契约。",
            documentationUrl =
                "https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/mcp/mcp-client/README.md",
            reloadInstruction = "把 row 合并到选定 profile patch 后重启或热重载 Harness。",
            configuration = ManualConfigurationFormatter::deepSeekCordisRow,
        ),
        ManualAgentDefinition(
            id = "workbuddy",
            displayName = "WorkBuddy",
            reason = "公开契约面向 Connector 包，不是项目目录自动发现；插件不会代替用户安装 Connector。",
            documentationUrl = "https://open.workbuddy.cn/en/docs/connector",
            reloadInstruction = "导入或更新 Connector 后按 WorkBuddy 提示重新加载。",
            configuration = ManualConfigurationFormatter::workBuddyConnectorJson,
        ),
        ManualAgentDefinition(
            id = "pi",
            displayName = "Pi",
            reason = "当前版本尚未确认稳定的原生 MCP 配置入口。",
            documentationUrl = null,
            reloadInstruction = "当前版本未验证自动配置，请按所用 Pi 版本文档手动配置。",
            configuration = ManualConfigurationFormatter::genericMcpServersJson,
        ),
    )
}

internal object ManualConfigurationFormatter {
    private val gson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()

    fun headerJson(endpoint: McpEndpoint): String = gson.toJson(endpoint.headers())

    fun genericMcpServersJson(endpoint: McpEndpoint): String = gson.toJson(JsonObject().apply {
        add("mcpServers", JsonObject().apply {
            add(endpoint.serverName, JsonObject().apply {
                addProperty("type", "http")
                addProperty("url", endpoint.url)
                add("headers", endpoint.headers())
                add("enabledTools", JsonArray().also { tools -> endpoint.enabledTools.forEach(tools::add) })
            })
        })
    })

    fun deepSeekCordisRow(endpoint: McpEndpoint): String = """
        - insert:
            - id: mcp-${endpoint.serverName}
              name: '@deepseek-ai/dsh-mcp-client'
              config:
                serverName: '${endpoint.serverName}'
                transport: streamable-http
                url: '${endpoint.url}'
                headers:
                  Authorization: '${endpoint.authorizationHeader}'
                  ${McpProtocol.DIAGNOSTIC_CLIENT_HEADER}: '${endpoint.diagnosticClientName}'
                failOnStartupError: true
    """.trimIndent()

    fun workBuddyConnectorJson(endpoint: McpEndpoint): String = $$"""
        {
          "mcpServers": {
            "$${endpoint.serverName}": {
              "type": "streamableHttp",
              "url": "$${endpoint.url}",
              "headers": {
                "Authorization": "Bearer ${MCP_SERVICE_RESTART_TOKEN}",
                "$${McpProtocol.DIAGNOSTIC_CLIENT_HEADER}": "$${endpoint.diagnosticClientName}"
              },
              "timeout": 30000
            }
          }
        }
    """.trimIndent()

    fun toolsListCommand(endpoint: McpEndpoint): String = """
        curl -X POST '${endpoint.url}' \
          -H 'Authorization: ${endpoint.authorizationHeader}' \
          -H '${McpProtocol.DIAGNOSTIC_CLIENT_HEADER}: ${endpoint.diagnosticClientName}' \
          -H 'Content-Type: application/json' \
          -H 'Accept: application/json, text/event-stream' \
          -H 'MCP-Protocol-Version: ${endpoint.protocolVersion}' \
          --data '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'
    """.trimIndent()
}
