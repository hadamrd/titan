# Deploying Titan

Titan deploys as three container images — **server**, **UI**, and
**worker** — plus a Postgres database and a Keycloak (OIDC) provider. The
local docker rig (`rig/local/`) runs this stack on one machine for
development; the k3s Helm chart (`rig/k3s/`) runs it on a Kubernetes
cluster as the standing/public rig. Both deploy the same images.

## The images

| Image | Role | Service |
|---|---|---|
| `titan-server` | Quarkus API + orchestrator. Runs Flyway on boot to migrate the schema. | port 8080 |
| `titan-ui` | React SPA served by nginx. | port 80 |
| `titan-worker` | Pull-based execution agent. Polls `task_queue`; no inbound service. | (outbound only) |

Each image is built from the Dockerfile under
`rig/local/Dockerfile.titan-{server,ui,worker}` — the local rig and the
cluster rig share the same build, so what you dogfood locally is what
ships. Publish them to any OCI registry (the chart defaults reference a
placeholder `registry.example.com`; point them at yours).

## Local rig (docker-compose)

For development and end-to-end testing:

```sh
task dev:titan       # build + start postgres, keycloak, server, ui, worker
task dev:logs        # tail all services
task dev:down        # tear down + wipe volumes
```

Local secrets live in a gitignored `rig/local/.env`, scaffolded from
`.env.example` on first run. See [Getting started](../getting-started.md)
for the full local walkthrough.

## Cluster rig (k3s Helm chart)

The chart at `rig/k3s/helm/titan` packages the whole stack — server, UI,
worker, Postgres, Keycloak, ingress, and the secret wiring — as one
release. A deploy is essentially one `helm upgrade --install`; Helm owns
ordering via `--wait` and init-container waits.

### Prerequisites

- Helm 3 and `kubectl`, with a context for your cluster.
- `cert-manager` + an ingress controller (e.g. `ingress-nginx`) on the
  cluster, only if you enable ingress with TLS.

### Configure

All site-specific config goes in one values file,
`rig/k3s/rig-values.yaml` (gitignored), scaffolded from
`rig-values.yaml.example`. The keys you'll set:

```yaml
deploy:
  namespace: titan

# Pin each image to the tag you published.
titanServer:
  image: registry.example.com/titan-server
  tag: "1.0.0-rc1"
titanUi:
  image: registry.example.com/titan-ui
  tag: "1.0.0-rc1"
titanWorker:
  image: registry.example.com/titan-worker
  tag: "1.0.0-rc1"

ingress:
  enabled: true
  className: nginx
  host: titan.example.com

# titan-server credential-store backend: db-envelope (default) | infisical
titanServer:
  secretsBackend: "db-envelope"
  artifacts:
    backend: fs          # fs | s3
```

Never point a production rig at a floating tag (`latest`); pin an explicit
version.

### Required secrets

The chart materialises a single `titan-secrets` Secret. Either set real
values in the gitignored `rig-values.yaml` under the `secrets:` block, or
set `secrets.create: false` and provision a Secret named `titan-secrets`
yourself (ExternalSecret / SOPS / sealed-secrets) with the keys the chart
references:

| Key | Purpose |
|---|---|
| `POSTGRES_PASSWORD` | Postgres password (honoured only on the first DB init). |
| `TITAN_KEK` | Credential-envelope key-encryption key — the root of trust for every stored secret. |
| `KEYCLOAK_ADMIN_PASSWORD` | Keycloak admin password. |
| `ARTIFACTS_ACCESS_KEY` / `ARTIFACTS_SECRET_KEY` | Only when `titanServer.artifacts.backend=s3`. |

In production, sync these from an external secret store rather than
committing them. The credential KEK in particular is load-bearing — a
server with no KEK provider configured refuses to boot. See
[runbooks/kek-config.md](runbooks/kek-config.md).

### Deploy

```sh
# Lint / dry-run before the first deploy.
task k3s:lint
task k3s:template

# Deploy (helm upgrade --install).
task deploy:k3s
```

Under the hood:

```sh
helm upgrade --install titan rig/k3s/helm/titan \
  -f rig/k3s/rig-values.yaml \
  -n <namespace> --create-namespace \
  --timeout 5m
```

### Verify

