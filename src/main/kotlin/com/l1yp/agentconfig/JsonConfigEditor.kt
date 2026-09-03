package com.l1yp.agentconfig

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal object JsonConfigEditor {
    private val gson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()

    fun upsert(source: String, objectPath: List<String>, propertyName: String, value: JsonObject): String {
        val root = parseRoot(source)
        targetObject(root, objectPath, create = true).add(propertyName, value.deepCopy())
        return gson.toJson(root)
    }

    fun remove(source: String, objectPath: List<String>, propertyName: String): String {
        val root = parseRoot(source)
        targetObject(root, objectPath, create = false).remove(propertyName)
        return gson.toJson(root)
    }

    fun serverNames(source: String, objectPath: List<String>): Set<String> =
        targetObject(parseRoot(source), objectPath, create = false).keySet()

    fun nodeHash(source: String, objectPath: List<String>, propertyName: String): String? =
        targetObject(parseRoot(source), objectPath, create = false)
            .get(propertyName)
            ?.let(JsonElement::toString)
            ?.let(ConfigTextFile::digest)

    fun isEffectivelyEmpty(source: String): Boolean = removeEmptyObjects(parseRoot(source)).size() == 0

    private fun parseRoot(source: String): JsonObject {
        if (source.isBlank()) return JsonObject()
        val parsed = JsonParser.parseString(source)
        require(parsed.isJsonObject) { "Configuration root must be a JSON object" }
        return parsed.asJsonObject
    }

    private fun targetObject(root: JsonObject, path: List<String>, create: Boolean): JsonObject {
        var current = root
        path.forEach { segment ->
            val existing = current.get(segment)
            if (existing == null) {
                require(create) { "Configuration does not contain object '${path.joinToString(".")}'" }
                current = JsonObject().also { current.add(segment, it) }
            } else {
                require(existing.isJsonObject) { "Configuration field '$segment' must be an object" }
                current = existing.asJsonObject
            }
        }
        return current
    }

    private fun removeEmptyObjects(value: JsonObject): JsonObject {
        val result = value.deepCopy()
        result.entrySet().toList().forEach { (name, child) ->
            if (child.isJsonObject) {
                val compacted = removeEmptyObjects(child.asJsonObject)
                if (compacted.size() == 0) result.remove(name) else result.add(name, compacted)
            }
        }
        return result
    }
}
