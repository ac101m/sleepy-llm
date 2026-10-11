package com.ac101m.sleepyllm.backend

import com.ac101m.sleepyllm.wake.WakeSender
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    ) = BackendLifecycle(
        wakeSender = wake,
        healthChecker = HealthChecker { probe() },
        wakeTimeoutMs = timeoutMs,
        pollIntervalMs = pollMs,
        wakeAttempts = attempts,
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
}