```sh
task k3s:status                              # pod status
task k3s:logs                                # tail titan-server

# With ingress enabled — must return 200.
curl -fsS https://titan.example.com/api/v1/stats

# Without ingress — port-forward the UI.
kubectl -n <ns> port-forward svc/titan-ui 5180:80
```

### Roll back / uninstall

```sh
helm history titan -n <ns>
helm rollback titan <REV> -n <ns>

helm uninstall titan -n <ns>
# PVCs (postgres data, artifacts, worker workspace) are NOT auto-deleted.
kubectl -n <ns> delete pvc -l app.kubernetes.io/instance=titan
```

### Multi-worker & public ingress

The k3s chart can expose a TLS-fronted public host and run more than one
worker replica off the same queue. Both are off by default; turn them on in
`rig-values.yaml`.

**Prerequisites for TLS ingress:**

- An ingress controller on the cluster (e.g. `ingress-nginx`; k3s ships
  Traefik by default — either install `ingress-nginx` and set
  `ingress.className=nginx`, or set `ingress.className=traefik`).
- `cert-manager` with a working `ClusterIssuer` (the example values
  reference `letsencrypt-prod`).
- A public DNS A record for your `ingress.host` pointing at the cluster's
  ingress LoadBalancer IP. This is the one step that requires out-of-band
  action — the DNS record must exist before TLS issuance can succeed.

With `ingress.enabled=true` the chart also sets `KC_HOSTNAME_URL` on Keycloak
from `ingress.host`, and `titan-server` runs with `QUARKUS_PROFILE=prod` so
`quarkus.http.proxy.proxy-address-forwarding` is active — that is the single
switch that makes OIDC redirects land back on the public host instead of an
internal `titan-server:8080`.

**Multi-worker scaling.** Set `titanWorker.replicas` above 1 and the chart
runs that many `titan-worker` pods, all polling the same `task_queue` on the
same `queue_name`. Postgres-side `SELECT … FOR UPDATE SKIP LOCKED` (in
`TaskQueueDao`) guarantees each task is claimed by exactly one worker — adding
replicas is pure horizontal scale, no dispatcher to configure. The invariant
is hard-asserted by `MultiWorkerClaimIsolationIT`
(`./gradlew :titan-server:integrationTest --tests MultiWorkerClaimIsolationIT`);
if a task ever runs on two workers, that test catches it.

Workers MUST stay pull-based — a push-mode dispatcher would break the
`SKIP LOCKED` isolation guarantee and the "just scale replicas" property.

To confirm a live multi-worker rig is claiming each task exactly once:

```sql
SELECT task_token, COUNT(DISTINCT claimed_by)
FROM titan.task_queue
WHERE claimed_by IS NOT NULL
GROUP BY task_token HAVING COUNT(DISTINCT claimed_by) > 1;
-- MUST return zero rows.
```

## Cutting a release

Releases are manual and version-pinned.

1. **Update `CHANGELOG.md`** — a new `## [X.Y.Z] - YYYY-MM-DD` section,
   grouped by Added / Fixed / Security / Breaking, one line per merged PR.
2. **Bump the version.** The canonical pin is `version` in
   `gradle.properties`. Align the chart at `rig/k3s/helm/titan/Chart.yaml`
   (`version:` and `appVersion:`) to the same `X.Y.Z`. The local rig has
   no version pin — it always builds from the working tree.
3. **Commit + tag.**

   ```sh
   git tag -a vX.Y.Z -m "Release X.Y.Z"
   git push origin vX.Y.Z
   ```

4. **Publish images** for the tag to your registry, then bump the
   `titanServer.tag` / `titanUi.tag` / `titanWorker.tag` in
   `rig-values.yaml` and run `task deploy:k3s`.

Titan follows [Semantic Versioning](https://semver.org/). Pre-1.0
releases may break on a minor bump; from 1.0.0 on, breaks require a major
bump.

## CI

CI runs as a Titan self-pipeline — Titan building Titan — rather than via
GitHub Actions. The local pre-merge gate is `task ci:verify`; see
[runbooks/ci-on-pr.md](runbooks/ci-on-pr.md).

## Observability

`titan-server` and `titan-worker` emit structured JSON logs and
OpenTelemetry traces, and the server exposes Prometheus metrics on
`/q/metrics`. See [logging](logging.md) and
[runbooks/observability.md](runbooks/observability.md).
