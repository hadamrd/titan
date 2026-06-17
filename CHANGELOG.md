# Changelog

All notable changes to Titan are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [1.0.0-rc1] - 2026-05-24

First release candidate on the road to Titan 1.0. Rolls up the S1–S4
sprint output that landed on `trunk` after the 0.1.0 cut (PRs #504 →
#566). The chief reason this is a separate cut is that **PR #566
restored `:titan-server:test` to BUILD SUCCESSFUL** — the test gate
that 0.1.0 had to ship despite, is now real.

Images: `ghcr.io/hadamrd/titan-{server,ui,worker}:v1.0.0-rc1` and the
moving tag `:latest-rc` (NOT `:latest` — that stays on 0.1.0 until the
final 1.0 cut).

### Upgrade path from 0.1.0

This is an additive release — no wire-protocol break versus 0.1.0.

1. Pull the new images (`v1.0.0-rc1` for server, ui, and worker).
2. Bump the `titanServer.tag` / `titanUi.tag` / `titanWorker.tag` in
   `rig/k3s/rig-values.yaml` to `v1.0.0-rc1` (the chart `version` +
   `appVersion` are bumped to match).
3. `task deploy:k3s`.

- **Schema migrations.** rc1 adds one Flyway migration — the `audit_log`
  table (#519) — run on server boot. Rollback is destructive (drop the
  table).
- **No config changes required.** Audit emit points, per-PAT scopes,
  status pills, and multi-worker all read sensible defaults. PATs created
  under 0.1.0 are grandfathered to the full scope set.
- **`:latest` does NOT move.** rc1 publishes `:latest-rc`; clients pinned
  to `:latest` keep pulling 0.1.0 until the final 1.0.0 cut.

### Added

#### UI (v3-stack build-detail and beyond)
- `/builds/$id` v3-stack — DAG + tree rail + terminal console (#550).
- v3-stack polish — minimap viewport + console search + selection
  caret (#554).
- v3-stack card faithfulness + DAG collapse + STEP sub-info
  (#558, closes #552 #553).
- Tests + Artifacts panels restyled to v3-stack (#560, closes #559).
- Console auto-follow live builds (#565, closes #563).
- `/builds/$id` YAML tab — surfaces as-built `pipeline_script` (#534,
  closes #533).
- Per-step log filtering on Pipeline tab click (#542, closes #537).

#### Audit + admin
- `audit_log` table + emit points + `GET /api/v1/audit` (#519, partial
  #478).
- `/audit` page with filters + admin sidebar entry (#522, closes #517).
- `/queue` empty state shows recent `task_archive` activity (#524,
  closes #523).

#### Jobs
- `/jobs` last-build status pill per job (#531, closes #529).
- `/jobs` default sort = FAILED first + persisted preference (#546,
  closes #544).
- New Job dialog on `/jobs` — POST `/api/v1/jobs` (#515, closes #512).
- Trigger edit UI on `/jobs/$id` (#505, closes #439).

#### Auth / PAT
- Per-PAT scopes + stale `libs.versions.toml` fix (#526, closes #500).
- PAT mechanism beats OIDC via `getPriority()` override
  (#543, closes #527).

#### Infra / k3s / SPI
- Public ingress (TLS) + multi-worker scale + claim-isolation IT
  (#555, closes #473).
- `titan-secrets-vault` skeleton — SecretsBackend SPI demo
  (#547, closes #475). Note: NOT wired to a runtime backend; see
  release notes "Known broken".
- Reserved-namespace shadow refusal + summary log (#521, closes #474).

#### Release
- `dev/release/publish-trunk.sh` + task target (#509, closes #472).

#### Docs / design
- Build-detail mockup bundle (v1/v2/v3) + chat transcript (#545).

#### Tests
- Real-build golden path + fix Pipeline-empty cache (#516,
  closes #514).
- PAT + OIDC coexistence smoke in `KeycloakAuthIT` (#530,
  closes #499).
- `PatAuthenticationMechanism` `@QuarkusTest` IT (#525, closes #498).
- `task ci:verify` + PR template require full suite pass
  (#564, closes #381).

### Fixed

- Engine: stamp `flow_nodes.log_task_id` at dispatch, not in a later
  reconcile sweep (#541, closes #536).
- Engine: H2-portable `reapStale` — compute cutoff in Java
  (#520, closes #518).
- Engine: stale-build reaper extended to QUEUED (#504, closes #488).
- UI: delete `/pipelines` vestigial route + redirect to `/jobs`
  (#540, closes #538).
- UI: final node refetch on build terminal — kills stale-RUNNING bug +
  harden spec (#535).
- Seed: correct PDL YAML in seed-data.sh + add parser gate (#506).
- Seed: honest fixtures — no fake QUEUED/RUNNING + tag with
  seed-fixture (#513).
- Seed: job creation via POST `/api/v1/jobs` (#511, closes #507).
- Engine: wire `EXECUTE_COMMAND` `task_token` into `flow_nodes
  .log_task_id` (#510, closes #508).
- Test: restore `:titan-server:test` — register stub `TokenAuth`
  IdentityProvider (#561, closes #556).
- Test: three unmasked `:titan-server:test` bugs surfaced by #561
  (#566, closes #562).

### Security

- Per-PAT scope claims enforced at the auth mechanism (#526).
- PAT-vs-OIDC precedence locked — PAT wins by explicit priority,
  closing the long-running ambiguity bug (#543, #527).

### Tech debt

- `project(:titan-X)` refs everywhere — kill `libs.versions.toml`
  drift class (#532, closes #528).

### Known broken / deferred

- `titan-secrets-vault` SPI shipped without a runtime backend wire-up
  (#549). Do not point a pipeline at the vault SecretsBackend until the
  wire-up lands — use `titan-keyprovider-infisical` instead.
- `#527` follow-ups: PAT scope claims in OIDC identity, and a per-request
  audit trail of the PAT-vs-OIDC mechanism choice. The acute precedence
  bug is fixed (#543); these do not block rc1.
- `#527` alternates deferred to 1.0.0: "rotate PAT in place" UX and the
  "PAT expiry email warning" feature.
- Configuration cache stays OFF until the Test-task `classpath`
  rework lands. Warm builds still benefit from the build cache + daemon.

## [0.1.0] - 2026-05-24

First public release. Titan ships as a standalone, cloud-native CI/CD
control plane built from nine Gradle product modules plus a Vite/React
UI. The 0.1.0 milestone marks a demonstrable, dogfoodable rig: real
pipelines, real workers, real builds, GitHub + cron triggers, typed
secrets, OIDC + PAT auth, v3 UI on live data, and a green long-journey
end-to-end suite.

### Added

#### Pipeline language (PDL)
- PDL: `matrix:` (axes + `maxParallel`) and `each:` (var + in) prototypes.
- PDL: `when:` conditional expressions evaluated against `params`.
- PDL: `timeout:` at pipeline and stage scope.
- PDL: `gate:` stage type with `requiresApproval` + `approvers`.
- PDL: `failurePolicy` promoted to a typed enum
  (`blockOnFailure | continueOnFailure`) (#453, closes #392).
- PDL: `notify:` lifecycle hooks (webhook, Slack) typed via `credentialsId`.
- PDL: typed `credentials:` block — no plaintext secrets in YAML (#387).
- PDL: `dependsOn` on a matrix/each prototype resolves to all cells with
  fan-in semantics (#400, closes #398).
- Dogfood: `.titan/pipeline.yml` — Titan compiles, tests, and deploys
  Titan; every shipped grammar keyword is exercised (#395).

#### Triggers
- GitHub webhook trigger — PDL grammar, SPI, HMAC-verified receiver
  (#403, closes #397).
- Cron trigger (`H * * * *`).
- UI: read-only trigger chips on `/jobs/$id` (#495, closes #438).

#### Server / API
- Admin queue controls — `POST /api/v1/queue/{drain,reorder}`
  (#373, closes #347).
- Stage-level `notify:` hooks fire on stage terminal transition
  (#390, closes #359).
- `GET /api/v1/artifacts/{id}/download` — stream archived bytes
  (#399, closes #393).
- `GET /api/v1/info` — public version + commit + uptime tile (#424).
- `JobDto.pipelineScript` exposed for the detail page (#459, closes #457).
- OIDC via Keycloak (`dev`/`dev` in the local rig).

#### Auth
- Personal access tokens — server + UI (#464, closes #434).
- PAT bearer authentication on all `/api/v1/*` routes (#497, closes #477).

#### UI (v3)
- Overview wired to live stats + activity feed; Queue drain admin
  (#378, closes #346 #304 #347).
- Drag-drop reorder UX for the Queue admin (#396, closes #379).
- Builds detail — flow nodes + test results + artifacts panels.
- Design pass on `/queue`, `/builds`, `/workers` — compact priority,
  status dots, tabular nums (#410 #411 #417).
- Route audit + null-safety + empty-state polish across 4 routes (#415).
- Global `/builds` view + drop `/metrics` placeholder from Overview (#458).
- `/builds/$buildId` — null-safe duration + restore job name (#412).
- `/builds/$id` — pre-select failing/running/last step (#487, closes #450).
- `/builds` status column renders coloured chip (#483, #449).
- `/pipelines/$id` detail aligned on `jobId` semantic (#467, #456).
- Pipelines list nav + drop hardcoded sample PDL (#456, #444 #445).
- "New job" CTA on `/jobs` page header (#455).
- Polish `/jobs/$jobId` + `/pipelines/$pipelineId` detail pages (#432).
- Polish `/profile` + `/settings` for 0.1.0 (#422).
- Onboarding moment — first-90-seconds wizard (#423).
- Brand polish — favicon, page titles, monogram, login (#426, #47).
- `/workers` design pass — StatusDot, tabular nums, null-bar test (#417).
- Stream build logs with fetch+bearer — fixes SSE 401 (#463).
- Em-dash placeholder cleanup — sidebar, jobs folder, workers DISK
  (#494, #448).

#### Worker / engine
- Standalone pull-based worker (fat jar) — no external controller.
- QueueProcessor scheduler wired + `ORCHESTRATE` entrypoint + claim-type
  isolation (#489).
- Archive completed tasks + preserve `build.started_at` (#490, #489 follow-up).
- Archive sweep survives `task_archive` id collisions (#491, #490 follow-up).
- Reconciler reads `task_queue ∪ task_archive` (#496, closes #493).

#### Observability
- titan-worker Logback JSON + OpenTelemetry SDK + span continuation
  from `traceparent` (#394, closes #315).
- OTLP exporter + `traceparent` column on `task_queue` (#382, closes #314).

#### Extensions
- `titan-artifact-s3` (S3/R2), `titan-artifact-nexus`,
  `titan-keyprovider-infisical` — ServiceLoader jars under
  `titan-extensions/`.

#### Rig
- k3s Helm chart standalone — a self-contained server, no plugin host
  (#401, closes #329).
- `titan-demo` job + `task dogfood:fire` — real worker execution
  end-to-end (#462).
- `/settings` build-info wiring + worker stuck Draining fix
  (#460, closes #446 #447).
- `dogfood-fire.sh` parses `build_id` correctly (#485, closes #470).
- `titan-e2e` Keycloak client with direct grants (#431).
- Realistic seed fleet — 3 jobs + 12 builds across 24h (#430).
- Seed: `titan-hello` SUCCESS build with real logs (#427).
- Seed: `flow_nodes` + `test_result` on FAILED build #4 (#413).
- Seed: artifacts on FAILED build #4 (#421, closes #416 #419).
- Seed: log + archive linkage for legacy titan-server builds #1..#4
  (#461).

#### CI / release
- Dogfood pipeline publishes `titan-{server,ui,worker}` images to GHCR
  on trunk after the E2E gate (#414, closes #402).
- Gate vite build in pre-commit; add `task verify:ui` (#429).
- Public-discoverability release prep (#420).

#### Tests
- E2E: SRE 3am journey — integrated 10-step UI walkthrough
  (#404, #418).
- E2E: real-pipeline end-to-end spec — drives engine, asserts UI;
  catches engine bugs seed-only specs miss (#492).
- E2E: click-everything contract — 4 specs to catch recent UI bug
  classes (#468).
- E2E: tighten v3 specs to hard-fail on recent bug classes
  (#465, #68/#66/#443/#408/#445).
- E2E: pipeline lifecycle golden paths — 4 v3 specs (#433).
- E2E: admin + token mgmt golden paths — specs 11-14 (#436).
- E2E: graduate sre-3am-journey fixmes (steps 4, 6, 9, 10) (#418).
- E2E: graduate long-journey step 7 to FAILED #4 artifacts (#425).
- E2E: 04-test-results and 05-artifacts-browser unskipped + seeded
  (#374, #391).
- UI: regression guard for `/queue` route post #396 dnd-kit (#409).
- E2E: add DOM lib to tsconfig for `page.evaluate` callbacks (#441).

#### Docs / planning
- Release-cutting procedure documented in
  [`docs/operations/deploying.md`](docs/operations/deploying.md).
- Roadmap audit + CONSTITUTION §1/§2/§2b + S1-S4 sprint plan to 1.0
  (#482).
- Demo pass: 15-route walkthrough + bug list (#452).

### Fixed

- PDL: `dependsOn` against matrix/each prototypes (#400).
- Engine: reconciler now sees archived tasks, fixing build state drift
  (#496, closes #493).
- Engine: archive sweep handles `task_archive` id collisions (#491).
- Engine: completed tasks archived + `build.started_at` preserved
  (#490, follow-up to #489).
- Engine: `QueueProcessor` scheduler wired + ORCHESTRATE entrypoint +
  claim-type isolation (#489).
- DB: V19 portable to H2 — unblocks `@QuarkusTest` Flyway boot
  (#502, closes #501).
- UI: `/pipelines/0` infinite skeleton; added `/pipelines` index (#408).
- UI: null-safe duration formatter on build detail (#412).
- UI: SSE 401 on log streaming — switch to fetch + bearer (#463).
- UI: `/builds` status column coloured chip (#483).
- UI: `/builds/$id` pre-select failing/running/last step (#487).
- UI: pipelines list nav + drop hardcoded sample PDL (#456).
- UI: `/pipelines/$id` detail aligned on jobId semantic (#467).
- UI: restore orphan `useDocumentTitle` + pin `titan-ui/src/lib` in
  `.gitignore` (#428).
- UI: em-dash placeholder cleanup across sidebar, jobs folder, workers
  DISK (#494).
- UI: 5 pre-existing route-test failures + a css-build TS2307 (#383).
- Server: `StatsDao` dead CANCELLED branch + cross-test pollution in
  `StatsApiTest` (#376).
- Server: `WorkersApiTest` 12 failures — `FakeTitanStores` migration
  list missing V16/V17 (#384, closes #377).
- Test infra: `TitanGateIT` — drain pipeline-agent-routed queues
  (#484, closes #454).
- Test infra: auto-discover `V<N>__*.sql` in test helpers — kills the
  stale-list drift class (#385, closes #369).
- Seed: drop perpetually-stuck RUNNING builds; retire stale RUNNING
  > 5 min (#466).
- Rig: `dogfood-fire.sh` parses `build_id` correctly (#485).
- Cleanup: delete dead `WebhookTrigger` SPI (#421, closes #416 #419).

### Security

- All notify/credentials flows route through typed `credentialsId` —
  no URL or string sniffing, no plaintext secrets in YAML (#387).
- GitHub webhook receiver verifies HMAC signatures (#403).
- Personal access tokens for non-OIDC API access (#464, #497).

### Breaking / Removed

- `titan-plugin/` deleted; Maven removed entirely (Phase 3).
- `titan-db-core` `DatabaseConfig` deleted — the last legacy coupling
  (#386, closes #328).
- `WebhookTrigger` SPI deleted (#421) — superseded by the typed
  GitHub trigger (#403).
