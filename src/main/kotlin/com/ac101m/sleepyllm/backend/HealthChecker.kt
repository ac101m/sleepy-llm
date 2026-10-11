package com.ac101m.sleepyllm.backend

import com.ac101m.sleepyllm.config.BackendConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

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
            delay(pollIntervalMs)
        }
        return probe()
    }

    companion object {
        /**
         * Probes `GET {backend.baseUrl}{backend.health.path}`; any 2xx/3xx status
         * counts as healthy. Connect failures and non-2xx/3xx statuses are treated
         * as "not ready yet" (a server that is still booting usually refuses
         * connections or returns 503).
         */
        fun http(client: HttpClient, backend: BackendConfig): HealthChecker {
            val url = "${backend.baseUrl}${backend.health.path}"
            val logger = LoggerFactory.getLogger(HealthChecker::class.java)
            return HealthChecker {
                runCatching {
                    val response = client.get(url)
                    val healthy = response.status.value in 200..399
                    if (!healthy) {
                        logger.info("health probe {} -> {} (not ready yet)", url, response.status.value)
                    }
                    response.bodyAsChannel().cancel(cause = null)
                    healthy
                }.getOrDefault(false)
            }
        }
    }
}
