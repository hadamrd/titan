# Observability — wiring Titan up to your tracing / metrics back-end

> Audience: SRE / platform engineer running Titan in prod. Assumes Titan is
> deployed (Helm or `task dev:titan`) and you can curl `/q/metrics`.

Titan emits **Prometheus metrics** on `/q/metrics` and **OpenTelemetry OTLP
traces** to whatever endpoint you point it at. This runbook walks through
the three things SREs ask for when first dogfooding the engine:

1. Confirm metrics are scraping (`titan_step_duration_seconds`,
   `titan_build_duration_seconds`, `titan_queue_depth`, …).
2. Hook traces up to Jaeger, Grafana Tempo, Honeycomb, or any OTLP/HTTP
   collector.
3. Read the per-step trace tree end-to-end (webhook → orchestrator → worker
   subprocess) and know what to expect at each layer.

If any step here fails, see **Troubleshooting** at the bottom — every
documented failure mode is a real one we've hit in dogfood.

---

## 1. Metrics — what's on `/q/metrics`

| Metric                                       | Type      | Labels                       | Issue   |
| -------------------------------------------- | --------- | ---------------------------- | ------- |
| `titan_builds_total`                         | counter   | `status`                     | #649    |
| `titan_build_duration_seconds`               | histogram | `status`                     | #649    |
| **`titan_step_duration_seconds`**            | histogram | **`job`, `step`, `status`**  | **#1081** |
| `titan_queue_depth`                          | gauge     | `queue`                      | #649    |
| `titan_worker_count`                         | gauge     | `status`                     | #649    |
| `titan_audit_events_total`                   | counter   | `action`                     | #649    |
| `titan_engine_transitions_total`             | counter   | `kind`                       | #1050   |
| `titan_otel_export_failures_total`           | counter   | `reason`                     | #1081   |

### `titan_step_duration_seconds` — the new histogram

Buckets (seconds): **1, 5, 15, 60, 300, 1800**. These match the SLO ladder
the Grafana dashboard plots; do NOT add buckets without updating both.

The `step` label is the node's `display_name` (or the node id if it has no
display name). The `job` label is the job's `full_name`. The `status`
label is the closed set `{SUCCESS, FAILED, ABORTED, CANCELLED, SKIPPED}` —
anything else is coerced to `unknown` to prevent unbounded cardinality.

PromQL recipes:

```promql
# p95 step duration per job, last 5 minutes
histogram_quantile(
  0.95,
  sum by (job, step, le) (
    rate(titan_step_duration_seconds_bucket[5m])
  )
)

# Top 10 slowest steps in your fleet, last hour
topk(10,
  histogram_quantile(0.95,
    sum by (job, step, le) (rate(titan_step_duration_seconds_bucket[1h]))
  )
)

# Step failure rate per pipeline
sum by (job) (rate(titan_step_duration_seconds_count{status="FAILED"}[5m]))
/
sum by (job) (rate(titan_step_duration_seconds_count[5m]))
```

### Scrape config

A minimal Prometheus scrape:

```yaml
scrape_configs:
  - job_name: titan
    scrape_interval: 15s
    metrics_path: /q/metrics
    static_configs:
      - targets: ["titan-server.titan.svc:8080"]
```

---

## 2. Traces — pointing Titan at a collector

Titan uses the **OpenTelemetry OTLP/HTTP** wire format on both the
controller and the worker. The env var that turns export on:

```sh
TITAN_OTEL_ENDPOINT="http://otel-collector.observability:4318"
```

Set it on **both** `titan-server` and `titan-worker` Deployments. When
unset, both processes install a no-op SDK — the rest of the engine still
runs, but nothing is exported.

### Jaeger

Jaeger ≥ 1.35 speaks OTLP natively:

```sh
docker run -d --name jaeger \
  -p 16686:16686 \
  -p 4318:4318 \
  jaegertracing/all-in-one:latest

# point Titan at it
export TITAN_OTEL_ENDPOINT=http://localhost:4318
task dev:titan
```

UI at `http://localhost:16686` — pick service `titan-server` or
`titan-worker` in the dropdown.

### Grafana Tempo

```yaml
# tempo-values.yaml (Helm)
distributor:
  receivers:
    otlp:
      protocols:
        http:
          endpoint: 0.0.0.0:4318
```

```sh
helm install tempo grafana/tempo -f tempo-values.yaml
export TITAN_OTEL_ENDPOINT=http://tempo.tempo.svc:4318
```

### Honeycomb

