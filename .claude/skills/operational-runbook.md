---
name: operational-runbook
applies-to: bes-agent
related-design-doc: docs/design/06-control-plane.md
---

## What this is

Template + procedure for writing runbooks under
`docs/operations/runbooks/` and post-mortems under `docs/operations/post-mortems/`.
Used by the BES Intervention Officer (A10) and, less frequently, the
DevOps Agent (A8).

## When to write a post-mortem

- Any P1 (production down) — required.
- Any P2 (degraded) that lasted more than 30 minutes — required.
- Any P3 that recurred more than once — required (becomes a runbook seed).
- Any P4 — optional, but if you spent more than an hour on it, write
  it; future-you will thank you.

## When to write a runbook

- The post-mortem identifies a procedural fix worth capturing.
- The same symptom has shown up three times — automate away or write
  the runbook so the *fourth* time is a one-liner.
- The on-call rotation is non-trivial and someone could be paged at
  3am — every plausibly-paged scenario gets a runbook.

## Naming conventions

- Post-mortems: `docs/operations/post-mortems/YYYY-MM-DD-kebab-slug.md` —
  date-prefixed so they sort chronologically.
- Runbooks: `docs/operations/runbooks/<topic>.md` — topic-named so they
  group by the system they describe (e.g.
  `discovery-worker-silent.md`, `database-pool-closed-mid-flight.md`).

## Templates

Both templates live in
[`.claude/agents/bes-agent.md`](../agents/bes-agent.md). Copy from
there.

## Worked examples

Two seed runbooks distilled from real incidents in this session:

- `docs/operations/runbooks/discovery-worker-silent.md` — when
  DiscoveryWorker is firing but no events appear.
- `docs/operations/runbooks/database-pool-closed-mid-flight.md` — when DAO
  calls fail with `HikariDataSource has been closed` mid-boot.

## Anti-patterns

- **Vague runbooks.** "If discovery doesn't work, restart the server."
  Useless — nobody reads that. Either be specific ("If
  `discovery_sources.last_polled_at` hasn't advanced in 5 minutes,
  the worker thread died; check `hpi-run.log` for `discovery worker
  tick failed`") or don't write it.
- **Post-mortems with no action items.** If there's truly nothing to
  do, write "Lesson: this is acceptable; action: none." Don't omit
  the section — the omission looks like an oversight.
- **Editing history.** Post-mortems are append-only. If new info
  comes to light a week later, append a CORRECTION section dated
  the day of correction; never silently revise the original.

## Cross-references

- `design/06-control-plane.md` — andon, freeze, override semantics.
- `.claude/agents/bes-agent.md` — full BES role + protocol.
- `task dev:titan` — boot the local rig when the boot is the symptom.
