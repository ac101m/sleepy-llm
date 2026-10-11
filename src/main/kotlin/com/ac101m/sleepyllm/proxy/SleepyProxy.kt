package com.ac101m.sleepyllm.proxy

import com.ac101m.sleepyllm.backend.ReadyWaiter
import com.ac101m.sleepyllm.config.BackendConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
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
import org.slf4j.LoggerFactory

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
) {
    private val logger = LoggerFactory.getLogger(SleepyProxy::class.java)

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
        if (!lifecycle.awaitReady()) {
            logger.warn(
                "rejecting {} {}: backend is asleep and could not be woken in time",
                call.request.httpMethod,
                call.request.uri,
            )
            return call.respond(
                HttpStatusCode.ServiceUnavailable,
                RESPONSE_UNAVAILABLE,
            )
        }

        try {
            forward(call)
        } catch (t: Throwable) {
            logger.error(
                "proxy error for {} {}",
                call.request.httpMethod,
                call.request.uri,
                t,
            )
            return call.respond(
                HttpStatusCode.BadGateway,
                "Sleepy LLM could not reach the backend: ${t.message ?: t::class.simpleName}",
            )
        }
    }

    private suspend fun forward(call: ApplicationCall) {
        val incoming = call.request
        val requestBody = call.receiveChannel()

        val response = client.prepareRequest {
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
        }.execute()

        // Capture the streaming body channel eagerly (the non-suspend readFrom()
        // cannot call the suspend bodyAsChannel() itself).
        val responseBody = response.bodyAsChannel()

        call.respond(object : OutgoingContent.ReadChannelContent() {
            override val status: HttpStatusCode = response.status
            override val headers: Headers = response.headers.copyExcluding(excludedResponseHeaders)
            override fun readFrom(): ByteReadChannel = responseBody
        })
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
