# Titan k3s rig — standalone Helm chart

> **Release:** chart v0.1.0 pins `ghcr.io/hadamrd/titan-{server,ui,worker}:v0.1.0`.
> See [`CHANGELOG.md`](../../CHANGELOG.md) for what shipped in 0.1.0, and
> [`docs/operations/deploying.md`](../../docs/operations/deploying.md) for the deploy + release procedure.

The Kubernetes counterpart to the local docker rig (`rig/local/`).
Deploys the same five-container Titan stack as a single Helm chart:

| Component      | Image                              | Service          |
|----------------|------------------------------------|------------------|
| postgres       | `postgres:16-alpine`               | `postgres:5432`  |
| keycloak       | `quay.io/keycloak/keycloak:25.0`   | `keycloak:8080`  |
| titan-server   | `ghcr.io/hadamrd/titan-server`     | `titan-server:8080` |
| titan-ui       | `ghcr.io/hadamrd/titan-ui`         | `titan-ui:80`    |
| titan-worker   | `ghcr.io/hadamrd/titan-worker`     | _(no Service — outbound only)_ |

No legacy plugin-host controller, no `.hpi` side-load, no JCasC, no Crossplane —
the pre-pivot pieces are gone. titan-server runs Flyway on boot to migrate the
schema; titan-worker waits on that schema before claiming tasks.

## Prerequisites

- Helm 3
- kubectl, with a kube context for your k3s cluster
  (run `task k3s:kubeconfig` to set one up via the SSH tunnel)
- `cert-manager` + `ingress-nginx` on the cluster (only if `ingress.enabled=true`)

## Deploy

```bash
# First run — scaffold rig-values.yaml from the example, then edit it.
task deploy:k3s
$EDITOR rig/k3s/rig-values.yaml

# Real deploy.
task deploy:k3s
```

Under the hood this runs:

```bash
helm upgrade --install titan rig/k3s/helm/titan \
  -f rig/k3s/rig-values.yaml \
  -n <deploy.namespace> --create-namespace \
  --timeout 5m
```

## Access the UI

With `ingress.enabled: true` and `ingress.host: titan.example.com`:

```bash
# Smoke — must return 200.
curl -fsS https://titan.example.com/api/v1/stats
```

Without an Ingress (in-cluster only), port-forward:

```bash
kubectl -n <ns> port-forward svc/titan-ui 5180:80
# then open http://localhost:5180/
```

## Logs

```bash
task k3s:logs                                                    # tail titan-server
kubectl -n <ns> logs -f deploy/titan-server
kubectl -n <ns> logs -f sts/titan-worker -c worker
kubectl -n <ns> logs -f sts/postgres
kubectl -n <ns> logs -f deploy/keycloak
```

## Roll back

```bash
helm --kube-context titan-k3s history titan -n <ns>
helm --kube-context titan-k3s rollback titan <REV> -n <ns>
```

## Uninstall

```bash
helm --kube-context titan-k3s uninstall titan -n <ns>
# PVCs (postgres data, titan-artifacts, worker workspace) are NOT auto-deleted.
kubectl -n <ns> delete pvc -l app.kubernetes.io/instance=titan
```

## Image tags and the dogfood pipeline

The chart pins each Titan image to an explicit tag in
`rig/k3s/helm/titan/values.yaml`:

```
titanServer.tag, titanUi.tag, titanWorker.tag   # default: "0.1.0"
```

Those tags are published by the **dogfood pipeline** (`.titan/pipeline.yml`,
final stage `Publish Images`) on every successful trunk build. The pipeline
builds each image from the SAME Dockerfile the local rig uses
(`rig/local/Dockerfile.titan-{server,ui,worker}`) and pushes two tags per
component to `ghcr.io/hadamrd/`:

| Tag                | Stable? | What it points at                     |
|--------------------|---------|---------------------------------------|
| `<sha7>` (e.g. `c359d6c`) | yes  | the exact trunk commit that built it |
| `latest`           | rolling | the most recent successful trunk build|

For real rig promotions, bump `titanServer.tag` / `titanUi.tag` /
`titanWorker.tag` in `rig-values.yaml` to the desired `<sha7>` — never point
production rigs at `latest`. The chart's default `0.1.0` is a placeholder for
the first manually-tagged release; CI never rewrites the chart.

Publish requires a `usernamePassword` credential in CredentialsService under
id `ghcr-publisher` (GHCR username + a PAT with `write:packages` scope).
Until the credential is seeded, the `Publish Images` stage fails fast at
dispatch — by design (no silent skip on prod).

## Secrets

The chart materialises a single `titan-secrets` Secret from the `secrets:`
block in `rig-values.yaml`. For real environments either:

1. Edit `rig-values.yaml` with real values (file is gitignored), OR
2. Set `secrets.create: false` and provision a Secret named `titan-secrets`
   yourself (ExternalSecret / SOPS / sealed-secrets etc.) with the same
   keys the chart references:
   `POSTGRES_PASSWORD`, `TITAN_KEK`, `KEYCLOAK_ADMIN_PASSWORD`, and (when
   `titanServer.artifacts.backend=s3`) `ARTIFACTS_ACCESS_KEY`,
   `ARTIFACTS_SECRET_KEY`.

