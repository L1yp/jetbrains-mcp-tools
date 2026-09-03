package com.l1yp.logging

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpLogSanitizerTest {
    @Test
    fun `redacts authorization tokens query tokens and URL user info`() {
        val sanitized = McpLogSanitizer.sanitize(
            """
            Authorization: Bearer secret-token
            {"Authorization":"Bearer json-secret"}
            https://user:password@example.com/repository.git?access_token=query-secret
            """.trimIndent(),
        )

        assertFalse(sanitized.contains("secret-token"))
        assertFalse(sanitized.contains("json-secret"))
        assertFalse(sanitized.contains("user:password"))
        assertFalse(sanitized.contains("query-secret"))
        assertTrue(sanitized.contains("Bearer <redacted>"))
        assertTrue(sanitized.contains("access_token=<redacted>"))
        assertTrue(sanitized.contains("https://<redacted>@example.com"))
    }
}
