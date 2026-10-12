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
  `wake.mode = "command"` runs `wakeonlan <mac>`, with an optional `wake.subnet`
  broadcast forwarded via `-i` for subnets other than the default.
- **Cached model list**: `GET /v1/models` is answered from a disk cache whenever
  the backend is not ready, so model-list polling never wakes the server. The
  cache refreshes atomically on every successful `/v1/models` the proxy performs —
  health probes (when `backend.health.path` defaults to `/v1/models`) and
  forwarded client requests — and with a cold cache the first request wakes the
  backend once to bootstrap it. While the backend is ready, the endpoint is
  always forwarded live, so a server that swaps in a new model is reported
  accurately. Set `backend.models-cache-file` to empty to disable.

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
  }
  wake {
    mode = "command"          # or "dummy"
    command = "wakeonlan"     # binary resolved on PATH (or absolute path)
    subnet = "192.168.10.255" # optional; broadcast passed to wakeonlan -i (needed across subnets)
    mac = "aa:bb:cc:dd:ee:ff" # the machine hosting the backend
  }
}
```

With `wake.mode = "command"`, Sleepy LLM verifies at startup that `wake.command`
can be executed (via `--version` / `--help`) and refuses to start if the tool is
missing from the PATH or not executable. `wake.mode = "dummy"` performs no such
check.

## Roadmap

- Idle-timeout shutdown of the backend (Wake-on-LAN can only wake; powering
  down will require a separate mechanism, e.g. SSH).
