# How a production-grade CI/CD engine was built by autonomous AI coding loops

Titan is a standalone, cloud-native CI/CD execution engine: a Quarkus control
plane, a pull-based worker, a YAML pipeline language, a Postgres-backed durable
state machine, and a React operator UI. Almost all of it was designed and built
by autonomous AI coding agents running in a supervised loop, against a fixed
quality bar, with a human acting as the merge gate rather than the typist.

This is the honest writeup of how that works — the architecture of the harness,
not a victory lap. Everything below is anchored to a file you can open in the
repository. Where a claim can't be backed by an artifact, it isn't made.

If you're skeptical that "built by AI" can mean anything more than "I pasted
some functions from a chat window," good. That skepticism is the right starting
point, and the rest of this post is built to survive it.

---

## The bet

The interesting question about AI-written software was never "can a model write
a function?" It obviously can. The interesting question is whether you can run
*many* agents, *in parallel*, for *hundreds of cycles*, against a *real
codebase*, without the architecture silently rotting:

- without the fifth agent re-deciding what the first three already locked;
- without a stub UI shipping on top of an endpoint that doesn't exist;
- without a `Thread.sleep` creeping back into a system whose entire premise is
  durable, restart-survivable timers;
- without the test suite filling up with tautologies that re-state the
  implementation and assert nothing.

Those are not model problems. A bigger model doesn't fix them — it just produces
the same drift faster. They are *control-system* problems. The bet behind Titan
is that the engineering leverage is not in the model at all; it's in the harness
around it: the data the agents read, the gates that fail their bad work, and the
self-correction that classifies a failure instead of blindly retrying it.

The slogan we kept coming back to, from the loop's own charter:

> The loop is dumb on purpose; the dataplane carries the smarts; the rituals
> carry the freshness; the human is the merge gate.

---

## The harness

The loop that turns a groomed backlog into merged pull requests is deliberately
simple. The intelligence is not in the loop code — it's in the four things the
loop reads and enforces. (The loop itself is an open-source package,
`forge-loop`; this repo carries only its configuration in
[`forge-loop.yaml`](../../forge-loop.yaml) and Titan-specific brief overrides
under `.forge-loop/briefs/`.)

### 1. GitHub issues *are* the task queue

There is no bespoke orchestration database. The backlog is the set of GitHub
issues, and their labels are a state machine. This is a design choice, not
laziness: the queue is inspectable in a UI every engineer already knows, and a
human can override any transition by editing a label.

A ticket is only eligible for dispatch when it reaches **Ready**, which requires
explicit `## Acceptance` checkboxes and a `cites:` field pointing at a canonical
design doc. No acceptance criteria, no dispatch. The brief verifier rejects it.

### 2. Twelve specialist agents with hard file-ownership boundaries

Work isn't dispatched to a generic "coding assistant." It's routed to one of
twelve specialists, each defined as a real file in
[`.claude/agents/`](../../.claude/agents/) — frontmatter declaring its name and
allowed tools, a body that is the system prompt it runs under. A sample of the
roster:

- **engine-agent** owns the server-side Java engine — orchestrator, queue
  processor, durable timers, DAOs, the runtime state machine — and stays out of
  the HTTP DTO surface, the UI, the worker, and the PDL grammar.
- **storage-agent** is the *only* agent that writes SQL or designs schemas:
  Flyway migrations, JDBI DAOs, the appliers that sink data into the DB. It's
  deliberately split from the engine agent so the persistence layer gets its own
  design rigor — migration-immutability rules an engine-focused agent would
  shortcut.
- **frontend-agent** owns the entire React UI (Vite, React 19, TypeScript
  strict, TanStack Router/Query/Table, Tailwind, shadcn) plus the Playwright
  suite, and touches no Java.
- **test-agent** owns every `src/test/`, `src/integrationTest/`, and `e2e/` —
  and is *forbidden* from touching production source. If it finds a bug, it
  files an issue; a test PR can never smuggle a behavioral change past review.
- **cqc-agent** is the "would this pass review at a serious shop?" voice —
  Spotless, SpotBugs, Error Prone, accessibility, dead code — and applies only
  mechanical fixes itself; behavioral changes go back to the originating agent.
- **po-agent** is the product owner: it breaks a human's vision into themed
  tickets with acceptance criteria and `cites:` references, and guards scope
  against the design contract. It writes no code at all.

The specialization is what makes parallelism *safe*. Two agents can run at the
same time precisely because their file-ownership boundaries don't overlap. The
boundary table is the collision-detection mechanism: same file = guaranteed
collision, sequence them; different files = parallel-safe.

