package com.ac101m.sleepyllm.backend

import com.ac101m.sleepyllm.config.BackendConfig
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

/**
 * Determines whether the inference server is ready to serve traffic.
 *
 * Implemented as a probe function so the wait loop is trivially testable without
 * a network (see [BackendLifecycleTest]); [http] adapts it to the real health
 * endpoint over HTTP.
 */
class HealthChecker(private val probe: suspend () -> Boolean) {

    /**
     * Polls [probe] every [pollIntervalMs] until it returns true or [timeoutMs]
     * elapses. Always performs at least one final probe at the deadline.
     */
    suspend fun waitUntilHealthy(timeoutMs: Long, pollIntervalMs: Long): Boolean {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadlineNanos) {
            if (probe()) return true
            delay(pollIntervalMs.milliseconds)
        }
        return probe()
    }

    /**
     * Whether the backend can serve traffic right now, from a single probe. Used
     * by the READY-state monitor, which has no deadline of its own — the per-probe
     * bound, when one is configured, lives in [http]'s [probeTimeoutMs] parameter
     * (a bare [probe] has no such limit).
     */
    suspend fun isHealthy(): Boolean = probe()

    companion object {
        /**
         * Probes `GET {backend.baseUrl}{backend.health.path}`; any 2xx/3xx status
         * counts as healthy. Connect failures and non-2xx/3xx statuses are treated
         * as "not ready yet" (a server that is still booting usually refuses
         * connections or returns 503).
         *
         * @param onHealthyBody when non-null and the probe returns 2xx/3xx, the
         *   response body is read fully and passed to this callback (instead of
         *   being discarded) — used e.g. to refresh the `/v1/models` disk cache
         *   from the health endpoint it is already fetching. When null the body is
         *   cancelled and discarded as before.
         * @param probeTimeoutMs when non-null, bounds each probe with a per-request
         *   connect + request timeout (overriding the client's defaults for this
         *   probe only). The READY-state monitor uses this so a probe that reuses a
         *   stale keep-alive connection to a powered-off backend cannot stall
         *   detection indefinitely.
         */
        fun http(
            client: HttpClient,
            backend: BackendConfig,
            onHealthyBody: ((ByteArray) -> Unit)? = null,
            probeTimeoutMs: Long? = null,
        ): HealthChecker {
            val url = "${backend.baseUrl}${backend.health.path}"
            val logger = LoggerFactory.getLogger(HealthChecker::class.java)
            return HealthChecker {
                runCatching {
                    val response = if (probeTimeoutMs != null) {
                        client.get(url) {
                            timeout {
                                connectTimeoutMillis = probeTimeoutMs
                                requestTimeoutMillis = probeTimeoutMs
                            }
                        }
                    } else {
                        client.get(url)
                    }
                    val healthy = response.status.value in 200..399
                    if (!healthy) {
                        logger.info("health probe {} -> {} (not ready yet)", url, response.status.value)
                        response.bodyAsChannel().cancel(cause = null)
                    } else if (onHealthyBody != null) {
                        onHealthyBody(response.bodyAsBytes())
                    } else {
                        response.bodyAsChannel().cancel(cause = null)
                    }
                    healthy
                }.getOrDefault(false)
            }
        }
    }
}
