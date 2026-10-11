package com.ac101m.sleepyllm.wake

import com.ac101m.sleepyllm.config.WakeConfig
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * [WakeSender] that shells out to a Wake-on-LAN utility, e.g. `wakeonlan aa:bb:cc:dd:ee:ff`
 * (or `wakeonlan -i <subnet> aa:bb:cc:dd:ee:ff` when a subnet is configured).
 *
 * The binary is resolved from [WakeConfig.command] and the machine's MAC address from
 * [WakeConfig.mac]; [WakeConfig.subnet], when set, is passed via `-i` so the magic
 * packet reaches the backend's subnet rather than the tool's default broadcast.
 * If the process exits non-zero the command's combined output is included in the
 * thrown exception so the failure is diagnosable in logs.
 */
class CommandWakeSender(
    private val command: List<String>,
    private val timeoutMs: Long = 15_000L,
) : WakeSender {
    private val logger = LoggerFactory.getLogger(CommandWakeSender::class.java)

    override fun wake() {
        logger.info("running wake command: {}", command.joinToString(" "))
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException("wake command timed out after ${timeoutMs}ms: ${command.joinToString(" ")}")
        }
        if (process.exitValue() != 0) {
            throw IllegalStateException("wake command exited with ${process.exitValue()}: ${output.trim()}")
        }
        if (output.isNotBlank()) {
            logger.debug("wake command output: {}", output.trim())
        }
    }

    companion object {
        fun from(wake: WakeConfig): CommandWakeSender {
            // Fail fast: a proxy whose wake tool cannot run is dead weight — and
            // without this, a missing binary only surfaces as a per-request 503.
            verifyRunnable(wake.command)
            return CommandWakeSender(buildCommand(wake))
        }

        /**
         * Proves, at startup, that [tool] can actually be spawned (`--version`,
         * falling back to `--help`, since wake-on-LAN tools differ in which flag
         * they support). A spawn failure for both means the binary is absent from
         * the PATH or not executable.
         *
         * Only spawn-ability is verified — a tool that runs but fails later (wrong
         * MAC, unreachable subnet) still fails loudly at wake time. [spawn] is
         * injectable purely for tests; the default spawns the real tool.
         *
         * Throws [IllegalStateException] so the proxy refuses to start, rather than
         * echoing a misleading "backend is asleep" 503 for every request.
         */
        fun verifyRunnable(
            tool: String,
            timeoutMs: Long = 5_000L,
            spawn: (List<String>) -> Process = { cmd -> ProcessBuilder(cmd).redirectErrorStream(true).start() },
        ) {
            fun probe(flag: String): Boolean = try {
                val process = spawn(listOf(tool, flag))
                try {
                    process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                } finally {
                    process.destroyForcibly()
                }
            } catch (e: IOException) {
                false
            }

            if (!probe("--version") && !probe("--help")) {
                throw IllegalStateException(
                    "wake command '$tool' could not be run: it is not on the PATH or is not executable." +
                        " Install the wake tool on this host, or set wake.mode = \"dummy\""
                )
            }
        }

        /**
         * Builds the wake command line: `command [-i subnet] mac`. The `-i` pair is
         * inserted only when [WakeConfig.subnet] is set, so a blank or unset subnet
         * falls back to the wake tool's default broadcast address.
         */
        fun buildCommand(wake: WakeConfig): List<String> {
            val args = mutableListOf(wake.command)
            wake.subnet?.takeIf { it.isNotBlank() }?.let {
                args += "-i"
                args += it
            }
            args += wake.mac
            return args
        }
    }
}
