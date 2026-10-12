package com.ac101m.sleepyllm.backend

import com.ac101m.sleepyllm.wake.WakeSender
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.io.Closeable
import kotlin.time.Duration.Companion.milliseconds

enum class BackendStatus {
    /** Backend is powered down; a request will trigger a wake. */
    ASLEEP,

    /** A wake is in flight; incoming requests wait on the same wake. */
    WAKING,

    /** Backend is healthy; requests are forwarded immediately. */
    READY,
}

/** Abstraction over "wait until the backend can serve traffic". */
interface ReadyWaiter {
    /**
     * Current backend state-machine status. READY means traffic is forwarded
     * directly; ASLEEP/WAKING mean a request will trigger (or join) a wake.
     */
    val status: BackendStatus

    /**
     * Returns true once the backend is ready to serve, or false when it could not
     * be woken within the configured timeout. May block for the duration of a wake.
     */
    suspend fun awaitReady(): Boolean

    /**
     * Notifies the lifecycle that a forwarded request could not connect to the
     * backend while it was believed READY. The lifecycle flips READY → ASLEEP so
     * the next request wakes the backend again. No-op when the backend is not
     * currently READY (avoids racing an in-flight wake).
     */
    suspend fun onBackendUnreachable()
}

/**
 * Owns the backend state machine (ASLEEP -> WAKING -> READY) and deduplicates
 * wake-up work: concurrent callers share a single in-flight wake instead of each
 * firing a packet. If waking fails or times out, the state returns to ASLEEP so
 * the next request makes a fresh attempt.
 *
 * Sleepy LLM is not the only actor in the sleep/wake cycle, so while READY the
 * lifecycle also detects the backend being powered off by some other means: a
 * background monitor probes it (via [monitorProbe]) every [monitorIntervalMs] and
 * flips READY -> ASLEEP after [downAfterFailures] consecutive failures, and
 * [onBackendUnreachable] provides the same flip when a forwarded request fails to
 * connect. Detection is passive — it never fires a wake itself; the next
 * [awaitReady] request does that via the wake path above.
 */
class BackendLifecycle(
    private val wakeSender: WakeSender,
    private val healthChecker: HealthChecker,
    private val wakeTimeoutMs: Long,
    private val pollIntervalMs: Long,
    private val wakeAttempts: Int = 3,
    private val wakeRetryDelayMs: Long = 500L,
    /** Probe used by the READY-state monitor; defaults to [healthChecker]::isHealthy. */
    private val monitorProbe: (suspend () -> Boolean)? = null,
    /** Period between readiness probes while READY; null disables the monitor. */
    private val monitorIntervalMs: Long? = null,
    /** Consecutive failed probes before a READY backend is declared ASLEEP. */
    private val downAfterFailures: Int = 3,
) : ReadyWaiter, Closeable {

    private val logger = LoggerFactory.getLogger(BackendLifecycle::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    @Volatile
    override var status: BackendStatus = BackendStatus.ASLEEP
        private set

    private var pendingWake: CompletableDeferred<Boolean>? = null

    /** Running READY-state monitor, if any. Only modified under [mutex]. */
    private var monitorJob: Job? = null

    override suspend fun awaitReady(): Boolean {
        if (status == BackendStatus.READY) return true

        val deferred = mutex.withLock {
            when (status) {
                BackendStatus.READY -> null
                else -> pendingWake ?: beginWake()
            }
        }

        val ready = deferred?.await() ?: return true
        if (!ready) {
            // The wake coroutine already reset state; nothing to do here other
            // than report the failure to this caller.
        }
        return ready
    }

    override suspend fun onBackendUnreachable() =
        markDown("a forwarded request failed to connect")

    /**
     * Flips READY -> ASLEEP (cancelling the monitor) and logs the reason, so the
     * next [awaitReady] request wakes the backend again. No-op unless the backend
     * was still believed READY, so a stale signal cannot undo a WAKING or ASLEEP
     * state.
     */
    private suspend fun markDown(reason: String) {
        mutex.withLock {
            if (status == BackendStatus.READY) {
                logger.warn("backend marked ASLEEP: {}", reason)
                status = BackendStatus.ASLEEP
                monitorJob?.cancel()
                monitorJob = null
            }
        }
    }

    private fun beginWake(): CompletableDeferred<Boolean> {
        // Belt and suspenders: every path that leaves READY already cancels the
        // monitor, so this only guards against unexpected interleavings.
        monitorJob?.cancel()
        monitorJob = null
        status = BackendStatus.WAKING
        val deferred = CompletableDeferred<Boolean>()
        pendingWake = deferred
        scope.launch {
            val ready = performWake()
            deferred.complete(ready)
            mutex.withLock {
                if (pendingWake === deferred) {
                    status = if (ready) BackendStatus.READY else BackendStatus.ASLEEP
                    // Clear the deduplication handle once the wake has settled, so a
                    // later READY -> ASLEEP flip (detection) cannot make the next
                    // awaitReady() "join" this completed wake and wrongly return true
                    // instead of beginning a fresh one.
                    pendingWake = null
                    if (ready) {
                        // Start the monitor inside the same lock that set READY, so a
                        // concurrent reactive flip cannot leave it running against an
                        // ASLEEP backend.
                        startMonitor()
                    } else {
                        monitorJob?.cancel()
                        monitorJob = null
                    }
                }
            }
            if (ready) {
                logger.info("backend is ready; forwarding traffic")
            } else {
                logger.warn("backend did not become ready; next request will retry the wake")
            }
        }
        return deferred
    }

    /**
     * Starts the READY-state monitor: probes the backend every [monitorIntervalMs]
     * and flips READY -> ASLEEP after [downAfterFailures] consecutive failures
     * (via [markDown], so it self-cancels cleanly). Only ever called under
     * [mutex], at the READY-write site.
     */
    private fun startMonitor() {
        val probe = monitorProbe ?: healthChecker::isHealthy
        val interval = monitorIntervalMs ?: return
        monitorJob = scope.launch {
            var consecutive = 0
            while (isActive) {
                if (probe()) {
                    consecutive = 0
                } else {
                    consecutive++
                    if (consecutive >= downAfterFailures.coerceAtLeast(1)) {
                        markDown("health probe failed $consecutive consecutive times")
                        return@launch
                    }
                    logger.debug("health probe failing ({}/{}): not ready yet", consecutive, downAfterFailures)
                }
                delay(interval.milliseconds)
            }
        }
    }

    private suspend fun performWake(): Boolean {
        var wakeSucceeded = false
        repeat(wakeAttempts.coerceAtLeast(1)) {
            if (!wakeSucceeded) {
                try {
                    wakeSender.wake()
                    wakeSucceeded = true
                    logger.info("wake signal sent to backend")
                } catch (t: Throwable) {
                    logger.warn("wake attempt failed: {}", t.message)
                    delay(wakeRetryDelayMs.milliseconds)
                }
            }
        }
        if (!wakeSucceeded) {
            // Fail fast rather than burn the full health-check timeout.
            logger.error("wake failed after {} attempt(s); not waiting for backend", wakeAttempts)
            return false
        }
        return healthChecker.waitUntilHealthy(wakeTimeoutMs, pollIntervalMs)
    }

    override fun close() {
        scope.cancel()
    }
}
