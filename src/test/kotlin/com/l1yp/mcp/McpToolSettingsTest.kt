package com.l1yp.mcp

import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import kotlin.test.Test
import kotlin.test.assertEquals

class McpToolSettingsTest {
    private val registry = ToolRegistry(
        listOf(
            fakeTool("first"),
            fakeTool("second"),
            fakeTool("third"),
        ),
    )

    @Test
    fun `enables all known tools by default`() {
        assertEquals(listOf("first", "second", "third"), McpToolSettings().enabledToolNames(registry))
    }

    @Test
    fun `persists disabled tools while preserving registry order`() {
        val settings = McpToolSettings()

        settings.updateEnabledToolNames(setOf("third", "first", "unknown"), registry)

        assertEquals(listOf("first", "third"), settings.enabledToolNames(registry))
        assertEquals(listOf("second"), settings.state.disabledToolNames)
    }

    private fun fakeTool(name: String): McpTool = object : McpTool {
        override val definition = McpToolDefinition(
            name = name,
            description = "test",
            inputSchema = JsonObject(),
            outputSchema = JsonObject(),
            annotations = JsonObject(),
        )

        override fun call(project: Project, arguments: JsonObject): McpToolCallResult =
            McpToolCallResult.success(JsonObject())
    }
}
