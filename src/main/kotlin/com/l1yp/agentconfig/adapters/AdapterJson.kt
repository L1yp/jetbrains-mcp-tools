package com.l1yp.agentconfig.adapters

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.l1yp.agentconfig.McpEndpoint
import com.l1yp.mcp.McpProtocol

internal fun McpEndpoint.headers(): JsonObject = JsonObject().apply {
    addProperty("Authorization", authorizationHeader)
    addProperty(McpProtocol.DIAGNOSTIC_CLIENT_HEADER, diagnosticClientName)
}

internal fun List<String>.toJsonArray(): JsonArray = JsonArray().also { array ->
    forEach(array::add)
}
