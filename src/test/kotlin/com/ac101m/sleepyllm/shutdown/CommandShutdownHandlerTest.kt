package com.ac101m.sleepyllm.shutdown

import com.ac101m.sleepyllm.config.ShutdownConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommandShutdownHandlerTest {

    private fun fakeProcess(exitCode: Int = 0, timedOut: Boolean = false): Process = object : Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = exitCode
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = !timedOut
        override fun exitValue(): Int = exitCode
        override fun isAlive(): Boolean = timedOut
        override fun destroy() {}
    }

    private fun handler(
        checkCommand: List<String>? = null,
        spawn: (List<String>) -> Process = { fakeProcess(exitCode = 0) },
    ) = CommandShutdownHandler(command = listOf("poweroff"), checkCommand = checkCommand, spawn = spawn)

    @Test
    fun `isSafeToShutdown is always true without a guard command`() {
        val spawned = java.util.concurrent.atomic.AtomicInteger(0)
        val h = handler(spawn = { spawned.incrementAndGet(); fakeProcess() })
        assertFalse(h.hasLoginGuard(), "no check-command means no login guard")
        assertTrue(h.isSafeToShutdown())
        assertEquals(0, spawned.get(), "with no check-command the guard must not be spawned at all")
    }

    @Test
    fun `isSafeToShutdown is true when the guard exits zero`() {
        assertTrue(handler(checkCommand = listOf("who"), spawn = { fakeProcess(exitCode = 0) }).isSafeToShutdown())
    }

    @Test
    fun `isSafeToShutdown is false when the guard exits non-zero`() {
        assertFalse(handler(checkCommand = listOf("who"), spawn = { fakeProcess(exitCode = 1) }).isSafeToShutdown())
    }

    @Test
    fun `isSafeToShutdown is false when the guard times out`() {
        assertFalse(handler(checkCommand = listOf("who"), spawn = { fakeProcess(timedOut = true) }).isSafeToShutdown())
    }

    @Test
    fun `isSafeToShutdown is false when the guard cannot be spawned`() {
        assertFalse(handler(checkCommand = listOf("who"), spawn = { throw IOException("ENOENT") }).isSafeToShutdown())
    }

    @Test
    fun `shutdown returns true when the command exits zero`() {
        assertTrue(handler(spawn = { fakeProcess(exitCode = 0) }).shutdown())
    }

    @Test
    fun `shutdown returns false when the command exits non-zero`() {
        assertFalse(handler(spawn = { fakeProcess(exitCode = 42) }).shutdown())
    }

    @Test
    fun `shutdown returns false when the command times out`() {
        assertFalse(handler(spawn = { fakeProcess(timedOut = true) }).shutdown())
    }

    @Test
    fun `shutdown returns false and does not throw when the command cannot be spawned`() {
        assertFalse(handler(spawn = { throw IOException("ENOENT") }).shutdown())
    }

    @Test
    fun `from wires the configured command and guard`() {
        val spawned = mutableListOf<List<String>>()
        val cfg = ShutdownConfig(
            idleTimeoutMs = 1_000,
            pollIntervalMs = 100,
            command = listOf("ssh", "host", "sudo", "poweroff"),
            checkCommand = listOf("ssh", "host", "who"),
        )
        val h = CommandShutdownHandler.from(cfg, spawn = { cmd ->
            spawned += cmd
            fakeProcess(exitCode = 0)
        })
        assertTrue(h.hasLoginGuard(), "a configured check-command must be reported as an active guard")
        assertTrue(h.isSafeToShutdown())
        assertTrue(h.shutdown())
        assertEquals(
            listOf(listOf("ssh", "host", "who"), listOf("ssh", "host", "sudo", "poweroff")),
            spawned,
            "the guard and shutdown argv from the config must both be used",
        )
    }
}
