# Configuration

Every environment variable and setting the Titan server, worker, and extensions read.

Two mechanisms are in play:

- **The worker** is entirely environment-driven (`WorkerConfig.fromEnv`).
- **The server** is configured through Quarkus MicroProfile Config. Most properties resolve `${ENV_VAR:default}` from the environment, so the operator-facing knob is the environment variable named inside `${…}`. The server also honours Quarkus profile overrides (`%dev`, `%test`, `%prod`); production rigs run with `QUARKUS_PROFILE=prod`.

In production (`%prod`) several settings **fail fast** with no localhost fallback: `TITAN_DB_URL`, `TITAN_PUBLIC_URL`, `TITAN_OIDC_ISSUER`, and (on the worker, unless `TITAN_DEV_MODE=true`) `TITAN_WORKSPACE` and `TITAN_LIBRARIES_ROOT`.

## Database

Read by both server and worker.

| Env var | Controls | Default |
|---|---|---|
| `TITAN_DB_URL` | JDBC connection URL | none in `%prod` (fail-fast) |
| `TITAN_DB_USER` | DB user | `titan` |
| `TITAN_DB_PASSWORD` | DB password | empty (server) / `titan` (worker) |
| `TITAN_DB_POOL_SIZE` | connection-pool max size (server) | `10` |

Flyway runs migrations at startup against schema `titan`. See [database.md](database.md).

## HTTP / server

| Knob | Controls | Default |
|---|---|---|
| `quarkus.http.port` | HTTP listen port | `8080` |
| `TITAN_PUBLIC_URL` | externally-reachable base URL (callback links, build deep-links) | none in `%prod` (fail-fast) |

Reverse-proxy `X-Forwarded-*` trust is enabled in `%prod`. CORS is enabled only in `%dev` for the Vite dev server; production rigs set `QUARKUS_HTTP_CORS*` explicitly. OpenAPI is at `/q/openapi`, health at `/q/health` (`/live`, `/ready`), Prometheus metrics at `/q/metrics`.

## Authentication / OIDC

| Env var | Controls | Default |
|---|---|---|
| `TITAN_OIDC_ISSUER` | OIDC issuer URL (server-side bearer validation) | none in `%prod` (fail-fast) |
| `TITAN_OIDC_CLIENT_ID` | server-side service client id | `titan-server` |
| `TITAN_OIDC_CLIENT_SECRET` | service client secret | empty |
| `TITAN_OIDC_AUDIENCE` | expected token audience | `titan-server` |
| `TITAN_UI_OIDC_CLIENT_ID` | public SPA client id (served to the UI) | `titan-ui` |
| `TITAN_UI_OIDC_AUTHORITY` | browser-facing issuer override | falls back to the issuer URL |
| `TITAN_KC_ADMIN_CLIENT_ID` | Keycloak admin client (backs user admin) | unset |
| `TITAN_KC_ADMIN_CLIENT_SECRET` | Keycloak admin client secret | unset |

## Worker

All read from the environment (`WorkerConfig`).

| Env var | Controls | Default |
|---|---|---|
| `TITAN_AGENT_ID` | agent id (also a queue target) | `worker-<hostname>` |
| `TITAN_AGENT_NAME` | display name | `Titan Worker @ <hostname>` |
| `TITAN_LABELS` | CSV labels → one step-queue per label | `linux` |
| `TITAN_EXECUTORS` | concurrent executors | `1` |
| `TITAN_REMOTE_FS` | shared workspace root reported to the controller | `/titan` |
| `TITAN_MODE` | usage mode | `NORMAL` |
| `TITAN_QUEUE` | explicit step-queue override | `default` |
| `TITAN_SYNTHESIS_QUEUE` | synthesis queue name (must match the controller) | `synthesis` |
| `TITAN_WORKSPACE` | workspace root | required unless `TITAN_DEV_MODE=true` |
| `TITAN_LIBRARIES_ROOT` | shared-library cache root | required unless `TITAN_DEV_MODE=true` |
| `TITAN_WORKSPACE_HOST_ROOT` | Docker-out-of-Docker host-path translation | empty (identity) |
| `TITAN_DEV_MODE` | allow tmpdir workspace/library fallback | `false` |
| `TITAN_POLL_MS` | task poll interval (ms) | `1000` |
| `TITAN_HEARTBEAT_MS` | heartbeat interval (ms) | `10000` |
| `TITAN_STEPS_DIR` | third-party step jar / manifest directory | `./steps/` |
| `TITAN_OTEL_ENDPOINT` | worker OTLP exporter endpoint (unset → no-op tracer) | unset |

