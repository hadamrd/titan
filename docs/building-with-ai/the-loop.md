# The autonomous sprint loop

The loop is the engine that turns a groomed backlog into merged pull
requests. It is deliberately simple: the intelligence lives in the data it
reads (the constitution, the design docs, the agent skill books), not in
the loop code itself. The loop's only job is to **dispatch on-vision work
without drift**.

This page describes one full cycle and the safety mechanisms wrapped around
it. The loop itself is the open-source `forge-loop` package;
this repo carries only its configuration (`forge-loop.yaml`) and Titan-specific
brief overrides under `.forge-loop/briefs/`.

## GitHub issues are the dataplane

There is no bespoke task queue. The backlog *is* the set of GitHub issues,
and their labels are a state machine. This is a design choice, not laziness:
it means the queue is inspectable in a UI every engineer already knows, and
a human can override any transition by editing a label.

The board has six columns. The loop only operates on four of them; the first
two are human-only:

| Column | What's in it | Loop touches it? |
|---|---|---|
| Inbox | Raw ideas, captured fast | No |
| Refined | Has labels but no acceptance criteria yet | No |
| Ready | Acceptance checkboxes + a `cites:` reference to a design doc | Reads from |
| In-flight | Agent dispatched, branch open | Writes to |
| Review | PR open, awaiting human merge | Writes to |
| Needs-human | STOPped, failed, or vision-rejected | Writes to |

A ticket is only eligible for dispatch when it reaches **Ready**, which
requires explicit `## Acceptance` checkboxes and a `cites:` field pointing at
a canonical design doc. No acceptance criteria, no dispatch — the brief
verifier rejects it.

## One tick, end to end

The loop runs on a fixed cadence (configured at a 60-second tick interval in
`forge-loop.yaml`; the charter describes a 20-minute cache-warm re-anchor for
the manually-driven mode). Each tick re-reads the charter and constitution
from disk first — so when those docs evolve, the loop's own behavior
self-corrects on the next wake. A tick does this:

1. **Sync.** Pull open PRs, the In-flight column, the Ready column, and any
   new agent reports since the last tick. Read the last few telemetry lines
   from `loop.jsonl` for short-term memory.
2. **Reconcile in-flight.** For each dispatched agent, handle its report
   (see *Handling reports* below) or, if silent too long, poke it — never
   kill it mid-flight.
3. **Promote PRs.** Run the pre-review gates against each new PR. Pass → move
   the ticket to Review for human merge. Fail → comment the specific gate
   that tripped and route to Needs-human.
4. **Dispatch new work** — only if fewer than three PRs are in flight. Pick
   the top-priority Ready ticket, run the vision oracle, and if it scores
   high enough, assemble a brief and dispatch an agent into an isolated git
   worktree.
5. **Sweep stalled reviews.** Ping (don't escalate) PRs sitting unmerged for
   over a day. The human is the only merge authority.
6. **Telemetry.** Append one JSON line to `loop.jsonl`. Append-only; never
   rewritten.

Work fans out **in parallel** — the loop is configured for three concurrent
workers, each in its own `/tmp` worktree, so three agents ship at once
without touching each other's checkout. The cap is three for a concrete
reason: at six in flight, worktree collisions started causing merge
conflicts (recorded in the loop charter §7).

## The vision oracle — the gate before the gate

Before any agent is dispatched, the loop runs a **vision oracle** pass: a
cheap, structured LLM call that scores the assembled brief against the
constitution and the cited design doc. This is the single most important
guardrail, because it stops drift *before* tokens are spent rather than
catching it in review afterward.

Without it, the loop degrades into a Markov chain — each tick locally
correct, the whole globally drifting, because an agent reasons from its
immediate context plus training data rather than from the Titan worldview.

The oracle scores five checks, one point each
(the vision oracle):

1. **Vision alignment** — does each acceptance criterion map to one of the
   five non-negotiables?
2. **Anti-pattern avoidance** — does the implied implementation path steer
   clear of the constitution's forbidden patterns (legacy plugin-host imports, secrets
   in caches, unscoped full-suite test runs)?
3. **Decision freshness** — is the cited design doc still locked, or is the
   ticket quietly re-deciding something already settled?
4. **Scope sanity** — does the size estimate match the touch surface the
   acceptance criteria imply?
5. **Reference reality** — do all the file paths and pattern files the brief
   cites actually exist on trunk (no references to deleted directories)?

A score of 5 is a clean dispatch. A 4 dispatches *with the failing check
written into the brief as an explicit warning the agent must address*.
Below 4, the loop refuses to dispatch and routes the ticket to Needs-human
with the failing checks as the comment. Crucially, the oracle never proposes
a fix — it scores, it doesn't redesign. That is human territory.

