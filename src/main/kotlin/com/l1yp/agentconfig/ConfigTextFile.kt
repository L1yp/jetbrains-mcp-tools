package com.l1yp.agentconfig

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

internal data class TextFileSnapshot(
    val path: Path,
    val exists: Boolean,
    val content: String,
    val hasBom: Boolean,
    val newline: String,
    val endsWithNewline: Boolean,
    val digest: String,
)

internal sealed interface AtomicWriteResult {
    data class Success(val changed: Boolean) : AtomicWriteResult
    data class Conflict(val reason: String) : AtomicWriteResult
    data class Failure(val reason: String) : AtomicWriteResult
}

internal object ConfigTextFile {
    private val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    fun read(path: Path): TextFileSnapshot {
        if (!Files.exists(path)) {
            return TextFileSnapshot(path, false, "", false, "\n", false, MISSING_DIGEST)
        }
        val bytes = Files.readAllBytes(path)
        val hasBom = bytes.size >= utf8Bom.size && utf8Bom.indices.all { bytes[it] == utf8Bom[it] }
        val offset = if (hasBom) utf8Bom.size else 0
        val content = String(bytes, offset, bytes.size - offset, StandardCharsets.UTF_8)
        val newline = if ("\r\n" in content) "\r\n" else "\n"
        return TextFileSnapshot(
            path = path,
            exists = true,
            content = content,
            hasBom = hasBom,
            newline = newline,
            endsWithNewline = content.endsWith("\n"),
            digest = sha256(bytes),
        )
    }

    fun writeAtomically(
        path: Path,
        originalDigest: String,
        transform: (String) -> String,
    ): AtomicWriteResult {
        var expectedDigest = originalDigest
        repeat(2) { attempt ->
            val before = runCatching { read(path) }.getOrElse { error ->
                return AtomicWriteResult.Failure(error.message ?: error.javaClass.simpleName)
            }
            if (before.digest != expectedDigest) expectedDigest = before.digest
            val transformed = runCatching { transform(before.content) }.getOrElse { error ->
                return AtomicWriteResult.Failure(error.message ?: error.javaClass.simpleName)
            }
            val normalized = preserveStyle(transformed, before)
            val bytes = encode(normalized, before.hasBom)
            if (before.exists && MessageDigest.isEqual(Files.readAllBytes(path), bytes)) {
                return AtomicWriteResult.Success(changed = false)
            }

            val verification = runCatching { read(path) }.getOrElse { error ->
                return AtomicWriteResult.Failure(error.message ?: error.javaClass.simpleName)
            }
            if (verification.digest != expectedDigest) {
                if (attempt == 0) {
                    expectedDigest = verification.digest
                    return@repeat
                }
                return AtomicWriteResult.Conflict("Configuration changed concurrently twice")
            }

            return runCatching {
                writeBytes(path, bytes)
                AtomicWriteResult.Success(changed = true)
            }.getOrElse { error ->
                AtomicWriteResult.Failure(error.message ?: error.javaClass.simpleName)
            }
        }
        return AtomicWriteResult.Conflict("Configuration changed concurrently")
    }

    fun deleteIfUnchanged(path: Path, originalDigest: String): AtomicWriteResult {
        val current = runCatching { read(path) }.getOrElse { error ->
            return AtomicWriteResult.Failure(error.message ?: error.javaClass.simpleName)
        }
        if (!current.exists) return AtomicWriteResult.Success(changed = false)
        if (current.digest != originalDigest) {
            return AtomicWriteResult.Conflict("Configuration changed before it could be removed")
        }
        return runCatching {
            Files.delete(path)
            AtomicWriteResult.Success(changed = true)
        }.getOrElse { error -> AtomicWriteResult.Failure(error.message ?: error.javaClass.simpleName) }
    }

    fun digest(content: String): String = sha256(content.toByteArray(StandardCharsets.UTF_8))

    fun isUnchangedAfterStyle(content: String, before: TextFileSnapshot): Boolean =
        preserveStyle(content, before) == before.content

    private fun preserveStyle(content: String, before: TextFileSnapshot): String {
        val lfContent = content.replace("\r\n", "\n")
        val newlineAdjusted = if (before.newline == "\r\n") lfContent.replace("\n", "\r\n") else lfContent
        if (!before.exists) return newlineAdjusted.trimEnd('\r', '\n') + "\n"
        return when {
            before.endsWithNewline -> newlineAdjusted.trimEnd('\r', '\n') + before.newline
            else -> newlineAdjusted.trimEnd('\r', '\n')
        }
    }

    private fun encode(content: String, bom: Boolean): ByteArray {
        val contentBytes = content.toByteArray(StandardCharsets.UTF_8)
        return if (bom) utf8Bom + contentBytes else contentBytes
    }

    private fun writeBytes(path: Path, bytes: ByteArray) {
        Files.createDirectories(path.parent)
        val temporaryPath = Files.createTempFile(path.parent, ".${path.fileName}.", ".tmp")
        try {
            FileChannel.open(
                temporaryPath,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use { channel ->
                var buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            try {
                Files.move(
                    temporaryPath,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporaryPath, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporaryPath)
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private const val MISSING_DIGEST = "missing"
}
