package com.l1yp.mcp

import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

class McpHttpSecurityTest {
    private val project = fakeProject("project")
    private val registry = McpProjectTokenRegistry().apply { bind(project, "secret-token") }
    private val security = McpHttpSecurity(registry)

    @Test
    fun `accepts a valid project token from a loopback client`() {
        val result = security.authorize(validRequest())

        assertSame(project, assertIs<McpHttpSecurityResult.Authorized>(result).project)
    }

    @Test
    fun `rejects missing and incorrect tokens without crossing projects`() {
        val anotherProject = fakeProject("another")
        registry.bind(anotherProject, "another-token")

        assertEquals(401, rejected(validRequest().copy(authorization = null)).httpStatus)
        assertEquals(401, rejected(validRequest().copy(authorization = "Bearer wrong")).httpStatus)
        assertSame(
            anotherProject,
            assertIs<McpHttpSecurityResult.Authorized>(
                security.authorize(validRequest().copy(authorization = "Bearer another-token")),
            ).project,
        )
    }

    @Test
    fun `rejects non-loopback remotes origins and hosts`() {
        assertEquals(
            403,
            rejected(
                validRequest().copy(remoteAddress = InetSocketAddress(InetAddress.getByName("192.0.2.1"), 1234)),
            ).httpStatus,
        )
        assertEquals(403, rejected(validRequest().copy(origin = "https://example.com")).httpStatus)
        assertEquals(403, rejected(validRequest().copy(host = "example.com:63342")).httpStatus)
        assertEquals(403, rejected(validRequest().copy(host = "127.0.0.1:63343")).httpStatus)
    }

    @Test
    fun `allows absent and explicit loopback origins`() {
        assertIs<McpHttpSecurityResult.Authorized>(security.authorize(validRequest().copy(origin = null)))
        assertIs<McpHttpSecurityResult.Authorized>(
            security.authorize(validRequest().copy(origin = "http://localhost:3000")),
        )
        assertIs<McpHttpSecurityResult.Authorized>(
            security.authorize(validRequest().copy(origin = "http://[::1]:3000")),
        )
    }

    @Test
    fun `requires JSON content and both MCP accept media types`() {
        assertEquals(400, rejected(validRequest().copy(contentType = "text/plain")).httpStatus)
        assertEquals(400, rejected(validRequest().copy(accept = "application/json")).httpStatus)
        assertIs<McpHttpSecurityResult.Authorized>(
            security.authorize(validRequest().copy(contentType = "application/vnd.mcp+json; charset=utf-8")),
        )
    }

    @Test
    fun `rate limits repeated authentication failures and clears on success`() {
        repeat(8) {
            assertEquals(401, rejected(validRequest().copy(authorization = "Bearer wrong-$it")).httpStatus)
        }
        assertEquals(429, rejected(validRequest()).httpStatus)

        var now = 0L
        val limiter = AuthenticationRateLimiter { now }
        val isolatedSecurity = McpHttpSecurity(registry, limiter)
        repeat(8) {
            rejected(isolatedSecurity.authorize(validRequest().copy(authorization = "Bearer wrong-$it")))
        }
        now = 30_001L
        assertIs<McpHttpSecurityResult.Authorized>(isolatedSecurity.authorize(validRequest()))
    }

    @Test
    fun `generates independent 256-bit URL-safe tokens`() {
        val first = McpProjectTokenGenerator.generate()
        val second = McpProjectTokenGenerator.generate()

        assertNotEquals(first, second)
        assertEquals(32, Base64.getUrlDecoder().decode(first).size)
        assertEquals(43, first.length)
    }

    private fun validRequest() = McpHttpSecurityRequest(
        remoteAddress = InetSocketAddress(InetAddress.getLoopbackAddress(), 12345),
        origin = null,
        host = "127.0.0.1:63342",
        authorization = "Bearer secret-token",
        contentType = "application/json; charset=utf-8",
        accept = "application/json, text/event-stream",
        actualPort = 63342,
    )

    private fun rejected(request: McpHttpSecurityRequest): McpHttpSecurityResult.Rejected =
        rejected(security.authorize(request))

    private fun rejected(result: McpHttpSecurityResult): McpHttpSecurityResult.Rejected =
        assertIs(result)

    private fun fakeProject(label: String): Project {
        var proxy: Project? = null
        proxy = Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { _, method, args ->
            when (method.name) {
                "isDisposed" -> false
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> label
                else -> error("Unexpected Project method: ${method.name}")
            }
        } as Project
        return proxy
    }
}
