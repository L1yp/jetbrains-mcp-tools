package com.l1yp.tool

import com.l1yp.mcp.ToolRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RestartRunConfigurationToolTest {
    @Test
    fun `registry exposes run configuration and Git tools`() {
        assertEquals(
            listOf(
                "get_restartable_run_configurations",
                "restart_run_configuration",
                "get_git_repositories",
                "get_git_remotes",
                "git_fetch",
                "git_pull",
                "git_push",
            ),
            ToolRegistry.DEFAULT.definitions.map { it.name },
        )
    }

    @Test
    fun `tool definitions include schemas and safety annotations`() {
        val definitions = ToolRegistry.DEFAULT.definitions.associateBy { it.name }
        val listTool = definitions.getValue("get_restartable_run_configurations")
        val restartTool = definitions.getValue("restart_run_configuration")
        val remotesTool = definitions.getValue("get_git_remotes")
        val fetchTool = definitions.getValue("git_fetch")
        val pullTool = definitions.getValue("git_pull")
        val pushTool = definitions.getValue("git_push")

        assertEquals("object", listTool.inputSchema.get("type").asString)
        assertEquals("object", listTool.outputSchema.get("type").asString)
        assertTrue(listTool.annotations.get("readOnlyHint").asBoolean)
        assertFalse(restartTool.annotations.get("readOnlyHint").asBoolean)
        assertTrue(restartTool.annotations.get("destructiveHint").asBoolean)
        assertTrue(remotesTool.annotations.get("readOnlyHint").asBoolean)
        assertTrue(fetchTool.annotations.get("openWorldHint").asBoolean)
        assertFalse(fetchTool.annotations.get("destructiveHint").asBoolean)
        assertTrue(pullTool.annotations.get("destructiveHint").asBoolean)
        assertTrue(pushTool.annotations.get("destructiveHint").asBoolean)
    }
}