The worked examples in the oracle doc are real: a step-level `when:` runtime
evaluator scored 5 (cites two locked design docs, single module, two tests);
a hypothetical "Slack emoji reactions on build cards" scored 2 and was
correctly rejected as serving zero non-negotiables.

## Pre-review gates — what blocks a PR from a human's attention

Once an agent opens a PR, five automatic gates decide whether it is worth a
human's review time. All must pass; any failure routes to Needs-human with a
specific comment (the loop charter §4):

1. **Base is trunk.** Never a feature branch — a past PR lost its content by
   merging into a since-deleted branch.
2. **References its source ticket** via `Closes #N` / `Refs #N`.
3. **Boot evidence is present** — a Playwright trace, curl output, a
   screenshot, or a `task dev:titan` smoke line. "Should work" is not
   evidence; a past PR shipped four bugs because nobody booted it.
4. **Tests included for the diff type** — touch CSS, include the CSS-build
   test; touch a route, include the route test; touch an API, include a
   `*Test.java`; touch auth, include an auth IT.
5. **No anti-pattern** from the constitution's rejection list (a hard grep
   over the diff).

On top of the loop's own gates, the configured **critic** reviews each PR's
diff against the issue's acceptance criteria before auto-merge is even
considered. It is pinned to the same model tier as the worker that wrote the
code — a deliberate choice so the reviewer is never the weakest link judging
stronger output — and it must produce at least one finding to approve (no
lazy rubber-stamp). Severity-1 and severity-2 findings block; only genuinely
clean or cosmetic-only diffs pass through (`forge-loop.yaml` `critic:` block).

## Handling reports and self-correcting on failure

Agents report one of four statuses: `DONE`, `DONE_WITH_CONCERNS`,
`NEEDS_CONTEXT`, or `BLOCKED`. The first two go through the gates toward
Review. The interesting ones are the failures.

The single biggest failure mode of an autonomous coding loop is **silent
retry**: an agent STOPs on something real, the loop reads it as transient,
re-dispatches, the agent STOPs again, and drift accelerates as bad PRs pile
up. The self-correction discipline exists to prevent exactly that
(the loop's self-correction policy).

Every `BLOCKED` report is classified into one of five buckets before the
loop decides anything:

| Class | What it means | Retry budget |
|---|---|---|
| Architectural conflict | "This needs to change a subsystem I don't own" | 0 — the discovery *is* the work; retrying re-derives the same wall |
| Dataplane gap | "A cited design doc or pattern file is missing/stale" | 0 — fix the dataplane, re-enter Ready |
| Spec ambiguity | "Acceptance criteria don't say whether A or B" | 0 — don't burn tokens on a coin flip |
| Out-of-scope discovery | "This fans out into separate work" | 0 — auto-file the split, route the original to human |
| Transient infra | Rig down, image pull failed, or a mid-task truncation | 1 |

The cap is **one retry per ticket per cycle**, and only for the transient
class. A ticket cycling to Needs-human is a signal, not a state to grind
through.

The transient class has a sharp, real recovery procedure. Agents sometimes
truncate mid-task with real work sitting uncommitted in their worktree. The
loop's first move is not to retry — it is to **inspect the worktree**. If the
agent produced good files but never pushed, the loop stages the intended
files, commits, pushes, and opens the PR tagged `DONE_WITH_CONCERNS` with a
follow-up issue for the missing piece — *no retry consumed*, because inline
recovery is deterministic and cheaper than re-running the agent. The canonical
worked recovery (a `when:` runtime evaluator that truncated after the unit
test but before the integration test) is documented step-by-step in the
self-correction doc.

## Why it doesn't drift

Three things keep hundreds of cycles from rotting the architecture:

- **The oracle stops bad briefs at the door** — drift is prevented upstream,
  not patched downstream.
- **The drift floor is measured.** A merged PR followed by a "fix" PR within
  the next few cycles counts as drift; exceeding the floor auto-pauses new
  dispatch and emits an investigation signal. The loop watches its own
  output quality as a first-class metric.
- **Rituals keep the dataplane fresh.** Daily summaries, a weekly
  dataplane-doctor that flags stale docs and orphaned references, and
  longer-cadence constitution and architecture reviews
  (the loop's grooming rituals). Short cadence catches
  symptoms; long cadence catches root causes.

The summary, from the charter: *the loop is dumb on purpose; the dataplane
carries the smarts; the rituals carry the freshness; the human is the merge
gate.*
