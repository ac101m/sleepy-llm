package com.ac101m.sleepyllm.os

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
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
 * The drain and the wait are both bounded by [timeoutMs]: output is read as it
 * arrives (a chatty child can therefore never block on a full pipe buffer), and
 * a child that stays alive with its output pipe still open — e.g. `ssh` to an
 * unreachable host — is killed on the deadline, reported with
 * [CommandResult.timedOut] = true. The drain polls `available()` and only reads
 * when data is known to be present, so no read blocks past the deadline; once
 * the child exits (or is killed, which closes the pipe) the remainder is
 * drained non-blockingly.
 *
 * Throws whatever [spawn] throws (e.g. [java.io.IOException] when a binary is
 * missing) — callers that must not fail (the shutdown paths) wrap it.
 *
 * [spawn] is injectable purely for tests; the default spawns a real process.
 */
fun runCommand(
    command: List<String>,
    timeoutMs: Long,
    spawn: (List<String>) -> Process = { cmd -> ProcessBuilder(cmd).redirectErrorStream(true).start() },
): CommandResult {
    val process = spawn(command)
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    val captured = ByteArrayOutputStream()
    val input = process.inputStream
    val buf = ByteArray(8 * 1024)
    var timedOut = false
    while (true) {
        if (input.available() > 0) {
            val n = input.read(buf, 0, buf.size)
            if (n < 0) break
            captured.write(buf, 0, n)
            continue
        }
        if (!process.isAlive) break                        // exited; the pipe may still hold output
        if (System.nanoTime() >= deadline) {
            timedOut = true
            process.destroyForcibly()
            break
        }
        try {
            Thread.sleep(2)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            timedOut = true
            process.destroyForcibly()
            break
        }
    }
    // The child is dead (or destroyed, which closed the pipe): drain the rest
    // non-blockingly — every read here is guarded by available().
    while (input.available() > 0) {
        val n = input.read(buf, 0, buf.size)
        if (n < 0) break
        captured.write(buf, 0, n)
    }
    val exitCode = try {
        process.exitValue()
    } catch (e: IllegalThreadStateException) {
        process.waitFor()      // child was killed; reap promptly for a stable exit code
    }
    return CommandResult(exitCode = exitCode, output = captured.toString(StandardCharsets.UTF_8), timedOut = timedOut)
}