The worker also injects `TITAN_BUILD_ID` and `TITAN_STAGE_ID` into step environments at runtime — these are set per build, not operator knobs.

## Artifact storage

The store kind is selected by `TITAN_ARTIFACT_STORE`; every other `TITAN_ARTIFACT_*` variable is prefix-stripped, lower-cased, and passed into the backend's config map. With no store configured, `archiveArtifacts` fails closed. See [spi.md](spi.md#artifactstore-spi).

| Env var | Controls |
|---|---|
| `TITAN_ARTIFACT_STORE` | backend kind: `fs`, `s3`, `nexus`, or blank |
| `TITAN_ARTIFACT_ROOT` | (fs) storage root |

**S3 / R2** (`titan-artifact-s3`):

| Env var | Maps to |
|---|---|
| `TITAN_ARTIFACT_BUCKET` | `bucket` |
| `TITAN_ARTIFACT_ENDPOINT` | `endpoint` (set for R2) |
| `TITAN_ARTIFACT_REGION` | `region` (R2: `auto`) |
| `TITAN_ARTIFACT_ACCESSKEY` | `accesskey` |
| `TITAN_ARTIFACT_SECRETKEY` | `secretkey` |
| `TITAN_ARTIFACT_PATHSTYLE` | `pathstyle` (default `false`) |

**Nexus** (`titan-artifact-nexus`):

| Env var | Maps to |
|---|---|
| `TITAN_ARTIFACT_URL` | `url` |
| `TITAN_ARTIFACT_REPOSITORY` | `repository` |
| `TITAN_ARTIFACT_USERNAME` | `username` |
| `TITAN_ARTIFACT_PASSWORD` | `password` |

## Secrets / credential encryption

