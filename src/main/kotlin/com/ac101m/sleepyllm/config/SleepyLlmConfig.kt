package com.ac101m.sleepyllm.config

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import java.io.File

/**
 * Typed view of the application configuration, loaded from HOCON.
 *
 * The config is read from the file referenced by the `SLEEPY_LLM_CONFIG` environment
 * variable when set, falling back to `application.conf` on the classpath. See
 * `src/main/resources/application.conf` for the full set of options and defaults.
 */
data class SleepyLlmConfig(
    val proxy: ProxyConfig,
    val backend: BackendConfig,
    val wake: WakeConfig,
)

data class ProxyConfig(
    val host: String,
    val port: Int,
)

data class BackendConfig(
    val scheme: String,
    val host: String,
    val port: Int,
    val health: HealthConfig,
) {
    /** Origin URL of the inference server, e.g. `http://127.0.0.1:8000`. */
    val baseUrl: String get() = "$scheme://$host:$port"
}

data class HealthConfig(
    val path: String,
    val timeoutMs: Long,
    val pollIntervalMs: Long,
)

enum class WakeMode {
    DUMMY,
    COMMAND,
}

data class WakeConfig(
    val mode: WakeMode,
    val command: String,
    val mac: String,
)

object SleepyLlmConfigLoader {
    private const val CONFIG_ENV_VAR = "SLEEPY_LLM_CONFIG"

    fun load(): SleepyLlmConfig {
        val config = loadHocon()
        val root = config.getConfig("sleepyllm")
        return SleepyLlmConfig(
            proxy = ProxyConfig(
                host = root.getString("proxy.host"),
                port = root.getInt("proxy.port"),
            ),
            backend = BackendConfig(
                scheme = root.getString("backend.scheme"),
                host = root.getString("backend.host"),
                port = root.getInt("backend.port"),
                health = HealthConfig(
                    path = root.getString("backend.health.path"),
                    timeoutMs = root.getDuration("backend.health.timeout").toMillis(),
                    pollIntervalMs = root.getDuration("backend.health.poll-interval").toMillis(),
                ),
            ),
            wake = WakeConfig(
                mode = WakeMode.valueOf(root.getString("wake.mode").uppercase()),
                command = root.getString("wake.command"),
                mac = root.getString("wake.mac"),
            ),
        )
    }

    private fun loadHocon(): Config {
        val classpath = ConfigFactory.load()
        return System.getenv(CONFIG_ENV_VAR)
            ?.takeIf { it.isNotBlank() }
            ?.let { path ->
                val file = File(path)
                require(file.isFile) { "$CONFIG_ENV_VAR points to a non-existent file: $path" }
                ConfigFactory.parseFile(file).withFallback(classpath)
            }
            ?: classpath
    }
}
