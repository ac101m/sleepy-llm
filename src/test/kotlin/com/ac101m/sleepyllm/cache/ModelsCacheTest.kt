package com.ac101m.sleepyllm.cache

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import java.io.File
import java.nio.file.Files

class ModelsCacheTest {

    /** Fresh, non-existent cache path inside a throwaway temp directory. */
    private fun tempPath(): File {
        val dir = Files.createTempDirectory("models-cache-test").toFile()
        return File(dir, "models.json")
    }

    @Test
    fun `roundtrips and persists across instances`() {
        val path = tempPath()
        val body = """{"object":"list","data":[{"id":"m1","object":"model"}]}""".toByteArray()
        ModelsCache(path).write(body)
        assertContentEquals(body, ModelsCache(path).read())
    }

    @Test
    fun `absent file reads as null`() {
        assertNull(ModelsCache(tempPath()).read())
    }

    @Test
    fun `empty file reads as null`() {
        val path = tempPath()
        path.writeBytes(ByteArray(0))
        assertNull(ModelsCache(path).read())
    }

    @Test
    fun `unreadable path reads as null`() {
        val dir = Files.createTempDirectory("models-cache-test").toFile()
        assertNull(ModelsCache(dir).read(), "a directory is not a readable cache file")
    }

    @Test
    fun `write overwrites and cleans up the temp file`() {
        val path = tempPath()
        val first = """{"object":"list","data":[]}""".toByteArray()
        val second = """{"object":"list","data":[{"id":"m2"}]}""".toByteArray()
        ModelsCache(path).write(first)
        ModelsCache(path).write(second)
        assertContentEquals(second, ModelsCache(path).read())
        assertFalse(
            File(path.parentFile, "${path.name}.tmp").exists(),
            "no stale temp file should remain after a write",
        )
    }

    @Test
    fun `oversized body is skipped and prior copy preserved`() {
        val path = tempPath()
        val cache = ModelsCache(path, maxBytes = 4)
        cache.write("ok!".toByteArray())
        cache.write("way-too-large".toByteArray())
        assertContentEquals("ok!".toByteArray(), cache.read())
    }

    @Test
    fun `write failure is swallowed and prior copy preserved`() {
        val path = tempPath()
        val cache = ModelsCache(path)
        cache.write("first".toByteArray())
        assertContentEquals("first".toByteArray(), cache.read())

        // Remove the parent directory so the next write cannot succeed.
        path.parentFile.deleteRecursively()
        cache.write("second".toByteArray()) // must not throw
        assertContentEquals("first".toByteArray(), cache.read(), "in-memory copy must survive a failed write")
    }
}
