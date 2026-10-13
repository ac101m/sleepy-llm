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
    /**
     * Idle shutdown of the machine hosting the backend. Null (or unset) disables
     * the feature: the proxy may wake the machine but will never power it down.
     */
    val shutdown: ShutdownConfig? = null,
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
    /**
     * Where to persist the `/v1/models` response so the proxy can serve the model
     * list without waking the backend. Null disables the cache (today's behavior).
     */
    val modelsCacheFile: File? = null,
    /**
     * Down-detection of an externally shut-down backend while it is believed READY.
     * Null disables the poller; the reactive hook (a forwarded request that cannot
     * connect flips READY -> ASLEEP) is always active regardless.
     */
    val monitor: BackendMonitorConfig? = null,
) {
    /** Origin URL of the inference server, e.g. `http://127.0.0.1:8000`. */
    val baseUrl: String get() = "$scheme://$host:$port"

    companion object {
        /** OpenAI-compatible model-listing endpoint, also the default health-check path. */
        const val MODELS_PATH = "/v1/models"
    }
}

data class HealthConfig(
    val path: String,
    val timeoutMs: Long,
    val pollIntervalMs: Long,
)

/**
 * Proactive down-detection of the backend while the proxy believes it is READY:
 * probe the health endpoint every [intervalMs] and, after [downAfterFailures]
 * consecutive failed probes, treat the backend as powered down (flip READY ->
 * ASLEEP) so the next request wakes it on demand. Each probe is bounded by
 * [timeoutMs] — kept short so a stale keep-alive connection to a dead backend
 * cannot stall detection, and request-scoped so it never affects forwarded traffic.
 */
data class BackendMonitorConfig(
    val intervalMs: Long,
    val timeoutMs: Long,
    val downAfterFailures: Int,
)

/**
 * Idle shutdown of the backend machine: after [idleTimeoutMs] with no real
 * traffic (and, when [checkCommand] is set, no logged-in users), run [command]
 * to power it down. The next request then wakes it again via the normal path.
 */
data class ShutdownConfig(
    val idleTimeoutMs: Long,
    /** How often the idle loop re-checks the login guard and the idle clock while READY. */
    val pollIntervalMs: Long,
    /** Power-off command as argv, e.g. `["ssh", "user@host", "sudo", "poweroff"]`. */
    val command: List<String>,
    /**
     * Optional guard command: exit 0 = nobody is logged in (safe to shut down),
     * non-zero = someone is logged in (defer). Null means the proxy assumes full
     * control of the lifecycle and shuts the machine down on idle alone.
     */
    val checkCommand: List<String>? = null,
)

enum class WakeMode {
    DUMMY,
    COMMAND,
}

data class WakeConfig(
    val mode: WakeMode,
    /**
     * The wake command, as argv, run verbatim via ProcessBuilder with no shell —
     * exactly like the shutdown command. The MAC address, broadcast/interface
     * flags and any tool-specific options are all part of this list, e.g.
     * `["wakeonlan", "-i", "192.168.10.255", "aa:bb:cc:dd:ee:ff"]`; the proxy does
     * not assemble them. Only used when [mode] is COMMAND.
     */
    val command: List<String>,
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
                modelsCacheFile = if (root.hasPath("backend.models-cache-file")) {
                    root.getString("backend.models-cache-file").trim()
                        .takeIf { it.isNotEmpty() }
                        ?.let { File(it) }
                } else {
                    null
                },
                monitor = if (root.hasPath("backend.monitor") && root.getBoolean("backend.monitor.enabled")) {
                    BackendMonitorConfig(
                        intervalMs = root.getDuration("backend.monitor.interval").toMillis(),
                        timeoutMs = root.getDuration("backend.monitor.timeout").toMillis(),
                        downAfterFailures = root.getInt("backend.monitor.failures"),
                    )
                } else {
                    null
                },
            ),
            wake = run {
                val mode = WakeMode.valueOf(root.getString("wake.mode").uppercase())
                val command = root.getStringList("wake.command")
                require(mode != WakeMode.COMMAND || command.isNotEmpty()) {
                    "wake.mode = \"command\" requires wake.command (a list of argv) to be set; or set wake.mode = \"dummy\""
                }
                WakeConfig(mode, command)
            },
            shutdown = if (root.hasPath("shutdown") && root.getBoolean("shutdown.enabled")) {
                val command = root.getStringList("shutdown.command")
                require(command.isNotEmpty()) {
                    "shutdown.enabled = true requires shutdown.command (a list of argv) to be set"
                }
                ShutdownConfig(
                    idleTimeoutMs = root.getDuration("shutdown.idle-timeout").toMillis(),
                    pollIntervalMs = root.getDuration("shutdown.poll-interval").toMillis(),
                    command = command,
                    checkCommand = root.getStringList("shutdown.check-command").takeIf { it.isNotEmpty() },
                )
            } else {
                null
            },
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
