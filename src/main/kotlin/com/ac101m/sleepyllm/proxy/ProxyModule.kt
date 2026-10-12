package com.ac101m.sleepyllm.proxy

import com.ac101m.sleepyllm.backend.BackendLifecycle
import com.ac101m.sleepyllm.backend.HealthChecker
import com.ac101m.sleepyllm.config.SleepyLlmConfig
import com.ac101m.sleepyllm.wake.WakeSender
import com.ac101m.sleepyllm.wake.WakeSenderFactory
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
 *   (or dummy) wake implementation with a controllable fake.
 */
fun Application.module(config: SleepyLlmConfig, wakeSender: WakeSender? = null) {
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
    val lifecycle = BackendLifecycle(
        wakeSender = wakeSender ?: WakeSenderFactory.create(config.wake),
        healthChecker = HealthChecker.http(client, config.backend),
        wakeTimeoutMs = config.backend.health.timeoutMs,
        pollIntervalMs = config.backend.health.pollIntervalMs,
    )
    val proxy = SleepyProxy(client, config.backend, lifecycle)

    intercept(ApplicationCallPipeline.ApplicationPhase.Call) {
        proxy.handle(this.context)
    }

    environment.monitor.subscribe(ApplicationStopped) {
        lifecycle.close()
        client.close()
    }
}
