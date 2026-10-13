package com.ac101m.sleepyllm.shutdown

/**
 * Performs the user-configured idle shutdown of the backend machine: a guard
 * check for logged-in users, and the power-off command itself.
 *
 * Implementations are side-effect-only and must not throw — they report success
 * as a [Boolean] so the idle loop in
 * [com.ac101m.sleepyllm.backend.BackendLifecycle] can react without handling
 * exceptions for the routine "command failed" case.
 */
interface ShutdownHandler {

    /**
     * Whether the machine is safe to power down right now. Exit 0 from the
     * configured check-command means nobody is logged in (safe); a non-zero exit,
     * a timeout, or an un-runnable guard means someone is logged in or the machine
     * could not be reached to confirm — the proxy defers (never power off a
     * machine whose logins we could not check). With no check-command configured
     * this is always true: the proxy assumes full control of the lifecycle.
     */
    fun isSafeToShutdown(): Boolean

    /**
     * Issues the shutdown command. Returns true only when it exited 0; a timeout
     * or non-zero exit returns false (and is logged), leaving the machine up.
     */
    fun shutdown(): Boolean
}
