# Sleepy LLM

A reverse proxy for LLM inference servers that wakes your server on demand and
shuts it down when idle. When a request arrives, Sleepy LLM wakes the backend
(via a Wake-on-LAN command), waits for it to become healthy, then forwards the
request. While the server is awake, traffic is proxied straight through.

## How it works

```
client ──► Sleepy LLM ──► wake on demand (Wake-on-LAN command / dummy) ──► LLM inference server
                │
                └── health-checks backend, forwards request once ready
```

- **Reverse proxy**: application-level proxy that forwards every method, path
  and query string to the backend. Request and response bodies are streamed, so
  LLM token streaming / SSE passes through unchanged.
- **Wake on demand**: on the first request, a wake signal is sent and the proxy
  polls the backend's health endpoint until it is ready (or times out). Requests
  arriving during a wake share the same in-flight wake — exactly one packet is
  sent, never one per request.
- **Pluggable wake**: `wake.mode = "dummy"` does nothing (safe for development);
  `wake.mode = "command"` runs the user-configured `wake.command` verbatim as argv
  (no shell) — e.g. `["wakeonlan", "-i", "192.168.10.255", "aa:bb:cc:dd:ee:ff"]` —
  so the MAC address, broadcast and any tool-specific flags are fully under the
  user's control.
- **Cached model list**: `GET /v1/models` is answered from a disk cache whenever
  the backend is not ready, so model-list polling never wakes the server. The
  cache refreshes atomically on every successful `/v1/models` the proxy performs —
  health probes (when `backend.health.path` defaults to `/v1/models`) and
  forwarded client requests — and with a cold cache the first request wakes the
  backend once to bootstrap it. While the backend is ready, the endpoint is
  always forwarded live, so a server that swaps in a new model is reported
  accurately. Set `backend.models-cache-file` to empty to disable.
- **Down detection**: Sleepy LLM is not the only actor in the sleep/wake cycle —
  the machine can be powered off directly at any time. While the backend is
  READY the proxy probes its health endpoint (`backend.monitor.interval`, default
  10s) and, after `backend.monitor.failures` (3) consecutive failed probes — each
  bounded by `backend.monitor.timeout` (1s) — or as soon as a forwarded request
  fails to connect, treats the backend as powered down and returns to
  wake-on-demand, so the next request wakes it again. Polling only runs while the
  backend is awake, so it never causes a wake; it only notices when the backend
  has gone. The poller is deliberately slow (10s) because latency-sensitive
  detection is the reactive path — a request that fails to connect flips state
  immediately; the poller exists to catch a backend powered off while idle.
- **Idle shutdown** (opt-in): when `shutdown` is enabled, the proxy powers the
  backend machine down after `shutdown.idle-timeout` (default 30m) of no real
  traffic, and issues the user-configured `shutdown.command` to do it. The idle
  clock resets on every real forwarded request — but not on `/v1/models`, so a
  dashboard polling the model list can't keep the machine awake. An optional
  `shutdown.check-command` guards against powering the machine down while someone
  is logged in: if it exits non-zero (or can't be run) the machine is treated as
  occupied and shutdown is deferred, keeping it awake for a full window after the
  user logs out. After shutdown, the state returns to wake-on-demand, so the next
  request wakes it again.

## Building

Requires **JDK 21+** — both for Gradle itself (the Gradle 9.8 wrapper needs 17+;
the build targets JVM 21 bytecode via a toolchain) and for running the fat jar
on the deploy host. Set `JAVA_HOME` to a JDK 21+ install if it is not already.

```bash
./gradlew build       # compile + test
./gradlew run         # start the proxy with defaults
./gradlew shadowJar   # self-contained jar: build/libs/sleepy-llm-0.1.0-all.jar
```

## Configuration

Defaults live in `src/main/resources/application.conf`. Override via a file
pointed to by the `SLEEPY_LLM_CONFIG` environment variable (e.g. one that sets
the real MAC address):

```hocon
sleepyllm {
  proxy  { host = "0.0.0.0", port = 8080 }
  backend {
    host = "127.0.0.1", port = 8000
    health { path = "/v1/models", timeout = 60s, poll-interval = 1s }
    models-cache-file = "./sleepy-llm-models.json" # optional; "" disables the /v1/models cache
    monitor {                    # down detection while READY (see "How it works")
      enabled = true
      interval = 10s             # probe the health endpoint while the backend is awake
      timeout = 1s               # per-probe deadline; must stay well below health.timeout
      failures = 3               # consecutive failed probes before READY -> ASLEEP
    }
  }
  wake {
    mode = "command"          # or "dummy"
    command = ["wakeonlan", "-i", "192.168.10.255", "aa:bb:cc:dd:ee:ff"]
                              # ^ full argv, run as-is; MAC, broadcast and tool flags all here
  }
  shutdown {                  # optional; disabled by default
    enabled = true
    idle-timeout = 30m        # power down after this long with no real requests (and no logged-in users)
    poll-interval = 1m        # how often to re-check the login guard and idle clock
    command = ["ssh", "user@host", "sudo", "poweroff"]  # argv; run via ProcessBuilder, no shell
    check-command = ["ssh", "user@host", "test", "-z", "$(who)"]  # optional; exit 0 = nobody logged in
  }
}
```

With `wake.mode = "command"`, Sleepy LLM verifies at startup that the program
named by the first element of `wake.command` can be spawned (via `--version`,
falling back to `--help`) and refuses to start if it is missing from the PATH or
not executable. `wake.mode = "dummy"` performs no such check.

## Roadmap

- Idle-timeout shutdown of the backend (Wake-on-LAN can only wake; powering
  down will require a separate mechanism, e.g. SSH).
