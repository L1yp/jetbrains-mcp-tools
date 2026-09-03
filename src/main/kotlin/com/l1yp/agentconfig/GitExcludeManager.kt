package com.l1yp.agentconfig

import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal enum class GitTrackingStatus {
    NOT_A_REPOSITORY,
    UNTRACKED,
    TRACKED,
    UNKNOWN,
}

internal sealed interface GitProtectionResult {
    data object Protected : GitProtectionResult
    data class Blocked(val reason: String) : GitProtectionResult
}

internal class GitExcludeManager {
    fun trackingStatus(project: Project, configPath: Path): GitTrackingStatus {
        val projectRoot = project.basePath?.let(Path::of)?.toAbsolutePath()?.normalize()
            ?: return GitTrackingStatus.NOT_A_REPOSITORY
        val repositoryRoot = when (val lookup = repositoryRoot(projectRoot)) {
            is RepositoryLookup.Found -> lookup.path
            RepositoryLookup.NotARepository -> return GitTrackingStatus.NOT_A_REPOSITORY
            RepositoryLookup.Unknown -> return GitTrackingStatus.UNKNOWN
        }
        val relative = relativePath(repositoryRoot, configPath) ?: return GitTrackingStatus.UNKNOWN
        val result = runGit(repositoryRoot, "ls-files", "--error-unmatch", "--", relative)
            ?: return GitTrackingStatus.UNKNOWN
        return when (result.exitCode) {
            0 -> GitTrackingStatus.TRACKED
            1 -> GitTrackingStatus.UNTRACKED
            else -> GitTrackingStatus.UNKNOWN
        }
    }

    fun protect(project: Project, configPath: Path): GitProtectionResult {
        val projectRoot = project.basePath?.let(Path::of)?.toAbsolutePath()?.normalize()
            ?: return GitProtectionResult.Protected
        val repositoryRoot = when (val lookup = repositoryRoot(projectRoot)) {
            is RepositoryLookup.Found -> lookup.path
            RepositoryLookup.NotARepository -> return GitProtectionResult.Protected
            RepositoryLookup.Unknown -> return GitProtectionResult.Blocked("Unable to locate the Git repository root")
        }
        val relative = relativePath(repositoryRoot, configPath)
            ?: return GitProtectionResult.Blocked("Configuration path is outside the Git repository")
        when (trackingStatus(project, configPath)) {
            GitTrackingStatus.TRACKED -> return GitProtectionResult.Blocked(
                "Configuration is tracked by Git; automatic writes are disabled",
            )
            GitTrackingStatus.UNKNOWN -> return GitProtectionResult.Blocked(
                "Unable to determine whether the configuration is tracked by Git",
            )
            GitTrackingStatus.NOT_A_REPOSITORY -> return GitProtectionResult.Protected
            GitTrackingStatus.UNTRACKED -> Unit
        }

        val gitPath = runGit(repositoryRoot, "rev-parse", "--path-format=absolute", "--git-path", "info/exclude")
            ?: return GitProtectionResult.Blocked("Unable to locate .git/info/exclude")
        if (gitPath.exitCode != 0) {
            return GitProtectionResult.Blocked("Unable to locate .git/info/exclude")
        }
        val excludePath = Path.of(gitPath.output.trim())
        val pattern = "/${relative.replace('\\', '/')}"
        return runCatching {
            val snapshot = ConfigTextFile.read(excludePath)
            val existingLines = snapshot.content.lineSequence().map(String::trim).toSet()
            if (pattern !in existingLines) {
                ConfigTextFile.writeAtomically(excludePath, snapshot.digest) { current ->
                    current.trimEnd('\r', '\n') + if (current.isBlank()) pattern else "${snapshot.newline}$pattern"
                }.let { result ->
                    if (result !is AtomicWriteResult.Success) {
                        return GitProtectionResult.Blocked("Unable to update .git/info/exclude")
                    }
                }
            }
            GitProtectionResult.Protected
        }.getOrElse { error ->
            GitProtectionResult.Blocked(error.message ?: "Unable to update .git/info/exclude")
        }
    }

    private fun relativePath(root: Path, path: Path): String? {
        val normalized = path.toAbsolutePath().normalize()
        if (!normalized.startsWith(root)) return null
        return root.relativize(normalized).toString()
    }

    private fun repositoryRoot(projectRoot: Path): RepositoryLookup {
        val result = runGit(projectRoot, "rev-parse", "--show-toplevel")
            ?: return if (hasGitMarker(projectRoot)) RepositoryLookup.Unknown else RepositoryLookup.NotARepository
        if (result.exitCode != 0) return RepositoryLookup.NotARepository
        val path = result.output.lineSequence().firstOrNull()?.trim()?.takeIf(String::isNotEmpty)
            ?: return RepositoryLookup.Unknown
        return RepositoryLookup.Found(Path.of(path).toAbsolutePath().normalize())
    }

    private fun hasGitMarker(start: Path): Boolean {
        var current: Path? = start
        while (current != null) {
            if (Files.exists(current.resolve(".git"))) return true
            current = current.parent
        }
        return false
    }

    private fun runGit(root: Path, vararg arguments: String): GitCommandResult? = runCatching {
        val process = ProcessBuilder(listOf("git", "-C", root.toString()) + arguments)
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        GitCommandResult(
            exitCode = process.exitValue(),
            output = process.inputStream.readAllBytes().toString(StandardCharsets.UTF_8),
        )
    }.getOrNull()

    private data class GitCommandResult(val exitCode: Int, val output: String)

    private sealed interface RepositoryLookup {
        data class Found(val path: Path) : RepositoryLookup
        data object NotARepository : RepositoryLookup
        data object Unknown : RepositoryLookup
    }

    private companion object {
        const val GIT_TIMEOUT_SECONDS = 3L
    }
}
