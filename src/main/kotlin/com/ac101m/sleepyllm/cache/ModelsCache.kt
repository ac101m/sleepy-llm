package com.ac101m.sleepyllm.cache

import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Mirrors the OpenAI-compatible `/v1/models` response on disk so the proxy can
 * serve the model list without waking the backend.
 *
 * The file is read once at construction into an in-memory mirror, so [read] is
 * lock-free and disk-free on the request path. [write] is blocking disk I/O and
 * must only be called from a background context (the health-probe callback and
 * the models forward both run on `Dispatchers.IO`); it never throws — on any
 * failure the previous copy (disk and memory) is preserved and the error is
 * logged, so a disk error can neither fail a forwarded request nor flip a
 * health probe to unhealthy. A half-written file is never observable: updates
 * go to a sibling `<file>.tmp` and are moved into place atomically.
 */
class ModelsCache(
    private val file: File,
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
) {
    private val logger = LoggerFactory.getLogger(ModelsCache::class.java)

    @Volatile
    private var content: ByteArray = loadInitial()

    /** Cached bytes, or null when nothing usable is cached yet. */
    fun read(): ByteArray? = content.takeIf { it.isNotEmpty() }

    /**
     * Atomically replaces the cached copy on disk and in memory. Never throws:
     * failures are logged and leave the previous copy intact.
     */
    fun write(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        if (bytes.size > maxBytes) {
            logger.warn("refusing to cache {} bytes (max {})", bytes.size, maxBytes)
            return
        }
        val absolute = file.absoluteFile
        val dir = absolute.parentFile
        val tmp = File(dir, "${absolute.name}.tmp")
        try {
            tmp.writeBytes(bytes)
            moveIntoPlace(tmp, absolute)
            content = bytes
        } catch (t: Throwable) {
            logger.warn("failed to update models cache {}: {}", file, t.message)
        } finally {
            if (tmp.exists()) tmp.delete() // best-effort cleanup
        }
    }

    private fun moveIntoPlace(tmp: File, target: File) {
        val tmpPath = tmp.toPath()
        val targetPath = target.toPath()
        try {
            Files.move(tmpPath, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmpPath, targetPath, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun loadInitial(): ByteArray = try {
        file.readBytes()
    } catch (e: IOException) {
        // Absent file, unreadable file, or a path that is a directory: start cold.
        if (file.exists()) logger.info("models cache {} unreadable ({}); starting cold", file, e.message)
        EMPTY
    }

    private companion object {
        /** Sanity cap: a models listing is small; refuses to cache large error blobs. */
        const val DEFAULT_MAX_BYTES = 16 * 1024 * 1024

        val EMPTY = ByteArray(0)
    }
}
