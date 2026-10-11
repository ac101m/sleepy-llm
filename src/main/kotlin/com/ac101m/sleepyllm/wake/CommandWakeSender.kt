package com.ac101m.sleepyllm.wake

import com.ac101m.sleepyllm.config.WakeConfig
import org.slf4j.LoggerFactory

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
        fun from(wake: WakeConfig): CommandWakeSender =
            CommandWakeSender(buildCommand(wake))

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
