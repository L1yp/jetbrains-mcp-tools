package com.l1yp.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.intellij.openapi.project.Project

internal class McpJsonRpcDispatcher(
    private val serverVersion: String,
    private val toolRegistry: ToolRegistry = ToolRegistry.DEFAULT,
) {
    fun dispatch(
        requestBody: String,
        protocolVersionHeader: String?,
        project: Project?,
    ): McpDispatchResult {
        val payload = try {
            JsonParser.parseString(requestBody)
        } catch (_: JsonParseException) {
            return protocolError(400, JsonNull.INSTANCE, JsonRpcErrorCode.PARSE_ERROR, "Parse error")
        } catch (_: IllegalStateException) {
            return protocolError(400, JsonNull.INSTANCE, JsonRpcErrorCode.PARSE_ERROR, "Parse error")
        }
        if (!payload.isJsonObject) {
            return protocolError(400, JsonNull.INSTANCE, JsonRpcErrorCode.INVALID_REQUEST, "Invalid Request")
        }

        val request = payload.asJsonObject
        val idValidation = validateId(request)
        if (idValidation != null) return idValidation
        val id = request.get("id") ?: JsonNull.INSTANCE
        val method = request.string("method")
            ?: return protocolError(
                400,
                id,
                JsonRpcErrorCode.INVALID_REQUEST,
                "method must be a string",
            )
        if (request.string("jsonrpc") != "2.0") {
            return protocolError(
                400,
                id,
                JsonRpcErrorCode.INVALID_REQUEST,
                "jsonrpc must be '2.0'",
            )
        }
        val isNotification = !request.has("id")
        val params = request.get("params")?.let { element ->
            if (!element.isJsonObject) {
                return notificationAwareError(
                    isNotification,
                    id,
                    400,
                    JsonRpcErrorCode.INVALID_PARAMS,
                    "params must be an object",
                )
            }
            element.asJsonObject
        } ?: JsonObject()

        if (method != "initialize" && protocolVersionHeader != McpProtocol.VERSION) {
            return protocolError(
                400,
                id,
                JsonRpcErrorCode.INVALID_REQUEST,
                "MCP-Protocol-Version must be ${McpProtocol.VERSION}",
            )
        }
        if (method == "initialize" &&
            protocolVersionHeader != null &&
            protocolVersionHeader != McpProtocol.VERSION
        ) {
            return protocolError(
                400,
                id,
                JsonRpcErrorCode.INVALID_REQUEST,
                "Unsupported MCP-Protocol-Version '$protocolVersionHeader'",
            )
        }

        if (isNotification) {
            return when (method) {
                "notifications/initialized", "notifications/cancelled" -> McpDispatchResult(202)
                else -> McpDispatchResult(202)
            }
        }

        return try {
            when (method) {
                "initialize" -> initialize(id, params)
                "ping" -> success(id, JsonObject())
                "tools/list" -> listTools(id, params)
                "tools/call" -> callTool(id, params, project)
                else -> protocolError(200, id, JsonRpcErrorCode.METHOD_NOT_FOUND, "Method not found: $method")
            }
        } catch (_: IllegalArgumentException) {
            protocolError(200, id, JsonRpcErrorCode.INVALID_PARAMS, "Invalid params")
        } catch (_: Exception) {
            protocolError(500, id, JsonRpcErrorCode.INTERNAL_ERROR, "Internal error")
        }
    }

    private fun initialize(id: JsonElement, params: JsonObject): McpDispatchResult {
        val requestedVersion = params.string("protocolVersion")
            ?: return protocolError(
                400,
                id,
                JsonRpcErrorCode.INVALID_PARAMS,
                "protocolVersion is required",
            )
        if (requestedVersion != McpProtocol.VERSION) {
            return protocolError(
                400,
                id,
                JsonRpcErrorCode.INVALID_PARAMS,
                "Unsupported protocolVersion '$requestedVersion'; only ${McpProtocol.VERSION} is supported",
            )
        }

        return success(id, JsonObject().apply {
            addProperty("protocolVersion", McpProtocol.VERSION)
            add("capabilities", JsonObject().apply {
                add("tools", JsonObject().apply { addProperty("listChanged", false) })
            })
            add("serverInfo", JsonObject().apply {
                addProperty("name", McpProtocol.SERVER_NAME)
                addProperty("version", serverVersion)
            })
            addProperty("instructions", McpProtocol.INSTRUCTIONS)
        })
    }

    private fun listTools(id: JsonElement, params: JsonObject): McpDispatchResult {
        if (params.size() != 0) {
            return protocolError(200, id, JsonRpcErrorCode.INVALID_PARAMS, "tools/list does not accept params")
        }
        val tools = JsonArray()
        toolRegistry.definitions.forEach { definition ->
            tools.add(JsonObject().apply {
                addProperty("name", definition.name)
                addProperty("description", definition.description)
                add("inputSchema", definition.inputSchema.deepCopy())
                add("outputSchema", definition.outputSchema.deepCopy())
                add("annotations", definition.annotations.deepCopy())
            })
        }
        return success(id, JsonObject().apply { add("tools", tools) })
    }

    private fun callTool(id: JsonElement, params: JsonObject, project: Project?): McpDispatchResult {
        if (params.keySet().any { it !in setOf("name", "arguments") }) {
            return protocolError(200, id, JsonRpcErrorCode.INVALID_PARAMS, "Unknown tools/call parameter")
        }
        val name = params.string("name")
            ?: return protocolError(200, id, JsonRpcErrorCode.INVALID_PARAMS, "Tool name is required")
        val arguments = params.get("arguments")?.let { element ->
            if (!element.isJsonObject) {
                return protocolError(200, id, JsonRpcErrorCode.INVALID_PARAMS, "Tool arguments must be an object")
            }
            element.asJsonObject
        } ?: JsonObject()
        val tool = toolRegistry.find(name)
            ?: return protocolError(200, id, JsonRpcErrorCode.INVALID_PARAMS, "Unknown tool '$name'")
        val targetProject = project
            ?: return protocolError(500, id, JsonRpcErrorCode.INTERNAL_ERROR, "Project context is unavailable")
        val callResult = tool.call(targetProject, arguments)
        val result = JsonObject().apply {
            add("content", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "text")
                    addProperty("text", callResult.text)
                })
            })
            callResult.structuredContent?.let { add("structuredContent", it.deepCopy()) }
            addProperty("isError", callResult.isError)
        }
        return success(id, result)
    }

    private fun validateId(request: JsonObject): McpDispatchResult? {
        if (!request.has("id")) return null
        val id = request.get("id")
        if (id.isJsonPrimitive && (id.asJsonPrimitive.isString || id.asJsonPrimitive.isNumber)) return null
        return protocolError(
            400,
            JsonNull.INSTANCE,
            JsonRpcErrorCode.INVALID_REQUEST,
            "id must be a string or number",
        )
    }

    private fun success(id: JsonElement, result: JsonObject): McpDispatchResult = McpDispatchResult(
        httpStatus = 200,
        responseBody = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            add("id", id.deepCopy())
            add("result", result)
        }.toString(),
    )

    private fun notificationAwareError(
        notification: Boolean,
        id: JsonElement,
        status: Int,
        code: Int,
        message: String,
    ): McpDispatchResult = if (notification) {
        McpDispatchResult(202)
    } else {
        protocolError(status, id, code, message)
    }

    private fun protocolError(
        status: Int,
        id: JsonElement,
        code: Int,
        message: String,
    ): McpDispatchResult = McpDispatchResult(
        httpStatus = status,
        responseBody = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            add("id", id.deepCopy())
            add("error", JsonObject().apply {
                addProperty("code", code)
                addProperty("message", message)
            })
        }.toString(),
    )
}

private fun JsonObject.string(name: String): String? = get(name)
    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
    ?.asString
