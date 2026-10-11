package com.ac101m.sleepyllm.wake

import com.ac101m.sleepyllm.config.WakeConfig
import org.slf4j.LoggerFactory

/**
 * [WakeSender] that shells out to a Wake-on-LAN utility, e.g. `wakeonlan aa:bb:cc:dd:ee:ff`.
 *
 * The binary is resolved from [WakeConfig.command] and the machine's MAC address from
 * [WakeConfig.mac]. If the process exits non-zero the command's combined output is
 * included in the thrown exception so the failure is diagnosable in logs.
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
            CommandWakeSender(listOf(wake.command, wake.mac))
    }
}
