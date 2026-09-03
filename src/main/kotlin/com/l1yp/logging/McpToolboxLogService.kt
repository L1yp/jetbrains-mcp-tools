package com.l1yp.logging

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList

internal enum class McpLogLevel {
    INFO,
    SUCCESS,
    WARNING,
    ERROR,
}

internal data class McpLogEntry(
    val timestamp: Instant,
    val level: McpLogLevel,
    val source: String,
    val message: String,
)

internal sealed interface McpLogUpdate {
    data class EntryAdded(val entry: McpLogEntry) : McpLogUpdate
    data object Cleared : McpLogUpdate
}

internal fun interface McpLogListener {
    fun onUpdate(update: McpLogUpdate)
}

@Service(Service.Level.PROJECT)
internal class McpToolboxLogService(private val project: Project) {
    private val entries = ArrayDeque<McpLogEntry>()
    private val listeners = CopyOnWriteArrayList<McpLogListener>()

    fun snapshot(): List<McpLogEntry> = synchronized(entries) { entries.toList() }

    fun addListener(parentDisposable: Disposable, listener: McpLogListener): List<McpLogEntry> {
        val snapshot = synchronized(entries) {
            listeners.add(listener)
            entries.toList()
        }
        Disposer.register(parentDisposable) { listeners.remove(listener) }
        return snapshot
    }

    fun clear() {
        synchronized(entries) {
            entries.clear()
            notifyListeners(McpLogUpdate.Cleared)
        }
    }

    fun info(source: String, message: String) = append(McpLogLevel.INFO, source, message)

    fun success(source: String, message: String) = append(McpLogLevel.SUCCESS, source, message)

    fun warning(source: String, message: String) = append(McpLogLevel.WARNING, source, message)

    fun error(source: String, message: String) = append(McpLogLevel.ERROR, source, message)

    private fun append(level: McpLogLevel, source: String, message: String) {
        if (project.isDisposed) return
        val entry = McpLogEntry(
            timestamp = Instant.now(),
            level = level,
            source = McpLogSanitizer.sanitize(source).take(MAX_SOURCE_LENGTH),
            message = McpLogSanitizer.sanitize(message).take(MAX_MESSAGE_LENGTH),
        )
        synchronized(entries) {
            entries.addLast(entry)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
            notifyListeners(McpLogUpdate.EntryAdded(entry))
        }
        LOG.info("[${entry.level}] [${entry.source}] ${entry.message}")
    }

    private fun notifyListeners(update: McpLogUpdate) {
        listeners.forEach { listener ->
            runCatching { listener.onUpdate(update) }.onFailure { listeners.remove(listener) }
        }
    }

    private companion object {
        const val MAX_ENTRIES = 2_000
        const val MAX_SOURCE_LENGTH = 80
        const val MAX_MESSAGE_LENGTH = 4_000
        val LOG = logger<McpToolboxLogService>()
    }
}

internal object McpLogSanitizer {
    private val bearerToken = Regex("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]+")
    private val authorizationJson = Regex("(?i)(\"Authorization\"\\s*:\\s*\")[^\"]*(\")")
    private val tokenParameter = Regex("(?i)([?&](?:access_)?token=)[^&#\\s]+")
    private val urlUserInfo = Regex("(https?://)[^/@\\s]+@", RegexOption.IGNORE_CASE)

    fun sanitize(value: String): String = value
        .replace(authorizationJson, $$"$1<redacted>$2")
        .replace(bearerToken, $$"$1<redacted>")
        .replace(tokenParameter, $$"$1<redacted>")
        .replace(urlUserInfo, $$"$1<redacted>@")
}
