package com.l1yp.agentconfig

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConfigEditorsTest {
    private val server = JsonObject().apply {
        addProperty("type", "http")
        addProperty("url", "http://127.0.0.1:63342/api/jetbrains-mcp-tools")
    }

    @Test
    fun `strict JSON merge preserves unknown fields and other servers`() {
        val source = """{
          "model": "gpt-5",
          "mcpServers": {
            "other": {"command": "other"}
          }
        }""".trimIndent()

        val updated = JsonConfigEditor.upsert(source, listOf("mcpServers"), "idea", server)
        val root = JsonParser.parseString(updated).asJsonObject

        assertEquals("gpt-5", root.get("model").asString)
        assertTrue(root.getAsJsonObject("mcpServers").has("other"))
        assertEquals(
            server,
            root.getAsJsonObject("mcpServers").getAsJsonObject("idea"),
        )
        assertEquals(setOf("other", "idea"), JsonConfigEditor.serverNames(updated, listOf("mcpServers")))
        assertNotNull(JsonConfigEditor.nodeHash(updated, listOf("mcpServers"), "idea"))
    }

    @Test
    fun `JSONC merge keeps comments trailing commas and unknown settings`() {
        val source = """{
          // Model selection must remain untouched.
          "model": "gpt-5",
          "mcp": {
            "servers": {
              "other": { "type": "local" }, // keep this server
            },
          },
        }""".trimIndent()

        val updated = JsoncConfigEditor.upsert(source, listOf("mcp", "servers"), "idea", server)
        JsoncConfigEditor.validate(updated)

        assertTrue(updated.contains("// Model selection must remain untouched."))
        assertTrue(updated.contains("// keep this server"))
        assertTrue(updated.contains("\"model\": \"gpt-5\""))
        assertEquals(setOf("other", "idea"), JsoncConfigEditor.serverNames(updated, listOf("mcp", "servers")))

        val changedServer = server.deepCopy().apply { addProperty("oauth", false) }
        val replaced = JsoncConfigEditor.upsert(updated, listOf("mcp", "servers"), "idea", changedServer)
        val removed = JsoncConfigEditor.remove(replaced, listOf("mcp", "servers"), "idea")
        JsoncConfigEditor.validate(removed)
        assertFalse(JsoncConfigEditor.serverNames(removed, listOf("mcp", "servers")).contains("idea"))
        assertTrue(removed.contains("other"))
    }

    @Test
    fun `JSONC merge creates missing nested objects without rewriting the file`() {
        val source = """{
          "permissions": { "shell": false }
        }""".trimIndent()

        val updated = JsoncConfigEditor.upsert(source, listOf("mcp", "servers"), "idea", server)

        JsoncConfigEditor.validate(updated)
        assertTrue(updated.contains("\"permissions\": { \"shell\": false }"))
        assertEquals(setOf("idea"), JsoncConfigEditor.serverNames(updated, listOf("mcp", "servers")))
    }

    @Test
    fun `TOML managed table preserves all unrelated tables and comments`() {
        val source = """
            model = "gpt-5"
            # keep
            [mcp_servers.other]
            command = "other"
        """.trimIndent()
        val body = """
            url = "http://127.0.0.1:63342/api/jetbrains-mcp-tools"
            http_headers = { Authorization = "Bearer secret" }
        """.trimIndent()

        val updated = TomlConfigEditor.upsertManagedTable(source, "mcp_servers.idea", body)
        TomlConfigEditor.validate(updated)

        assertTrue(updated.contains("model = \"gpt-5\""))
        assertTrue(updated.contains("# keep"))
        assertTrue(updated.contains("[mcp_servers.other]"))
        assertEquals(setOf("other", "idea"), TomlConfigEditor.tableNames(updated, "mcp_servers."))
        assertNotNull(TomlConfigEditor.tableBodyHash(updated, "mcp_servers.idea"))

        val removed = TomlConfigEditor.removeManagedTable(updated, "mcp_servers.idea")
        TomlConfigEditor.validate(removed)
        assertTrue(removed.contains("[mcp_servers.other]"))
        assertFalse(removed.contains("[mcp_servers.idea]"))
    }

    @Test
    fun `TOML server names normalize quoted keys for collision detection`() {
        val source = """
            [mcp_servers."jetbrains_tools"]
            url = "http://127.0.0.1:63342/mcp"
        """.trimIndent()

        assertEquals(
            setOf("jetbrains_tools"),
            TomlConfigEditor.tableNames(source, "mcp_servers."),
        )
    }

    @Test
    fun `atomic writer preserves UTF-8 BOM CRLF and missing final newline`() {
        val directory = Files.createTempDirectory("mcp-config-text-")
        val path = directory.resolve("config.json")
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        Files.write(path, bom + "{\r\n  \"中文\": true\r\n}".toByteArray(StandardCharsets.UTF_8))
        val snapshot = ConfigTextFile.read(path)

        val result = ConfigTextFile.writeAtomically(path, snapshot.digest) { source ->
            JsonConfigEditor.upsert(source, listOf("mcpServers"), "idea", server)
        }

        assertEquals(true, assertIs<AtomicWriteResult.Success>(result).changed)
        val bytes = Files.readAllBytes(path)
        assertTrue(bytes.take(3).toByteArray().contentEquals(bom))
        val content = String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        assertTrue(content.contains("\r\n"))
        assertFalse(content.endsWith("\n"))
        assertTrue(content.contains("中文"))
    }

    @Test
    fun `atomic writer recomputes once after a concurrent change`() {
        val directory = Files.createTempDirectory("mcp-config-race-")
        val path = directory.resolve("config.json")
        Files.writeString(path, "{\"value\":1}", StandardCharsets.UTF_8)
        val snapshot = ConfigTextFile.read(path)
        var calls = 0

        val result = ConfigTextFile.writeAtomically(path, snapshot.digest) { source ->
            calls++
            if (calls == 1) Files.writeString(path, "{\"value\":2}", StandardCharsets.UTF_8)
            source.replace(Regex("\\d"), "3")
        }

        assertIs<AtomicWriteResult.Success>(result)
        assertEquals(2, calls)
        assertEquals("{\"value\":3}", Files.readString(path).trim())
    }

    @Test
    fun `atomic writer refuses to overwrite a concurrently claimed server node`() {
        val directory = Files.createTempDirectory("mcp-config-node-race-")
        val path = directory.resolve("config.json")
        Files.writeString(path, "{}", StandardCharsets.UTF_8)
        val snapshot = ConfigTextFile.read(path)
        var calls = 0

        val result = ConfigTextFile.writeAtomically(path, snapshot.digest) { source ->
            calls++
            val currentHash = runCatching {
                JsonConfigEditor.nodeHash(source, listOf("mcpServers"), "idea")
            }.getOrNull()
            require(currentHash == null) { "Server node changed concurrently" }
            if (calls == 1) {
                Files.writeString(
                    path,
                    """{"mcpServers":{"idea":{"url":"https://user.example/mcp"}}}""",
                    StandardCharsets.UTF_8,
                )
            }
            JsonConfigEditor.upsert(source, listOf("mcpServers"), "idea", server)
        }

        assertIs<AtomicWriteResult.Failure>(result)
        assertTrue(Files.readString(path).contains("user.example"))
    }
}
