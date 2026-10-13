package com.ac101m.sleepyllm.os

import java.util.concurrent.TimeUnit

/**
 * Outcome of running a command via [runCommand].
 *
 * [timedOut] distinguishes a process killed by the timeout from one that simply
 * exited non-zero, so callers (the wake tool, the shutdown guard) can report the
 * right failure mode instead of conflating them.
 */
data class CommandResult(
    val exitCode: Int,
    /** Combined stdout+stderr captured from the child; callers trim before logging. */
    val output: String,
    val timedOut: Boolean,
)

/**
 * Runs [command] as a child process with stdout and stderr merged, waits for it
 * to finish, and captures its output.
 *
 * The child's output is drained to EOF *before* [waitFor], so a chatty child can
 * never block on a full pipe buffer; the wait is then bounded by [timeoutMs],
 * after which the child is killed with [CommandResult.timedOut] = true. Throws
 * whatever [spawn] throws (e.g. [java.io.IOException] when a binary is missing) —
 * callers that must not fail (the shutdown paths) wrap it.
 *
 * [spawn] is injectable purely for tests; the default spawns a real process.
 */
fun runCommand(
    command: List<String>,
    timeoutMs: Long,
    spawn: (List<String>) -> Process = { cmd -> ProcessBuilder(cmd).redirectErrorStream(true).start() },
): CommandResult {
    val process = spawn(command)
    val output = process.inputStream.bufferedReader().use { it.readText() }
    val timedOut = !process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
    if (timedOut) process.destroyForcibly()
    return CommandResult(exitCode = process.exitValue(), output = output, timedOut = timedOut)
}
