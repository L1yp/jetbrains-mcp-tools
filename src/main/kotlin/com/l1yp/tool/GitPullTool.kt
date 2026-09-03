package com.l1yp.tool

import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import com.l1yp.mcp.McpTool
import com.l1yp.mcp.McpToolCallResult
import com.l1yp.mcp.McpToolDefinition
import git4idea.commands.GitCommand

internal class GitPullTool : McpTool {
    override val definition = McpToolDefinition(
        name = "git_pull",
        description = "Pulls the current branch from its configured upstream through IDEA authentication. Defaults to fast-forward-only; rebase or merge must be explicitly selected.",
        inputSchema = schema(
            """{
                "type":"object",
                "properties":{
                    "repositoryRoot":{"type":"string","minLength":1},
                    "strategy":{"type":"string","enum":["ff_only","rebase","merge"],"default":"ff_only"}
                },
                "additionalProperties":false
            }""".trimIndent(),
        ),
        outputSchema = commandOutputSchema(
            "\"remote\":{\"type\":\"string\"},\"branch\":{\"type\":\"string\"},\"strategy\":{\"type\":\"string\"},\"beforeHead\":{\"type\":\"string\"},\"afterHead\":{\"type\":\"string\"},\"changed\":{\"type\":\"boolean\"}",
            "\"repositoryRoot\",\"remote\",\"branch\",\"strategy\",\"beforeHead\",\"afterHead\",\"changed\",\"exitCode\",\"messages\"",
        ),
        annotations = schema(
            """{"readOnlyHint":false,"destructiveHint":true,"idempotentHint":false,"openWorldHint":true}""",
        ),
    )

    override fun call(project: Project, arguments: JsonObject): McpToolCallResult {
        GitToolSupport.requireOnly(arguments, setOf("repositoryRoot", "strategy"))
        val repositoryRoot = GitToolSupport.optionalString(arguments, "repositoryRoot")
        val strategy = GitToolSupport.optionalString(arguments, "strategy") ?: "ff_only"
        require(strategy in STRATEGIES) { "strategy must be ff_only, rebase, or merge" }

        return try {
            GitToolSupport.onPooledThread {
                val repository = GitToolSupport.selectRepository(project, repositoryRoot)
                val currentBranch = repository.currentBranch
                    ?: throw GitToolFailure("Cannot pull while ${GitToolSupport.exposedRoot(repository)} has a detached HEAD")
                val trackInfo = repository.getBranchTrackInfo(currentBranch.name)
                    ?: throw GitToolFailure(
                        "Branch '${currentBranch.name}' has no configured upstream; push it with setUpstream=true first",
                    )
                val beforeHead = GitToolSupport.head(repository)
                    ?: throw GitToolFailure("Cannot resolve HEAD in ${GitToolSupport.exposedRoot(repository)}")
                val parameters = buildList {
                    when (strategy) {
                        "ff_only" -> add("--ff-only")
                        "rebase" -> add("--rebase")
                        "merge" -> {
                            add("--no-rebase")
                            add("--no-edit")
                        }
                    }
                    add(trackInfo.remote.name)
                    add(trackInfo.remoteBranch.nameForRemoteOperations)
                }
                val result = GitToolSupport.runRemoteCommand(
                    repository,
                    GitCommand.PULL,
                    trackInfo.remote,
                    parameters,
                )
                if (!result.success()) return@onPooledThread GitToolSupport.commandFailure("git pull", result)

                GitToolSupport.refresh(repository, refreshWorkingTree = true)
                val afterHead = GitToolSupport.head(repository) ?: beforeHead
                McpToolCallResult.success(JsonObject().apply {
                    addProperty("repositoryRoot", GitToolSupport.exposedRoot(repository))
                    addProperty("remote", trackInfo.remote.name)
                    addProperty("branch", trackInfo.remoteBranch.nameForRemoteOperations)
                    addProperty("strategy", strategy)
                    addProperty("beforeHead", beforeHead)
                    addProperty("afterHead", afterHead)
                    addProperty("changed", beforeHead != afterHead)
                    GitToolSupport.addCommandResult(this, result)
                })
            }
        } catch (error: GitToolFailure) {
            McpToolCallResult.error(error.message.orEmpty())
        } catch (error: Exception) {
            McpToolCallResult.error("git pull failed: ${GitToolSupport.safeErrorDetail(error)}")
        }
    }

    private companion object {
        val STRATEGIES = setOf("ff_only", "rebase", "merge")
    }
}
