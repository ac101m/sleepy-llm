package com.ac101m.sleepyllm.backend

import com.ac101m.sleepyllm.wake.WakeSender
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class BackendLifecycleTest {

    private class CountingWakeSender : WakeSender {
        @Volatile
        var calls = 0

        override fun wake() {
            calls++
        }
    }

    private fun lifecycle(
        wake: WakeSender,
        probe: () -> Boolean,
        timeoutMs: Long,
        pollMs: Long,
        attempts: Int = 3,
        monitorIntervalMs: Long? = null,
        downAfterFailures: Int = 3,
    ) = BackendLifecycle(
        wakeSender = wake,
        healthChecker = HealthChecker { probe() },
        wakeTimeoutMs = timeoutMs,
        pollIntervalMs = pollMs,
        wakeAttempts = attempts,
        monitorIntervalMs = monitorIntervalMs,
        downAfterFailures = downAfterFailures,
    )

    @Test
    fun `wakes then waits until the backend is healthy`() = runBlocking {
        val wake = CountingWakeSender()
        var probes = 0
        val lc = lifecycle(wake, probe = { probes++; probes >= 3 }, timeoutMs = 2_000, pollMs = 10)
        lc.use {
            assertTrue(lc.awaitReady())
            assertEquals(1, wake.calls)
            assertTrue(probes >= 3, "expected several health probes before ready")
            assertEquals(BackendStatus.READY, lc.status)
        }
    }

    @Test
    fun `concurrent requesters share a single wake`() = runBlocking {
        val wake = CountingWakeSender()
        var probes = 0
        val lc = lifecycle(wake, probe = { probes++; probes >= 2 }, timeoutMs = 2_000, pollMs = 10)
        lc.use {
            val results = (1..10).map { async { lc.awaitReady() } }.awaitAll()
            assertTrue(results.all { it }, "all concurrent requesters should eventually see READY")
            assertEquals(1, wake.calls, "only one wake packet should be sent")
        }
    }

    @Test
    fun `once ready, later requests do not wake again`() = runBlocking {
        val wake = CountingWakeSender()
        val lc = lifecycle(wake, probe = { true }, timeoutMs = 2_000, pollMs = 10)
        lc.use {
            assertTrue(lc.awaitReady())
            assertTrue(lc.awaitReady())
            assertTrue(lc.awaitReady())
            assertEquals(1, wake.calls, "subsequent ready requests must not re-wake")
        }
    }

    @Test
    fun `failed wake returns false and resets to asleep`() = runBlocking {
        val wake = object : WakeSender {
            override fun wake(): Nothing = throw IllegalStateException("no MAC address configured")
        }
        val lc = lifecycle(wake, probe = { false }, timeoutMs = 100, pollMs = 5, attempts = 2)
        lc.use {
            assertFalse(lc.awaitReady())
            assertEquals(BackendStatus.ASLEEP, lc.status, "state must reset so the next request retries")
            // A subsequent request should make a fresh (still failing) attempt.
            assertFalse(lc.awaitReady())
        }
    }

    @Test
    fun `health timeout resets to asleep for retry`() = runBlocking {
        val wake = CountingWakeSender()
        val lc = lifecycle(wake, probe = { false }, timeoutMs = 100, pollMs = 5)
        lc.use {
            assertFalse(lc.awaitReady())
            assertEquals(BackendStatus.ASLEEP, lc.status)
        }
    }

    @Test
    fun `poller marks a ready backend asleep after repeated failures`() = runBlocking {
        val wake = CountingWakeSender()
        val healthy = AtomicBoolean(true)
        val lc = lifecycle(
            wake,
            probe = { healthy.get() },
            timeoutMs = 2_000,
            pollMs = 5,
            monitorIntervalMs = 5,
            downAfterFailures = 2,
        )
        lc.use {
            assertTrue(lc.awaitReady())
            assertEquals(BackendStatus.READY, lc.status)

            healthy.set(false)
            withTimeout(2_000) {
                while (lc.status == BackendStatus.READY) delay(10)
            }
            assertEquals(BackendStatus.ASLEEP, lc.status)
            assertEquals(1, wake.calls, "detection must not fire a wake")
        }
    }

    @Test
    fun `a transient single probe failure does not flip a ready backend`() = runBlocking {
        val wake = CountingWakeSender()
        // Succeed during the initial wake, then fail exactly twice (below the
        // threshold of three) before recovering.
        val wakeComplete = AtomicBoolean(false)
        val failuresRemaining = AtomicInteger(2)
        val probe = {
            if (!wakeComplete.get()) {
                wakeComplete.set(true)
                true
            } else if (failuresRemaining.get() > 0) {
                failuresRemaining.set(failuresRemaining.get() - 1)
                false
            } else {
                true
            }
        }
        val lc = lifecycle(
            wake,
            probe = probe,
            timeoutMs = 2_000,
            pollMs = 5,
            monitorIntervalMs = 5,
            downAfterFailures = 3,
        )
        lc.use {
            assertTrue(lc.awaitReady())
            // Far longer than the probe interval, so both failures fall inside one
            // monitoring window and then recovery is observed.
            delay(500)
            assertEquals(BackendStatus.READY, lc.status, "two failures must not trip a threshold of three")
            assertEquals(1, wake.calls)
        }
    }

    @Test
    fun `a healthy backend stays ready while monitored`() = runBlocking {
        val wake = CountingWakeSender()
        val lc = lifecycle(
            wake,
            probe = { true },
            timeoutMs = 2_000,
            pollMs = 5,
            monitorIntervalMs = 20,
        )
        lc.use {
            assertTrue(lc.awaitReady())
            delay(200)
            assertEquals(BackendStatus.READY, lc.status)
            assertEquals(1, wake.calls, "monitoring a healthy backend must never re-wake it")
        }
    }

    @Test
    fun `onBackendUnreachable flips a ready backend to asleep and stops the monitor`() = runBlocking {
        val wake = CountingWakeSender()
        val healthy = AtomicBoolean(true)
        val lc = lifecycle(
            wake,
            probe = { healthy.get() },
            timeoutMs = 2_000,
            pollMs = 5,
            monitorIntervalMs = 5,
        )
        lc.use {
            assertTrue(lc.awaitReady())
            assertEquals(BackendStatus.READY, lc.status)

            lc.onBackendUnreachable()
            assertEquals(BackendStatus.ASLEEP, lc.status)

            // The monitor was cancelled: even with the backend now failing probes
            // (which would have tripped it had it kept running), nothing revives the
            // state — only a fresh request does, through the wake path.
            healthy.set(false)
            delay(200)
            assertEquals(BackendStatus.ASLEEP, lc.status)
            assertEquals(1, wake.calls, "marking down must not fire a wake")
        }
    }

    @Test
    fun `onBackendUnreachable is a no-op when not ready`() = runBlocking {
        val wake = object : WakeSender {
            override fun wake(): Nothing = throw IllegalStateException("no MAC address configured")
        }
        val lc = lifecycle(wake, probe = { false }, timeoutMs = 100, pollMs = 5, attempts = 1)
        lc.use {
            assertFalse(lc.awaitReady())
            assertEquals(BackendStatus.ASLEEP, lc.status)
            // Calling the hook from a non-READY state must not throw or change state.
            lc.onBackendUnreachable()
            assertEquals(BackendStatus.ASLEEP, lc.status, "the hook is a no-op when not READY")
        }
    }
}
