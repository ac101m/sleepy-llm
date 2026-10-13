package com.ac101m.sleepyllm.backend

import com.ac101m.sleepyllm.shutdown.ShutdownHandler
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

    /** Idle-shutdown actor whose guard and result are toggled by the test. */
    private class FakeShutdownHandler : ShutdownHandler {
        @Volatile
        var safe = true

        @Volatile
        var result = true

        val shutdownCalls = AtomicInteger(0)

        override fun isSafeToShutdown(): Boolean = safe

        override fun shutdown(): Boolean {
            shutdownCalls.incrementAndGet()
            return result
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
        idleTimeoutMs: Long? = null,
        shutdownPollIntervalMs: Long = 10,
        shutdownHandler: ShutdownHandler? = null,
    ) = BackendLifecycle(
        wakeSender = wake,
        healthChecker = HealthChecker { probe() },
        wakeTimeoutMs = timeoutMs,
        pollIntervalMs = pollMs,
        wakeAttempts = attempts,
        monitorIntervalMs = monitorIntervalMs,
        downAfterFailures = downAfterFailures,
        idleTimeoutMs = idleTimeoutMs,
        shutdownPollIntervalMs = shutdownPollIntervalMs,
        shutdownHandler = shutdownHandler,
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

    @Test
    fun `idle shutdown fires after no activity for the duration`() = runBlocking {
        val wake = CountingWakeSender()
        val handler = FakeShutdownHandler()
        val lc = lifecycle(
            wake,
            probe = { true },
            timeoutMs = 2_000,
            pollMs = 5,
            idleTimeoutMs = 50,
            shutdownPollIntervalMs = 10,
            shutdownHandler = handler,
        )
        lc.use {
            assertTrue(lc.awaitReady())
            withTimeout(2_000) {
                while (handler.shutdownCalls.get() == 0) delay(10)
            }
            assertEquals(1, handler.shutdownCalls.get())
            assertEquals(BackendStatus.ASLEEP, lc.status, "issuing a shutdown must flip READY -> ASLEEP")
            assertEquals(1, wake.calls, "the idle shutdown itself must never fire a wake")
        }
    }

    @Test
    fun `idle shutdown loop is not started when not configured`() = runBlocking {
        val wake = CountingWakeSender()
        val handler = FakeShutdownHandler()
        val lc = lifecycle(
            wake,
            probe = { true },
            timeoutMs = 2_000,
            pollMs = 5,
            shutdownHandler = handler, // idleTimeoutMs = null
        )
        lc.use {
            assertTrue(lc.awaitReady())
            delay(300)
            assertEquals(0, handler.shutdownCalls.get(), "without an idle window the loop must not run")
            assertEquals(BackendStatus.READY, lc.status)
        }
    }

    @Test
    fun `idle shutdown loop does not run while not ready`() = runBlocking {
        val wake = object : WakeSender {
            override fun wake(): Nothing = throw IllegalStateException("no MAC address configured")
        }
        val handler = FakeShutdownHandler()
        val lc = lifecycle(
            wake,
            probe = { false },
            timeoutMs = 100,
            pollMs = 5,
            attempts = 1,
            idleTimeoutMs = 50,
            shutdownPollIntervalMs = 10,
            shutdownHandler = handler,
        )
        lc.use {
            assertFalse(lc.awaitReady())
            assertEquals(BackendStatus.ASLEEP, lc.status)
            delay(300)
            assertEquals(0, handler.shutdownCalls.get(), "idle shutdown must only run against a READY backend")
        }
    }

    @Test
    fun `request activity resets the idle timer`() = runBlocking {
        val wake = CountingWakeSender()
        val handler = FakeShutdownHandler()
        val lc = lifecycle(
            wake,
            probe = { true },
            timeoutMs = 2_000,
            pollMs = 5,
            idleTimeoutMs = 100,
            shutdownPollIntervalMs = 10,
            shutdownHandler = handler,
        )
        lc.use {
            assertTrue(lc.awaitReady())
            // Four hundred milliseconds of wall time — four idle windows' worth —
            // during which each onBackendActivity() resets the clock, so the
            // machine must never power itself down.
            repeat(8) {
                lc.onBackendActivity()
                delay(50)
            }
            assertEquals(0, handler.shutdownCalls.get(), "real traffic must reset the idle timer")
            assertEquals(BackendStatus.READY, lc.status)
            // Once the traffic stops the window finally elapses.
            withTimeout(2_000) {
                while (handler.shutdownCalls.get() == 0) delay(10)
            }
            assertEquals(1, handler.shutdownCalls.get())
        }
    }

    @Test
    fun `logged-in user defers shutdown until they log out`() = runBlocking {
        val wake = CountingWakeSender()
        val handler = FakeShutdownHandler()
        val lc = lifecycle(
            wake,
            probe = { true },
            timeoutMs = 2_000,
            pollMs = 5,
            idleTimeoutMs = 150,
            shutdownPollIntervalMs = 10,
            shutdownHandler = handler,
        )
        lc.use {
            assertTrue(lc.awaitReady())
            handler.safe = false // a user logs in
            delay(500)           // far past the idle window
            assertEquals(0, handler.shutdownCalls.get(), "a logged-in user must defer shutdown")
            assertEquals(BackendStatus.READY, lc.status)

            handler.safe = true // they log out
            // From the first "nobody logged in" observation the machine gets a full
            // fresh window, so shutdown fires only after the whole duration.
            withTimeout(2_000) {
                while (handler.shutdownCalls.get() == 0) delay(10)
            }
            assertEquals(1, handler.shutdownCalls.get())
            assertEquals(BackendStatus.ASLEEP, lc.status)
        }
    }

    @Test
    fun `shutdown failure keeps status ready and retries after a fresh window`() = runBlocking {
        val wake = CountingWakeSender()
        val handler = FakeShutdownHandler().apply { result = false }
        val lc = lifecycle(
            wake,
            probe = { true },
            timeoutMs = 2_000,
            pollMs = 5,
            idleTimeoutMs = 50,
            shutdownPollIntervalMs = 10,
            shutdownHandler = handler,
        )
        lc.use {
            assertTrue(lc.awaitReady())
            withTimeout(2_000) {
                while (handler.shutdownCalls.get() < 3) delay(10)
            }
            assertTrue(handler.shutdownCalls.get() >= 3, "a failed shutdown must be retried")
            assertEquals(BackendStatus.READY, lc.status, "a failed shutdown must not flip state")
        }
    }

    @Test
    fun `idle shutdown loop stops when the backend leaves ready`() = runBlocking {
        val wake = CountingWakeSender()
        val handler = FakeShutdownHandler()
        val lc = lifecycle(
            wake,
            probe = { true },
            timeoutMs = 2_000,
            pollMs = 5,
            idleTimeoutMs = 50,
            shutdownPollIntervalMs = 10,
            shutdownHandler = handler,
        )
        lc.use {
            assertTrue(lc.awaitReady())
            lc.onBackendUnreachable() // READY -> ASLEEP reactively
            assertEquals(BackendStatus.ASLEEP, lc.status)
            delay(300)
            assertEquals(0, handler.shutdownCalls.get(), "the idle loop must stop once READY is left")
        }
    }

    @Test
    fun `idle shutdown fires at most once`() = runBlocking {
        val wake = CountingWakeSender()
        val handler = FakeShutdownHandler()
        val lc = lifecycle(
            wake,
            probe = { true },
            timeoutMs = 2_000,
            pollMs = 5,
            idleTimeoutMs = 50,
            shutdownPollIntervalMs = 10,
            shutdownHandler = handler,
        )
        lc.use {
            assertTrue(lc.awaitReady())
            withTimeout(2_000) {
                while (handler.shutdownCalls.get() == 0) delay(10)
            }
            assertEquals(1, handler.shutdownCalls.get())
            delay(200)
            assertEquals(1, handler.shutdownCalls.get(), "shutdown must be issued exactly once")
        }
    }
}
