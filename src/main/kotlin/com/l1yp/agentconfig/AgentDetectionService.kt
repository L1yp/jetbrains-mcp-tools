package com.l1yp.agentconfig

import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal class AgentDetectionService(
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private val cache = ConcurrentHashMap<String, CachedDetection>()

    fun detect(adapter: AgentConfigAdapter, project: Project): AgentDetection {
        val projectDetection = adapter.detect(project)
        if (projectDetection.detected) return projectDetection
        val command = COMMANDS_BY_ADAPTER[adapter.id] ?: return projectDetection
        val cached = cache[command]
        val now = clockMillis()
        if (cached != null && now - cached.timestamp < CACHE_MILLIS) return cached.detection
        val detection = findCommand(command)
        cache[command] = CachedDetection(now, detection)
        return detection
    }

    private fun findCommand(command: String): AgentDetection = runCatching {
        val locator = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "where" else "which"
        val process = ProcessBuilder(locator, command).redirectErrorStream(true).start()
        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return AgentDetection(false)
        }
        if (process.exitValue() != 0) return AgentDetection(false)
        val path = process.inputStream.readAllBytes().toString(StandardCharsets.UTF_8)
            .lineSequence()
            .firstOrNull()
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        AgentDetection(path != null, path)
    }.getOrDefault(AgentDetection(false))

    private data class CachedDetection(val timestamp: Long, val detection: AgentDetection)

    private companion object {
        const val CACHE_MILLIS = 30_000L
        const val COMMAND_TIMEOUT_SECONDS = 2L
        val COMMANDS_BY_ADAPTER = mapOf(
            "codex" to "codex",
            "trae" to "trae",
            "qoder" to "qoder",
            "oh_my_pi" to "omp",
            "kimi_code" to "kimi",
            "zcode" to "zcode",
            "opencode" to "opencode",
            "mimocode" to "mimo",
        )
    }
}
