# CI on PR — every PR runs the full suite

## Symptoms

- A PR landed on `trunk` that introduced (or re-exposed) a unit-test or
  integration-test failure, and nobody noticed until days later.
- Batches of pre-existing failures accumulate silently, and individual fixes
  ("just my one file") merge green while the suite as a whole has been red
  for weeks.
- A contributor says "tests pass on my machine" but they only ran
  `./gradlew :titan-server:test --tests SomeClass`, not the full reactor.
- `task dev:titan` boots, the UI looks fine, but `./gradlew check` from a
  clean checkout fails.

This is exactly the regression class issue #381 exists to prevent: **the
full suite was never gated on PR**, so failures were a discovery process,
not a merge blocker.

## Diagnosis

The chain of trust for a PR is:

1. Contributor ran *something* locally — but what?
2. Reviewer trusts the contributor.
3. Merger trusts the reviewer.
4. `trunk` breaks; the next contributor inherits the breakage.

There is no GitHub Actions backstop (CONSTITUTION §2 / NG13 — GHA is
forbidden; CI is the dogfooded Titan self-pipeline on the k3s rig). Until
the Titan-on-k3s self-pipeline claims this repo's
commits end-to-end (see issue #555 — public ingress shipped, DNS + claim
flow tracked separately), the gate is **human discipline plus one canonical
command**.

That command is `task ci:verify`.

## Fix

### What every contributor runs before requesting review

```bash
task ci:verify
```

This wraps the real gates, in order:

