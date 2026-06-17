---
name: next-sprint
description: Plan and dispatch the next Forge-Loop sprint. Re-anchors on the Titan vision (CONSTITUTION §1-§2), the enforced quality chart (best-practices rows 1-17), and the well-architected-CI-engine criteria; then picks the next 3-4 highest-leverage tickets, opens a worktree per ticket, dispatches in parallel, and tracks merges. Designed to be triggered on a recurring interval by /loop so the CTO never has to micromanage the queue.
---

# Next Sprint

A six-minute heartbeat that keeps the Forge moving: re-anchor → pick → dispatch → maintain queue.

## ★★★ V1 SHIPPABLE BAR (2026-05-24, CTO directive) ★★★

The loop's sole objective until further notice is **a stable, clean, V1-shippable Titan**. Concretely:

1. **Smoke green.** `task rig:smoke` (Layer-1 fixture E2E) must be ≥12/14 passing
   on the local rig, sustained across 3 consecutive ticks, before any new feature
   ticket is considered.
2. **No NaN / no `—` / no mashed pills** in the v3 UI. The Builds page, Job
   detail, and Build detail are the V1 front door — they must look intentional.
3. **No tight loops in the engine.** ORCHESTRATE, BAKE, SYNTHESIZE, ADVANCE
   each appear ≤O(events) times per build, not O(seconds).
4. **No silent stalls.** A build whose step's queue has no worker fails fast
   with a clear error, NOT QUEUED-forever.
5. **No dev-only escape hatches in production paths.** DevAutoKeyProvider stays
   opt-in and inert by default; production fail-closes without a real KEK.

Until those 5 hold, **every dispatched ticket must be a P1 bug fix, a reliability
improvement, or an E2E gap-closer**. Features stay frozen.

How to apply per tick:
- Step 3a's audit list always starts with the V1-shippable-bar checks above.
- A ticket that does not advance one of the 5 bullets above gets dropped, even
  if the brief was vision-oracle clean.
- The loop's win condition is "smoke green 3 ticks running + UI clean" — that
  is the trigger to lift the freeze and open the V1 release branch.

## 0. Anchor — read these before picking any ticket

### Vision (CONSTITUTION §1)
> Titan is the modern cloud-native control plane — a standalone CI/CD product under Adaptiq, optimized for SREs running real production deploy pipelines, with a calm modern UI, a pluggable extension model, and a YAML pipeline language designed for humans.

### Five non-negotiables — every yes/no goes through these
1. **Standalone, forever.** No legacy plugin-host core, no host-framework extension annotations, no plugin-host global singletons.
2. **The UI is the product.** v3 design floor (Geist + oklch + shadcn + tweaks panel). Calm, dense, designed.
3. **YAML pipelines are the contract.** Every feature surfaces as a `.titan/pipeline.yml` keyword first.
4. **One coherent product.** No escape hatches, no "raw config" view.
5. **Real workloads from day 1.** No demos that lie. Real data, or honestly tagged `(synthesized)` / `(pending)`.

Full doc: [`docs/building-with-ai/constitution.md`](../../../docs/building-with-ai/constitution.md).

