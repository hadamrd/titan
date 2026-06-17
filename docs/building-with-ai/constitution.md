# Titan Constitution

**Status:** living document. PR-only edits with title prefix `constitution:` and a 2-line rationale block.
**Last reviewed:** 2026-05-23.
**Read by:** every dispatched agent on every tick. This is the worldview anchor.

---

## 1. What Titan is — one line

> **Titan is a modern CI/CD control plane** — a standalone CI/CD product under **Adaptiq**, optimized for SREs running real production deploy pipelines, with a calm modern UI, a pluggable extension model, and a YAML pipeline language designed for humans.

**0.1.0 ships:** the dogfoodable rig — pipelines, builds, workers, gates, cron + GitHub triggers, secrets via envelope encryption, OIDC + PAT auth, the v3 UI with every page wired to real data, a green long-journey e2e.

**1.0 ships:** everything 0.1.0 ships *plus* third-party extension SPIs (step / trigger / secrets), GHCR image-publish on every merge, multi-worker k3s deployment, RBAC beyond ADMIN/READ_JOB, audit log surface, full bearer-token auth path across the API.

**Anything beyond 1.0** (matrix-by-include/exclude, services:/sidecars: on stages, GitLab/Bitbucket triggers, native compile) is a marketable bet, not a survival bet.

**Status — 2026-06-08 (grooming snapshot).** Most of the 1.0 surface is built; the
remaining MVP work is **provisioning + proof + RBAC-enforcement wiring, not
greenfield**. See `docs/design/61-roadmap-snapshot-2026-06-08.md` for the full map.
- ✅ shipped: secrets-via-envelope + an **Infisical** secrets-backend SPI; OIDC +
  PAT bearer auth; the audit-log surface; artifact-store SPIs (db / S3-R2 / Nexus);
  the v3 UI on real data.
- 🟡 RBAC (~80%): role model + per-scope authz + SSO-mapping CRUD exist but
  enforcement is half-wired — #1221 (patchJob bug), #1235 (SSO augmentor), #1236
  (role-grant API), #1237 (roles UI).
