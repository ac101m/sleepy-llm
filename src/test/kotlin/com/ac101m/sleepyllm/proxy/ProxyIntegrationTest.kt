package com.ac101m.sleepyllm.proxy

import com.ac101m.sleepyllm.cache.ModelsCache
import com.ac101m.sleepyllm.config.BackendConfig
import com.ac101m.sleepyllm.config.BackendMonitorConfig
import com.ac101m.sleepyllm.config.HealthConfig
import com.ac101m.sleepyllm.config.ProxyConfig
import com.ac101m.sleepyllm.config.ShutdownConfig
import com.ac101m.sleepyllm.config.SleepyLlmConfig
import com.ac101m.sleepyllm.config.WakeConfig
import com.ac101m.sleepyllm.shutdown.ShutdownHandler
import com.ac101m.sleepyllm.wake.WakeSender
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory
import java.io.File
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class ProxyIntegrationTest {

    /** Stand-in for a real LLM server: starts unhealthy, becomes ready on wake. */
    private class FakeBackend {
        @Volatile
        var healthy = false

        /** Body served by the OpenAI-compatible models endpoint. */
        @Volatile
        var modelsBody = """{"object":"list","data":[{"id":"test-model","object":"model"}]}"""

        /** Blocks the /sse handler after its first event until the test releases it. */
        val sseBarrier = CompletableDeferred<Unit>()

        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            routing {
                get("/health") {
                    if (healthy) call.respondText("ok") else call.respond(HttpStatusCode.ServiceUnavailable, "booting")
                }
                get("/v1/models") {
                    if (healthy) call.respondText(modelsBody, ContentType.Application.Json)
                    else call.respond(HttpStatusCode.ServiceUnavailable, "booting")
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
                // A stream that keeps producing after the first events, so a client
                // that hangs up mid-way leaves the proxy with writes into a dead
                // socket long after it has gone — the production failure shape.
                get("/stream-multi") {
                    call.respondBytesWriter(contentType = ContentType.Text.Plain) {
                        repeat(20) {
                            writeFully("event-$it".toByteArray(), 0, "event-$it".length)
                            flush()
                            delay(30)
                        }
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

    /** Idle-shutdown actor whose shutdown powers the backend down and counts calls. */
    private class FakeShutdownHandler(
        private val onShutdown: () -> Unit,
    ) : ShutdownHandler {
        val shutdownCalls = AtomicInteger(0)

        @Volatile
        var safe = true

        override fun hasLoginGuard(): Boolean = true

        override fun isSafeToShutdown(): Boolean = safe

        override fun shutdown(): Boolean {
            shutdownCalls.incrementAndGet()
            onShutdown()
            return true
        }
    }

    private fun config(
        backendPort: Int,
        timeoutMs: Long,
        healthPath: String = "/health",
        modelsCacheFile: File? = null,
        monitor: BackendMonitorConfig? = null,
        shutdown: ShutdownConfig? = null,
    ) = SleepyLlmConfig(
        proxy = ProxyConfig(host = "127.0.0.1", port = 0),
        backend = BackendConfig(
            scheme = "http",
            host = "127.0.0.1",
            port = backendPort,
            health = HealthConfig(path = healthPath, timeoutMs = timeoutMs, pollIntervalMs = 20),
            modelsCacheFile = modelsCacheFile,
            monitor = monitor,
        ),
        wake = WakeConfig(command = emptyList()),
        shutdown = shutdown,
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
    fun `a connection lost mid-stream is logged as abandoned, not a proxy error`() = runBlocking {
        val backend = FakeBackend().apply { healthy = true }.start()
        val wake = DelayedWakeSender(onWake = {})
        // A real Netty proxy over sockets: the in-process test engine buffers
        // response bodies asynchronously, so it cannot exercise the closed-channel
        // write that a lost client connection produces on a live streaming response.
        val proxy = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            module(config(backend.port(), 5_000), wake)
        }

        // Capture the proxy's own log lines: the contract is that a connection
        // lost mid-stream surfaces as a WARN (naming the request, and how long it
        // had been streaming), never as a "proxy error" ERROR.
        val proxyLogger = LoggerFactory.getLogger(SleepyProxy::class.java) as ch.qos.logback.classic.Logger
        val captured = ListAppender<ILoggingEvent>()
        captured.setContext(proxyLogger.loggerContext)
        captured.start()
        proxyLogger.addAppender(captured)
        try {
            proxy.start(wait = false)
            val proxyPort = proxy.engine.resolvedConnectors().first().port

            // Reach READY exactly once so the backend status is known-good before
            // the abort and wake.calls gives a stable baseline.
            val client = HttpClient(CIO)
            try {
                assertEquals(HttpStatusCode.OK, client.get("http://127.0.0.1:$proxyPort/echo").status)
                assertEquals(1, wake.calls)
            } finally {
                client.close()
            }

            // Open a raw socket, request the streaming endpoint, read a couple of
            // events, then walk away while the backend is still producing — the
            // production pattern behind every closed-channel error in the field
            // log. SO_LINGER 0 forces an RST, so the proxy's subsequent writes
            // into the connection fail immediately rather than vanishing into a
            // half-closed socket.
            Socket().apply {
                setSoLinger(true, 0)
                connect(java.net.InetSocketAddress("127.0.0.1", proxyPort), 2_000)
                getOutputStream().write("GET /stream-multi HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                getOutputStream().flush()
            }.use { socket ->
                val input = socket.getInputStream()
                val sb = StringBuilder()
                val buf = ByteArray(1024)
                withTimeout(5_000) {
                    while (!sb.contains("event-1")) {
                        val n = input.read(buf)
                        if (n < 0) throw IllegalStateException("stream closed before the second event: $sb")
                        sb.append(String(buf, 0, n))
                    }
                }
            } // socket is closed here with an RST; the backend keeps producing

            // The backend keeps streaming for ~600ms after the connection is gone,
            // so the proxy is guaranteed to attempt writes into it.
            delay(1_000)

            val events = synchronized(captured) { captured.list.toList() }
            assertTrue(
                events.any { it.level == Level.WARN && it.formattedMessage.contains("lost after") },
                "a connection lost mid-stream should be logged as a WARN naming the request, got: ${events.map { it.formattedMessage }}",
            )
            assertTrue(
                events.none { it.level == Level.ERROR },
                "a lost connection must not be logged as a proxy error, got: ${events.filter { it.level == Level.ERROR }}",
            )

            // The loss must not have marked the backend ASLEEP (no re-wake) and
            // must not have wedged the proxy.
            val client2 = HttpClient(CIO)
            try {
                val after = client2.get("http://127.0.0.1:$proxyPort/echo")
                assertEquals(HttpStatusCode.OK, after.status, "proxy must keep serving after a lost connection")
                assertEquals(1, wake.calls, "a lost client connection must not flip the backend asleep")
            } finally {
                client2.close()
            }
        } finally {
            proxyLogger.detachAppender(captured)
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

    @Test
    fun `cached models copy is served without waking the backend`() = runBlocking {
        val backend = FakeBackend().start() // never becomes healthy
        val wake = DelayedWakeSender(onWake = {})
        val cacheFile = Files.createTempFile("sleepy-models-", ".json").toFile()
        // The cache is loaded at module construction, so prime it on disk first.
        val cachedBody = """{"object":"list","data":[{"id":"cached-model"}]}"""
        ModelsCache(cacheFile).write(cachedBody.toByteArray())
        try {
            testApplication {
                application { module(config(backend.port(), 5_000, modelsCacheFile = cacheFile), wake) }
                val client = createClient { }

                val response = client.get("/v1/models")
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("application/json", response.headers[HttpHeaders.ContentType])
                assertEquals(cachedBody, response.bodyAsText())
                assertEquals(0, wake.calls, "a cached /v1/models request must never wake the backend")
            }
        } finally {
            backend.stop()
        }
    }

    @Test
    fun `ready backend forwards models live and refreshes the cache`() = runBlocking {
        val backend = FakeBackend().apply { healthy = true }.start()
        val wake = DelayedWakeSender(onWake = {})
        val cacheFile = Files.createTempFile("sleepy-models-", ".json").toFile()
        try {
            testApplication {
                application { module(config(backend.port(), 5_000, modelsCacheFile = cacheFile), wake) }
                val client = createClient { }

                // First request wakes (the lifecycle starts ASLEEP); afterwards the backend is READY.
                assertEquals("hello path=/echo x=-", client.get("/echo").bodyAsText())
                assertEquals(1, wake.calls)

                // Simulate the server migrating to a new model while up.
                val migrated = """{"object":"list","data":[{"id":"new-model","object":"model"}]}"""
                backend.modelsBody = migrated

                val response = client.get("/v1/models")
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals(migrated, response.bodyAsText(), "a READY backend must be forwarded live, not served from a stale cache")
                assertEquals(1, wake.calls, "a READY backend must not be re-woken")

                // The forward refreshed the on-disk cache.
                assertContentEquals(migrated.toByteArray(), ModelsCache(cacheFile).read())
            }
        } finally {
            backend.stop()
        }
    }

    @Test
    fun `cold models cache bootstraps via a single wake`() = runBlocking {
        val backend = FakeBackend().start()
        val wake = DelayedWakeSender(
            onWake = { Thread({ Thread.sleep(150); backend.healthy = true }, "fake-wake").start() },
        )
        val cacheFile = Files.createTempFile("sleepy-models-", ".json").toFile()
        try {
            testApplication {
                application { module(config(backend.port(), 5_000, modelsCacheFile = cacheFile), wake) }
                val client = createClient { }

                val response = client.get("/v1/models")
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals(backend.modelsBody, response.bodyAsText())
                assertEquals(1, wake.calls, "a cold models cache should wake exactly once to bootstrap")
                assertContentEquals(backend.modelsBody.toByteArray(), ModelsCache(cacheFile).read())
            }
        } finally {
            backend.stop()
        }
    }

    @Test
    fun `health probe refreshes the cache when it targets v1 models`() = runBlocking {
        val backend = FakeBackend().start()
        val wake = DelayedWakeSender(
            onWake = { Thread({ Thread.sleep(100); backend.healthy = true }, "fake-wake").start() },
        )
        val cacheFile = Files.createTempFile("sleepy-models-", ".json").toFile()
        try {
            testApplication {
                // Health path equals /v1/models, so a successful probe populates the cache.
                application {
                    module(config(backend.port(), 5_000, healthPath = "/v1/models", modelsCacheFile = cacheFile), wake)
                }
                val client = createClient { }

                assertEquals("hello path=/echo x=-", client.get("/echo").bodyAsText())
                assertEquals(1, wake.calls)
                assertContentEquals(
                    backend.modelsBody.toByteArray(),
                    ModelsCache(cacheFile).read(),
                    "a healthy /v1/models probe should populate the cache",
                )
            }
        } finally {
            backend.stop()
        }
    }

    @Test
    fun `caching disabled wakes and forwards models like any request`() = runBlocking {
        val backend = FakeBackend().apply { healthy = true }.start()
        val wake = DelayedWakeSender(onWake = {})
        try {
            testApplication {
                // modelsCacheFile defaults to null: feature off, today's behavior.
                application { module(config(backend.port(), 5_000), wake) }
                val client = createClient { }

                val response = client.get("/v1/models")
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals(backend.modelsBody, response.bodyAsText())
                assertEquals(1, wake.calls, "with caching disabled a models request wakes like any other")
            }
        } finally {
            backend.stop()
        }
    }

    @Test
    fun `a request that fails to connect marks a ready backend asleep`() = runBlocking {
        val backend = FakeBackend().apply { healthy = true }.start()
        val wake = DelayedWakeSender(onWake = {})
        try {
            testApplication {
                // monitor = null means the poller is off: only the reactive hook can
                // flip READY -> ASLEEP. (A poller might flip first and turn the
                // asserted 502 below into a 503 instead.)
                application { module(config(backend.port(), 200, monitor = null), wake) }
                val client = createClient { }

                assertEquals(HttpStatusCode.OK, client.get("/echo").status)
                assertEquals(1, wake.calls)

                backend.stop()
                delay(200) // let the engine release the port deterministically

                val second = client.get("/echo")
                assertEquals(
                    HttpStatusCode.BadGateway,
                    second.status,
                    "a request that fails to connect to a dead backend should return 502",
                )
                assertEquals(1, wake.calls, "the failed connect must not itself fire a wake")

                val third = client.get("/echo")
                assertEquals(
                    HttpStatusCode.ServiceUnavailable,
                    third.status,
                    "after the flip, the next request takes the wake path instead of forwarding",
                )
                assertEquals(2, wake.calls, "the next request must re-attempt the wake")
            }
        } finally {
            backend.stop()
        }
    }

    @Test
    fun `poller marks the backend asleep while idle after it goes down`() = runBlocking {
        val backend = FakeBackend().apply { healthy = true }.start()
        val wake = DelayedWakeSender(onWake = {})
        try {
            testApplication {
                application {
                    module(
                        config(
                            backend.port(),
                            200,
                            monitor = BackendMonitorConfig(intervalMs = 20, timeoutMs = 100, downAfterFailures = 3),
                        ),
                        wake,
                    )
                }
                val client = createClient { }

                assertEquals(HttpStatusCode.OK, client.get("/echo").status)
                assertEquals(1, wake.calls)

                backend.stop()
                // No requests: only the idle poller can notice the backend went down.
                delay(1_000)

                val response = client.get("/echo")
                assertEquals(
                    HttpStatusCode.ServiceUnavailable,
                    response.status,
                    "an idle-detected down backend takes the wake path on the next request",
                )
                // Had the poller not flipped READY -> ASLEEP while idle, this request
                // would have forwarded and returned 502 with wake.calls still 1.
                assertEquals(2, wake.calls, "the idle poller must have marked the backend down")
            }
        } finally {
            backend.stop()
        }
    }

    @Test
    fun `idle shutdown powers the backend down and the next request wakes it again`() = runBlocking {
        val backend = FakeBackend().apply { healthy = true }.start()
        val wake = DelayedWakeSender(onWake = { backend.healthy = true })
        val handler = FakeShutdownHandler(onShutdown = { backend.healthy = false })
        try {
            testApplication {
                application {
                    module(
                        config(
                            backend.port(),
                            5_000,
                            shutdown = ShutdownConfig(idleTimeoutMs = 200, pollIntervalMs = 50, command = listOf("poweroff")),
                        ),
                        wake,
                        handler,
                    )
                }
                val client = createClient { }

                assertEquals(HttpStatusCode.OK, client.get("/echo").status)
                assertEquals(1, wake.calls)

                // No requests: the idle loop powers the machine down on its own.
                withTimeout(5_000) {
                    while (handler.shutdownCalls.get() == 0) delay(20)
                }
                assertEquals(1, handler.shutdownCalls.get())
                assertEquals(1, wake.calls, "the idle shutdown itself must never fire a wake")

                // The shutdown flipped READY -> ASLEEP, so the next request wakes it again.
                val reawoken = client.get("/echo")
                withTimeout(5_000) {
                    while (wake.calls < 2) delay(20)
                }
                assertEquals(2, wake.calls, "after an idle shutdown the next request must re-wake")
                assertEquals(HttpStatusCode.OK, reawoken.status)
            }
        } finally {
            backend.stop()
        }
    }

    @Test
    fun `forwarded v1 models polling does not reset the idle timer`() = runBlocking {
        val backend = FakeBackend().apply { healthy = true }.start()
        val wake = DelayedWakeSender(onWake = {})
        val handler = FakeShutdownHandler(onShutdown = { backend.healthy = false })
        try {
            testApplication {
                application {
                    module(
                        config(
                            backend.port(),
                            5_000,
                            shutdown = ShutdownConfig(idleTimeoutMs = 200, pollIntervalMs = 50, command = listOf("poweroff")),
                        ),
                        wake,
                        handler,
                    )
                }
                val client = createClient { }

                // Reach READY once: this request resets the timer, but the /v1/models
                // polling that follows must not count as activity.
                assertEquals(HttpStatusCode.OK, client.get("/echo").status)
                assertEquals(1, wake.calls)

                // Poll /v1/models every 40ms — comfortably inside the 200ms idle
                // window. Had that traffic counted as activity, the shutdown could
                // never fire; it must fire anyway.
                withTimeout(5_000) {
                    while (handler.shutdownCalls.get() == 0) {
                        val response = client.get("/v1/models")
                        if (handler.shutdownCalls.get() > 0) break // shutdown fired mid-poll
                        assertEquals(HttpStatusCode.OK, response.status)
                        delay(40)
                    }
                }
                assertTrue(handler.shutdownCalls.get() >= 1, "model polling must not keep the machine awake")
            }
        } finally {
            backend.stop()
        }
    }

    @Test
    fun `real forwarded requests reset the idle timer`() = runBlocking {
        val backend = FakeBackend().apply { healthy = true }.start()
        val wake = DelayedWakeSender(onWake = {})
        val handler = FakeShutdownHandler(onShutdown = { backend.healthy = false })
        try {
            testApplication {
                application {
                    module(
                        config(
                            backend.port(),
                            5_000,
                            shutdown = ShutdownConfig(idleTimeoutMs = 300, pollIntervalMs = 50, command = listOf("poweroff")),
                        ),
                        wake,
                        handler,
                    )
                }
                val client = createClient { }

                // Nine requests 100ms apart — far longer than the 300ms window, yet
                // each real request resets it, so the machine must stay up throughout.
                repeat(9) {
                    assertEquals(HttpStatusCode.OK, client.get("/echo").status)
                    delay(100)
                }
                assertEquals(0, handler.shutdownCalls.get(), "real traffic must reset the idle timer")
                assertEquals(1, wake.calls, "real traffic must not cause extra wakes either")

                // Once the traffic stops, the machine powers itself down.
                withTimeout(5_000) {
                    while (handler.shutdownCalls.get() == 0) delay(20)
                }
                assertEquals(1, handler.shutdownCalls.get())
            }
        } finally {
            backend.stop()
        }
    }
}
