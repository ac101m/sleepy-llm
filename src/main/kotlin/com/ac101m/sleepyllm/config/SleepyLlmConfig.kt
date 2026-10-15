package com.ac101m.sleepyllm.config

import com.typesafe.config.Config
import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigRenderOptions
import org.slf4j.LoggerFactory
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
    /**
     * Log a line per request — forwarded ("forwarded GET /v1/chat/completions ->
     * 200 (42ms)"), served from the models cache, or a live models fetch that was
     * not cached. Useful when troubleshooting a real client, but noisy under
     * steady polling load, so it is off unless explicitly enabled.
     */
    val logRequests: Boolean = false,
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

data class WakeConfig(
    /**
     * The wake command, as argv, run verbatim via ProcessBuilder with no shell —
     * exactly like the shutdown command. The MAC address, broadcast/interface
     * flags and any tool-specific options are all part of this list, e.g.
     * `["wakeonlan", "-i", "192.168.10.255", "aa:bb:cc:dd:ee:ff"]`; the proxy does
     * not assemble them. There is no mode switch — if you want wake to be a no-op
     * placeholder, point this at a command that does nothing (e.g. `["true"]`).
     */
    val command: List<String>,
)

object SleepyLlmConfigLoader {
    private const val CONFIG_ENV_VAR = "SLEEPY_LLM_CONFIG"
    private val logger = LoggerFactory.getLogger(SleepyLlmConfigLoader::class.java)

    /**
     * Every recognized key in the `sleepyllm` tree, per object, so a typo'd field
     * cannot be silently ignored. HOCON accepts unknown keys without complaint, so
     * `check_config` instead of `check-command` would otherwise fall back to its
     * classpath default and quietly disable the login guard — the exact failure
     * [validateKnownKeys] exists to make loud. Paths are relative to `sleepyllm`.
     */
    private val knownKeys: Map<String, Set<String>> = mapOf(
        "" to setOf("proxy", "backend", "wake", "shutdown"),
        "proxy" to setOf("host", "port", "log-requests"),
        "backend" to setOf("scheme", "host", "port", "health", "models-cache-file", "monitor"),
        "backend.health" to setOf("path", "timeout", "poll-interval"),
        "backend.monitor" to setOf("enabled", "interval", "timeout", "failures"),
        "wake" to setOf("command"),
        "shutdown" to setOf("enabled", "idle-timeout", "poll-interval", "command", "check-command"),
    )

    /**
     * Rejects unrecognized keys anywhere in the `sleepyllm` tree, naming the
     * offending key(s) and the parent so the fix is obvious. [load] calls this on
     * the merged (user file + classpath) config, so both sources are validated and
     * a typo is a hard startup error rather than a silent behavior change. Kept
     * [internal] for tests. Throws [ConfigException.BadValue].
     */
    internal fun validateKnownKeys(root: Config) {
        for ((path, allowed) in knownKeys) {
            val obj = if (path.isEmpty()) root else runCatching { root.getConfig(path) }.getOrNull() ?: continue
            val unknown = obj.root().keys - allowed
            if (unknown.isNotEmpty()) {
                val where = if (path.isEmpty()) "sleepyllm" else "sleepyllm.$path"
                throw ConfigException.BadValue(
                    where,
                    "unrecognized key(s): ${unknown.joinToString(", ")} (known: ${allowed.joinToString(", ")}). " +
                        "HOCON would silently ignore these, so they have no effect; this is usually a typo, " +
                        "e.g. \"check_config\" instead of \"check-command\".",
                )
            }
        }
    }

    fun load(): SleepyLlmConfig {
        val config = loadHocon()
        val root = config.getConfig("sleepyllm")
        validateKnownKeys(root)
        val parsed = SleepyLlmConfig(
            proxy = ProxyConfig(
                host = root.getString("proxy.host"),
                port = root.getInt("proxy.port"),
                logRequests = root.getBoolean("proxy.log-requests"),
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
                val command = root.getStringList("wake.command")
                require(command.isNotEmpty()) {
                    "wake.command (a list of argv) must be set, e.g. " +
                        "[\"wakeonlan\", \"-i\", \"192.168.10.255\", \"aa:bb:cc:dd:ee:ff\"]; " +
                        "to make wake a no-op placeholder set it to [\"true\"]"
                }
                WakeConfig(command)
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
        logEffectiveConfig(root)
        return parsed
    }

    /**
     * Logs the effective, merged configuration once at startup, rendered back by the
     * HOCON parser itself so it reads like application.conf — nested, indented,
     * real key names. This mirrors the file's shape an operator can diff mentally
     * against their override, and shows at once which features are armed and with
     * which commands. Command lists are shown verbatim (they are run via
     * ProcessBuilder, no shell, so this mirrors what will actually execute); keep
     * credentials out of the config, not just out of the logs.
     */
    private fun logEffectiveConfig(root: Config) {
        val rendered = ConfigFactory.empty()
            .withValue("sleepyllm", root.root())
            .root()
            .render(ConfigRenderOptions.defaults().setOriginComments(false).setComments(false).setJson(false))
        logger.info("effective configuration:\n{}", rendered.compactArrays())
    }

    /**
     * The renderer writes each array element on its own line (and the closing `]`
     * on a line of its own); the only lists in this config are short argv commands,
     * which read better the way they appear in application.conf (`command = ["ssh",
     * ...]`, one line). Collapses exactly those blocks — it only fires on a line
     * ending in `[`, which in this render is always an array opener, never an object.
     */
    private fun String.compactArrays(): String {
        val out = StringBuilder()
        val lines = lineSequence().iterator()
        while (lines.hasNext()) {
            val line = lines.next()
            if (!line.endsWith('[')) {
                out.appendLine(line)
                continue
            }
            val elements = mutableListOf<String>()
            while (lines.hasNext()) {
                val part = lines.next().trim().removeSuffix(",")
                when {
                    part == "]" -> break                       // `]` on its own line
                    part.endsWith(']') -> {                     // last element shares the line
                        elements += part.dropLast(1).trim()
                        break
                    }
                    else -> elements += part
                }
            }
            out.appendLine("${line.dropLast(1)}[${elements.joinToString(", ")}]")
        }
        return out.toString()
    }

    private fun loadHocon(): Config {
        val classpath = ConfigFactory.load()
        return System.getenv(CONFIG_ENV_VAR)
            ?.takeIf { it.isNotBlank() }
            ?.let { path ->
                val file = File(path)
                require(file.isFile) { "$CONFIG_ENV_VAR points to a non-existent file: $path" }
                // Resolve the user file against the classpath tree so `${...}` references
                // in an override render (and are read by the getters in [load]) as their
                // actual values rather than unresolved tokens.
                ConfigFactory.parseFile(file).withFallback(classpath).resolve()
            }
            ?: classpath
    }
}
