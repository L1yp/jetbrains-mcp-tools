package com.l1yp.agentconfig.adapters

import com.intellij.openapi.project.Project
import com.l1yp.agentconfig.AgentConfigLocation
import com.l1yp.agentconfig.AgentDetection
import com.l1yp.agentconfig.FileAgentConfigAdapter
import com.l1yp.agentconfig.McpEndpoint
import com.l1yp.agentconfig.SupportLevel
import com.l1yp.agentconfig.TomlConfigEditor
import com.l1yp.mcp.McpProtocol
import java.nio.file.Files
import java.nio.file.Path

internal class CodexConfigAdapter : FileAgentConfigAdapter(
    id = ID,
    displayName = "Codex",
    supportLevel = SupportLevel.STABLE_AUTO_CONFIG,
) {
    override fun detect(project: Project): AgentDetection {
        val path = path(project) ?: return AgentDetection(false)
        val evidence = when {
            Files.exists(path) -> path
            Files.exists(path.parent) -> path.parent
            else -> null
        }
        return AgentDetection(evidence != null, evidence?.toString())
    }

    override fun locate(project: Project): AgentConfigLocation = AgentConfigLocation(
        path = path(project),
        description = ".codex/config.toml",
    )

    override fun reloadInstruction(): String = "请确认项目已信任，再新建 Codex 任务或重启 Codex。"

    override fun upsert(source: String, endpoint: McpEndpoint): String =
        TomlConfigEditor.upsertManagedTable(source, tableName(endpoint.serverName), tableBody(endpoint))

    override fun removeNode(source: String, serverName: String): String =
        TomlConfigEditor.removeManagedTable(source, tableName(serverName))

    override fun serverNames(source: String): Set<String> =
        TomlConfigEditor.tableNames(source, TABLE_PREFIX)

    override fun nodeHash(source: String, serverName: String): String? =
        TomlConfigEditor.tableBodyHash(source, tableName(serverName))

    override fun isEffectivelyEmpty(source: String): Boolean = source.isBlank()

    private fun path(project: Project): Path? = project.basePath?.let(Path::of)?.resolve(".codex/config.toml")

    companion object {
        const val ID = "codex"
        private const val TABLE_PREFIX = "mcp_servers."

        internal fun tableBody(endpoint: McpEndpoint): String {
            val enabledTools = endpoint.enabledTools.joinToString(", ") { "\"${tomlEscape(it)}\"" }
            return """
                url = "${tomlEscape(endpoint.url)}"
                http_headers = { Authorization = "${tomlEscape(endpoint.authorizationHeader)}", "${McpProtocol.DIAGNOSTIC_CLIENT_HEADER}" = "${tomlEscape(endpoint.diagnosticClientName)}" }
                enabled_tools = [$enabledTools]
                startup_timeout_sec = ${toSeconds(endpoint.startupTimeoutMillis)}
                tool_timeout_sec = ${toSeconds(endpoint.toolTimeoutMillis)}
            """.trimIndent()
        }

        private fun tableName(serverName: String): String = TABLE_PREFIX + if (SAFE_KEY.matches(serverName)) {
            serverName
        } else {
            "\"${tomlEscape(serverName)}\""
        }

        private fun toSeconds(milliseconds: Long): Long = (milliseconds + 999) / 1000

        private fun tomlEscape(value: String): String = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

        private val SAFE_KEY = Regex("[A-Za-z0-9_-]+")
    }
}
