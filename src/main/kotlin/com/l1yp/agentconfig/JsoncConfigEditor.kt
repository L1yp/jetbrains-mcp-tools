package com.l1yp.agentconfig

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal object JsoncConfigEditor {
    private val gson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()

    fun upsert(source: String, objectPath: List<String>, propertyName: String, value: JsonObject): String {
        val initial = source.ifBlank { "{}" }
        val root = Parser(initial).parseRoot()
        return upsertAt(initial, root, objectPath, 0, propertyName, gson.toJson(value))
    }

    fun remove(source: String, objectPath: List<String>, propertyName: String): String {
        val root = Parser(source).parseRoot()
        val target = findObject(root, objectPath) ?: return source
        val index = target.properties.indexOfFirst { it.name == propertyName }
        if (index < 0) return source
        val property = target.properties[index]
        val removal = when {
            property.commaPosition != null -> property.keyStart until (property.commaPosition + 1)
            index > 0 && target.properties[index - 1].commaPosition != null ->
                target.properties[index - 1].commaPosition!! until property.value.end
            else -> property.keyStart until property.value.end
        }
        return source.removeRange(removal)
    }

    fun serverNames(source: String, objectPath: List<String>): Set<String> {
        if (source.isBlank()) return emptySet()
        val target = findObject(Parser(source).parseRoot(), objectPath) ?: return emptySet()
        return target.properties.mapTo(linkedSetOf()) { it.name }
    }

    fun nodeHash(source: String, objectPath: List<String>, propertyName: String): String? {
        if (source.isBlank()) return null
        val target = findObject(Parser(source).parseRoot(), objectPath) ?: return null
        val property = target.properties.firstOrNull { it.name == propertyName } ?: return null
        return ConfigTextFile.digest(source.substring(property.value.start, property.value.end))
    }

    fun validate(source: String) {
        if (source.isBlank()) return
        Parser(source).parseRoot()
    }

    fun isEffectivelyEmpty(source: String): Boolean {
        if (source.isBlank()) return true
        return isEmptyObject(Parser(source).parseRoot())
    }

    private fun isEmptyObject(node: ObjectNode): Boolean =
        node.properties.all { property ->
            val child = property.value as? ObjectNode ?: return@all false
            isEmptyObject(child)
        }

    private fun upsertAt(
        source: String,
        current: ObjectNode,
        path: List<String>,
        pathIndex: Int,
        propertyName: String,
        value: String,
    ): String {
        if (pathIndex == path.size) return upsertProperty(source, current, propertyName, value)
        val segment = path[pathIndex]
        val property = current.properties.firstOrNull { it.name == segment }
        if (property == null) {
            val nested = buildNestedObject(path.drop(pathIndex + 1), propertyName, value)
            return upsertProperty(source, current, segment, nested)
        }
        val child = property.value as? ObjectNode
            ?: error("Configuration field '$segment' must be an object")
        return upsertAt(source, child, path, pathIndex + 1, propertyName, value)
    }

    private fun buildNestedObject(path: List<String>, propertyName: String, value: String): String {
        var node = JsonObject().apply { add(propertyName, JsonParser.parseString(value)) }
        path.asReversed().forEach { segment ->
            node = JsonObject().apply { add(segment, node) }
        }
        return gson.toJson(node)
    }

    private fun upsertProperty(source: String, target: ObjectNode, name: String, value: String): String {
        val existing = target.properties.firstOrNull { it.name == name }
        if (existing != null) {
            val indentation = lineIndent(source, existing.keyStart)
            val rendered = indentContinuation(value, indentation)
            return source.replaceRange(existing.value.start, existing.value.end, rendered)
        }

        val newline = if ("\r\n" in source) "\r\n" else "\n"
        val baseIndent = lineIndent(source, target.start)
        val childIndent = target.properties.firstOrNull()?.let { lineIndent(source, it.keyStart) }
            ?.takeIf(String::isNotEmpty)
            ?: "$baseIndent  "
        val rendered = "\"${escape(name)}\": ${indentContinuation(value, childIndent)}"
        val closingBrace = target.end - 1
        if (target.properties.isEmpty()) {
            return source.replaceRange(
                closingBrace,
                closingBrace,
                "$newline$childIndent$rendered$newline$baseIndent",
            )
        }

        val lastProperty = target.properties.last()
        var adjustedSource = source
        var adjustedClosingBrace = closingBrace
        if (lastProperty.commaPosition == null) {
            adjustedSource = source.replaceRange(lastProperty.value.end, lastProperty.value.end, ",")
            adjustedClosingBrace++
        }
        return adjustedSource.replaceRange(
            adjustedClosingBrace,
            adjustedClosingBrace,
            "$newline$childIndent$rendered$newline$baseIndent",
        )
    }

    private fun indentContinuation(value: String, indentation: String): String = value
        .replace("\r\n", "\n")
        .split('\n')
        .mapIndexed { index, line -> if (index == 0) line else indentation + line }
        .joinToString("\n")

    private fun lineIndent(source: String, position: Int): String {
        val lineStart = source.lastIndexOf('\n', (position - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        return source.substring(lineStart, position).takeWhile { it == ' ' || it == '\t' }
    }

    private fun escape(value: String): String = gson.toJson(value).removeSurrounding("\"")

    private fun findObject(root: ObjectNode, path: List<String>): ObjectNode? {
        var current = root
        path.forEach { segment ->
            val property = current.properties.firstOrNull { it.name == segment } ?: return null
            current = property.value as? ObjectNode ?: error("Configuration field '$segment' must be an object")
        }
        return current
    }

    private sealed class Node(open val start: Int, open val end: Int)

    private data class ObjectNode(
        override val start: Int,
        override val end: Int,
        val properties: List<PropertyNode>,
    ) : Node(start, end)

    private data class ArrayNode(override val start: Int, override val end: Int) : Node(start, end)

    private data class ValueNode(override val start: Int, override val end: Int) : Node(start, end)

    private data class PropertyNode(
        val name: String,
        val keyStart: Int,
        val value: Node,
        val commaPosition: Int?,
    )

    private class Parser(private val source: String) {
        private var index = 0

        fun parseRoot(): ObjectNode {
            skipTrivia()
            val root = parseValue() as? ObjectNode ?: error("Configuration root must be a JSON object")
            skipTrivia()
            require(index == source.length) { "Unexpected content at offset $index" }
            return root
        }

        private fun parseValue(): Node {
            skipTrivia()
            require(index < source.length) { "Expected JSON value at end of file" }
            return when (source[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> ValueNode(index, parseStringEnd())
                else -> parsePrimitive()
            }
        }

        private fun parseObject(): ObjectNode {
            val start = index++
            val properties = mutableListOf<PropertyNode>()
            skipTrivia()
            if (consume('}')) return ObjectNode(start, index, properties)
            while (true) {
                skipTrivia()
                require(index < source.length && source[index] == '"') { "Expected object key at offset $index" }
                val keyStart = index
                val keyEnd = parseStringEnd()
                val name = JsonParser.parseString(source.substring(keyStart, keyEnd)).asString
                skipTrivia()
                require(consume(':')) { "Expected ':' after '$name'" }
                val value = parseValue()
                skipTrivia()
                val commaPosition = if (consume(',')) index - 1 else null
                properties += PropertyNode(name, keyStart, value, commaPosition)
                skipTrivia()
                if (consume('}')) return ObjectNode(start, index, properties)
                require(commaPosition != null) { "Expected ',' or '}' at offset $index" }
            }
        }

        private fun parseArray(): ArrayNode {
            val start = index++
            skipTrivia()
            if (consume(']')) return ArrayNode(start, index)
            while (true) {
                parseValue()
                skipTrivia()
                val comma = consume(',')
                skipTrivia()
                if (consume(']')) return ArrayNode(start, index)
                require(comma) { "Expected ',' or ']' at offset $index" }
            }
        }

        private fun parsePrimitive(): ValueNode {
            val start = index
            while (index < source.length) {
                val character = source[index]
                if (character.isWhitespace() || character in charArrayOf(',', '}', ']') || startsComment()) break
                index++
            }
            require(index > start) { "Expected JSON value at offset $index" }
            val value = source.substring(start, index)
            require(value == "true" || value == "false" || value == "null" || value.toDoubleOrNull() != null) {
                "Invalid JSON value '$value'"
            }
            return ValueNode(start, index)
        }

        private fun parseStringEnd(): Int {
            require(consume('"')) { "Expected string at offset $index" }
            var escaped = false
            while (index < source.length) {
                val character = source[index++]
                if (escaped) {
                    escaped = false
                } else if (character == '\\') {
                    escaped = true
                } else if (character == '"') {
                    return index
                }
            }
            error("Unterminated string")
        }

        private fun skipTrivia() {
            while (index < source.length) {
                when {
                    source[index].isWhitespace() -> index++
                    source.startsWith("//", index) -> {
                        index += 2
                        while (index < source.length && source[index] != '\n') index++
                    }
                    source.startsWith("/*", index) -> {
                        val end = source.indexOf("*/", index + 2)
                        require(end >= 0) { "Unterminated block comment" }
                        index = end + 2
                    }
                    else -> return
                }
            }
        }

        private fun startsComment(): Boolean =
            source.startsWith("//", index) || source.startsWith("/*", index)

        private fun consume(character: Char): Boolean {
            if (index >= source.length || source[index] != character) return false
            index++
            return true
        }
    }
}
