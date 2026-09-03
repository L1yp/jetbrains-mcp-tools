package com.l1yp.agentconfig

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProjectLeaseHandleTest {
    @Test
    fun `only one handle can hold a project lease`() {
        val path = Files.createTempDirectory("mcp-lease-").resolve("project.lock")

        val first = assertNotNull(ProjectLeaseHandle.tryAcquire(path, "first"))
        try {
            assertNull(ProjectLeaseHandle.tryAcquire(path, "second"))
        } finally {
            first.close()
        }

        assertNotNull(ProjectLeaseHandle.tryAcquire(path, "third")).close()
    }
}
