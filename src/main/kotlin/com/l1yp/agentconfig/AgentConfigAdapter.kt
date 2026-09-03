package com.l1yp.agentconfig

import com.intellij.openapi.project.Project
import java.nio.file.Path

internal data class McpEndpoint(
    val serverName: String,
    val url: String,
    val authorizationHeader: String,
    val protocolVersion: String,
    val startupTimeoutMillis: Long,
    val toolTimeoutMillis: Long,
    val enabledTools: List<String>,
)

internal enum class SupportLevel {
    STABLE_AUTO_CONFIG,
    EXPERIMENTAL_AUTO_CONFIG,
    MANUAL_INFORMATION_ONLY,
}

internal data class AgentDetection(
    val detected: Boolean,
    val evidence: String? = null,
)

internal data class AgentConfigLocation(
    val path: Path?,
    val description: String,
)

internal sealed interface ConfigChange {
    val adapterId: String

    data class Ready(
        override val adapterId: String,
        val project: Project,
        val path: Path,
        val beforePreview: String,
        val afterPreview: String,
        val originalDigest: String,
        val createdFileByPlugin: Boolean,
        val endpoint: McpEndpoint,
        internal val transform: (String) -> String,
    ) : ConfigChange

    data class Unchanged(
        override val adapterId: String,
        val path: Path,
    ) : ConfigChange

    data class Blocked(
        override val adapterId: String,
        val path: Path?,
        val reason: String,
    ) : ConfigChange
}

internal sealed interface ApplyResult {
    data class Applied(
        val path: Path,
        val changed: Boolean,
        val reloadInstruction: String,
    ) : ApplyResult

    data class Removed(val path: Path, val fileDeleted: Boolean) : ApplyResult

    data class Failed(val path: Path?, val reason: String) : ApplyResult
}

internal interface AgentConfigAdapter {
    val id: String
    val displayName: String
    val supportLevel: SupportLevel

    fun detect(project: Project): AgentDetection

    fun locate(project: Project): AgentConfigLocation

    fun preview(project: Project, endpoint: McpEndpoint): ConfigChange

    fun apply(change: ConfigChange): ApplyResult

    fun remove(project: Project): ApplyResult

    fun reloadInstruction(): String

    fun existingServerNames(project: Project): Set<String> = emptySet()

    fun ownedNodeHash(project: Project, serverName: String): String? = null
}
