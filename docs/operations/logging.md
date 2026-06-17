# titan-server logging

`titan-server` emits structured JSON logs to stdout. OTLP trace export and
worker-side logging ship today; for the tracing and metrics side of the
observability stack see [runbooks/observability.md](runbooks/observability.md).

## Format

`titan-server` emits **JSON-per-line** to stdout via the
[`quarkus-logging-json`](https://quarkus.io/guides/logging#json-logging) extension.
Each record is a single line containing at least:

| Field             | Example                                | Notes                                   |
| ----------------- | -------------------------------------- | --------------------------------------- |
| `timestamp`       | `2026-05-21T10:42:01.234Z`             | ISO-8601, UTC                           |
| `level`           | `INFO`                                 | JBoss LogManager level                  |
| `loggerName`      | `io.adaptiq.titan.engine.Orchestrator` | fully-qualified logger                  |
| `message`         | `build 1234 transitioned to SUCCESS`   | formatted message                       |
| `service.name`    | `titan-server`                         | static additional field                 |
| `service.version` | `0.1.0-SNAPSHOT`                       | resolved from `quarkus.application.version` |
| `exception`       | `{ "refId":..., "exceptionType":... }` | only on `throwable` records             |

Exception output mode is `detailed-and-formatted` — both a structured
exception block and the rendered stacktrace string are emitted, so a log
viewer can show whichever it can render.

## Profile guard

| Profile        | `quarkus.log.console.json` | Why                                  |
| -------------- | -------------------------- | ------------------------------------ |
| default / prod | `true`                     | machine-ingest                       |
| `%dev`         | `false`                    | readable terminal output for devs    |
| `%test`        | `false`                    | readable test output in CI / locally |

The test suite includes `JsonLoggerTest` which asserts the binding under both
default-test and a forced-on (prod-equivalent) profile.

## Disabling

Override the env var at runtime:

```sh
export QUARKUS_LOG_CONSOLE_JSON=false
```

…or set `quarkus.log.console.json=false` in `application.properties` of a
downstream image. No code change required.

## Why JSON-per-line

Loki, Datadog, Grafana Cloud, and CloudWatch all ingest JSON-per-line stdout
natively. Emitting structured records at the source removes a parsing pipeline
(grok / regex / multiline-stitching) — which is the most common failure mode
for log shipping in CI/CD systems where exceptions span many lines.

## Beyond JSON-per-line

JSON-per-line is the log transport. The rest of the observability surface —
the OTLP trace exporter, `traceparent` propagation across the task queue, and
`titan-worker`'s own structured logging — ships alongside it; see
[runbooks/observability.md](runbooks/observability.md) for wiring those to a
back-end. Log-level tuning, MDC contexts, and async handlers are configured the
standard Quarkus way and are out of scope for this note.
