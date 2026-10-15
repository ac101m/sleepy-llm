package com.ac101m.sleepyllm.wake

import com.ac101m.sleepyllm.config.WakeConfig
import com.ac101m.sleepyllm.os.runCommand
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * [WakeSender] that runs a user-configured Wake-on-LAN command, e.g.
 * `wakeonlan -i 192.168.10.255 aa:bb:cc:dd:ee:ff`.
 *
 * The whole invocation is taken verbatim from [WakeConfig.command] and run via
 * [runCommand] with no shell, exactly like the shutdown command — the proxy does
 * not assemble or second-guess it. If the process exits non-zero the command's
 * combined output is included in the thrown exception so the failure is
 * diagnosable in logs.
 */
class CommandWakeSender(
    private val command: List<String>,
    private val timeoutMs: Long = 15_000L,
) : WakeSender {
    private val logger = LoggerFactory.getLogger(CommandWakeSender::class.java)

    override fun wake() {
        logger.info("running wake command: {}", command.joinToString(" "))
        val result = runCommand(command, timeoutMs)
        if (result.timedOut) {
            throw IllegalStateException("wake command timed out after ${timeoutMs}ms: ${command.joinToString(" ")}")
        }
        if (result.exitCode != 0) {
            throw IllegalStateException("wake command exited with ${result.exitCode}: ${result.output.trim()}")
        }
        if (result.output.isNotBlank()) {
            logger.debug("wake command output: {}", result.output.trim())
        }
    }

    companion object {
        fun from(wake: WakeConfig): CommandWakeSender {
            // Fail fast: a proxy whose wake command cannot run is dead weight — and
            // without this, a missing binary only surfaces as a per-request 503.
            verifyRunnable(wake.command)
            return CommandWakeSender(wake.command)
        }

        /**
         * Proves, at startup, that the program named by the first element of [command]
         * can actually be spawned (`--version`, falling back to `--help`, since
         * wake-on-LAN tools differ in which flag they support). A spawn failure for
         * both means the binary is absent from the PATH or not executable.
         *
         * Only spawn-ability is verified — a command that runs but fails later (wrong
         * MAC, unreachable subnet) still fails loudly at wake time. [spawn] is
         * injectable purely for tests; the default spawns the real tool.
         *
         * Throws [IllegalStateException] so the proxy refuses to start, rather than
         * echoing a misleading "backend is asleep" 503 for every request.
         */
        fun verifyRunnable(
            command: List<String>,
            timeoutMs: Long = 5_000L,
            spawn: (List<String>) -> Process = { cmd -> ProcessBuilder(cmd).redirectErrorStream(true).start() },
        ) {
            val tool = command.first()

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
                        " Install the wake tool on this host, or point wake.command at one that is"
                )
            }
        }
    }
}