| Stage | Command underneath | What it catches |
|---|---|---|
| 1. Gradle check | `./gradlew check` | Unit tests, Spotless, SpotBugs, JaCoCo on every product module |
| 2. Integration tests | `./gradlew integrationTest` | Testcontainers ITs across all modules — needs Docker running |
| 3. **Quarkus app-build** | `./gradlew :titan-server:quarkusBuild -x test` (`task ci:quarkus-build`) | **Builds the deployable artifact.** ARC/DI wiring, config-injection validation, build-time augmentation — the break class that compiles + unit-tests green but only explodes at rig boot (DI-scope #1153, empty-default config #1156). |
| 4. **Dup-migration scan** | `dev/sprint-loop/check-duplicate-migrations.sh` (`task verify:migrations`) | Two Flyway `V<N>__*.sql` files sharing a version prefix — Flyway refuses to boot, but only discovers it at startup (#1157). |
| 5. UI verify | `cd titan-ui && pnpm verify` | `pnpm type-check` + `pnpm test --run` + `pnpm build` (vite-build catches rollup-strict orphan imports vitest misses + tsc errors #1154). The `pnpm build` here also **regenerates** `routeTree.gen.ts` in place, feeding stage 6. |
| 6. **routeTree freshness** | `dev/sprint-loop/check-routetree-fresh.sh --git titan-ui/src/routeTree.gen.ts` (`task verify:routetree`) | A committed `titan-ui/src/routeTree.gen.ts` that drifted from what `pnpm build` regenerates — `tsc`/`vite` pass against the stale file, drift only bites at runtime (#1155). Compares the working tree against **HEAD** (not the index), so a mid-workflow staged copy can't mask a stale commit. `task verify:routetree` declares `deps: [verify:ui]` so a standalone run still rebuilds first instead of trivially passing on an un-regenerated tree; inside `ci:verify` go-task dedupes that dep (no double build). |

Stages 3, 4 and 6 close the **build-time break class** described in #1159:
a whole category of breaks compiled green, passed unit tests + plain
`gradle check`, merged, and only exploded when the rig tried to boot. Before
this gate, the Quarkus app-build only ran *post-merge* in
`dev/release/publish-trunk.sh` — so tonight SEVEN breaks (incl. #1153–#1157)
sat undeployable on trunk for 8 days with nobody aware.

**Added wall-clock cost:** stages 4 and 6 are sub-second shell checks. Stage 3
(`quarkusBuild`) adds roughly **30–90 s** on a warm Gradle cache (augmentation
only — `-x test`, no native image; native is explicitly out of scope). The UI
build in stage 5 is unchanged. Net: the gate is a couple of minutes longer, in
exchange for catching boot-time breaks before merge instead of 8 days after.

A passing run ends with:

```
BUILD SUCCESSFUL — task ci:verify passed (gradle check + integrationTest + quarkusBuild + dup-migration + ui verify + routeTree freshness)
```

Paste the last ~10 lines of that output into the PR's **Test plan** section.
The PR template (`.github/PULL_REQUEST_TEMPLATE.md`) has the checkbox.

### Reproducing each new gate failure locally

Every new sub-check fails loud and names *which* check tripped (not an opaque
Gradle stack). To prove a check works, reintroduce its break on a scratch
branch and watch the gate go red:

| Break class | How to reproduce | Expected red |
|---|---|---|
| #1153 DI-scope | `git revert` the #1153 fix (or add `@Inject` on a non-bean) → `task ci:quarkus-build` | ARC augmentation fails with the DI-scope error; `[ci:quarkus-build] FAILED` banner |
| #1156 empty-default config | `git revert` the #1156 fix (or a `@ConfigProperty` with no value + no default) → `task ci:quarkus-build` | Quarkus config validation fails at augmentation |
| #1157 dup migration | drop a colliding `cp titan-db-core/.../migration/V41__pat_job_pattern.sql .../V40__dup.sql` → `task verify:migrations` | `[dup-migration] FAIL: duplicate Flyway migration version(s)` naming the version + both files |
| #1155 stale routeTree | **Realistic (gate path):** add/remove a route under `titan-ui/src/routes/`, commit *without* rebuilding/regenerating, then `task verify:routetree` — its `pnpm build` (via the `verify:ui` dep) regenerates the tree, which now differs from the committed copy. **Quick (logic only):** `echo "// drift" >> titan-ui/src/routeTree.gen.ts && bash dev/sprint-loop/check-routetree-fresh.sh --git titan-ui/src/routeTree.gen.ts` (calls the script directly so no rebuild clobbers the injected drift). | `[routetree] FAIL: ... is stale` + `run pnpm -C titan-ui build and commit` |
| #1154 UI tsc | introduce a type error in any `titan-ui/src/**/*.ts` → `task verify:ui` | `pnpm type-check` fails |
| #1199 UI tsc at commit | introduce a type error in any staged `titan-ui/src/**/*.{ts,tsx}` → `git commit` | pre-commit **gate 5** fails: `tsc --noEmit found TYPE errors` (vite build alone transpiled past it pre-#1199, so the error reached the deploy's tsc, #1194) |

The two new shell checks have table-driven unit tests (clean → exit 0,
injected collision/stale → non-zero with the offender named):

```bash
bash dev/sprint-loop/tests/test_check_duplicate_migrations.sh
bash dev/sprint-loop/tests/test_check_routetree_fresh.sh
bash dev/sprint-loop/tests/test_ui_precommit_gate.sh   # #1199 — gate 5 runs tsc --noEmit, not just vite build
```

The `test_ui_precommit_gate.sh` cases use an injected fake `pnpm` runner (no
`node_modules`, sub-second). The **negative end-to-end path** the issue's test
matrix asks for — `task install-hooks`, then a real `git commit` of a
#1194-class error (a `PageHeaderProps` redeclaring `title` over
`HTMLAttributes`) being **rejected** — was additionally verified by running the
real `dev/git-hooks/pre-commit` hook against a staged UI `.tsx` with a fake
runner that mimics the #1194 shape (vite green / tsc red): the hook exits
non-zero with `gate 5: ... found TYPE errors`, and the clean counterpart (vite
green / tsc green) exits 0. The same run also confirmed the gate counts a
staged path **containing a space** as one file (the `readarray` newline split,
not `$IFS` word-split).

### Why this is enforced for UI-only PRs too

#1154 (a `tsc` error) slipped to trunk even though `verify:ui` *does* run
`pnpm type-check` — because the gate was not actually invoked on a UI-only PR.
The fix is that the loop critic invokes the **single** canonical command
`task ci:verify` on **every** PR regardless of which files changed (Java, UI,
or docs). There is no UI-only express lane and no "Java-only, skip the UI
build" lane — `task ci:verify` always runs all six stages. Keeping one command
(not per-language sub-gates the critic has to route between) is what closes the
#1154 routing gap.

**Critic path for #1199 is already covered** — no Taskfile change was needed.
The issue's second ask ("add `tsc --noEmit` to the loop's pre-merge critic
build") is satisfied today by stage 5 above: `task ci:verify → verify:ui →
pnpm type-check` (`tsc -p tsconfig.json --noEmit`, see `Taskfile.yml`
`verify:ui`). #1199 therefore only had to close the **commit-time** gap (the
pre-commit gate 5 in `dev/git-hooks/ui-precommit-gate.sh`); the critic already
type-checks. Both layers now run `tsc --noEmit`: the pre-commit hook blocks the
push, and the critic blocks the merge.

### What the reviewer enforces

- The Test-plan checkbox `task ci:verify → BUILD SUCCESSFUL` is ticked.
- The pasted tail actually says BUILD SUCCESSFUL (not "92 tests passed, 3
  failed" with the failures hand-waved as "pre-existing").
- If the contributor skipped `integrationTest` because "Docker wasn't
  available," the PR is held until they re-run with Docker — there is no
  unit-tests-only express lane.

### What the merger enforces

- One last `git fetch origin trunk && git rebase origin/trunk` then
  `task ci:verify` from the rebased branch, locally. The reason: a green
  PR + a green `trunk` can still produce a red merge result if two PRs
  touched the same surface in incompatible ways.

## Prevention

The interim human gate above is **Path B** of the #381 plan. Path A — the
real fix — is for the Titan controller on the k3s rig (#555 public ingress)
to claim this repo's commits via its GitHub-webhook trigger (#403 family)
and run `.titan/pipeline.yml` with the same three stages. When Path A is
live:

- Delete the "paste tail of `task ci:verify`" line from the PR template.
- Replace it with "Titan pipeline run: <link to rig URL>".
- Keep `task ci:verify` as the local pre-flight (faster than waiting for
  the rig).

Tracking issue for Path A: continuation of #381 once #555's CTO action item
(DNS + external rig liveness) is resolved.

### Why no GitHub Actions

This is locked. CONSTITUTION §2 + the project's CI strategy memo
(`feedback_ci_strategy.md`) forbid `.github/workflows/*.yml`. The whole
point of Titan as a product is that **we dogfood it for our own CI**.
Adding a GHA workflow to "just hold the line until the rig is up" is the
exact category of band-aid that ossifies — we'd never remove it. Don't.

### Why not a pre-push git hook

Pre-push hooks (a) get bypassed with `--no-verify`, (b) don't run on PRs
from external contributors, and (c) re-running the 5-10 minute IT suite
on every push (not just every PR) burns developer flow. The hook-installer
in `dev/git-hooks/` stays scoped to formatting / fast lint checks.

## Cross-references

- Issue #381 — the gap this runbook closes (P1, sprint S2).
- Issue #1159 — extend the gate to build the deployable artifact pre-merge
  (Quarkus app-build + dup-migration + routeTree freshness). Closes the
  build-time break class (#1153–#1157) that compiled green but broke at boot.
- Issue #555 — public k3s rig ingress (Path A prerequisite).
- Issue #403 family — GitHub-webhook trigger on the rig.
- `docs/CONSTITUTION.md` §2 — no-legacy-plugin-host, GHA-free non-negotiables.
- `Taskfile.yml` — the `ci:verify` task definition.
- `.github/PULL_REQUEST_TEMPLATE.md` — the checkbox that operationalises
  this runbook.