(A note on honesty: a couple of agent definitions still carry vocabulary from
Titan's pre-standalone era — legacy package names, references to a since-deleted
module. That drift is real and visible in the files. It's kept rather than
airbrushed; the ownership boundaries are current and load-bearing, the
historical phrasing is being groomed out on cadence.)

### 3. A skill library the agents read before acting

Agents don't re-derive recurring procedures. They pull from a skill library at
[`.claude/skills/`](../../.claude/skills/) — worked procedures, each with a real
example from this codebase: adding a DAO, writing a Flyway migration, building a
ServiceLoader SPI shell, adding a PDL step, authoring a chaos test, broadcasting
an SSE event. When an agent notices it's doing the same thing for the third
time, it distills the pattern into a new skill file, and the next invocation
references it instead of re-deriving it. This is how the dataplane gets *smarter*
over time without the loop code changing at all.

### 4. The constitution and the quality bar that keep the output FAANG-grade

This is the part that makes machine-written code trustworthy, and it's where
most "AI built it" stories quietly have nothing.

[`constitution.md`](constitution.md) is read by every dispatched agent on every
task. It's deliberately small (target: under 200 lines) so it stays
load-bearing — the product's spine, not a style guide. It carries one line of
what Titan is, **five non-negotiables** (no legacy plugin-host coupling, ever;
the UI ships its seam in the same sprint as its backend; YAML pipelines are the
contract; one coherent product with no escape hatches; real workloads from day
one — no fake demo data), the well-architected-engine invariants, the locked
architectural decisions (Quarkus over Spring, Postgres via JDBI, Gradle not
Maven — each a dated line tied to the PR that made it), and an **anti-pattern
table**: the rejection list, where each row is a class of bug that already
happened once, cited to the PR that taught the lesson.

That last point is the mechanism that compounds. Every time an agent is STOPped
on a novel class of bug, the lesson becomes a constitution row — and from then
on the vision oracle rejects any future brief that would re-commit it. *The
constitution grows by accretion of lessons learned.* It rarely shrinks.

[`quality-bar.md`](quality-bar.md) is a table where every single row has an
**"Enforced by"** column naming the mechanism that fails the work. If a row has
no enforcement, it doesn't belong in the chart. The enforcement is layered: one
build path (a single `Taskfile` + committed Gradle wrapper, so you can't
accidentally use a different toolchain); a verify build (Spotless, SpotBugs,
Error Prone, JaCoCo coverage floors — nothing merges red); pre-commit hooks (no
untracked source, a line-count cap, no forbidden imports, and — the hard-won one
— a real `vite build` *plus* `tsc --noEmit` on changed UI files); branch
protection; and code review as the catch-all that feeds the next gate.

The standing rule: a practice a gate catches automatically is stronger than one
only a human reading the diff catches. The goal is to keep promoting review nits
into gates and deleting them from the human's burden.

---

## The loop, one tick at a time

The pieces above come together on a fixed cadence
([`forge-loop.yaml`](../../forge-loop.yaml) sets a 60-second tick, three
concurrent workers). Each tick re-reads the charter and constitution from disk
first, so when those docs evolve, the loop's behavior self-corrects on the next
wake. One tick:

1. **Sync** — pull open PRs, the in-flight and Ready columns, new agent reports;
   read recent telemetry from `loop.jsonl` for short-term memory.
2. **Reconcile in-flight** — handle each dispatched agent's report, or poke it
   if silent too long. Never kill an agent mid-flight.
3. **Promote PRs** — run the pre-review gates against each new PR; pass moves the
   ticket to Review for human merge, fail comments the specific gate that
   tripped and routes to Needs-human.
4. **Dispatch new work** — only if fewer than three PRs are in flight (above
   that, worktrees start colliding). Pick the top Ready ticket, run the vision
   oracle, and if it scores high enough, assemble a brief and dispatch the named
   specialist into an isolated git worktree.
5. **Sweep stalled reviews** — ping, don't escalate, PRs unmerged for over a
   day. The human is the only merge authority.
6. **Telemetry** — append one JSON line to `loop.jsonl`. Append-only.

### The vision oracle: the gate before the gate

Before any agent is dispatched, the loop runs a **vision oracle** pass — a cheap,
structured LLM call that scores the assembled brief against the constitution and
the cited design doc on five checks, one point each:

1. **Vision alignment** — does each acceptance criterion map to one of the five
   non-negotiables?
2. **Anti-pattern avoidance** — does the implied path steer clear of the
   constitution's forbidden patterns?