### Quality enforcement chart (docs/best-practices.md, rows 1-17)
| # | Practice | Gate |
|---|----------|------|
| 1 | `task` is the one build entry point | code review · only Taskfile.yml exists |
| 2 | self-contained build (gradle wrapper, no system gradle/maven) | wrapper committed |
| 3 | Repo layout per `docs/repo-layout.md` | code review |
| 4 | Java Spotless-formatted | `task lint` (spotlessCheck) |
| 5 | No new SpotBugs / Error Prone findings | `./gradlew check` |
| 6 | Tests live with their module (`<module>/src/test`, e2e in `e2e/`) | code review |
| 7 | **Adversarial tests** — sad path, independent oracle | code review |
| 8 | Scoped tests locally (`--tests <Class>`); full suite is CI-only | convention |
| 9 | Branch + PR off trunk, never force-push | branch protection |
| 10 | Commits explain *why*, co-author trailer | code review |
| 11 | No forbidden brand strings | CI string scan |
| 12 | No GitHub Actions (NG13) — CI is dogfood Titan on k3s | policy |
| 13 | Secrets never in git, Infisical/k8s Secrets at deploy | `.gitignore` + scan |
| 14 | PDL step = ~3 files (handler + registration + TCK) | `add-pdl-step` skill + `GrammarSchemaContractTest` |
| 15 | **Design before code** — non-trivial work has `docs/design/NN-*.md` | code review |
| 16 | Mutation testing pilot on `flow.expr` (PIT, ratchet floor) | mutation gradle task |
| 17 | **titan-ui builds before it ships** — `vite build` in pre-commit gate 5 (catches rollup-vs-vitest resolver drift; repaired 4× in PRs #415/#422/#426/#428) | pre-commit hook + `task verify:ui` |

### Pre-commit gates 0-5 (`dev/git-hooks/pre-commit`)
0. Untracked `.java` under `src/main/java/` blocked
1. `spotlessCheck` on affected Gradle modules
2. 1000-line cap on Java files (god-class guard, references design/59 decomposition)
3. No legacy/host-framework imports or plugin-host global singletons — only the vendored cron grammar under `titan-trigger-api/.../cron/internal/` is exempt
4. No `pom.xml`, `mvnw`, `.mvn/` (Maven is dead)
5. **`vite build`** on staged `titan-ui/**/*.{ts,tsx}` — catches missing imports rollup will reject but vitest tolerates

### What "well-architected CI engine" means here
Titan must keep these properties as it evolves. Any sprint tick that erodes one needs CTO blessing:

- **Single source of grammar** — the YAML schema is generated from the parser, not hand-written (design/47). Every PDL keyword is a `StepScope` or `Scope` class; the schema regenerates and `TitanSchemaGenerationTest` is the contract.
- **Discriminated-union typed config** — `type: <discriminator>` over runtime sniffing on URLs / values. Codified in `[[feedback_principled_typed_design]]`.
- **Secrets only via CredentialsService** — never plaintext in `config_json` (CONSTITUTION §6). Slack (#387) and GitHub webhooks (#403) are the canon.
- **Pull-based workers** — workers poll `task_queue`; controller never RPCs into a worker. Drain = stop polling.
- **OIDC + PKCE** — no session cookies, no API password auth (personal access tokens via PR #464 for CLI/script auth).
- **Idempotent reapers** — `QueueProcessor.tick()` is called every 500ms; every action it takes must be idempotent. Same for `seed-data.sh`.
- **OTel end-to-end** — server (#382) → worker (#394) traces continue via `task_queue.trace_parent`. JSON logs everywhere.
- **Observable failures** — every failure mode emits a structured event the UI can render (per design/58 alignment audit).
- **One UI seam per backend feature** — when the backend ships an endpoint, the UI ships its wiring in the same sprint (memory: [[feedback_ui_specialist_always_parallel]]).

## 1. The tick algorithm — fires on **3-minute idle**

```
0. ★ IDLE GATE ★ — first action of every tick
   The cron fires every 3 minutes, but the loop should only DO work when
   the system has been idle for that interval. Otherwise we trample
   in-flight smoke runs, mid-rebuild rigs, or recently-dispatched agents.

   0a. Last-activity probe:
       - last_commit_age = (now - git log -1 --format=%ct on trunk)
       - last_dispatch_age = (now - ts of last `dispatched` entry in
         docs/operations/loop.jsonl)
       - last_smoke_age = (now - ts of last line in
         docs/operations/rig-smoke.jsonl)
       Take the MINIMUM of the three as the idle clock.

   0b. If idle < 180s (3 minutes): EXIT IMMEDIATELY. Append a single
       JSONL line `{ts, tick: "<N>-idleskip", reason: "idle <3min"}` to
       loop.jsonl so the run is auditable. No dispatch, no sync, no
       merge. The next cron firing will check again.

   0c. If idle >= 180s: proceed to step 1.

   Rationale: the loop's job is to fill quiet space, not to compete
   with in-flight work. A 3-min idle gate means an agent that finishes
   work + commits will get at least one full minute of quiet before
   the loop wakes — enough to be confident nothing else is in flight.

1. Sync trunk + state survey
   git fetch origin && git checkout trunk && git reset --hard origin/trunk
   gh pr list --state open --json number,title

2. Merge anything green from the previous tick
   for each open PR in CI-green state, run gh pr merge <N> --squash --delete-branch

3. ★ E2E GAP CHECK — ALWAYS FIRST ★
   Titan ships if and only if every shipped primitive has a real end-to-end
   spec that drives `commit → trigger → build → real artifact` on the rig.
   Random feature ticks without E2E coverage produce drift. Therefore:

   3a. Audit: list every primitive merged in the last 5 ticks that does NOT
       yet have a v3 Playwright spec exercising it via the
       `hadamrd/titan-e2e-fixture` repo (GitHub webhook → bake → render →
       worker run → assert real artifact). Examples of "primitive":
         - approval gate · stage retry · replay-from-failed · k8sApply (+ teardown)
         - gitTag · setBuildName · trigger-with-params · cancel propagation
         - audit filter · bulk approve · in-log search · stage timing
         - build comparison · log diff · top-failing-jobs · /system info
         - PAT auth · OIDC · starred jobs · cron triggers
       Each gap = ONE candidate ticket: "e2e: <primitive> via fixture repo".

   3b. Default split (PER TICK) — **FEATURE FREEZE in effect**:
         - 3 of 3 tickets MUST be E2E gap-closers + bug fixes surfaced
           by E2E + reliability work (rig stability, migration drift,
           secret handling, integration-seam hardening).
         - NO new features. Period. The "1 fresh feature" slot from
           prior versions of this skill is REMOVED until the CTO
           explicitly lifts the freeze.
         - The ONLY exception is a feature so shamefully missing that
           Titan-as-product cannot credibly call itself a CI/CD tool
           without it (titan-cli is the canonical example). Such a
           feature requires (a) explicit CTO sign-off in the ticket
           body, (b) full Layer 1 + Layer 2 E2E shipped in the same
           PR, (c) the feature ticket is the ONLY non-fix tick that
           sprint.

   3c. If audit list is empty (every recent primitive has e2e coverage AND
       the suite is green on the rig), drop to the legacy backlog order:
         a. P1 bugs in last 24h
         b. Followups blocking 0.1.0
         c. design/58-alignment-grooming NOW or NEXT-30
         d. UI ticket per [[feedback_ui_specialist_always_parallel]]

   3d. Liveness probe — every 5th tick: run `task e2e -- --grep "@golden"`
       (or the smallest credible smoke set) on the rig and append the
       result to loop.jsonl. If it has been >2h since last rig pipeline
       (check via /api/v1/builds?since=2h), DISPATCH a smoke run as one
       of this tick's tickets.

4. Vision-oracle each picked ticket (docs/operations/vision-oracle.md)
   5-check rubric: vision-alignment / anti-pattern-avoidance / decision-freshness / scope-sanity / reference-reality.
   Score ≥ 4 → DISPATCH. Score < 4 → comment NEEDS_HUMAN on the issue + skip.

5. Dispatch each tick in parallel, ONE Agent({run_in_background: true}) call per ticket
   MANDATORY in the brief:
   - WORKSPACE SETUP block: `git worktree add /tmp/wt-<N> -b <branch> origin/trunk`
   - Reading-order pointer to design docs + the relevant prior PRs
   - CONSTITUTION hard-rule block (standalone-engine, no plaintext secrets, OIDC, etc.)
   - Pre-commit gate 5 reminder: vite build MUST pass
   - Discriminated-union typed-design reminder
   - Adversarial test reminder (independent oracle, sad-path hunt)
   - For E2E briefs specifically: the spec MUST exercise the
     `hadamrd/titan-e2e-fixture` repo (or extend it with a new
     `.titan/pipelines/<name>.yml`), MUST drive a real worker-executed
     build, and MUST assert at least one real side-effect (artifact
     archived, gitTag pushed, k8s manifest applied, build display_name
     persisted — pick what the primitive actually produces). NO inline
     `POST /api/v1/jobs` shortcuts unless explicitly justified.

     **Two E2E layers, both required for full coverage:**

     **Layer 1 — local rig, synthesized webhook (fast feedback).** Spec
     creates a job with pipelineScript pulled from the fixture YAML +
     posts an HMAC-signed webhook payload to /api/v1/triggers/github.
     This is what most current specs do. Tagged `@golden`. Runs in
     `task rig:smoke`.

     **Layer 2 — k3s rig, real commit (end-to-end truth).** Spec cuts a
     unique branch on `hadamrd/titan-e2e-fixture` (timestamp-suffixed),
     pushes a real commit (modifies a sentinel file), then polls the
     k3s rig at its public ingress for the build to appear (proves the
     full chain: git push → GitHub webhook fire → rig ingress TLS → bake
     → build → side-effect). Tagged `@real-commit`. Runs in
     `task rig:smoke:real` (separate task, longer budget). `finally{}`
     deletes the branch.

     New primitive E2E coverage is COMPLETE only when both layers exist
     for it. Layer 1 is the unit-of-trust for the loop's per-tick smoke;
     Layer 2 is the unit-of-trust for shipping to 0.1.0.
   - COMMIT + PUSH BEFORE REPORTING

6. Telemetry
   Append to docs/operations/loop.jsonl:
   {"ts": <iso>, "tick": <N>, "dispatched": [<issue>, ...], "e2e_count": <N>, "rig_last_build_age_sec": <N>, "in_flight": <count>}

7. Schedule next wake — handled by /loop's interval; this skill is the per-tick body.
```

## 2. Anti-patterns to avoid in this skill

- **Don't dispatch identical tickets to multiple agents** — check open worktrees + PRs first
- **Don't dispatch backend-only sprints** — UI must ship in every batch
- **Don't accept agent output with soft assertions** — the spec must hard-fail on the bug class it claims to test
- **Don't let `test.fixme` accumulate** — every fixme has a follow-up issue with a deadline
- **Don't merge without the agent providing test output** — "BUILD SUCCESSFUL" line in the PR body is the contract
- **★ Don't dispatch a fresh feature when E2E gaps exist** — step 3 is binding, not advisory. The loop's job is `commit → trigger → real run → real artifact` coverage of every primitive, not feature throughput. A sprint that ships 3 features and 0 E2E tests is a failed sprint, even if all 3 PRs merge clean.
- **Don't fake E2E** — `POST /api/v1/jobs` with inline `pipelineScript` is NOT an E2E test of the discovery path. Real E2E goes through `hadamrd/titan-e2e-fixture` (or extends it) via the GitHub-webhook trigger.
- **★ FEATURE FREEZE** — until the CTO explicitly lifts it, the loop ships ZERO new features. Every tick is E2E coverage + bug fixes + reliability hardening. The yardstick for any "new" idea: would Titan be embarrassed to call itself a CI/CD product at 0.1.0 without this? If no → don't file the ticket, don't dispatch it. The loop has been overshooting on features and underdelivering on reliability — that ends now.
- **Don't hallucinate features** — every dispatched ticket must reference a real signal: an open P1 bug, a failing E2E spec, a primitive without E2E coverage, a known migration drift, an integration-seam crash. If the brief starts with "it would be nice to have…", reject the brief.

## 3. STOP classifier — when to halt the loop

- 3 consecutive ticks where the agent returns BLOCKED with the same root cause → halt + ping CTO
- Drift floor: more than 5% of merged PRs in the last 24h required a follow-up fix → halt + post `drift-spike` issue (per `docs/operations/loop-self-correction.md`)
- Any `loop:halt` labelled issue exists on the board → halt immediately at next tick's STEP 1 sync
