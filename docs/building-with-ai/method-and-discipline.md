# Method and discipline

The question an experienced engineer asks about AI-written code is the right
one: *how do you keep it from being slop?* Volume without a quality bar is a
liability, not an asset. This page is the answer — the discipline that keeps
machine-generated code at a level that would survive review at a serious shop.

The short version: **every quality rule has a mechanism that fails the work if
the rule is broken.** A rule that can only be caught by a human reading the
diff is weaker than one a gate catches automatically, and the standing goal is
to promote review nits into gates and delete them from the human's burden.

## The constitution — the worldview anchor

[`constitution.md`](constitution.md) is read by every dispatched agent on
every task. It is not a style guide; it is the product's spine, and it is
deliberately small (target: under 200 lines) so it stays load-bearing. It
carries:

- **One line of what Titan is** — so every yes/no/later decision has a north
  star.
- **Five non-negotiables** — no legacy plugin-host coupling, forever; the UI is the product and
  ships its seam in the same sprint as its backend; YAML pipelines are the
  contract; one coherent product with no escape hatches; real workloads from
  day one with no fake demo data.
- **The well-architected-engine invariants** — single source of grammar,
  discriminated-union typed config over string sniffing, secrets only via the
  credentials service, pull-based workers, OIDC-for-humans / tokens-for-
  machines, idempotent reapers, end-to-end tracing, observable failures.
- **Locked architectural decisions**, each a dated line tied to the PR that
  made it — Quarkus over Spring, Postgres via JDBI, Gradle not Maven, envelope
  encryption with per-secret keys, ServiceLoader SPIs everywhere. Reverting one
  requires an explicit `constitution:` PR.
- **An anti-pattern table** — the rejection list. Each row is a class of bug
  that already happened once, cited to the PR that taught the lesson. The
  constitution *grows by accretion of lessons learned*; it rarely shrinks.

That last point is the mechanism that compounds: every time an agent is
STOPped on a novel class bug, the lesson becomes a constitution row, and from
then on the vision oracle rejects any future brief that would re-commit it.

## The enforced quality chart

[`quality-bar.md`](quality-bar.md) is a table where every row has
an **"Enforced by"** column naming the mechanism that fails the work. If a row
has no enforcement, it doesn't belong in the chart. The enforcement is layered:

1. **One build path.** A single `Taskfile` entry point and a committed build
   wrapper — you cannot accidentally use a different toolchain.
2. **The verify build** — Spotless formatting, SpotBugs and Error Prone static
   analysis, coverage floors. Nothing merges red.
3. **Git pre-commit hooks** — a series of gates: no untracked source files,
   formatting clean, a line-count cap, no forbidden legacy plugin-host imports, no Maven
   artifacts, a real `vite build` *plus* `tsc --noEmit` on changed UI files
   (because the test runner's resolver is more lenient than the bundler's, and
   the bundler only transpiles types without checking them — both gates exist
   because both classes of error reached trunk once), and complexity-based soft
   warnings.
4. **Branch protection** — trunk takes PRs only; never a direct commit.
5. **Code review** — the catch-all for what tooling can't see, and the source
   of the next gate.

The chart is candid about its own gaps — it names which support modules don't
yet have static-analysis gates, and tracks closing them as follow-up. Honest
about coverage beats aspirational about coverage.

A few rows are worth highlighting because they encode hard-won lessons:

- **Tests are adversarial** — exercise the sad path; assert against an
  independent oracle, not a restated copy of the source. A tautological test
  that re-states the implementation is worse than no test, because it gives
  false confidence.
- **Reconciler tests end with one extra `advance()`** — any test of a loop that
  is supposed to converge must drive to a stable state, then run the loop once
  more and assert nothing changed. "Run it twice = no-op" is the idempotence
  contract; tests that stop at the first correct transition let a whole class
  of bug (a reconciler that quietly re-inserts a row every tick) ship.
- **The UI builds *and* type-checks before it ships** — repaired four separate
  times before it became a gate.

## Spec → plan → implement