3. **Decision freshness** — is the cited design doc still locked, or is the
   ticket quietly re-deciding something already settled?
4. **Scope sanity** — does the size estimate match the touch surface the
   acceptance criteria imply?
5. **Reference reality** — do the file paths and pattern files the brief cites
   actually exist on trunk?

A 5 dispatches cleanly. A 4 dispatches *with the failing check written into the
brief as an explicit warning the agent must address*. Below 4, the loop refuses
and routes to Needs-human. Crucially, the oracle scores; it never proposes a
fix. That is human territory.

This is the single most important guardrail, because it stops drift *before*
tokens are spent. Without it, the loop degrades into a Markov chain — each tick
locally correct, the whole globally drifting, because an agent reasons from its
immediate context plus training data rather than from the Titan worldview.

### Two keys on every PR before a human ever looks

No PR reaches the merge button on green tests alone. Five **pre-review gates**
hard-grep the diff: base must be trunk (a past PR lost its content by merging
into a since-deleted branch); it must reference its source ticket; **boot
evidence** must be present — a Playwright trace, curl output, a screenshot, a
smoke line (a past PR shipped four bugs because nobody booted it); tests must
match the diff type; and no anti-pattern from the rejection list.

Then a **critic** reviews the diff against the issue's acceptance criteria. It's
pinned to the *same model tier as the worker that wrote the code* — a deliberate
choice so the reviewer is never the weakest link judging stronger output — and
it must produce at least one finding to approve (no lazy rubber-stamp). Severity-1
and severity-2 findings block; only clean or cosmetic-only diffs pass through.
You can read the exact configuration in the `critic:` block of
[`forge-loop.yaml`](../../forge-loop.yaml).

And then the **human merges.** The autonomy ends at `gh pr create`.

---

## What worked, what was hard, and the guardrails that earned their keep

### The compounding mechanic (what worked best)

The single highest-leverage pattern wasn't writing code faster — it was making
each test *harden the engine permanently*:

1. Ship a fixture pipeline and an adversarial Playwright spec for it.
2. The first run of the spec surfaces one or two real engine bugs.
3. A maintenance cycle triages them into the backlog.
4. A work cycle fixes them; the fix merges; the rig redeploys.
5. Re-run the spec — it passes, and that engine seam is now regression-pinned
   forever.

Each fixture becomes a hardening multiplier rather than a one-off test. This is
why the loop *compounds* rather than merely *produces*.

### Silent retry (the hardest failure mode)

The single biggest failure mode of an autonomous loop is **silent retry**: an
agent STOPs on something real, the loop reads it as transient, re-dispatches,
the agent STOPs again, and drift accelerates as bad PRs pile up. The
self-correction discipline exists to prevent exactly that. Every `BLOCKED`
report is classified into one of five buckets *before* the loop decides
anything:

| Class | Retry budget |
|---|---|
| Architectural conflict ("needs to change a subsystem I don't own") | 0 — the discovery *is* the work |
| Dataplane gap ("a cited design doc or pattern file is missing/stale") | 0 — fix the dataplane, re-enter Ready |
| Spec ambiguity ("acceptance doesn't say whether A or B") | 0 — don't burn tokens on a coin flip |
| Out-of-scope discovery ("this fans out into separate work") | 0 — auto-file the split |
| Transient infra (rig down, image pull failed, mid-task truncation) | 1 |

The cap is one retry per ticket per cycle, only for the transient class. A
ticket cycling to Needs-human is a signal, not a state to grind through.

The transient class has a sharp recovery move. Agents sometimes truncate
mid-task with real work sitting uncommitted in their worktree. The loop's first
move is not to retry — it's to **inspect the worktree**. If the agent produced
good files but never pushed, the loop stages them, commits, pushes, and opens the
PR tagged `DONE_WITH_CONCERNS` with a follow-up issue — no retry consumed,
because inline recovery is deterministic and cheaper than re-running the agent.

### "Produced is not in effect" (the discipline that caught the subtlest bugs)

The sharpest discipline in the whole system is documented, with five real bugs
traced through it, in [`produced-vs-in-effect.md`](produced-vs-in-effect.md).
The bugs presented as five unrelated problems and turned out to be one bug
wearing five coats: in each, something was *produced* but never went *into
effect*, and a stale representation in the gap looked authoritative. A validation
message showed an old error while the parser was correct. A build produced a
fresh artifact while the running process served the old one. A subagent reported
"done" while the file was unchanged.

