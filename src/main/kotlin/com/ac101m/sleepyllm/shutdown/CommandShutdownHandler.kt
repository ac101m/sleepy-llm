package com.ac101m.sleepyllm.shutdown

import com.ac101m.sleepyllm.config.ShutdownConfig
import com.ac101m.sleepyllm.os.runCommand
import org.slf4j.LoggerFactory

/**
 * [ShutdownHandler] that shells out to user-configured commands.
 *
 * Both commands are full argv lists (e.g. `["ssh", "user@host", "sudo", "poweroff"]`)
 * run via [ProcessBuilder] with no shell, so the user controls every argument and
 * no quoting/escaping layer sits between the config and the child.
 *
 * The proxy can never run on the backend machine itself (it would shut itself
 * down), so these commands are typically remote and the user encodes reachability
 * in the command string. The guard's exit code is the contract: 0 = nobody logged
 * in (safe), non-zero = someone is logged in — or the machine was unreachable, in
 * which case [isSafeToShutdown] also reports "not safe" (fail-safe to keeping the
 * machine up).
 */
class CommandShutdownHandler(
    private val command: List<String>,
    private val checkCommand: List<String>? = null,
    private val timeoutMs: Long = 60_000L,
    private val spawn: (List<String>) -> Process = { cmd -> ProcessBuilder(cmd).redirectErrorStream(true).start() },
) : ShutdownHandler {
    private val logger = LoggerFactory.getLogger(CommandShutdownHandler::class.java)

    override fun isSafeToShutdown(): Boolean {
        val check = checkCommand ?: return true
        return try {
            val result = runCommand(check, timeoutMs, spawn)
            when {
                result.timedOut -> {
                    logger.warn("login check timed out after {}ms: {}", timeoutMs, check.joinToString(" "))
                    false
                }
                result.exitCode != 0 -> {
                    logger.debug("login check reported a logged-in user (exit {}): {}", result.exitCode, result.output.trim())
                    false
                }
                else -> true
            }
        } catch (t: Throwable) {
            logger.warn("login check could not be run, assuming someone is logged in: {}", t.message)
            false
        }
    }

    override fun shutdown(): Boolean {
        return try {
            val result = runCommand(command, timeoutMs, spawn)
            when {
                result.timedOut -> {
                    logger.warn("shutdown command timed out after {}ms: {}", timeoutMs, command.joinToString(" "))
                    false
                }
                result.exitCode != 0 -> {
                    logger.error("shutdown command exited with {}: {}", result.exitCode, result.output.trim())
                    false
                }
                else -> {
                    logger.info("shutdown command succeeded")
                    true
                }
            }
        } catch (t: Throwable) {
            logger.error("shutdown command could not be run: {}", t.message)
            false
        }
    }

    companion object {
        fun from(
            config: ShutdownConfig,
            spawn: (List<String>) -> Process = { cmd -> ProcessBuilder(cmd).redirectErrorStream(true).start() },
        ): CommandShutdownHandler = CommandShutdownHandler(config.command, config.checkCommand, spawn = spawn)
    }
}
