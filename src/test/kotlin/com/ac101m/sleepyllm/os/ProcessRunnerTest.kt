package com.ac101m.sleepyllm.os

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProcessRunnerTest {

    /**
     * A [Process] that "runs" without an OS process. The two-arg [Process.waitFor]
     * is the one [runCommand] uses; overriding it to [timedOut] simulates a child
     * that never exits, and [onDestroy] observes the forced kill.
     */
    private fun fakeProcess(
        output: String = "",
        exitCode: Int = 0,
        timedOut: Boolean = false,
        onDestroy: () -> Unit = {},
    ): Process = object : Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(output.toByteArray())
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = exitCode
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = !timedOut
        override fun exitValue(): Int = exitCode
        override fun destroy() {
            onDestroy()
        }
    }

    @Test
    fun `returns the exit code and captured output`() {
        val result = runCommand(listOf("true"), 1_000, spawn = { fakeProcess(output = "hello", exitCode = 0) })
        assertEquals(0, result.exitCode)
        assertEquals("hello", result.output)
        assertFalse(result.timedOut)
    }

    @Test
    fun `captures output even when the exit code is non-zero`() {
        val result = runCommand(listOf("false"), 1_000, spawn = { fakeProcess(output = "boom", exitCode = 1) })
        assertEquals(1, result.exitCode)
        assertEquals("boom", result.output)
        assertFalse(result.timedOut)
    }

    @Test
    fun `reports a timed-out process and force-destroys it`() {
        val destroyed = AtomicBoolean(false)
        val result = runCommand(listOf("sleep"), 1_000, spawn = {
            fakeProcess(timedOut = true, onDestroy = { destroyed.set(true) })
        })
        assertTrue(result.timedOut)
        assertTrue(destroyed.get(), "a timed-out process must be force-destroyed")
    }

    @Test
    fun `propagates a spawn failure`() {
        assertFailsWith<IOException> {
            runCommand(listOf("no-such-binary"), 1_000, spawn = { throw IOException("ENOENT") })
        }
    }
}