- 🟡 Golden-path demo: backend ~90% live on the rig; blocked on **a human
  registering the GitHub App** (#1238) before the green/red round-trip proofs
  (#1239 / #1240).
- 🟡 Pipeline-breadth e2e: specs merged; need rig/secret provisioning to flip
  skip→green (#1241 R2/Nexus, #1242 Infisical, #1243 shared-lib/multi-repo).
- 🔴 still open: GHCR image-publish-on-merge, multi-worker k3s.

---

## 2. The five non-negotiables

Every yes / no / later decision goes through these.

1. **No legacy plugin-host coupling, forever.** Pure Java, ServiceLoader, Quarkus CDI — no plugin-shaped abstractions, no `@Extension` annotations, no global-singleton service lookups. The engine is a standalone process, never a plugin inside a host.
2. **The UI is the product.** A daily-driver instrument for SREs at 3 am. Calm, dense, designed. Never sloppy. The v3 design system (Geist + oklch + tweaks panel + motion) is the floor. **Every backend feature ships with its UI seam in the same sprint** — backend-only sprints accrue UI debt that lands as bugs at the integration seam (lesson: [[feedback_ui_specialist_always_parallel]]; canonical demos broken by this: PR #432 KPI strip, PR #433 trigger chips).
3. **YAML pipelines are the contract.** Every product feature surfaces as a `.titan/pipeline.yml` keyword first, REST/UI second. Backwards-compatible parser evolution is required.
4. **One coherent product.** No escape hatches. No "raw config view". One place to do each thing, designed.
5. **Real workloads from day 1 — no fake data on demos.** Either a feature is wired to real data, or the surface is honestly tagged `(synthesized)` / `(pending)`. Em-dash placeholders are forbidden on demo-pass surfaces (canonical violations: #448 em-dash sweep, #66 / #70 demo-pass lessons). If a column can't be populated honestly, hide it or tag it.

---

## 2b. Well-architected CI engine — properties that must survive evolution

Any sprint tick that erodes one of these needs CTO blessing. These are the
load-bearing architectural invariants — moved here from `next-sprint/SKILL.md`
so the agents and the human reference the same canonical home.

1. **Single source of grammar.** The YAML schema is generated from the parser, not hand-written (design/47). Every PDL keyword is a `StepScope` or `Scope` class; the schema regenerates and `TitanSchemaGenerationTest` is the contract.
2. **Discriminated-union typed config.** `type: <discriminator>` over runtime sniffing on URLs / values. Codified in `[[feedback_principled_typed_design]]`. Slack notify (#387) and GitHub webhook trigger (#403) are canon.
3. **Secrets only via CredentialsService.** Never plaintext in `config_json` (CONSTITUTION §6). Envelope encryption with per-secret DEK wrapped under KEK (PR #286). KEK rotation re-wraps DEKs, payloads untouched.
4. **Pull-based workers.** Workers poll `task_queue`; controller never RPCs into a worker. Drain = stop polling. No worker-side push endpoints.
5. **OIDC + PKCE for humans, PAT for machines.** No session cookies, no API password auth. Personal access tokens (PR #464) cover CLI/script auth. Every API endpoint must accept both paths uniformly.
6. **Idempotent reapers.** `QueueProcessor.tick()` runs every 500ms; every action it takes must be idempotent under crash + restart. Same for `seed-data.sh`. No "did we already do this?" guard reads — design the state so the action is a no-op.
7. **OTel end-to-end.** Server (#382) → worker (#394) traces continue via `task_queue.trace_parent`. JSON logs everywhere. A failed build must be debuggable from the trace alone.
8. **Observable failures.** Every failure mode emits a structured event the UI can render (per design/58 alignment audit). Silent failures are dispatch-rejection antipatterns.
9. **One UI seam per backend feature.** Restating §2 in operational form — when the backend ships an endpoint, the UI ships its wiring in the same sprint. The brief-verifier rejects backend-only sprints.

`next-sprint/SKILL.md` references this section by name; do not duplicate the
list there.

---

## 3. What Titan is NOT

- Not a reskin of an older engine. Design from first principles; don't inherit assumptions from legacy CI models.
- Not a toy. The bar is GitHub Actions / GitLab CI / Buildkite — modern, cloud-native CI/CD.
- Not a platform for everything. We ship: pipelines, builds, workers, gates, triggers, secrets. We do **not** ship: project tracking, code review, artifact registries (we integrate), monitoring (we emit OTel).

---

## 4. Locked architectural decisions

Format: `YYYY-MM-DD — decision — why`. Each line is a PR that touched it. Reverting requires `constitution:` PR.

- **2026-04 — Quarkus 3, not Spring Boot.** Compile-time CDI, native compile path, less reflection.
- **2026-04 — Postgres via JDBI.** Flyway migrations, JSONB, mature drivers. No Mongo / SQLite.
- **2026-04 — Gradle 8.x, no Maven.** Settled by PR #340.
- **2026-05 — Authorization Code + PKCE via Quarkus OIDC + `oidc-client-ts`.** No session cookies.
- **2026-05 — Envelope encryption with per-secret DEK wrapped under KEK.** KEK rotation = DEK re-wrap, payloads untouched (PR #286).
- **2026-05 — `SecretsBackend` is a ServiceLoader SPI.** `db-envelope` ships by default; Vault / AWS-SM / GCP-SM are drop-in modules.
- **2026-05 — ServiceLoader SPIs everywhere.** `StepHandler`, `TriggerSource`, `ArtifactStore`, `CredentialKeyProvider`, `StepDescriptor` (via StepRegistry).
- **2026-05 — Vendored cron grammar** under `titan-trigger-api/.../trigger/cron/internal/`. Preserves H-hashing + `@daily` + `TZ=` with no external runtime dependency (PR #335).
- **2026-05 — Quarkus Cache (Caffeine) in-process.** No Redis. Three caches: `pipeline-model`, `job-lookup`, `cron-compile` (PR #333).
- **2026-05 — Pull-based workers.** Workers poll `task_queue`; controller never RPCs into a worker. Drain = stop polling.
- **2026-05 — Pipeline-model is a build-scoped, in-memory state.** Cached, invalidated on terminal status. Persistence is JSON in `titan.builds.pipeline_model_json`.
- **2026-05 — Matrix / each / templates fan out at parse time.** No runtime fan-out (yet).
- **2026-05 — Replay-from-node reuses the parent's baked pipeline model.** No SCM re-fetch, no re-parse (PR #319).

---

## 5. The 9 modules + ownership

| Module | Owns | Agent |
|---|---|---|
| `titan-step-api` | Step SPI (pure Java) + StepRegistry | pdl / test |
| `titan-trigger-api` | Trigger SPI + vendored cron grammar (pure Java) | engine |
| `titan-pipeline-model` | Grammar, parser, schema, scopes, model | pdl |
| `titan-db-core` | Flyway migrations + DB pool | storage |
| `titan-server` | Quarkus app: REST + orchestration + services | engine + server |
| `titan-worker` | Pull-based execution agent | worker |
| `titan-ui` | React/Vite v3 design system | frontend |
| `titan-extensions/titan-artifact-{s3,nexus}` | First-party artifact backends | engine |
| `titan-extensions/titan-keyprovider-infisical` | First-party KEK provider | engine |
| `e2e` | Playwright smoke + feature flows | test |

---

## 6. Anti-patterns — automatic dispatch rejection

The brief-verifier refuses to dispatch any task whose plan implies one of these. If discovered post-dispatch by the agent → STOP and report.

| Anti-pattern | Lesson source |
|---|---|
| Any legacy plugin-host import (the forbidden-import set enforced by the pre-commit hook) anywhere outside `titan-trigger-api/.../internal/` | Constitution #1 |
| New `pom.xml`, `mvnw`, `.mvn/`, or pipeline-definition files in any non-PDL format | PR #340 |
| Plaintext secret stored in `config_json` or any non-credentials column | Constitution + envelope-encryption lock |
| Cross-project `sourceSets["test"].output` access in any `build.gradle.kts` | PR #341 |
| `@import` directive AFTER `@tailwind` in a CSS file | PR #343 |
| `<meta http-equiv="Content-Security-Policy">` in `titan-ui/index.html` (nginx owns CSP) | PR #343 |
| Hardcoded legacy plugin-module path constants (e.g. `titan-plugin/src/...`) | PR #342 |
| Hooks called after early `return` in React components | PR #343 |
| Opening a PR with `--base` set to a non-trunk branch | PR #320 |
| Running the full test suite (`./gradlew test` without `--tests` filter) — > 20 min, forbidden | feedback_test_run_strategy memory |
| Caching credentials / sealed values in any cache | Constitution #1 (security) + PR #333 |
| Adding a UI surface backed by no real endpoint, without an `(synthesized)` / `(pending)` tag | Constitution #5 |
| Auto-merging PRs from the loop | Loop charter |
| Brief without explicit `## Acceptance` checkboxes | Brief verifier |

---

## 7. The unwritten rules — process

These don't fit in code but bind every dispatch.

1. **Boot what you ship.** If a PR touches a route, endpoint, or rig, evidence required: Playwright trace, curl output, or `task dev:titan` screenshot. No "should work" claims (PR #343 lesson).
2. **Tests ship with features.** A PR touching CSS includes the CSS-build vitest. Touching a route includes a route-resolution test. Touching OIDC touches `KeycloakAuthIT`.
3. **Port don't rewrite.** When moving code between modules, `git mv` + adjust packages. Logic changes are a separate commit (PR #331 chaos-rig migration is the canonical example).
4. **3 PRs in flight ceiling.** More than that and worktrees start colliding.
5. **No auto-merge.** The loop opens PRs; the human merges.
6. **STOP > guess.** An agent that hits an architectural surprise must STOP and report. Pretending it's transient = drift (PR #335 cron-grammar STOP is the canonical example).
7. **`gh pr create --base trunk` always.** Never base on a feature branch unless the dep is unavoidable, and then call it out (PR #320 lesson).

---

## 8. Evolution rules

| Section | Who edits | How often | Mechanism |
|---|---|---|---|
| §1 (one-liner) | CTO | Rare | `constitution:` PR |
| §2 (non-negotiables) | CTO | Rare | `constitution:` PR with rationale |
| §4 (locked decisions) | Agent on the deciding PR | Every architecture-class PR | One-line addition with PR number |
| §5 (modules) | Engine team | When a module is added/extracted | One row |
| §6 (anti-patterns) | Whoever STOPped an agent on a class bug | Every novel class-bug | Append row + cite the PR / STOP report |
| §7 (process rules) | CTO | When a pattern of mistakes emerges | `constitution:` PR |

The constitution **grows by accretion of lessons learned**. It rarely shrinks. The size is its own discipline cap (target: ≤ 200 lines).
