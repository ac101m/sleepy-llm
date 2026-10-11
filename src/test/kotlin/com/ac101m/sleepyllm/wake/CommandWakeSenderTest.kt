package com.ac101m.sleepyllm.wake

import com.ac101m.sleepyllm.config.WakeConfig
import com.ac101m.sleepyllm.config.WakeMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CommandWakeSenderTest {

    /** A [Process] that "runs" without an OS process, for probing logic tests. */
    private fun fakeProcess(exitCode: Int): Process = object : Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = exitCode
        override fun exitValue(): Int = exitCode
        override fun destroy() {}
    }

    private fun config(subnet: String?) = WakeConfig(
        mode = WakeMode.COMMAND,
        command = "wakeonlan",
        mac = "aa:bb:cc:dd:ee:ff",
        subnet = subnet,
    )

    @Test
    fun `command includes -i subnet when one is configured`() {
        assertEquals(
            listOf("wakeonlan", "-i", "192.168.10.255", "aa:bb:cc:dd:ee:ff"),
            CommandWakeSender.buildCommand(config("192.168.10.255")),
        )
    }

    @Test
    fun `command omits -i when no subnet is configured`() {
        assertEquals(
            listOf("wakeonlan", "aa:bb:cc:dd:ee:ff"),
            CommandWakeSender.buildCommand(config(null)),
        )
    }

    @Test
    fun `command omits -i when subnet is blank`() {
        assertEquals(
            listOf("wakeonlan", "aa:bb:cc:dd:ee:ff"),
            CommandWakeSender.buildCommand(config("   ")),
        )
    }

    @Test
    fun `verifyRunnable accepts a tool that spawns cleanly`() {
        val spawned = mutableListOf<List<String>>()
        CommandWakeSender.verifyRunnable("wakeonlan", spawn = { cmd ->
            spawned += cmd
            fakeProcess(exitCode = 0)
        })
        assertEquals(listOf("wakeonlan", "--version"), spawned[0])
    }

    @Test
    fun `verifyRunnable falls back to --help when --version cannot spawn`() {
        val spawned = mutableListOf<List<String>>()
        val spawn: (List<String>) -> Process = { cmd ->
            spawned += cmd
            if (cmd.last() == "--version") throw IOException("cannot run") else fakeProcess(exitCode = 1)
        }
        // Exit code is irrelevant to the probe: spawning alone proves presence.
        CommandWakeSender.verifyRunnable("wakeonlan", spawn = spawn)
        assertEquals(listOf("wakeonlan", "--version", "wakeonlan", "--help"), spawned.flatten())
    }

    @Test
    fun `verifyRunnable fails fast when the tool cannot be spawned at all`() {
        val error = assertFailsWith<IllegalStateException> {
            CommandWakeSender.verifyRunnable("no-such-wake-tool", spawn = { throw IOException("ENOENT") })
        }
        assertTrue("no-such-wake-tool" in error.message.orEmpty())
    }
}
