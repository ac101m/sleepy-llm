package com.ac101m.sleepyllm.proxy

import com.ac101m.sleepyllm.backend.BackendStatus
import com.ac101m.sleepyllm.backend.ReadyWaiter
import com.ac101m.sleepyllm.cache.ModelsCache
import com.ac101m.sleepyllm.config.BackendConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.FailToConnectException
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.content.OutgoingContent
import io.ktor.http.encodedPath
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.contentType
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ClosedByteChannelException
import io.ktor.util.cio.ChannelWriteException
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Application-level reverse proxy: forwards every request to the configured
 * inference server after waiting for it to be awake (see [ReadyWaiter]).
 *
 * Bodies in both directions are streamed as raw bytes, so long-lived, chunked
 * responses such as LLM token streaming / SSE pass through unchanged.
 */
class SleepyProxy(
    private val client: HttpClient,
    private val backend: BackendConfig,
    private val lifecycle: ReadyWaiter,
    /** Optional disk cache of the `/v1/models` response; null disables the feature. */
    private val modelsCache: ModelsCache? = null,
    /** When false (the default), per-request access logging is silent. */
    private val logRequests: Boolean = false,
) {
    private val logger = LoggerFactory.getLogger(SleepyProxy::class.java)
    private val modelsPath: String = BackendConfig.MODELS_PATH

    /**
     * Hop-by-hop / engine-managed request headers that must not be forwarded.
     * Lowercased so the case-insensitive HTTP header comparison works reliably
     * against whatever casing the peer used.
     */
    private val excludedRequestHeaders = setOf(
        HttpHeaders.Host.lowercase(),
        HttpHeaders.ContentLength.lowercase(),
        HttpHeaders.TransferEncoding.lowercase(),
        HttpHeaders.Connection.lowercase(),
    )

    /**
     * Hop-by-hop response headers stripped before Ktor re-serializes the response.
     * Content-Type is deliberately forwarded (via the copied headers); Content-Length
     * and Transfer-Encoding are recomputed by Ktor (chunked) since bodies stream.
     */
    private val excludedResponseHeaders = setOf(
        HttpHeaders.ContentLength,
        HttpHeaders.TransferEncoding,
        HttpHeaders.Connection,
        HttpHeaders.Trailer,
    )

    suspend fun handle(call: ApplicationCall) {
        if (tryServeCachedModels(call)) return

        val wakeStarted = System.nanoTime()
        if (!lifecycle.awaitReady()) {
            logger.warn(
                "rejecting {} {}: backend is asleep and could not be woken in time",
                call.request.httpMethod,
                call.request.uri,
            )
            // The client may have disconnected while we held the request for the
            // whole wake window; a 503 into a dead socket would throw and be logged
            // as a second failure, so it is best-effort.
            runCatching {
                call.respond(HttpStatusCode.ServiceUnavailable, RESPONSE_UNAVAILABLE)
            }
            return
        }
        val wakeWaitMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - wakeStarted)
        if (wakeWaitMs > 1_000) {
            logger.info(
                "held {} {} {}ms while waking the backend",
                call.request.httpMethod,
                call.request.uri,
                wakeWaitMs,
            )
        }

        // Real traffic resets the idle-shutdown clock; /v1/models is excluded so a
        // dashboard polling the model list can never keep the machine awake. Cached
        // model serves already returned above and never reach this point.
        val isModelsPoll = call.request.httpMethod == HttpMethod.Get && call.request.path() == modelsPath
        if (!isModelsPoll) lifecycle.onBackendActivity()

        val started = System.nanoTime()
        try {
            val status = forward(call)
            if (logRequests) {
                logger.info(
                    "forwarded {} {} -> {} ({}ms)",
                    call.request.httpMethod,
                    call.request.uri,
                    status.value,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                )
            }
        } catch (e: CancellationException) {
            // The request was cancelled (e.g. the client hung up) — normal control
            // flow, not a proxy failure; let the framework unwind the call.
            throw e
        } catch (e: ClientDisconnected) {
            // The client-side connection closed before or while we streamed the
            // response. That is not necessarily the client walking away: a read
            // that dies mid-stream can equally be a network drop (the client
            // blames us — an ECONNRESET — precisely because our teardown of the
            // dead channel is what emits the RST), or a keep-alive connection the
            // engine closed of its own accord. There is nobody left to receive a
            // status either way, so this is an abandoned request, not a proxy
            // failure. Log one line even when per-request logging is off (WARN,
            // no stack) so a quiet log still shows the cadence of drops: how long
            // each response had been streaming when the connection vanished, and
            // from where. Clustered durations betray a client-side read timeout;
            // same-millisecond bursts across several connections betray a single
            // network-path or proxy-side drop.
            logger.warn(
                "connection from {} to {} {} lost after {}ms of forwarding",
                call.request.local.remoteHost,
                call.request.httpMethod,
                call.request.uri,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
            )
            return
        } catch (t: Throwable) {
            if (isConnectionFailure(t)) {
                // The backend was believed READY but a forward could not reach it —
                // likely powered off by another actor. Flip it back to ASLEEP so the
                // next request wakes it again. The client still gets the 502; waking
                // the dead backend here is pointless and would race other state.
                logger.warn(
                    "backend unreachable for {} {}: {} — marking it ASLEEP so the next request retries the wake",
                    call.request.httpMethod,
                    call.request.uri,
                    t.message,
                )
                lifecycle.onBackendUnreachable()
            } else {
                logger.error(
                    "proxy error for {} {}",
                    call.request.httpMethod,
                    call.request.uri,
                    t,
                )
            }
            // Best-effort: the client may have gone away while this failed, in which
            // case this write throws too and is not ours to report.
            runCatching {
                call.respond(
                    HttpStatusCode.BadGateway,
                    "Sleepy LLM could not reach the backend: ${t.message ?: t::class.simpleName}",
                )
            }
        }
    }

    /**
     * Whether a thrown forward represents a connection-level failure — the backend
     * refused a connection, timed out, reset a stale keep-alive connection (the usual
     * first symptom after it is powered off), or could not be resolved — as opposed
     * to a proxy-side or protocol error. Walks the cause chain because Ktor
     * occasionally wraps the underlying socket I/O error. HTTP error statuses never
     * throw out of [forward], so they never reach this classifier.
     *
     * [SocketException] covers refused connects ([java.net.ConnectException]), the
     * Ktor engine's connect-timeout (which extends it) and connection resets in one
     * check; qualifying on it rather than only `ConnectException` is what catches a
     * powered-off backend the moment the first forwarded request reuses a dead
     * pooled connection.
     */
    private fun isConnectionFailure(t: Throwable): Boolean {
        var current: Throwable? = t
        while (current != null) {
            if (current is SocketException) return true
            if (current is UnknownHostException) return true
            if (current is FailToConnectException) return true
            current = current.cause
        }
        return false
    }

    /**
     * Serves the `/v1/models` model list from the disk cache so client polling of
     * that endpoint never wakes the backend. Returns true when the request was
     * answered from cache.
     *
     * The shortcut engages only when the backend is not READY and a cached copy
     * exists. A READY backend falls through and is forwarded live (keeping the
     * list fresh), and a cold cache falls through so the first request wakes the
     * backend once to bootstrap it.
     */
    private suspend fun tryServeCachedModels(call: ApplicationCall): Boolean {
        val cache = modelsCache ?: return false
        if (call.request.httpMethod != HttpMethod.Get || call.request.path() != modelsPath) return false
        if (lifecycle.status == BackendStatus.READY) return false
        val cached = cache.read() ?: return false
        if (logRequests) {
            val started = System.nanoTime()
            logger.info(
                "served {} {} from cache ({} bytes, {}ms)",
                call.request.httpMethod,
                call.request.uri,
                cached.size,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
            )
        }
        call.respond(object : OutgoingContent.ReadChannelContent() {
            override val contentType: ContentType? = ContentType.Application.Json
            override fun readFrom(): ByteReadChannel = ByteReadChannel(cached)
        })
        return true
    }

    /**
     * Forwards the incoming request to the backend and streams the response back
     * to the client. Returns the upstream status code, for the access-log line.
     */
    private suspend fun forward(call: ApplicationCall): HttpStatusCode {
        val incoming = call.request
        // Only the (small) models endpoint is buffered for caching; everything
        // else keeps the raw streaming path below.
        val cacheModels = modelsCache != null &&
            incoming.httpMethod == HttpMethod.Get &&
            incoming.path() == modelsPath
        val requestBody = call.receiveChannel()

        // Streaming block form of execute() — Ktor's plain execute() downloads the
        // whole response into memory before returning (it runs the default SaveBody
        // plugin), which would hang forever on a live SSE stream that stays open
        // while tokens are produced. The block form skips body saving, so the
        // response channel stays live and is forwarded as it arrives.
        //
        // execute's block is not generic (returns Unit), so the upstream status is
        // captured here for the caller's access-log line. The block always runs
        // exactly once unless it throws, in which case this never returns anyway.
        var upstreamStatus = HttpStatusCode.OK
        client.prepareRequest {
            method = incoming.httpMethod
            url {
                protocol = URLProtocol.HTTP
                host = backend.host
                port = backend.port
                encodedPath = incoming.path()
                parameters.appendAll(incoming.queryParameters)
            }
            incoming.headers.entries().forEach { (name, values) ->
                if (name.lowercase() in excludedRequestHeaders) return@forEach
                values.forEach { headers.append(name, it) }
            }
            setBody(object : OutgoingContent.ReadChannelContent() {
                override val contentType: ContentType? = incoming.contentType()
                override val contentLength: Long? = incoming.contentLength()
                override fun readFrom(): ByteReadChannel = requestBody
            })
        }.execute { response ->
            // Capture the streaming body channel eagerly (the non-suspend readFrom()
            // cannot call the suspend bodyAsChannel() itself). call.respond suspends
            // until the whole stream has been forwarded, so the block-form cleanup
            // runs only after the caller has received everything.
            val responseBody = if (cacheModels) {
                captureModelsResponse(response)
            } else {
                response.bodyAsChannel()
            }

            streamToClient(call, response.status, response.headers.copyExcluding(excludedResponseHeaders), responseBody)
            upstreamStatus = response.status
        }
        return upstreamStatus
    }

    /**
     * Writes a forwarded response body to the client. Once the backend has produced
     * the full response, a failure writing downstream can only mean the client-side
     * connection is gone — it was closed or reset mid-stream. That close is
     * translated into [ClientDisconnected] for [handle] to treat as an abandoned
     * request rather than a proxy error.
     *
     * Only the closed-channel family is classified this way: a [SocketException]
     * on the read side (the *backend* dying mid-stream) would otherwise be masked,
     * losing a legitimate down-detection signal — it is rethrown as-is so [handle]'s
     * connection-failure path still flips the backend ASLEEP.
     */
    private suspend fun streamToClient(
        call: ApplicationCall,
        status: HttpStatusCode,
        headers: Headers,
        body: ByteReadChannel,
    ) {
        try {
            call.respond(object : OutgoingContent.ReadChannelContent() {
                override val status: HttpStatusCode = status
                override val headers: Headers = headers
                override fun readFrom(): ByteReadChannel = body
            })
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            if (isClientGone(t)) throw ClientDisconnected(t)
            throw t
        }
    }

    /**
     * Whether [t] (or its cause chain) is a write to an already-closed client
     * channel: the client closed its connection, so there is nobody to receive the
     * rest of the response. Covers Ktor's [io.ktor.utils.io.ClosedByteChannelException],
     * the [java.nio.channels.ClosedChannelException] family raised for a closed
     * channel (Netty's `StacklessClosedChannelException` — what a closed socket
     * surfaces as — extends it), and Ktor's write wrapper around them.
     */
    private fun isClientGone(t: Throwable): Boolean {
        var current: Throwable? = t
        while (current != null) {
            if (current is io.ktor.utils.io.ClosedByteChannelException) return true
            if (current is java.nio.channels.ClosedChannelException) return true
            if (current is ChannelWriteException) return true
            current = current.cause
        }
        return false
    }

    /**
     * Marker for a request whose client-side connection closed before or during
     * the response write. [handle] catches it, logs one WARN line naming the
     * request, the peer, and how long it had been streaming, then returns without
     * trying to reach a connection that is gone.
     */
    private class ClientDisconnected(cause: Throwable) : IOException("client disconnected", cause)

    /**
     * Reads a forwarded `/v1/models` response fully and refreshes the disk cache
     * from it, so the cached copy tracks the live backend on every real request
     * (not just health pings). Models responses are small JSON, so buffering here
     * is safe; the body is re-served by the caller with the same pass-through
     * headers as the streaming path.
     */
    private suspend fun captureModelsResponse(response: HttpResponse): ByteReadChannel {
        val bytes = response.bodyAsBytes()
        if (response.status.value in 200..399) {
            modelsCache?.write(bytes)
            logger.debug("cached forwarded {} ({} bytes)", modelsPath, bytes.size)
        } else if (logRequests) {
            logger.info("GET {} -> {} (not cached)", modelsPath, response.status.value)
        }
        return ByteReadChannel(bytes)
    }

    private fun Headers.copyExcluding(excluded: Set<String>): Headers {
        val excludedLower = excluded.map { it.lowercase() }.toSet()
        val builder = HeadersBuilder()
        for (name in names()) {
            if (name.lowercase() in excludedLower) continue
            getAll(name)?.forEach { builder.append(name, it) }
        }
        return builder.build()
    }

    private companion object {
        const val RESPONSE_UNAVAILABLE =
            "Sleepy LLM: backend is asleep and could not be woken in time. Try again shortly."
    }
}
