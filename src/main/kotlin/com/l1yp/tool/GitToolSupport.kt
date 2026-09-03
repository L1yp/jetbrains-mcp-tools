package com.l1yp.tool

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import com.l1yp.mcp.McpToolCallResult
import git4idea.GitUtil
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitCommandResult
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRemote
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import java.util.concurrent.ExecutionException

internal object GitToolSupport {
    private const val COMMAND_TIMEOUT_MILLIS = 110_000
    private const val MAX_OUTPUT_LINES = 200
    private const val MAX_OUTPUT_LINE_LENGTH = 2_000

    fun repositories(project: Project): List<GitRepository> =
        GitRepositoryManager.getInstance(project).repositories.sortedBy { exposedRoot(it) }

    fun selectRepository(project: Project, requestedRoot: String?): GitRepository {
        val repositories = repositories(project)
        if (repositories.isEmpty()) {
            throw GitToolFailure("The current IDEA project has no Git repositories")
        }
        if (requestedRoot == null) {
            if (repositories.size == 1) return repositories.single()
            throw GitToolFailure(
                "repositoryRoot is required because this project has multiple Git repositories: " +
                    repositories.joinToString { exposedRoot(it) },
            )
        }

        val normalizedRoot = normalizeRoot(requestedRoot)
        return repositories.firstOrNull { normalizeRoot(exposedRoot(it)) == normalizedRoot }
            ?: throw GitToolFailure(
                "Git repository '$requestedRoot' is not managed by the current IDEA project; call get_git_repositories",
            )
    }

    fun selectRemote(repository: GitRepository, requestedName: String?): GitRemote {
        val remotes = repository.remotes.sortedBy(GitRemote::getName)
        if (requestedName != null) {
            return remotes.firstOrNull { it.name == requestedName }
                ?: throw GitToolFailure(
                    "Remote '$requestedName' was not found in ${exposedRoot(repository)}; available remotes: " +
                        remotes.joinToString { it.name },
                )
        }

        val trackedRemote = repository.currentBranch
            ?.let { repository.getBranchTrackInfo(it.name) }
            ?.remote
        return trackedRemote
            ?: remotes.firstOrNull { it.name == GitRemote.ORIGIN }
            ?: remotes.singleOrNull()
            ?: throw GitToolFailure(
                if (remotes.isEmpty()) {
                    "Git repository ${exposedRoot(repository)} has no remotes"
                } else {
                    "remote is required; available remotes: ${remotes.joinToString { it.name }}"
                },
            )
    }

    fun runRemoteCommand(
        repository: GitRepository,
        command: GitCommand,
        remote: GitRemote,
        parameters: List<String>,
        push: Boolean = false,
    ): GitCommandResult {
        val urls = if (push) remote.pushUrls.ifEmpty { remote.urls } else remote.urls
        val handler = GitLineHandler(repository.project, repository.root, command).apply {
            setUrls(urls)
            setSilent(false)
            setStdoutSuppressed(false)
            setStderrSuppressed(false)
            setTerminationTimeout(COMMAND_TIMEOUT_MILLIS)
            addParameters(parameters)
        }
        return Git.getInstance().runCommand(handler)
    }

    fun commandFailure(operation: String, result: GitCommandResult): McpToolCallResult {
        val detail = when {
            result.isAuthenticationFailed ->
                "IDE-managed Git authentication failed or was cancelled; verify the saved credential or SSH key passphrase in IDEA"
            else -> messages(result).firstOrNull() ?: "Git exited with code ${result.exitCode}"
        }
        return McpToolCallResult.error("$operation failed: $detail")
    }

    fun addCommandResult(target: JsonObject, result: GitCommandResult) {
        target.addProperty("exitCode", result.exitCode)
        target.add("messages", JsonArray().apply { messages(result).forEach(::add) })
    }

    fun refresh(repository: GitRepository, refreshWorkingTree: Boolean) {
        GitUtil.updateRepositories(listOf(repository))
        if (refreshWorkingTree) GitUtil.refreshVfsInRoot(repository.root)
    }

    fun head(repository: GitRepository): String? = GitUtil.getHead(repository)?.asString()

    fun safeErrorDetail(error: Exception): String =
        redactCredentials(error.message ?: error.javaClass.simpleName).take(MAX_OUTPUT_LINE_LENGTH)

    fun exposedRoot(repository: GitRepository): String = normalizeRoot(repository.root.path)

    fun optionalString(arguments: JsonObject, name: String): String? {
        if (!arguments.has(name)) return null
        val value = arguments.get(name)
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
            ?.trim()
            .orEmpty()
        require(value.isNotEmpty()) { "$name must not be blank" }
        return value
    }

    fun optionalBoolean(arguments: JsonObject, name: String, default: Boolean): Boolean {
        if (!arguments.has(name)) return default
        return arguments.get(name)
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
            ?.asBoolean
            ?: throw IllegalArgumentException("$name must be a boolean")
    }

    fun requireOnly(arguments: JsonObject, allowed: Set<String>) {
        require(arguments.keySet().all(allowed::contains)) { "Unknown argument" }
    }

    fun <T> onPooledThread(action: () -> T): T {
        val application = ApplicationManager.getApplication()
        if (application.isUnitTestMode) return action()
        return try {
            AppExecutorUtil.getAppExecutorService().submit<T> { action() }.get()
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }

    private fun messages(result: GitCommandResult): List<String> =
        (result.output + result.errorOutput)
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map(::redactCredentials)
            .map { it.take(MAX_OUTPUT_LINE_LENGTH) }
            .distinct()
            .take(MAX_OUTPUT_LINES)
            .toList()

    fun redactCredentials(value: String): String = URL_USER_INFO.replace(value, "$1***@")

    private fun normalizeRoot(value: String): String = value.replace('\\', '/').trimEnd('/')

    private val URL_USER_INFO = Regex("([A-Za-z][A-Za-z0-9+.-]*://)[^/@\\s]+@")
}

internal class GitToolFailure(message: String) : RuntimeException(message)
