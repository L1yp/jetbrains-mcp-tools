package com.l1yp.tool

import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import com.l1yp.mcp.McpTool
import com.l1yp.mcp.McpToolCallResult
import com.l1yp.mcp.McpToolDefinition
import git4idea.commands.GitCommand

internal class GitFetchTool : McpTool {
    override val definition = McpToolDefinition(
        name = "git_fetch",
        description = "Fetches one configured remote through IDEA's Git authentication. It never accepts credentials or arbitrary remote URLs.",
        inputSchema = schema(
            """{
                "type":"object",
                "properties":{
                    "repositoryRoot":{"type":"string","minLength":1},
                    "remote":{"type":"string","minLength":1},
                    "prune":{"type":"boolean","default":false}
                },
                "additionalProperties":false
            }""".trimIndent(),
        ),
        outputSchema = commandOutputSchema(
            "\"remote\":{\"type\":\"string\"},\"pruned\":{\"type\":\"boolean\"}",
            "\"repositoryRoot\",\"remote\",\"pruned\",\"exitCode\",\"messages\"",
        ),
        annotations = schema(
            """{"readOnlyHint":false,"destructiveHint":false,"idempotentHint":true,"openWorldHint":true}""",
        ),
    )

    override fun call(project: Project, arguments: JsonObject): McpToolCallResult {
        GitToolSupport.requireOnly(arguments, setOf("repositoryRoot", "remote", "prune"))
        val repositoryRoot = GitToolSupport.optionalString(arguments, "repositoryRoot")
        val remoteName = GitToolSupport.optionalString(arguments, "remote")
        val prune = GitToolSupport.optionalBoolean(arguments, "prune", false)

        return try {
            GitToolSupport.onPooledThread {
                val repository = GitToolSupport.selectRepository(project, repositoryRoot)
                val remote = GitToolSupport.selectRemote(repository, remoteName)
                val parameters = buildList {
                    if (prune) add("--prune")
                    add(remote.name)
                }
                val result = GitToolSupport.runRemoteCommand(
                    repository,
                    GitCommand.FETCH,
                    remote,
                    parameters,
                )
                if (!result.success()) return@onPooledThread GitToolSupport.commandFailure("git fetch", result)
                GitToolSupport.refresh(repository, refreshWorkingTree = false)
                McpToolCallResult.success(JsonObject().apply {
                    addProperty("repositoryRoot", GitToolSupport.exposedRoot(repository))
                    addProperty("remote", remote.name)
                    addProperty("pruned", prune)
                    GitToolSupport.addCommandResult(this, result)
                })
            }
        } catch (error: GitToolFailure) {
            McpToolCallResult.error(error.message.orEmpty())
        } catch (error: Exception) {
            McpToolCallResult.error("git fetch failed: ${GitToolSupport.safeErrorDetail(error)}")
        }
    }
}