```sh
export TITAN_OTEL_ENDPOINT=https://api.honeycomb.io
export OTEL_EXPORTER_OTLP_HEADERS="x-honeycomb-team=<YOUR-WRITE-KEY>"
export OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
```

The Titan-flavoured env var (`TITAN_OTEL_ENDPOINT`) is just a thin alias
for `otel.exporter.otlp.endpoint`. **Any** standard `OTEL_*` env var works
unchanged — sampling, headers, protocol, you name it.

### Generic OTLP/HTTP collector

Same shape — point `TITAN_OTEL_ENDPOINT` at `http://<collector>:4318`. If
your collector requires HTTPS or auth headers, use the standard `OTEL_*`
envs alongside.

---

## 3. The trace tree you should see

A single webhook-triggered build produces this hierarchy:

```
POST /api/v1/webhooks/github             (titan-server, kind=SERVER)
└── orchestrator.advance build=42        (titan-server, kind=INTERNAL)
    └── step build-image                 (titan-server, kind=INTERNAL)
        └── worker.runTask               (titan-worker,  kind=CONSUMER)
            └── exec docker build .      (titan-worker,  kind=INTERNAL)
```

Propagation hops:

1. **Inbound HTTP** — Quarkus' OTel extension extracts the W3C
   `traceparent` from the incoming GitHub webhook headers.
2. **Controller → worker** — the controller stamps the active span's
   `traceparent` onto `task_queue.trace_parent` at enqueue (see
   `TraceContext.currentTraceParent()` and `TaskQueueDao.enqueue`).
3. **Worker → subprocess** — the worker reads `trace_parent` off the
   claimed task row and continues the trace with `WorkerTracing
   .startTaskSpan(...)`. Subprocess instrumentation is the user's job —
   Titan exports `TRACEPARENT` into the step env via OTel's standard
   env-propagator, so any OTel-aware tool the build invokes will pick up
   the parent context for free.

The `task_queue.trace_parent` column is W3C-flat (55 chars,
`00-<32hex>-<16hex>-<2hex>`) — it's the propagation wire format the
collector understands without translation.

---

## 4. Troubleshooting

### "I see metrics but no traces"

Check the controller log for the line:

```
OTel: SDK autoconfigured (endpoint=...)
```

If you instead see `OTel: TITAN_OTEL_ENDPOINT unset — exporter disabled`,
the env var didn't reach the process. Verify via:

```sh
kubectl exec -it deploy/titan-server -- env | grep TITAN_OTEL_ENDPOINT
```

### "I see controller spans but no worker spans"

The worker logs the same `OTel: SDK autoconfigured` / `… unset` line at
boot. If the worker says `… unset`, set `TITAN_OTEL_ENDPOINT` on the
worker Deployment too — the controller-side env doesn't propagate.

### "I see worker spans but they're not children of the controller's span"

Check `task_queue.trace_parent` for the affected task:

```sql
SELECT task_token, trace_parent FROM titan.task_queue WHERE build_id = 42;
```

If `trace_parent` is `NULL`, the controller had no active span at enqueue
— it was a non-traced trigger (cron, dogfood replay, manual rerun). This
is expected.

If `trace_parent` is set but the worker still emits orphan spans, scrape
`/q/metrics` and look at `titan_otel_export_failures_total{reason=...}` —
a non-zero counter is the breadcrumb a previously-silent extract failure
now leaves. The matching WARN log from the controller has the cause.

### "Collector is unreachable; will Titan crash?"

**No.** Trace plumbing is best-effort everywhere:

- Boot-time autoconfigure failure → falls back to no-op SDK + WARN log.
- Per-span emit failure → swallowed at the OTel SDK layer (its own retry
  + drop policy applies).
- `traceparent` capture failure → WARN log + bumps
  `titan_otel_export_failures_total{reason="traceparent_capture"}`.

The build runs either way. Lost spans are an observability bug, not a
correctness bug.

### "How do I disable tracing entirely for a hot path?"

Unset `TITAN_OTEL_ENDPOINT`. The no-op SDK costs roughly one
`Span.current()` lookup per call site — negligible, but if you genuinely
need zero overhead the env-var gate is honoured at process boot.

---

## See also

- `docs/reference/pdl.md` — the PDL grammar (step labels emitted by
  the histogram come from `display_name` on each parsed node).
- `titan-server/src/main/java/io/adaptiq/titan/observability/` — the
  Java-side metric / trace plumbing.
- `titan-worker/src/main/java/io/adaptiq/titan/worker/WorkerTracing.java`
  — the worker-side OTel init.
