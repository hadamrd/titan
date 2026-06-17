# Best practices — and how they're enforced

Every rule here is *enforced*, not aspirational. The "Enforced by" column is
the mechanism that fails the work if the rule is broken. If a row has no
enforcement, it does not belong in this chart.

| # | Practice | Enforced by |
|---|----------|-------------|
| 1 | **One way to build/deploy** — go through `task` (`Taskfile.yml`). No ad-hoc scripts, no Makefile, no justfile. | Code review · only `Taskfile.yml` exists |
| 2 | **Self-contained build** — build via the committed Gradle wrapper (`./gradlew`); never assume a system Gradle. | Gradle wrapper committed (`gradlew`, `gradle/wrapper/`) |
| 3 | **Repo layout** — root = `settings.gradle.kts` (the Gradle reactor) + `dev/`, `rig/`, `e2e/`, `docs/`. Nothing else loose at root. | Code review · `docs/repo-layout.md` is the contract |
| 4 | **Formatting** — Java is Spotless-formatted (Google Java Format). Run `./gradlew spotlessApply` (or `task format`) before committing. | `task lint` (`spotlessCheck`) · pre-commit gate 1 · `task gradle:check` |
| 5 | **Static analysis** — no new SpotBugs findings. The `titan.java-library` convention plugin applies SpotBugs (effort=MAX, threshold=HIGH) to every Java module, so the engine, the model, the worker, and the extension modules are all gated uniformly. | `task gradle:check` (runs `spotbugsMain` per module) |
| 6 | **Tests live with their module** — unit in `<module>/src/test/`, Testcontainers ITs in `<module>/src/integrationTest/`; e2e in `e2e/`. | Code review |
| 7 | **Tests are adversarial** — exercise the sad path; assert against an independent oracle, not a restated copy of the source. | Code review |
| 8 | **Run tests scoped** — locally use `./gradlew -p <module> test --tests <Class>`; the full suite (20+ min) is CI-only. | Convention · CI runs the full suite via `task ci:verify` |
| 9 | **Branch + PR** — never commit directly to `trunk`; one reviewable PR per change; fast-forward or merge-commit, never force-push. | GitHub branch protection |
| 10 | **Commits explain *why*** — present-tense summary, body covers rationale; co-author trailer. | Code review |
| 11 | **No forbidden brand strings** in code/docs/fixtures (see the branding policy). | CI string scan |
| 12 | **No GitHub Actions** — CI is the Titan self-pipeline on the k3s rig (free, dogfoods the engine). | Policy (NG13) · no `.github/workflows` |
| 13 | **Secrets never in git** — pulled from Infisical / k8s Secrets at deploy time; rig-local secret files are gitignored. | `.gitignore` · CI secret scan |
| 14 | **A PDL step is ~3 files** — handler + registration + TCK test; don't touch the grammar/schema. | The `add-pdl-step` skill · `GrammarSchemaContractTest` |
| 15 | **Design before code** — non-trivial work has a `docs/design/NN-*.md` note; code references it. | Code review |
| 16 | **Coverage floors are JaCoCo-verified, not aspirational** — the convention plugin wires `jacocoTestCoverageVerification` into each module's `check`, with the floor set per-module via the `jacocoMinLineCoverage` / `jacocoMinBranchCoverage` properties; a module under its floor fails the build. | `task gradle:check` (JaCoCo verification) |
| 17 | **titan-ui builds AND type-checks before it ships** — vitest's resolver is more lenient than rollup's, so only a real `vite build` catches orphan / missing imports (drift repaired four times: PRs #415, #422, #426, #428); and `vite build` only *transpiles* (esbuild strips types without checking), so only `tsc --noEmit` catches genuine TS type errors — a type error that passed `vite build` reached trunk green and broke the deploy's `tsc` (#1194/#1199). | Pre-commit **gate 5** runs **`vite build` + `tsc -p tsconfig.json --noEmit`** on staged UI `.ts`/`.tsx` (via `dev/git-hooks/ui-precommit-gate.sh`); `task verify:ui` (rolled into `task verify` / `task ci:verify`) runs type-check + vitest + build. |
| 18 | **Reconciler tests end with one extra `advance()`** — every test of a reconciler-style loop (orchestrator `advance()`, queue tick, sweep) MUST drive the build to a stable state, snapshot the whole row tuple it cares about, run the loop **one more time** (or N more times, jqwik-style), and assert state unchanged. "Run it twice = no-op" is the contract; tests that stop at the first correct transition let the build-22 class of bug (a parked-state reconciler that quietly inserts a fresh row every tick) ship. The canonical pattern is `OrchestratorIdempotenceProperty`. | Code review · `OrchestratorIdempotenceProperty` is the reference |
| 20 | **Operator tour before merging UI changes** — for any PR that touches `titan-ui/`, run `task tour:run` against a populated `task dev:titan` rig and skim `e2e/tour/report.html` for new diffs. Catches the class of regressions e2e behaviour tests miss: clipped panels, vanished buttons on mobile, dark-mode contrast drift, missing empty-states. See `e2e/tour/README.md` (issue #1132). | Manual gate today; a CI block is planned once the baseline stabilises. |
| 19 | **Complexity is signalled beyond LOC** — per-method cyclomatic complexity, injected-collaborator count, test:source LOC ratio, and fan-in are surfaced as **soft warnings** at commit time and as an on-demand report. LOC alone is a crude proxy: a 200-line god-object trips none of the line caps. See docs/design/71-complexity-metrics.md and issue #909. | Pre-commit **gate 6** (soft) on staged Java; `task quality:metrics` / `task quality:hot` on demand |

## The enforcement layers

1. **Gradle wrapper + Taskfile** — there is exactly one build path; you cannot
   accidentally use a different one.
2. **`task gradle:check`** — Spotless, SpotBugs, and JaCoCo verification across
   all product modules; `task ci:verify` adds the Testcontainers ITs, the UI
   verify, and migration/route-tree freshness checks. Run by CI on every PR;
   nothing merges red.
3. **Git hooks** (`dev/git-hooks/`, installed by `task install-hooks`) —
   pre-commit guards: untracked `.java` under `src/main/java/` (the Daos-drift
   guard), `spotlessCheck`, a 1000-line file-size cap (700-line soft warning),
   **`vite build` + `tsc --noEmit`** on staged UI changes (vite build catches
   the rollup-vs-vitest resolver drift; tsc catches type errors vite transpiles
   past), and **complexity-based soft warnings** (per-method cyclomatic
   complexity, injected collaborators, test:source ratio, fan-in —
   informational, never blocking).
4. **GitHub branch protection** — `trunk` takes PRs only.
5. **Code review** — the catch-all for what tooling can't see (rows 1, 3, 6,
   7, 10, 15).

A practice that can only be caught by review is weaker than one a gate
catches. When you find a recurring review nit, the goal is to *promote it*
into a gate (Spotless rule, a test, a CI check) and delete it from the review
burden.