The fix, every time, is the same move: **stop reading the representation; measure
the thing at its point of effect.** Don't trust the displayed error — re-run the
parser. Don't trust "deploy succeeded" — exec into the container. Don't trust an
agent's "done" — diff the file. As the note puts it:

> A bug report, a green deploy, and a subagent's "done" are all claims about
> state. Reproduction, a container exec, and a diff are state. When they
> disagree, the claim is the bug.

This is *why* the pre-review gates demand boot evidence rather than a
"should work." A claim of working is not working.

### Why it doesn't drift over hundreds of cycles

Three things keep the architecture from rotting: the **oracle** stops bad briefs
at the door (prevention upstream, not patching downstream); the **drift floor is
measured** (a merged PR followed by a "fix" PR within a few cycles counts as
drift, and exceeding the floor auto-pauses dispatch and emits an investigation
signal — the loop watches its own output quality as a first-class metric); and
**rituals keep the dataplane fresh** (daily summaries, a weekly dataplane-doctor
that flags stale docs and orphaned references, longer-cadence constitution and
architecture reviews).

---

## Concrete evidence

Don't take the narrative on faith. The repo is the evidence:

- **The agent roster** — twelve `*.md` definition files in
  [`.claude/agents/`](../../.claude/agents/), each a real system prompt with
  declared file-ownership boundaries.
- **The skill library** — worked procedures with real codebase examples in
  [`.claude/skills/`](../../.claude/skills/).
- **The constitution** — [`constitution.md`](constitution.md): the five
  non-negotiables, the locked decisions, the anti-pattern rejection table.
- **The enforced quality chart** — [`quality-bar.md`](quality-bar.md): every row
  names the gate that fails the work.
- **The loop configuration** — [`forge-loop.yaml`](../../forge-loop.yaml): the
  parallelism cap, the same-tier critic block, the deploy hook.
- **A worked spec → plan pair**, kept verbatim as evidence of the build method:
  [`exemplar-spec-timer-subsystem.md`](exemplars/exemplar-spec-timer-subsystem.md)
  states *why* a worker-side `Thread.sleep` is the wrong way to wait for hours
  (it pins an executor thread and restarts from zero on a worker bounce) and
  argues for a controller-native timer where a wait is a persisted database
  fact; [`exemplar-plan-timer-substrate.md`](exemplars/exemplar-plan-timer-substrate.md)
  breaks that contract into small, independently committable tasks, each with the
  exact commands to run and the expected output. Read the plan end to end and
  decide for yourself whether it reads like engineering or like a prompt.

For the full tour of the method, start with
[method-and-discipline.md](method-and-discipline.md) and
[the-loop.md](the-loop.md).

---

## Limits, and what a human still does

To keep the rest of this credible, the boundaries — stated plainly:

- **A human owns vision, judgment, and every merge.** The constitution, the
  design docs the agents cite, the skill books, and the backlog priority order
  are all human-authored. The autonomy is in the *dispatch* and the *build*, not
  in the decision to ship. Risky changes — schema migrations, Helm charts,
  anything tagged `risk:high` — park for human review by design.
- **"Almost all of it" is the honest qualifier.** Humans own intent; agents own
  execution at volume. The split is deliberate, not a limitation we're hiding.
- **The output is not perfect, and the system knows it.** The loop measures its
  own drift rate and treats exceeding the floor as a stop-and-investigate signal.
- **This is not "any model, any prompt."** The agents run against a heavily
  curated dataplane. The quality of the output is a direct function of the
  quality of that dataplane — which is itself a maintained artifact, groomed on a
  cadence. Take away the constitution, the skill books, and the gates, and you're
  back to a chat window producing drift faster.

That is what "FAANG-grade, built by AI" means here: not that the machine is
trusted, but that it is *gated* — and the gates are real files in this repo you
can open and check.

---

## An invitation to AI-first contributors

If you build this way — or want to — Titan is a working reference implementation
of the harness, not a hypothetical. The agent definitions, the skill format, the
constitution structure, the gate mechanics, and the loop configuration are all
in the open. Fork the patterns. Open an issue describing a fixture pipeline and
the engine seam it would harden. Read an agent file and tell us where its
boundary is wrong. Push back on a constitution row you think is over-fitted to
one bug.

The most useful contribution isn't necessarily code — it's a sharper gate, a
new skill that retires a class of re-derivation, or a fixture that turns one
more engine seam into a permanent regression pin. That's the whole game: the
model is the commodity input; the discipline is the product.

Start here: [the loop](the-loop.md) · [the agent roster](agent-roster.md) ·
[the constitution](constitution.md) · [the exemplar plan](exemplars/exemplar-plan-timer-substrate.md).
