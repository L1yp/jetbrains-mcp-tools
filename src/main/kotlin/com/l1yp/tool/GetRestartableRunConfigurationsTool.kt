package com.l1yp.tool

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import com.l1yp.configuration.RunConfigurationExposureCatalog
import com.l1yp.mcp.McpTool
import com.l1yp.mcp.McpToolCallResult
import com.l1yp.mcp.McpToolDefinition

internal class GetRestartableRunConfigurationsTool : McpTool {
    override val definition = McpToolDefinition(
        name = "get_restartable_run_configurations",
        description = "Returns the persistent Run Configurations exposed by this project's MCP Toolbox settings.",
        inputSchema = schema("""{"type":"object","properties":{},"additionalProperties":false}"""),
        outputSchema = schema(
            """{
                "type":"object",
                "properties":{
                    "projectName":{"type":"string"},
                    "configurations":{"type":"array","items":{
                        "type":"object",
                        "properties":{
                            "name":{"type":"string"},
                            "typeId":{"type":"string"},
                            "typeName":{"type":"string"}
                        },
                        "required":["name","typeId","typeName"],
                        "additionalProperties":false
                    }}
                },
                "required":["projectName","configurations"],
                "additionalProperties":false
            }""".trimIndent(),
        ),
        annotations = schema(
            """{"readOnlyHint":true,"destructiveHint":false,"idempotentHint":true,"openWorldHint":false}""",
        ),
    )

    override fun call(project: Project, arguments: JsonObject): McpToolCallResult {
        if (arguments.size() != 0) {
            throw IllegalArgumentException("This tool does not accept arguments")
        }

        val configurations = JsonArray()
        RunConfigurationExposureCatalog.exposedSettings(project).forEach { settings ->
            configurations.add(JsonObject().apply {
                addProperty("name", settings.name)
                addProperty("typeId", settings.type.id)
                addProperty("typeName", settings.type.displayName)
            })
        }
        return McpToolCallResult.success(JsonObject().apply {
            addProperty("projectName", project.name)
            add("configurations", configurations)
        })
    }
}

internal fun schema(json: String): JsonObject = JsonParser.parseString(json).asJsonObject
