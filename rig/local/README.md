# Local rig — Titan standalone

A single `docker compose` stack that boots the entire Titan product end-to-end
on your laptop: Postgres, Keycloak (OIDC), `titan-server`, the SPA, and a
worker. Fully standalone.

## Run it

```sh
task dev:titan         # from the repo root — builds, then brings the rig up
```

That target runs `./gradlew :titan-server:build :titan-worker:shadowJar`
and `pnpm build` first, then `docker compose up -d`, and finally seeds the
database with sample jobs + builds.

| service       | URL                              | notes                                |
|---------------|----------------------------------|--------------------------------------|
| Titan UI      | <http://localhost:5180>          | SPA — entry point                    |
| titan-server  | <http://localhost:18080>         | API + `/q/health/ready` (host-side: 18080→8080 to dodge port clashes) |
| Keycloak      | <http://localhost:8081>          | admin / `admin`; realm `titan-dev`   |
| Postgres      | `localhost:5432` (user `titan`)  | password in `.env`                   |

**Log in as `dev` / `dev`** — the seeded user has every role
(`READ_JOB`, `TRIGGER_BUILD`, `EDIT_PIPELINE`, `ADMIN`).

```sh
task dev:logs          # follow all service logs
task dev:down          # tear down + wipe volumes (fresh start next time)
```

## Configuration

Copy `.env.example` to `.env` to override the dev defaults. Two values matter:

| key | what | default |
|-----|------|---------|
| `POSTGRES_PASSWORD` | Postgres `titan` role password | `titan-dev-only` |
| `TITAN_KEK` | AES-256 KEK (base64) sealing credential payloads | sample value — regenerate with `openssl rand -base64 32` |

Both have safe dev defaults; you only need a `.env` if you want to change them.

## Artifact store (dev)

The local rig's `titan-worker` boots with the **filesystem** artifact backend
wired in by default so `archiveArtifacts` steps work out of the box (#848):

| env var | dev value | notes |
|---|---|---|
| `TITAN_ARTIFACT_STORE` | `fs` | the backend kind — `fs` for local dev; `s3` / `nexus` in prod |
| `TITAN_ARTIFACT_ROOT` | `/titan/artifacts` | path under the shared `titan-ws` volume |

If you run a worker **outside docker** (e.g. `java -jar titan-worker.jar` while
debugging in an IDE), export the same vars before launch — `task dev:worker`
does this for you:

```sh
task dev:worker        # builds the shadow jar, exports the env, runs java -jar
```

Without these env vars set, the worker falls back to
`ArtifactSink.UNCONFIGURED` and every `archiveArtifacts` step fails the build
with a clear `no artifact store is configured for this worker` message in the
step log — the fail-loud contract from #848.

> **Production:** the `fs` backend is dev-only. Real deployments wire `s3`
> (R2/AWS) or `nexus` and inject credentials from a real secret store.

### Artifact store (Nexus — opt-in)

To exercise the **Nexus/Artifactory** backend (`NexusArtifactStore`) against a
real Nexus 3 — the path the `e2e/specs/v3/49-nexus-publish.spec.ts` round-trip
asserts — stack the opt-in compose overlay:

```sh
docker compose \
  -f docker-compose.yml -f docker-compose.override.yml -f docker-compose.nexus.yml up -d
```

This adds a `nexus` service (`http://localhost:8085`, dev creds `admin`/`admin123`
via `NEXUS_SECURITY_RANDOMPASSWORD=false`) and flips both `titan-server` and
`titan-worker` to `TITAN_ARTIFACT_STORE=nexus`. Endpoint + credentials are
env-sourced (`TITAN_ARTIFACT_URL` / `_REPOSITORY` / `_USERNAME` / `_PASSWORD`),
mirroring how R2 creds are injected — never baked into an image.

Nexus has no declarative repo seeding, so create the raw hosted repo once after
boot:

```sh
curl -u admin:admin123 -X POST -H 'Content-Type: application/json' \
  http://localhost:8085/service/rest/v1/repositories/raw/hosted \
  -d '{"name":"titan-artifacts","online":true,"storage":{"blobStoreName":"default","strictContentTypeValidation":false,"writePolicy":"ALLOW"}}'
```

Then drive spec 49 with the Nexus env exported:

```sh
NEXUS_URL=http://localhost:8085 NEXUS_REPOSITORY=titan-artifacts \
NEXUS_USERNAME=admin NEXUS_PASSWORD=admin123 NEXUS_WRITE_POLICY=allow \
LAYER2_RIG_AVAILABLE=1 task e2e -- 49-nexus-publish
```

> On the **k3s rig**, point `titanServer.artifacts.backend=nexus` and inject the
> same `TITAN_ARTIFACT_*` vars from Infisical (see `rig/k3s/README.md`); the spec
> reads `NEXUS_*` from the runner env exactly the same way.

## Files

- `docker-compose.yml` — the 5-service stack.
- `Dockerfile.titan-server` — packages `titan-server/build/quarkus-app/`.
- `Dockerfile.titan-ui` — multi-stage: pnpm build → nginx serve.
- `Dockerfile.titan-worker` — packages the shadow jar.
- `nginx.conf` — SPA fallback + `/api`, `/q` reverse-proxy + CSP.
- `keycloak/realm-titan-dev.json` — pre-seeded realm: clients `titan-ui` +
  `titan-server`, four roles, user `dev/dev`.
- `seed-data.sh` — inserts 2 jobs + 6 builds via psql on first boot.
- `.env.example` — the dev secrets template.
