# Maintaining the docs

This tree is **curated**. Every file earns its place or it gets deleted.
The goal is a small set of documents that are current, relevant, and easy
to navigate — not an archive of everything anyone ever wrote. Read this
before adding or moving a doc.

## The one rule

A document survives only if it does one of these:

1. **Documents the product** as it is now — a concept, guide, reference, or
   architectural explanation.
2. **Records a decision** — why we chose A over B (an ADR).
3. **Captures a real incident** with a durable lesson (a post-mortem).
4. **Helps someone operate it** — deploying, observing, a known-failure runbook.

If a file is none of these — a dated planning note, a status snapshot, a
scratch note, a "PR 1 of 3" framing, something tied to a ticket number that
has long closed — it does not belong in the tree. Rewrite it as durable
documentation or delete it.

## Where each kind of doc lives

| Section | What belongs here | What does NOT |
|---|---|---|
| `concepts/` | The mental model at product altitude — *what* a thing is (a pipeline, a build, the engine). Prose, no class names. | Code internals, lookup tables. |
| `architecture/` | *How* the engine works inside — the queue, recovery, synthesis; real classes and flows. | Product-altitude intros (those are concepts). |
| `reference/` | Exhaustive, lookup-style, code-authoritative — config keys, DB schema, PDL grammar, SPI signatures, the step catalog. | Narrative or tutorials. |
| `guides/` | Task-oriented how-to for a user/operator/contributor — "how to write a step", "how to wire credentials". | "Why we chose X" (that's a decision). |
| `decisions/` | One immutable numbered ADR per architectural decision. | Anything mutable; supersede, don't rewrite. |
| `operations/` | Running it in production — deploying, logging. | Product reference. |
| `operations/runbooks/` | A procedure for a *known* failure mode. | One-off debugging logs. |
| `operations/post-mortems/` | A retrospective on a *real* incident, with a lesson. | Closed issues with no durable lesson. |
| `building-with-ai/` | The AI-build method narrative, the constitution/quality bar, and a small set of `exemplars/`. | A dump of every loop plan. Keep 1–2 exemplars, not 13. |

## Altitude rule

`concepts` → `architecture` → `reference` go from shallow-and-prose to
deep-and-exhaustive. **A fact lives at exactly one altitude.** The others
cross-link to it; they never restate it. If you find yourself explaining the
recovery model in three files, two of them are wrong.

## Naming

- `kebab-case-topic.md`. The name is the navigation — no number prefixes.
- **No dates in filenames** except the two kinds that are inherently dated:
  post-mortems (`YYYY-MM-DD-slug.md`) and ADRs (`ADR-NNNN-slug.md`).
- No ticket/PR/loop-tick numbers in titles or in the body of a durable doc.

## Anti-rot rules

- **No private infrastructure** in docs — hostnames, IPs, bucket names,
  cluster contexts. Use `example.com` and placeholders.
- **Code wins.** When a reference doc and the code disagree, the doc is the
  bug. Spot-check claims against the source before you commit a doc.
- **No orphans.** Every doc is linked from its section `README.md`.
- **Decisions become ADRs**, not paragraphs buried in a guide.
- **Incidents become post-mortems** only if they teach something durable;
  otherwise they are a closed issue, not a document.

## "Where do I write X?"

| You want to… | Write it in… |
|---|---|
| Explain what a thing *is* | `concepts/` |
| Explain how the engine does it internally | `architecture/` |
| Document a flag, key, or signature someone looks up | `reference/` |
| Give the steps to accomplish a task | `guides/` |
| Record "we chose A over B, because…" | a new ADR in `decisions/` |
| Explain how to deploy/operate/observe | `operations/` |
| Write up an outage and its lesson | `operations/post-mortems/` |
| Document a procedure for a known failure | `operations/runbooks/` |
| Tell the AI-build story or keep evidence | `building-with-ai/` |

## Before you merge a doc

- It passes the one rule above.
- It's at the right altitude and doesn't restate another doc.
- It's linked from its section `README.md`.
- Its technical claims were checked against the code.
- No dates/ticket-numbers/private infra leaked in.
