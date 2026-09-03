package com.l1yp.tool

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import com.l1yp.mcp.McpTool
import com.l1yp.mcp.McpToolCallResult
import com.l1yp.mcp.McpToolDefinition

internal class GetGitRepositoriesTool : McpTool {
    override val definition = McpToolDefinition(
        name = "get_git_repositories",
        description = "Lists Git repositories managed by the current IDEA project, including branches, upstreams, and remote names. Call this before Git remote tools.",
        inputSchema = schema("""{"type":"object","properties":{},"additionalProperties":false}"""),
        outputSchema = schema(
            """{
                "type":"object",
                "properties":{
                    "projectName":{"type":"string"},
                    "repositories":{"type":"array","items":{
                        "type":"object",
                        "properties":{
                            "repositoryRoot":{"type":"string"},
                            "state":{"type":"string"},
                            "head":{"type":"string"},
                            "currentBranch":{"type":"string"},
                            "upstream":{"type":"object","properties":{
                                "remote":{"type":"string"},
                                "branch":{"type":"string"}
                            },"required":["remote","branch"],"additionalProperties":false},
                            "remotes":{"type":"array","items":{"type":"string"}}
                        },
                        "required":["repositoryRoot","state","remotes"],
                        "additionalProperties":false
                    }}
                },
                "required":["projectName","repositories"],
                "additionalProperties":false
            }""".trimIndent(),
        ),
        annotations = schema(
            """{"readOnlyHint":true,"destructiveHint":false,"idempotentHint":true,"openWorldHint":false}""",
        ),
    )

    override fun call(project: Project, arguments: JsonObject): McpToolCallResult {
        if (arguments.size() != 0) throw IllegalArgumentException("This tool does not accept arguments")

        val repositories = JsonArray()
        GitToolSupport.repositories(project).forEach { repository ->
            val currentBranch = repository.currentBranch
            val trackInfo = currentBranch?.let { repository.getBranchTrackInfo(it.name) }
            repositories.add(JsonObject().apply {
                addProperty("repositoryRoot", GitToolSupport.exposedRoot(repository))
                addProperty("state", repository.state.name.lowercase())
                GitToolSupport.head(repository)?.let { addProperty("head", it) }
                currentBranch?.let { addProperty("currentBranch", it.name) }
                trackInfo?.let {
                    add("upstream", JsonObject().apply {
                        addProperty("remote", it.remote.name)
                        addProperty("branch", it.remoteBranch.nameForRemoteOperations)
                    })
                }
                add("remotes", JsonArray().apply {
                    repository.remotes.sortedBy { it.name }.forEach { add(it.name) }
                })
            })
        }
        return McpToolCallResult.success(JsonObject().apply {
            addProperty("projectName", project.name)
            add("repositories", repositories)
        })
    }
}
