package com.l1yp.mcp

import com.intellij.openapi.project.Project
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

internal data class McpHttpSecurityRequest(
    val remoteAddress: InetSocketAddress?,
    val origin: String?,
    val host: String?,
    val authorization: String?,
    val contentType: String?,
    val accept: String?,
    val actualPort: Int,
    val exchange: McpHttpExchange = McpHttpExchange.STREAMABLE_POST,
)

internal enum class McpHttpExchange {
    STREAMABLE_POST,
    LEGACY_SSE_GET,
    LEGACY_SSE_POST,
}

internal sealed interface McpHttpSecurityResult {
    data class Authorized(val project: Project) : McpHttpSecurityResult

    data class Rejected(val httpStatus: Int, val message: String) : McpHttpSecurityResult
}

internal class McpHttpSecurity(
    private val tokenRegistry: McpProjectTokenRegistry,
    private val rateLimiter: AuthenticationRateLimiter = AuthenticationRateLimiter(),
) {
    fun authorize(request: McpHttpSecurityRequest): McpHttpSecurityResult {
        val remoteAddress = request.remoteAddress?.address
        if (remoteAddress == null || !remoteAddress.isLoopbackAddress) {
            return McpHttpSecurityResult.Rejected(403, "Only loopback clients are allowed")
        }
        if (!isAllowedOrigin(request.origin)) {
            return McpHttpSecurityResult.Rejected(403, "Origin must be loopback")
        }
        if (!isExpectedHost(request.host, request.actualPort)) {
            return McpHttpSecurityResult.Rejected(403, "Host must match the loopback server endpoint")
        }
        when (request.exchange) {
            McpHttpExchange.STREAMABLE_POST -> {
                if (!isJsonContentType(request.contentType)) {
                    return McpHttpSecurityResult.Rejected(400, "Content-Type must be application/json")
                }
                if (!acceptsAll(request.accept, JSON_MEDIA_TYPE, SSE_MEDIA_TYPE)) {
                    return McpHttpSecurityResult.Rejected(
                        400,
                        "Accept must include application/json and text/event-stream",
                    )
                }
            }
            McpHttpExchange.LEGACY_SSE_GET -> {
                if (!acceptsAll(request.accept, SSE_MEDIA_TYPE)) {
                    return McpHttpSecurityResult.Rejected(400, "Accept must include text/event-stream")
                }
            }
            McpHttpExchange.LEGACY_SSE_POST -> {
                if (!isJsonContentType(request.contentType)) {
                    return McpHttpSecurityResult.Rejected(400, "Content-Type must be application/json")
                }
            }
        }

        val rateLimitKey = remoteAddress.hostAddress
        if (rateLimiter.isBlocked(rateLimitKey)) {
            return McpHttpSecurityResult.Rejected(429, "Too many failed authentication attempts")
        }
        val token = request.authorization
            ?.takeIf { it.startsWith(BEARER_PREFIX) }
            ?.removePrefix(BEARER_PREFIX)
            ?.takeIf { it.isNotBlank() && it == it.trim() }
        val project = token?.let(tokenRegistry::resolve)
        if (project == null) {
            rateLimiter.recordFailure(rateLimitKey)
            return McpHttpSecurityResult.Rejected(401, "A valid project Bearer token is required")
        }
        rateLimiter.recordSuccess(rateLimitKey)
        return McpHttpSecurityResult.Authorized(project)
    }

    private fun isAllowedOrigin(origin: String?): Boolean {
        if (origin.isNullOrBlank()) return true
        val uri = runCatching { URI(origin) }.getOrNull() ?: return false
        return uri.scheme in setOf("http", "https") && uri.host?.let(::isLoopbackHost) == true
    }

    private fun isExpectedHost(hostHeader: String?, actualPort: Int): Boolean {
        val (host, port) = parseHostAndPort(hostHeader ?: return false) ?: return false
        return port == actualPort && isLoopbackHost(host)
    }

    private fun parseHostAndPort(value: String): Pair<String, Int>? {
        if (value.startsWith("[")) {
            val closingBracket = value.indexOf(']')
            if (closingBracket <= 1 || closingBracket + 2 > value.length || value[closingBracket + 1] != ':') {
                return null
            }
            val port = value.substring(closingBracket + 2).toIntOrNull() ?: return null
            return value.substring(1, closingBracket) to port
        }
        val separator = value.lastIndexOf(':')
        if (separator <= 0) return null
        val port = value.substring(separator + 1).toIntOrNull() ?: return null
        return value.substring(0, separator) to port
    }

    private fun isLoopbackHost(host: String): Boolean {
        val normalizedHost = host.removePrefix("[").removeSuffix("]")
        if (normalizedHost.equals("localhost", ignoreCase = true)) return true
        if (!normalizedHost.all {
                it.isDigit() || it == '.' || it == ':' || it in 'a'..'f' || it in 'A'..'F'
            }
        ) {
            return false
        }
        return runCatching { InetAddress.getByName(normalizedHost).isLoopbackAddress }.getOrDefault(false)
    }

    private fun isJsonContentType(contentType: String?): Boolean {
        val mediaType = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return false
        return mediaType == "application/json" || (mediaType.startsWith("application/") && mediaType.endsWith("+json"))
    }

    private fun acceptsAll(accept: String?, vararg requiredMediaTypes: String): Boolean {
        val mediaTypes = accept
            ?.split(',')
            ?.map { it.substringBefore(';').trim().lowercase() }
            ?.toSet()
            ?: return false
        return requiredMediaTypes.all { it in mediaTypes }
    }

    private companion object {
        const val BEARER_PREFIX = "Bearer "
        const val JSON_MEDIA_TYPE = "application/json"
        const val SSE_MEDIA_TYPE = "text/event-stream"
    }
}

internal class AuthenticationRateLimiter(
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private val failuresByClient = ConcurrentHashMap<String, ArrayDeque<Long>>()

    fun isBlocked(client: String): Boolean = synchronized(failuresByClient) {
        val failures = failuresByClient[client] ?: return@synchronized false
        prune(failures)
        failures.size >= MAX_FAILURES
    }

    fun recordFailure(client: String) {
        synchronized(failuresByClient) {
            val failures = failuresByClient.computeIfAbsent(client) { ArrayDeque() }
            prune(failures)
            failures.addLast(clockMillis())
        }
    }

    fun recordSuccess(client: String) {
        failuresByClient.remove(client)
    }

    private fun prune(failures: ArrayDeque<Long>) {
        val cutoff = clockMillis() - FAILURE_WINDOW_MILLIS
        while (failures.firstOrNull()?.let { it < cutoff } == true) {
            failures.removeFirst()
        }
    }

    private companion object {
        const val MAX_FAILURES = 8
        const val FAILURE_WINDOW_MILLIS = 30_000L
    }
}
