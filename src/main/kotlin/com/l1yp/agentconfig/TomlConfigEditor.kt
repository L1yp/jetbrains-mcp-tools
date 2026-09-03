package com.l1yp.agentconfig

import org.tomlj.Toml

internal object TomlConfigEditor {
    fun upsertManagedTable(source: String, tableName: String, body: String): String {
        validate(source)
        val newline = if ("\r\n" in source) "\r\n" else "\n"
        val normalizedBody = body.replace("\r\n", "\n").replace("\n", newline).trimEnd()
        val block = "[$tableName]$newline$normalizedBody"
        val ranges = tableRanges(source).filter { it.name == tableName }
        require(ranges.size <= 1) { "TOML declares [$tableName] more than once" }
        if (ranges.size == 1) {
            val range = ranges.single()
            return source.replaceRange(range.start, range.end, block + newline)
        }
        if (source.isBlank()) return block
        return source.trimEnd('\r', '\n') + newline + newline + block
    }

    fun removeManagedTable(source: String, tableName: String): String {
        validate(source)
        val ranges = tableRanges(source).filter { it.name == tableName }
        require(ranges.size <= 1) { "TOML declares [$tableName] more than once" }
        val range = ranges.singleOrNull() ?: return source
        return source.removeRange(range.start, range.end).trimEnd('\r', '\n')
    }

    fun tableBodyHash(source: String, tableName: String): String? {
        validate(source)
        val range = tableRanges(source).singleOrNull { it.name == tableName } ?: return null
        return ConfigTextFile.digest(source.substring(range.start, range.end).trim())
    }

    fun tableNames(source: String, prefix: String): Set<String> {
        validate(source)
        return tableRanges(source)
            .map(TableRange::name)
            .filter { it.startsWith(prefix) }
            .mapTo(linkedSetOf()) { decodeKey(it.removePrefix(prefix)) }
    }

    fun validate(source: String) {
        if (source.isBlank()) return
        val result = Toml.parse(source)
        require(!result.hasErrors()) {
            result.errors().joinToString("; ") { error -> error.toString() }
        }
    }

    private fun tableRanges(source: String): List<TableRange> {
        val matches = TABLE_HEADER.findAll(source).toList()
        return matches.mapIndexed { index, match ->
            TableRange(
                name = match.groupValues[1].trim(),
                start = match.range.first,
                end = matches.getOrNull(index + 1)?.range?.first ?: source.length,
            )
        }
    }

    private data class TableRange(val name: String, val start: Int, val end: Int)

    private fun decodeKey(key: String): String {
        if (!(key.startsWith('"') && key.endsWith('"'))) return key
        return key.substring(1, key.length - 1)
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    private val TABLE_HEADER = Regex("(?m)^[\\t ]*\\[([^]\\r\\n]+)](?:[\\t ]*#.*)?(?:\\r?\\n|$)")
}
