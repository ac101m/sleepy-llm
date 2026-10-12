package com.ac101m.sleepyllm.proxy

import com.ac101m.sleepyllm.config.BackendConfig
import com.ac101m.sleepyllm.config.HealthConfig
import com.ac101m.sleepyllm.config.ProxyConfig
import com.ac101m.sleepyllm.config.SleepyLlmConfig
import com.ac101m.sleepyllm.config.WakeConfig
import com.ac101m.sleepyllm.config.WakeMode
import com.ac101m.sleepyllm.wake.WakeSender
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProxyIntegrationTest {

    /** Stand-in for a real LLM server: starts unhealthy, becomes ready on wake. */
    private class FakeBackend {
        @Volatile
        var healthy = false

        /** Blocks the /sse handler after its first event until the test releases it. */
        val sseBarrier = CompletableDeferred<Unit>()

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
                // OpenAI-style server-sent events: writes one event, then blocks on
                // the barrier so the test can prove the first event reached the
                // client before the backend finished producing the stream.
                get("/sse") {
                    call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                        writeStringUtf8("data: event-one\n\n")
                        flush()
                        sseBarrier.await()
                        writeStringUtf8("data: event-two\n\n")
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
    fun `sse stream arrives progressively and keeps its content type`() = runBlocking {
        val backend = FakeBackend().apply { healthy = true }.start()
        val wake = DelayedWakeSender(onWake = {})
        // A real Netty proxy over sockets rather than testApplication: the
        // in-process test engine buffers response bodies asynchronously, so it
        // cannot exercise progressive streaming. This is the production shape
        // (client -> proxy -> backend over three real sockets).
        val proxy = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            module(config(backend.port(), 5_000), wake)
        }
        try {
            proxy.start(wait = false)
            val proxyPort = proxy.engine.resolvedConnectors().first().port

            val client = HttpClient(CIO)
            try {
                // prepareGet(url).execute { } rather than client.get(url): the plain
                // get() form downloads the whole response body into memory before
                // returning (Ktor's default body saving), which can never finish for
                // a live SSE stream whose backend holds the stream open. The block
                // form skips body saving and exposes the live channel — the same
                // structure the proxy itself uses on its outbound leg.
                client.prepareGet("http://127.0.0.1:$proxyPort/sse").execute { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                    assertEquals(
                        "text/event-stream",
                        response.headers[HttpHeaders.ContentType],
                        "Content-Type must survive the proxy, not be stripped or re-defaulted",
                    )

                    // The backend writes "event-one" then blocks on sseBarrier before
                    // producing "event-two". The client can only read the first line
                    // while the backend is still blocked — unless the proxy buffered
                    // the whole body, in which case this read would hang until the
                    // barrier is released below and the timeout would fire. Reading it
                    // first therefore proves the proxy streams, not buffers.
                    val channel = response.bodyAsChannel()
                    assertEquals("data: event-one", withTimeout(2_000) { channel.readUTF8Line() })
                    assertEquals("", withTimeout(2_000) { channel.readUTF8Line() }, "blank line separating SSE events")

                    backend.sseBarrier.complete(Unit)

                    val remainder = withTimeout(2_000) {
                        buildString {
                            while (true) {
                                val line = channel.readUTF8Line() ?: break
                                appendLine(line)
                            }
                        }
                    }
                    assertTrue(
                        "data: event-two" in remainder,
                        "second SSE event should arrive after the barrier is released, got: $remainder",
                    )
                }
            } finally {
                client.close()
            }
        } finally {
            proxy.stop(100, 500)
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
