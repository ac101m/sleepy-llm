package com.ac101m.sleepyllm.backend

import com.ac101m.sleepyllm.wake.WakeSender
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
}

/**
 * Owns the backend state machine (ASLEEP -> WAKING -> READY) and deduplicates
 * wake-up work: concurrent callers share a single in-flight wake instead of each
 * firing a packet. If waking fails or times out, the state returns to ASLEEP so
 * the next request makes a fresh attempt.
 */
class BackendLifecycle(
    private val wakeSender: WakeSender,
    private val healthChecker: HealthChecker,
    private val wakeTimeoutMs: Long,
    private val pollIntervalMs: Long,
    private val wakeAttempts: Int = 3,
    private val wakeRetryDelayMs: Long = 500L,
) : ReadyWaiter, Closeable {

    private val logger = LoggerFactory.getLogger(BackendLifecycle::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    @Volatile
    override var status: BackendStatus = BackendStatus.ASLEEP
        private set

    private var pendingWake: CompletableDeferred<Boolean>? = null

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

    private fun beginWake(): CompletableDeferred<Boolean> {
        status = BackendStatus.WAKING
        val deferred = CompletableDeferred<Boolean>()
        pendingWake = deferred
        scope.launch {
            val ready = performWake()
            deferred.complete(ready)
            mutex.withLock {
                if (pendingWake === deferred) {
                    status = if (ready) BackendStatus.READY else BackendStatus.ASLEEP
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