## Infisical-secret e2e (spec 46, #1242)

By default the rig resolves pipeline `credentials:` through the `db-envelope`
backend. e2e spec 46 (`e2e/specs/v3/46-infisical-secret.spec.ts`) is the only
spec that proves leak-proof binding of an **Infisical-sourced** secret, so it
`test.skip()`s unless the rig is opted into the Infisical backend AND the two
coordinates `E2E_INFISICAL_CRED` / `E2E_INFISICAL_VALUE` are exported. Without
the overlay the spec skips cleanly and the default rig stays green.

The opt-in is the committed overlay `rig/k3s/values-infisical-e2e.yaml`. It sets
`secretsBackend: infisical` on **both** the server and the worker (the worker is
what resolves the bound secret into the build env, so it carries the
`INFISICAL_TOKEN` too).

**1 — Provision the rig with the overlay.** Layer it after `rig-values.yaml` so
it wins, and supply the project id + the `staging`-scoped Infisical token
out-of-band (never in git):

```bash
helm --kube-context titan-k3s upgrade --install titan rig/k3s/helm/titan \
  -n titan --create-namespace \
  -f rig/k3s/rig-values.yaml \
  -f rig/k3s/values-infisical-e2e.yaml \
  --set titanServer.infisical.projectId="$INFISICAL_PROJECT_ID" \
  --set titanWorker.infisical.projectId="$INFISICAL_PROJECT_ID" \
  --set secrets.infisicalToken="$INFISICAL_TOKEN"
```

`INFISICAL_TOKEN` is rendered into `titan-secrets/INFISICAL_TOKEN` and mounted on
both pods; the `INFISICAL_PROJECT_ID/_ENV/_SECRET_PATH/_API_URL` coordinates come
from the overlay (env defaults to `staging`).

**2 — Pre-seed the one secret (idempotent).** The pipeline binds credential id
`titan-e2e/E2E_INFISICAL_SECRET`; the `key` part — `E2E_INFISICAL_SECRET` — must
equal the NAME of a secret in the Infisical `staging` env. The seed script
upserts it (no plaintext in git — the value comes from `$E2E_INFISICAL_VALUE` or
is generated):

```bash
export INFISICAL_TOKEN=...            # staging-scoped svc token
export INFISICAL_PROJECT_ID=...       # the workspace id
export E2E_INFISICAL_VALUE=...        # the known plaintext (optional; generated if unset)
task k3s:seed:infisical               # or: rig/k3s/seed-infisical-e2e-secret.sh
```

**3 — Run spec 46.** The task exports the two coordinates (re-reading the value
from Infisical when `E2E_INFISICAL_VALUE` is unset) and runs only spec 46:

```bash
export TITAN_API_URL=https://titan.test.example.com   # or http://localhost:18080
export E2E_INFISICAL_CRED=titan-e2e/E2E_INFISICAL_SECRET  # scope/key (must contain '/')
task e2e:infisical
```

The spec asserts: build SUCCESS (the Infisical binding resolved), `consume ok:
len=<N>` in the logs (proves the *correct* secret was bound), and the plaintext
absent from `pipelineScript`, the SSE log stream and the archived `leaked.txt`.

Helm-template + seed-script unit tests (no cluster needed):

```bash
task k3s:test:infisical
```

## Prometheus scrape (titan-server /q/metrics)

The chart stamps `prometheus.io/scrape`, `prometheus.io/path=/q/metrics`,
`prometheus.io/port=8080` on the titan-server Service and Pod template by
default (`monitoring.scrapeAnnotations.enabled=true`). If your cluster runs
kube-prometheus-stack (CRD `ServiceMonitor`), also set
`monitoring.serviceMonitor.enabled=true` in `rig-values.yaml` — and add any
labels your Prometheus Operator's `serviceMonitorSelector` requires under
`monitoring.serviceMonitor.labels`.

Verify Prometheus picked up the series:

```bash
kubectl -n monitoring port-forward svc/prometheus 9090:9090
# then in another shell:
curl -fsS 'http://localhost:9090/api/v1/query?query=titan_builds_started_total' | jq .
```

## Grafana dashboard (optional)

When kube-prometheus-stack is installed, enable the starter dashboard with
`monitoring.grafanaDashboard.enabled=true` in `rig-values.yaml`. It renders
a ConfigMap labelled `grafana_dashboard: "1"`; the Grafana sidecar
auto-discovers it and provisions it under the `Titan` folder.

Verify:

```bash
kubectl -n monitoring port-forward svc/kube-prometheus-stack-grafana 3000:80
# open http://localhost:3000 and look for "Titan — Overview" in the Titan folder
```

## Lint / dry-run locally

```bash
task k3s:lint        # helm lint
task k3s:template    # helm template | kubectl apply --dry-run=client

# Or directly:
helm lint rig/k3s/helm/titan -f rig/k3s/rig-values.yaml.example
helm template titan rig/k3s/helm/titan -f rig/k3s/rig-values.yaml.example | kubectl apply --dry-run=client -f -
```