Credentials are envelope-encrypted; the KEK comes from a `CredentialKeyProvider` chain (see [spi.md](spi.md#secrets-spis)).

| Env var (system property) | Controls | Default |
|---|---|---|
| `TITAN_SECRETS_BACKEND` | active secrets backend by `name()` | `db-envelope` |
| `TITAN_KEK` (`titan.kek`) | base64 AES-256 KEK (env key provider) | unset |
| `TITAN_PROFILE` | gate for the dev auto-KEK (must be `dev`) | unset |
| `LOOP_TITAN_ALLOW_DEV_KEK` (`loop.titan.allow.dev.kek`) | explicit dev-KEK opt-in | unset |
| `TITAN_DEV_KEK_PATH` (`…path`) | persisted dev-KEK file | `/var/titan/secrets/dev-kek` |

**Infisical** (`titan-keyprovider-infisical`) — inert unless a token and project are set:

| Env var | Controls | Default |
|---|---|---|
| `INFISICAL_TOKEN` / `INFISICAL_TOKEN_FILE` | auth token | — |
| `INFISICAL_PROJECT_ID` | project (required to activate) | — |
| `INFISICAL_ENV` | environment | `prod` |
| `INFISICAL_SECRET_PATH` | secret path | `/` |
| `INFISICAL_API_URL` | API base URL | `https://app.infisical.com` |
| `INFISICAL_KEY_SECRET_NAME` | secret holding the base64 KEK | `TITAN_CREDENTIAL_KEY` |

**Vault** (`titan-secrets-vault`) — activated by `VAULT_ADDR`:

| Env var | Controls | Default |
|---|---|---|
| `VAULT_ADDR` | Vault address (activates the backend) | — |
| `VAULT_TOKEN` | token auth (highest precedence) | — |
| `VAULT_ROLE` (+ `VAULT_K8S_SA_TOKEN_PATH`) | Kubernetes-role auth | — |
| `VAULT_ROLE_ID` + `VAULT_SECRET_ID` | AppRole auth | — |
| `VAULT_NAMESPACE` | Vault namespace | — |
| `VAULT_KV_MOUNT` | KV mount | `secret` |

## Engine scheduling and retention (server)

| Property (env var) | Controls | Default |
|---|---|---|
| `titan.trigger.rate.burst` (`TITAN_TRIGGER_RATE_BURST`) | manual-trigger token-bucket burst | `5` |
| `…rate.refill-per-min` (`TITAN_TRIGGER_RATE_REFILL_PER_MIN`) | token refill rate | `1` |
| `…rate.idle-eviction-seconds` (`TITAN_TRIGGER_RATE_IDLE_EVICTION_SECONDS`) | idle bucket eviction | `3600` |
| `quarkus.scheduler.titan.discovery.every` (`TITAN_DISCOVERY_EVERY`) | SCM discovery poll cadence | `1m` |
| `quarkus.scheduler.titan.agent-reaper.every` (`TITAN_AGENT_REAPER_EVERY`) | agent-reaper cadence | `30s` |
| `titan.agent-reaper.stale-seconds` (`TITAN_AGENT_REAPER_STALE_SECONDS`) | online→offline staleness | `90` |
| `LOOP_NO_WORKER_TIMEOUT_S` | no-worker build timeout (5–3600) | `60` |
| `LOOP_TRANSITION_SOFT_CAP` | transition-spam soft cap (warn) | `200` |
| `LOOP_TRANSITION_HARD_CAP` | transition-spam hard cap (halt) | `1000` |
| `titan.task-archive.retention-days` | task-archive retention | `30` |
| `titan.job-build-retention` | builds kept per job | `100` |
| `quarkus.scheduler.titan.audit-retention.cron` | audit purge cadence | `0 45 3 * * ?` |
| `titan.audit-retention.batch-size` | audit purge batch size | `10000` |

## Observability

| Property (env var) | Controls | Default |
|---|---|---|
| `quarkus.otel.enabled` (`TITAN_OTEL_ENABLED`) | server OpenTelemetry | `false` |
| `quarkus.otel.exporter.otlp.endpoint` (`TITAN_OTEL_ENDPOINT`) | OTLP endpoint | empty |

## Email digest (server)

`titan.notifications.email.digest.enabled` (`false`), `.recipients`, `.from` (`titan@localhost`), `.smtp.host` (`localhost`), `.smtp.port` (`25`), `.credential.scope`, `.credential.key`.

## SCM integration

These configure optional SCM integrations layered on top of the core engine — they are not required to run pipelines.

**GitHub:** `titan.github.pr-comments.enabled` (`true`), `titan.github.check-runs.enabled` (`true`), `titan.github.api-base-url` (`https://api.github.com`).
**GitLab:** `titan.gitlab.mr-comments.enabled` (`true`), `titan.gitlab.mr-reviews.enabled` (`true`), `gitlab.base-url`.
**Bitbucket:** `titan.bitbucket.pr-comments.enabled` (`true`), `titan.bitbucket.inline-review.enabled` (`false`), `bitbucket.base-url` (`https://api.bitbucket.org`).

**Pulsar** (SCM source integration):

| Env var / property | Controls | Default |
|---|---|---|
| `PULSAR_SCAN_ENABLED` (`pulsar.scan.enabled`) | enable the scheduled Pulsar poll scanner | `false` |
| `PULSAR_NODE_BASE_URL` (`pulsar.node-base-url`) | Pulsar node URL for check posts and clones | `http://pulsar:8080` |
| `PULSAR_WEBHOOK_SECRET` (`pulsar.webhook-secret`) | HMAC secret for the Pulsar events endpoint (no secret → 401) | unset |
| `titan.pulsar.checks.enabled` | post the `build` CI check back to Pulsar | `true` |

The `PULSAR_SCAN_ENABLED` / `PULSAR_NODE_BASE_URL` environment-variable spellings are produced by the k3s Helm chart, which maps them to the Quarkus properties above.
