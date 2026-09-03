package com.l1yp.agentconfig

import com.google.gson.JsonObject
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path

internal abstract class FileAgentConfigAdapter(
    final override val id: String,
    final override val displayName: String,
    final override val supportLevel: SupportLevel,
    private val gitExcludeManager: GitExcludeManager = GitExcludeManager(),
) : AgentConfigAdapter {
    final override fun preview(project: Project, endpoint: McpEndpoint): ConfigChange =
        previewInternal(project, endpoint, enforceOwnership = true)

    internal fun previewAfterUserConfirmation(project: Project, endpoint: McpEndpoint): ConfigChange =
        previewInternal(project, endpoint, enforceOwnership = false)

    private fun previewInternal(
        project: Project,
        endpoint: McpEndpoint,
        enforceOwnership: Boolean,
    ): ConfigChange {
        val ownership = project.service<AgentConfigSettings>().ownership(id)
        val path = ownership?.configPath?.takeIf(String::isNotBlank)?.let(Path::of) ?: locate(project).path
            ?: return ConfigChange.Blocked(id, null, "Project has no local configuration path")
        val tracking = gitExcludeManager.trackingStatus(project, path)
        if (tracking == GitTrackingStatus.TRACKED) {
            return ConfigChange.Blocked(id, path, "Configuration is tracked by Git")
        }
        if (tracking == GitTrackingStatus.UNKNOWN) {
            return ConfigChange.Blocked(id, path, "Unable to determine Git tracking state")
        }

        val snapshot = runCatching { ConfigTextFile.read(path) }.getOrElse { error ->
            return ConfigChange.Blocked(id, path, error.message ?: error.javaClass.simpleName)
        }
        if (ownership != null && enforceOwnership) {
            val currentHash = runCatching { nodeHash(snapshot.content, ownership.serverName) }.getOrElse { error ->
                return ConfigChange.Blocked(id, path, error.message ?: error.javaClass.simpleName)
            }
            if (currentHash != ownership.lastAppliedEndpointHash) {
                return ConfigChange.Blocked(id, path, "Plugin-owned MCP node was modified by the user")
            }
        }
        val previewNodeHash = runCatching { nodeHash(snapshot.content, endpoint.serverName) }.getOrElse { error ->
            return ConfigChange.Blocked(id, path, error.message ?: error.javaClass.simpleName)
        }
        val transform: (String) -> String = { source ->
            require(nodeHash(source, endpoint.serverName) == previewNodeHash) {
                "Plugin-owned MCP node changed concurrently"
            }
            upsert(source, endpoint)
        }
        val after = runCatching { transform(snapshot.content) }.getOrElse { error ->
            return ConfigChange.Blocked(id, path, error.message ?: error.javaClass.simpleName)
        }
        if (ConfigTextFile.isUnchangedAfterStyle(after, snapshot)) return ConfigChange.Unchanged(id, path)
        return ConfigChange.Ready(
            adapterId = id,
            project = project,
            path = path,
            beforePreview = redact(snapshot.content, endpoint),
            afterPreview = redact(after, endpoint),
            originalDigest = snapshot.digest,
            createdFileByPlugin = !snapshot.exists,
            endpoint = endpoint,
            transform = transform,
        )
    }

    final override fun apply(change: ConfigChange): ApplyResult {
        if (change is ConfigChange.Unchanged) {
            return ApplyResult.Applied(change.path, changed = false, reloadInstruction())
        }
        if (change is ConfigChange.Blocked) return ApplyResult.Failed(change.path, change.reason)
        require(change is ConfigChange.Ready && change.adapterId == id) { "Config change belongs to another adapter" }
        when (val protection = gitExcludeManager.protect(change.project, change.path)) {
            is GitProtectionResult.Blocked -> return ApplyResult.Failed(change.path, protection.reason)
            GitProtectionResult.Protected -> Unit
        }
        return when (
            val result = ConfigTextFile.writeAtomically(
                change.path,
                change.originalDigest,
                change.transform,
            )
        ) {
            is AtomicWriteResult.Success -> {
                val finalSnapshot = ConfigTextFile.read(change.path)
                val appliedHash = nodeHash(finalSnapshot.content, change.endpoint.serverName)
                    ?: return ApplyResult.Failed(change.path, "Applied MCP node could not be verified")
                change.project.service<AgentConfigSettings>().recordOwnership(
                    AgentConfigOwnership(
                        adapterId = id,
                        configPath = change.path.toString(),
                        serverName = change.endpoint.serverName,
                        lastAppliedEndpointHash = appliedHash,
                        createdFileByPlugin = change.createdFileByPlugin,
                    ),
                )
                ApplyResult.Applied(change.path, result.changed, reloadInstruction())
            }
            is AtomicWriteResult.Conflict -> ApplyResult.Failed(change.path, result.reason)
            is AtomicWriteResult.Failure -> ApplyResult.Failed(change.path, result.reason)
        }
    }

    final override fun remove(project: Project): ApplyResult {
        val settings = project.service<AgentConfigSettings>()
        val ownership = settings.ownership(id)
            ?: return ApplyResult.Failed(locate(project).path, "No plugin-owned MCP node is recorded")
        val path = Path.of(ownership.configPath)
        when (val protection = gitExcludeManager.protect(project, path)) {
            is GitProtectionResult.Blocked -> return ApplyResult.Failed(path, protection.reason)
            GitProtectionResult.Protected -> Unit
        }
        val snapshot = runCatching { ConfigTextFile.read(path) }.getOrElse { error ->
            return ApplyResult.Failed(path, error.message ?: error.javaClass.simpleName)
        }
        if (!snapshot.exists) {
            settings.removeOwnership(id)
            return ApplyResult.Removed(path, fileDeleted = false)
        }
        val currentHash = runCatching { nodeHash(snapshot.content, ownership.serverName) }.getOrNull()
        if (currentHash != ownership.lastAppliedEndpointHash) {
            return ApplyResult.Failed(path, "Plugin-owned MCP node was modified by the user")
        }
        val updated = runCatching { removeNode(snapshot.content, ownership.serverName) }.getOrElse { error ->
            return ApplyResult.Failed(path, error.message ?: error.javaClass.simpleName)
        }
        if (ownership.createdFileByPlugin && isEffectivelyEmpty(updated)) {
            return when (val result = ConfigTextFile.deleteIfUnchanged(path, snapshot.digest)) {
                is AtomicWriteResult.Success -> {
                    settings.removeOwnership(id)
                    ApplyResult.Removed(path, fileDeleted = result.changed)
                }
                is AtomicWriteResult.Conflict -> ApplyResult.Failed(path, result.reason)
                is AtomicWriteResult.Failure -> ApplyResult.Failed(path, result.reason)
            }
        }
        return when (
            val result = ConfigTextFile.writeAtomically(path, snapshot.digest) { current ->
                require(nodeHash(current, ownership.serverName) == ownership.lastAppliedEndpointHash) {
                    "Plugin-owned MCP node changed concurrently"
                }
                removeNode(current, ownership.serverName)
            }
        ) {
            is AtomicWriteResult.Success -> {
                settings.removeOwnership(id)
                ApplyResult.Removed(path, fileDeleted = false)
            }
            is AtomicWriteResult.Conflict -> ApplyResult.Failed(path, result.reason)
            is AtomicWriteResult.Failure -> ApplyResult.Failed(path, result.reason)
        }
    }

    final override fun existingServerNames(project: Project): Set<String> {
        val path = locate(project).path ?: return emptySet()
        if (!Files.exists(path)) return emptySet()
        return serverNames(ConfigTextFile.read(path).content)
    }

    final override fun ownedNodeHash(project: Project, serverName: String): String? {
        val path = locate(project).path ?: return null
        if (!Files.exists(path)) return null
        return nodeHash(ConfigTextFile.read(path).content, serverName)
    }

    protected abstract fun upsert(source: String, endpoint: McpEndpoint): String

    protected abstract fun removeNode(source: String, serverName: String): String

    protected abstract fun serverNames(source: String): Set<String>

    protected abstract fun nodeHash(source: String, serverName: String): String?

    protected abstract fun isEffectivelyEmpty(source: String): Boolean

    private fun redact(source: String, endpoint: McpEndpoint): String =
        source.replace(endpoint.authorizationHeader, "Bearer <redacted>")
}

