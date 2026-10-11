package com.ac101m.sleepyllm.proxy

import com.ac101m.sleepyllm.config.BackendConfig
import com.ac101m.sleepyllm.config.HealthConfig
import com.ac101m.sleepyllm.config.ProxyConfig
import com.ac101m.sleepyllm.config.SleepyLlmConfig
import com.ac101m.sleepyllm.config.WakeConfig
import com.ac101m.sleepyllm.config.WakeMode
import com.ac101m.sleepyllm.wake.WakeSender
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProxyIntegrationTest {

    /** Stand-in for a real LLM server: starts unhealthy, becomes ready on wake. */
    private class FakeBackend {
        @Volatile
        var healthy = false

        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            routing {
                get("/health") {
                    if (healthy) call.respondText("ok") else call.respond(HttpStatusCode.ServiceUnavailable, "booting")
                }
                get("/echo") {
                    call.respondText("hello path=${call.request.path()} x=${call.request.queryParameters["x"] ?: "-"}")
                }
                post("/echo") {
                    call.respondText("got:${call.receiveText()}")
                }
                get("/stream") {
                    call.respondBytesWriter(contentType = ContentType.Text.Plain) {
                        writeFully("part1".toByteArray(), 0, "part1".length)
                        flush()
                        delay(100)
                        writeFully("part2".toByteArray(), 0, "part2".length)
                        flush()
                    }
                }
            }
        }

        fun start(): FakeBackend {
            server.start(wait = false)
            return this
        }

        fun port(): Int = runBlocking { server.engine.resolvedConnectors().first().port }

        fun stop() = server.stop(100, 500)
    }

    /** Wake-up that flips the backend to healthy shortly after being invoked. */
    private class DelayedWakeSender(
        private val onWake: () -> Unit,
    ) : WakeSender {
        @Volatile
        var calls = 0

        override fun wake() {
            calls++
            onWake()
        }
    }

    private fun config(backendPort: Int, timeoutMs: Long) = SleepyLlmConfig(
        proxy = ProxyConfig(host = "127.0.0.1", port = 0),
        backend = BackendConfig(
            scheme = "http",
            host = "127.0.0.1",
            port = backendPort,
            health = HealthConfig(path = "/health", timeoutMs = timeoutMs, pollIntervalMs = 20),
        ),
        wake = WakeConfig(mode = WakeMode.DUMMY, command = "wakeonlan", mac = "aa:bb:cc:dd:ee:ff"),
    )

    @Test
    fun `wakes the backend, waits, then proxies the request`() = runBlocking {
        val backend = FakeBackend().start()
        val wake = DelayedWakeSender(
            onWake = { Thread({ Thread.sleep(150); backend.healthy = true }, "fake-wake").start() },
        )
        try {
            testApplication {
                application { module(config(backend.port(), 5_000), wake) }
                val client = createClient { }

                val response = client.get("/echo?x=1")
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("hello path=/echo x=1", response.bodyAsText())
                assertEquals(1, wake.calls, "the wake should have fired exactly once")
            }
        } finally {
            backend.stop()
        }
    }

    @Test
    fun `subsequent requests are proxied without waking again`() = runBlocking {
        val backend = FakeBackend().apply { healthy = true }.start()
        val wake = DelayedWakeSender(onWake = {})
        try {
            testApplication {
                application { module(config(backend.port(), 5_000), wake) }
                val client = createClient { }

                // The lifecycle starts ASLEEP, so the first request wakes once and
                // then finds the backend healthy; subsequent requests must not wake.
                assertEquals("hello path=/echo x=-", client.get("/echo").bodyAsText())
                assertEquals(1, wake.calls, "the first request should have woken the backend once")
                assertEquals("hello path=/echo x=-", client.get("/echo").bodyAsText())
                assertEquals("hello path=/echo x=-", client.get("/echo").bodyAsText())
                assertEquals(1, wake.calls, "an already-ready backend must not be re-woken")
            }
        } finally {
            backend.stop()
        }
    }

    @Test
    fun `request body is forwarded`() = runBlocking {
        val backend = FakeBackend().apply { healthy = true }.start()
        val wake = DelayedWakeSender(onWake = {})
        try {
            testApplication {
                application { module(config(backend.port(), 5_000), wake) }
                val client = createClient { }

                val response = client.post("/echo") {
                    setBody("hello from client")
                    contentType(ContentType.Text.Plain)
                }
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("got:hello from client", response.bodyAsText())
            }
        } finally {
            backend.stop()
        }
    }

    @Test
    fun `streaming response is forwarded intact`() = runBlocking {
        val backend = FakeBackend().apply { healthy = true }.start()
        val wake = DelayedWakeSender(onWake = {})
        try {
            testApplication {
                application { module(config(backend.port(), 5_000), wake) }
                val client = createClient { }

                val response = client.get("/stream")
                assertEquals(HttpStatusCode.OK, response.status)
                val body = response.bodyAsText()
                assertTrue("part1" in body && "part2" in body, "streamed parts should both arrive, got: $body")
            }
        } finally {
            backend.stop()
        }
    }

    @Test
    fun `request is rejected when the backend cannot be woken`() = runBlocking {
        val backend = FakeBackend().start() // never becomes healthy
        val wake = DelayedWakeSender(onWake = {})
        try {
            testApplication {
                application { module(config(backend.port(), 200), wake) }
                val client = createClient { }

                val response = client.get("/echo")
                assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            }
        } finally {
            backend.stop()
        }
    }
}
