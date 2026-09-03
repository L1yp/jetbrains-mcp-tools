package com.l1yp.agentconfig

import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.l1yp.mcp.McpProtocol
import org.jetbrains.ide.BuiltInServerManager
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

internal sealed interface ProjectLeaseStatus {
    data object Owned : ProjectLeaseStatus
    data class HeldByAnotherProcess(val metadata: String?) : ProjectLeaseStatus
    data class Unavailable(val reason: String) : ProjectLeaseStatus
}

@Service(Service.Level.PROJECT)
internal class ProjectConfigLease(private val project: Project) : Disposable {
    private var handle: ProjectLeaseHandle? = null

    @Synchronized
    fun acquire(): ProjectLeaseStatus {
        if (handle?.isValid == true) return ProjectLeaseStatus.Owned
        val basePath = project.basePath
            ?: return ProjectLeaseStatus.Unavailable("Project has no base path")
        val canonicalPath = runCatching { Path.of(basePath).toRealPath() }
            .getOrElse { Path.of(basePath).toAbsolutePath().normalize() }
        val lockPath = leaseDirectory().resolve("${sha256(canonicalPath.toString())}.lock")
        val newHandle = runCatching { ProjectLeaseHandle.tryAcquire(lockPath, metadata()) }
            .getOrElse { error ->
                return ProjectLeaseStatus.Unavailable(error.message ?: error.javaClass.simpleName)
            }
        if (newHandle == null) {
            val owner = runCatching { Files.readString(lockPath, StandardCharsets.UTF_8) }.getOrNull()
            return ProjectLeaseStatus.HeldByAnotherProcess(owner)
        }
        handle = newHandle
        return ProjectLeaseStatus.Owned
    }

    override fun dispose() {
        handle?.close()
        handle = null
    }

    private fun metadata(): String = JsonObject().apply {
        addProperty("pid", ProcessHandle.current().pid())
        addProperty("ideBuild", ApplicationInfo.getInstance().build.asString())
        addProperty(
            "endpoint",
            "http://127.0.0.1:${BuiltInServerManager.getInstance().port}${McpProtocol.ENDPOINT_PATH}",
        )
    }.toString()

    private fun leaseDirectory(): Path = Path.of(
        System.getProperty("user.home"),
        ".jetbrains-mcp-tools",
        "locks",
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

internal class ProjectLeaseHandle private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    val isValid: Boolean
        get() = lock.isValid

    override fun close() {
        runCatching(lock::release)
        runCatching(channel::close)
    }

    companion object {
        fun tryAcquire(path: Path, metadata: String): ProjectLeaseHandle? {
            Files.createDirectories(path.parent)
            val channel = FileChannel.open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
            )
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (lock == null) {
                channel.close()
                return null
            }
            val bytes = metadata.toByteArray(StandardCharsets.UTF_8)
            channel.truncate(0)
            channel.position(0)
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
            return ProjectLeaseHandle(channel, lock)
        }
    }
}