internal abstract class JsonFileAgentConfigAdapter(
    id: String,
    displayName: String,
    supportLevel: SupportLevel = SupportLevel.STABLE_AUTO_CONFIG,
    private val relativePaths: List<String>,
    private val defaultRelativePath: String = relativePaths.first(),
    private val objectPath: List<String>,
) : FileAgentConfigAdapter(id, displayName, supportLevel) {
    init {
        require(defaultRelativePath in relativePaths)
    }

    final override fun detect(project: Project): AgentDetection {
        val root = project.basePath?.let(Path::of) ?: return AgentDetection(false)
        val resolvedPaths = relativePaths.map(root::resolve)
        val evidence = resolvedPaths.firstOrNull(Files::exists)
            ?: resolvedPaths.map(Path::getParent).distinct().firstOrNull { it != root && Files.exists(it) }
        return AgentDetection(evidence != null, evidence?.toString())
    }

    final override fun locate(project: Project): AgentConfigLocation {
        val root = project.basePath?.let(Path::of)
            ?: return AgentConfigLocation(null, defaultRelativePath)
        val existing = relativePaths.map(root::resolve).firstOrNull(Files::exists)
        val path = existing ?: root.resolve(defaultRelativePath)
        return AgentConfigLocation(path, root.relativize(path).toString().replace('\\', '/'))
    }

    protected abstract fun serverNode(endpoint: McpEndpoint): JsonObject

    final override fun upsert(source: String, endpoint: McpEndpoint): String = if (looksLikeJsonc(source)) {
        JsoncConfigEditor.upsert(source, objectPath, endpoint.serverName, serverNode(endpoint))
    } else {
        JsonConfigEditor.upsert(source, objectPath, endpoint.serverName, serverNode(endpoint))
    }

    final override fun removeNode(source: String, serverName: String): String = if (looksLikeJsonc(source)) {
        JsoncConfigEditor.remove(source, objectPath, serverName)
    } else {
        JsonConfigEditor.remove(source, objectPath, serverName)
    }

    final override fun serverNames(source: String): Set<String> = if (looksLikeJsonc(source)) {
        JsoncConfigEditor.serverNames(source, objectPath)
    } else {
        runCatching { JsonConfigEditor.serverNames(source, objectPath) }.getOrDefault(emptySet())
    }

    final override fun nodeHash(source: String, serverName: String): String? = if (looksLikeJsonc(source)) {
        JsoncConfigEditor.nodeHash(source, objectPath, serverName)
    } else {
        runCatching { JsonConfigEditor.nodeHash(source, objectPath, serverName) }.getOrNull()
    }

    final override fun isEffectivelyEmpty(source: String): Boolean = if (looksLikeJsonc(source)) {
        JsoncConfigEditor.isEffectivelyEmpty(source)
    } else {
        JsonConfigEditor.isEffectivelyEmpty(source)
    }

    private fun looksLikeJsonc(source: String): Boolean = source.contains("//") || source.contains("/*") ||
        Regex(""",\s*[}\]]""").containsMatchIn(source)
}
