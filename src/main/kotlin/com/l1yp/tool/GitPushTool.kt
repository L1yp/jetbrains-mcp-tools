package com.l1yp.tool

import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import com.l1yp.mcp.McpTool
import com.l1yp.mcp.McpToolCallResult
import com.l1yp.mcp.McpToolDefinition
import git4idea.commands.GitCommand

internal class GitPushTool : McpTool {
    override val definition = McpToolDefinition(
        name = "git_push",
        description = "Pushes the current local branch without force through IDEA authentication. It can set upstream for a new branch and never accepts credentials or arbitrary URLs.",
        inputSchema = schema(
            """{
                "type":"object",
                "properties":{
                    "repositoryRoot":{"type":"string","minLength":1},
                    "remote":{"type":"string","minLength":1},
                    "setUpstream":{"type":"boolean","default":false}
                },
                "additionalProperties":false
            }""".trimIndent(),
        ),
        outputSchema = commandOutputSchema(
            "\"remote\":{\"type\":\"string\"},\"localBranch\":{\"type\":\"string\"},\"remoteBranch\":{\"type\":\"string\"},\"upstreamSet\":{\"type\":\"boolean\"}",
            "\"repositoryRoot\",\"remote\",\"localBranch\",\"remoteBranch\",\"upstreamSet\",\"exitCode\",\"messages\"",
        ),
        annotations = schema(
            """{"readOnlyHint":false,"destructiveHint":true,"idempotentHint":false,"openWorldHint":true}""",
        ),
    )

    override fun call(project: Project, arguments: JsonObject): McpToolCallResult {
        GitToolSupport.requireOnly(arguments, setOf("repositoryRoot", "remote", "setUpstream"))
        val repositoryRoot = GitToolSupport.optionalString(arguments, "repositoryRoot")
        val remoteName = GitToolSupport.optionalString(arguments, "remote")
        val setUpstream = GitToolSupport.optionalBoolean(arguments, "setUpstream", false)

        return try {
            GitToolSupport.onPooledThread {
                val repository = GitToolSupport.selectRepository(project, repositoryRoot)
                val currentBranch = repository.currentBranch
                    ?: throw GitToolFailure("Cannot push while ${GitToolSupport.exposedRoot(repository)} has a detached HEAD")
                val remote = GitToolSupport.selectRemote(repository, remoteName)
                val trackInfo = repository.getBranchTrackInfo(currentBranch.name)
                    ?.takeIf { it.remote == remote }
                val remoteBranch = trackInfo?.remoteBranch?.nameForRemoteOperations ?: currentBranch.name
                val refSpec = "refs/heads/${currentBranch.name}:refs/heads/$remoteBranch"
                val parameters = buildList {
                    add("--porcelain")
                    if (setUpstream) add("--set-upstream")
                    add(remote.name)
                    add(refSpec)
                }
                val result = GitToolSupport.runRemoteCommand(
                    repository,
                    GitCommand.PUSH,
                    remote,
                    parameters,
                    push = true,
                )
                if (!result.success()) return@onPooledThread GitToolSupport.commandFailure("git push", result)

                GitToolSupport.refresh(repository, refreshWorkingTree = false)
                McpToolCallResult.success(JsonObject().apply {
                    addProperty("repositoryRoot", GitToolSupport.exposedRoot(repository))
                    addProperty("remote", remote.name)
                    addProperty("localBranch", currentBranch.name)
                    addProperty("remoteBranch", remoteBranch)
                    addProperty("upstreamSet", setUpstream)
                    GitToolSupport.addCommandResult(this, result)
                })
            }
        } catch (error: GitToolFailure) {
            McpToolCallResult.error(error.message.orEmpty())
        } catch (error: Exception) {
            McpToolCallResult.error("git push failed: ${GitToolSupport.safeErrorDetail(error)}")
        }
    }
}

internal fun commandOutputSchema(additionalProperties: String, required: String): JsonObject = schema(
    """{
        "type":"object",
        "properties":{
            "repositoryRoot":{"type":"string"},
            $additionalProperties,
            "exitCode":{"type":"integer"},
            "messages":{"type":"array","items":{"type":"string"}}
        },
        "required":[$required],
        "additionalProperties":false
    }""".trimIndent(),
)