Non-trivial work follows a three-step pattern: a **design doc** establishes the
contract, an **implementation plan** breaks it into independently-verifiable
tasks, and only then does an agent write code. One worked pair is kept under
[`exemplars/`](exemplars/) as evidence you can read end to end:

- [`exemplar-spec-timer-subsystem.md`](exemplars/exemplar-spec-timer-subsystem.md) —
  the design spec. It opens by stating the goal (a controller-native timer
  where a wait is a persisted database fact, not a blocked thread), explains
  precisely *why the current `sleep` is wrong* (a worker-side `Thread.sleep`
  pins an executor thread and restarts from zero on a worker bounce — fine for
  seconds, wrong for hours), and enumerates explicit non-goals. That is a real
  engineering argument, not a prompt.
- [`exemplar-plan-timer-substrate.md`](exemplars/exemplar-plan-timer-substrate.md) —
  the implementation plan it executes against. It grounds itself in the spec's
  contract, then lays out small, independently committable tasks — each with
  the exact commands to run and the expected output, and a clear scope boundary
  on what is deferred to later phases.

The plans carry a standing instruction to their executing agents: implement
task-by-task with review checkpoints, using checkbox syntax for tracking. The
discipline is that a plan is a contract the agent executes against, not a
suggestion it improvises around.

## Verify at the point of effect, not the point of production

The sharpest discipline in this whole system is documented in
[`produced-vs-in-effect.md`](produced-vs-in-effect.md),
and it is the antidote to the most seductive AI failure mode: trusting a claim
of state instead of measuring state.

The note traces five bugs from one session that presented as five unrelated
problems and turned out to be one bug wearing five coats. In each, something
was *produced* but never went *into effect*, and a stale representation in the
gap looked authoritative:

- A validation message displayed an old error while the parser was actually
  correct.
- A check *displayed* an error but nothing on the save path *blocked* on it.
- A build produced a fresh artifact while the running process served the old
  one.
- A bundle rebuilt while the browser served a cached copy.
- An agent reported "done" while the file was unchanged.

The discipline, every time, is the same move: **stop reading the
representation; measure the thing at its point of effect.** Don't trust the
displayed error — re-run the parser on the exact input. Don't trust "deploy
succeeded" — exec into the container and check what's actually running. Don't
trust an agent's "done" — diff the file. As the note puts it: *a bug report, a
green deploy, and a subagent's "done" are all claims about state.
Reproduction, a container exec, and a diff are state. When they disagree, the
claim is the bug.*

This is why the loop's pre-review gates demand **boot evidence** — a real
trace, a real curl, a real screenshot — rather than a "should work." A claim
of working is not working.

## Adversarial review, two keys on every PR

No PR reaches a human's merge button on the strength of green tests alone:

- The loop's **five pre-review gates** hard-grep the diff for anti-patterns,
  confirm boot evidence, and require tests for the diff type (see
  [the-loop.md](the-loop.md)).
- A **critic** reviews the diff against the issue's acceptance criteria,
  pinned to the same model tier as the worker that wrote the code so the
  reviewer is never the weaker party, and required to surface at least one
  finding to approve — no rubber stamp. Risky severities block; only clean or
  cosmetic-only changes pass.
- The **cqc-agent** carries a concrete checklist — thread-unsafe statics,
  resource leaks, nullability annotations, logger discipline, permission
  gaps, accessibility, dead code — and reports block/warn/nit with file and
  line.
- The **human merges.** The autonomy ends at `gh pr create`. Every merge is a
  human decision, and risky work parks for review by design.

## The throughline

The model writes the code. The discipline decides whether it ships. A small
constitution anchors the worldview; a quality chart turns every rule into a
mechanism that fails bad work; a spec-then-plan habit forces design before
keystrokes; an adversarial-testing rule rejects tautologies; a "verify at the
point of effect" reflex refuses to trust claims of state; and a layered review
— gates, critic, quality control, human — stands between any diff and trunk.

That is what "FAANG-grade, built by AI" means here: not that the machine is
trusted, but that it is *gated* — and the gates are real files in this repo you
can open and check.
