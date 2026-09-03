package com.l1yp.tool

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import com.l1yp.mcp.McpTool
import com.l1yp.mcp.McpToolCallResult
import com.l1yp.mcp.McpToolDefinition

internal class GetGitRemotesTool : McpTool {
    override val definition = McpToolDefinition(
        name = "get_git_remotes",
        description = "Lists configured remote names and redacted fetch/push URLs for one Git repository managed by the current IDEA project.",
        inputSchema = schema(
            """{
                "type":"object",
                "properties":{"repositoryRoot":{"type":"string","minLength":1}},
                "additionalProperties":false
            }""".trimIndent(),
        ),
        outputSchema = schema(
            """{
                "type":"object",
                "properties":{
                    "projectName":{"type":"string"},
                    "repositoryRoot":{"type":"string"},
                    "remotes":{"type":"array","items":{
                        "type":"object",
                        "properties":{
                            "name":{"type":"string"},
                            "fetchUrls":{"type":"array","items":{"type":"string"}},
                            "pushUrls":{"type":"array","items":{"type":"string"}}
                        },
                        "required":["name","fetchUrls","pushUrls"],
                        "additionalProperties":false
                    }}
                },
                "required":["projectName","repositoryRoot","remotes"],
                "additionalProperties":false
            }""".trimIndent(),
        ),
        annotations = schema(
            """{"readOnlyHint":true,"destructiveHint":false,"idempotentHint":true,"openWorldHint":false}""",
        ),
    )

    override fun call(project: Project, arguments: JsonObject): McpToolCallResult {
        GitToolSupport.requireOnly(arguments, setOf("repositoryRoot"))
        val requestedRoot = GitToolSupport.optionalString(arguments, "repositoryRoot")
        return try {
            val repository = GitToolSupport.selectRepository(project, requestedRoot)
            McpToolCallResult.success(JsonObject().apply {
                addProperty("projectName", project.name)
                addProperty("repositoryRoot", GitToolSupport.exposedRoot(repository))
                add("remotes", JsonArray().apply {
                    repository.remotes.sortedBy { it.name }.forEach { remote ->
                        val pushUrls = remote.pushUrls.ifEmpty { remote.urls }
                        add(JsonObject().apply {
                            addProperty("name", remote.name)
                            add("fetchUrls", remote.urls.toRedactedJsonArray())
                            add("pushUrls", pushUrls.toRedactedJsonArray())
                        })
                    }
                })
            })
        } catch (error: GitToolFailure) {
            McpToolCallResult.error(error.message.orEmpty())
        }
    }

    private fun Collection<String>.toRedactedJsonArray(): JsonArray = JsonArray().apply {
        this@toRedactedJsonArray.forEach { add(GitToolSupport.redactCredentials(it)) }
    }
}
