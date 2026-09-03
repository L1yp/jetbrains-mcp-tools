package com.l1yp.agentconfig.adapters

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.l1yp.agentconfig.McpEndpoint

internal fun McpEndpoint.headers(): JsonObject = JsonObject().apply {
    addProperty("Authorization", authorizationHeader)
}

internal fun List<String>.toJsonArray(): JsonArray = JsonArray().also { array ->
    forEach(array::add)
}
