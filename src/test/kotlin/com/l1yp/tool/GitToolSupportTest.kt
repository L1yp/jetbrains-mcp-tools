package com.l1yp.tool

import com.google.gson.JsonObject
import git4idea.commands.GitCommandResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class GitToolSupportTest {
    @Test
    fun `command output redacts URL user info and removes duplicates`() {
        val result = GitCommandResult(
            true,
            0,
            listOf("done"),
            listOf("From https://user:secret@example.com/repository.git", "done"),
        )
        val content = JsonObject()

        GitToolSupport.addCommandResult(content, result)

        assertEquals(0, content.get("exitCode").asInt)
        assertEquals(
            listOf("From https://***@example.com/repository.git", "done"),
            content.getAsJsonArray("messages").map { it.asString },
        )
        assertFalse(content.toString().contains("secret"))
    }
}
