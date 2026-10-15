package com.ac101m.sleepyllm.proxy

import com.ac101m.sleepyllm.backend.BackendLifecycle
import com.ac101m.sleepyllm.backend.HealthChecker
import com.ac101m.sleepyllm.cache.ModelsCache
import com.ac101m.sleepyllm.config.BackendConfig
import com.ac101m.sleepyllm.config.SleepyLlmConfig
import com.ac101m.sleepyllm.shutdown.CommandShutdownHandler
import com.ac101m.sleepyllm.shutdown.ShutdownHandler
import com.ac101m.sleepyllm.wake.CommandWakeSender
import com.ac101m.sleepyllm.wake.WakeSender
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.ApplicationStopped

/**
 * Ktor module wiring for Sleepy LLM.
 *
 * A single interceptor on the application's own pipeline (never calling
 * `proceed()`) makes the proxy a true catch-all: every method and path,
 * including `/`, is forwarded to the backend.
 *
 * @param wakeSender an optional override, used by tests to substitute the real
 *   wake implementation with a controllable fake.
 * @param shutdownHandler an optional override of the idle-shutdown actor, used by
 *   tests to substitute a controllable fake; when null and `config.shutdown` is
 *   set, the real command-based handler is built from the config.
 */
fun Application.module(
    config: SleepyLlmConfig,
    wakeSender: WakeSender? = null,
    shutdownHandler: ShutdownHandler? = null,
) {
    val client = HttpClient(CIO) {
        // A reverse proxy must not impose a request deadline on the backend:
        // a token-streaming (SSE) response can legitimately stay open for
        // minutes, but CIO's default 15s request timeout counts that as a
        // timeout and would kill every long stream with a 502.
        //
        // The HttpTimeout plugin only takes effect (and disables that engine
        // timer) when at least one timeout is non-null, so a connect timeout is
        // configured deliberately: a dead backend still fails to connect fast,
        // while request duration and socket inactivity stay unlimited.
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = null
        }
    }
    val modelsCache = config.backend.modelsCacheFile?.let { ModelsCache(it) }
    val monitor = config.backend.monitor
    val shutdown = config.shutdown
    val lifecycle = BackendLifecycle(
        wakeSender = wakeSender ?: CommandWakeSender.from(config.wake),
        healthChecker = HealthChecker.http(
            client,
            config.backend,
            // Refresh the models cache from the health probe only when the probe
            // really is the models endpoint; an overridden health path (e.g. /health)
            // must not cache a non-models body.
            onHealthyBody = modelsCache
                ?.takeIf { config.backend.health.path == BackendConfig.MODELS_PATH }
                ?.let { cache -> { body -> cache.write(body) } },
        ),
        wakeTimeoutMs = config.backend.health.timeoutMs,
        pollIntervalMs = config.backend.health.pollIntervalMs,
        monitorProbe = monitor?.let { m ->
            // Dedicated probe for the READY-state monitor: a short per-probe
            // deadline so a stale keep-alive connection cannot stall detection, and
            // onHealthyBody is null so probing never writes the models cache to disk
            // once per second.
            HealthChecker.http(client, config.backend, probeTimeoutMs = m.timeoutMs)::isHealthy
        },
        monitorIntervalMs = monitor?.intervalMs,
        downAfterFailures = monitor?.downAfterFailures ?: 3,
        idleTimeoutMs = shutdown?.idleTimeoutMs,
        shutdownPollIntervalMs = shutdown?.pollIntervalMs ?: 60_000L,
        shutdownHandler = shutdownHandler ?: shutdown?.let(CommandShutdownHandler::from),
    )
    val proxy = SleepyProxy(client, config.backend, lifecycle, modelsCache, config.proxy.logRequests)

    intercept(ApplicationCallPipeline.ApplicationPhase.Call) {
        proxy.handle(this.context)
    }

    environment.monitor.subscribe(ApplicationStopped) {
        lifecycle.close()
        client.close()
    }
}
