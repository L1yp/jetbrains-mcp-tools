package com.l1yp.tool

import com.l1yp.mcp.ToolRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RestartRunConfigurationToolTest {
    @Test
    fun `registry exposes exactly the two restart workflow tools`() {
        assertEquals(
            listOf("get_restartable_run_configurations", "restart_run_configuration"),
            ToolRegistry.DEFAULT.definitions.map { it.name },
        )
    }

    @Test
    fun `tool definitions include schemas and safety annotations`() {
        val definitions = ToolRegistry.DEFAULT.definitions.associateBy { it.name }
        val listTool = definitions.getValue("get_restartable_run_configurations")
        val restartTool = definitions.getValue("restart_run_configuration")

        assertEquals("object", listTool.inputSchema.get("type").asString)
        assertEquals("object", listTool.outputSchema.get("type").asString)
        assertTrue(listTool.annotations.get("readOnlyHint").asBoolean)
        assertFalse(restartTool.annotations.get("readOnlyHint").asBoolean)
        assertTrue(restartTool.annotations.get("destructiveHint").asBoolean)
    }
}
