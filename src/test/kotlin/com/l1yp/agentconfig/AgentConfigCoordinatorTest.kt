package com.l1yp.agentconfig

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AgentConfigCoordinatorTest {
    @Test
    fun `uses stable default name and adds a project suffix only on collision`() {
        assertEquals(
            "jetbrains_tools",
            ServerNameSelector.select(null, emptySet(), "E:/work/order"),
        )
        val collided = ServerNameSelector.select(
            null,
            setOf("jetbrains_tools", "other"),
            "E:/work/order",
        )

        assertTrue(collided.matches(Regex("jetbrains_tools_[0-9a-f]{6}")))
        assertNotEquals(
            collided,
            ServerNameSelector.select(null, setOf("jetbrains_tools"), "E:/work/payment"),
        )
        assertEquals(
            "recorded_name",
            ServerNameSelector.select("recorded_name", setOf("jetbrains_tools"), "E:/work/order"),
        )
    }

    @Test
    fun `JSONC semantic emptiness ignores nested containers and comments`() {
        assertTrue(
            JsoncConfigEditor.isEffectivelyEmpty(
                """{
                  // managed containers may remain after removal
                  "mcp": { "servers": {} },
                }""".trimIndent(),
            ),
        )
    }
}
