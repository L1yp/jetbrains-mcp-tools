package com.l1yp.agentconfig

import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GitExcludeManagerTest {
    @Test
    fun `protects untracked local config and blocks tracked config`() {
        val root = Files.createTempDirectory("mcp-git-")
        if (!git(root, "init")) return
        val project = fakeProject(root)
        val manager = GitExcludeManager()
        val config = root.resolve(".trae/mcp.json")

        assertEquals(GitTrackingStatus.UNTRACKED, manager.trackingStatus(project, config))
        assertIs<GitProtectionResult.Protected>(manager.protect(project, config))
        val exclude = Files.readString(root.resolve(".git/info/exclude"), StandardCharsets.UTF_8)
        assertTrue(exclude.lineSequence().any { it.trim() == "/.trae/mcp.json" })

        Files.createDirectories(config.parent)
        Files.writeString(config, "{}", StandardCharsets.UTF_8)
        assertTrue(git(root, "add", ".trae/mcp.json", "-f"))
        assertEquals(GitTrackingStatus.TRACKED, manager.trackingStatus(project, config))
        assertIs<GitProtectionResult.Blocked>(manager.protect(project, config))
    }

    @Test
    fun `anchors excludes at the Git root for a project in a repository subdirectory`() {
        val repository = Files.createTempDirectory("mcp-git-parent-")
        if (!git(repository, "init")) return
        val projectRoot = repository.resolve("services/order")
        Files.createDirectories(projectRoot)
        val project = fakeProject(projectRoot)
        val config = projectRoot.resolve(".qoder/settings.local.json")

        assertIs<GitProtectionResult.Protected>(GitExcludeManager().protect(project, config))

        val exclude = Files.readString(repository.resolve(".git/info/exclude"), StandardCharsets.UTF_8)
        assertTrue(exclude.lineSequence().any { it.trim() == "/services/order/.qoder/settings.local.json" })
    }

    private fun git(root: Path, vararg arguments: String): Boolean = runCatching {
        val process = ProcessBuilder(listOf("git", "-C", root.toString()) + arguments)
            .redirectErrorStream(true)
            .start()
        process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0
    }.getOrDefault(false)

    private fun fakeProject(root: Path): Project {
        var proxy: Project? = null
        proxy = Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getBasePath" -> root.toString()
                "isDisposed" -> false
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> root.toString()
                else -> error("Unexpected Project method: ${method.name}")
            }
        } as Project
        return requireNotNull(proxy)
    }
}
