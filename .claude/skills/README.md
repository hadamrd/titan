# Titan — Agent Skills

This folder is the **skill library** for the agent roster defined in
[`.claude/agents/`](../agents/) and described in
[`docs/building-with-ai/agent-roster.md`](../../docs/building-with-ai/agent-roster.md).

A **skill** is a repeatable procedure with a worked example. When an
agent recognizes "I'm doing the same thing for the third time," it
distills the pattern into a skill file here. Future agent invocations
reference the skill instead of re-deriving the procedure.

## How agents use skills

1. Before starting work, agents scan their skill list (in their agent
   prompt) for relevant entries.
2. They read the skill, copy the pattern, adapt to the specific case.
3. If the pattern needs an addition or correction, they update the
   skill file at the same time.

## How agents create skills

When an agent observes a recurring pattern (≥3 instances), they
create a new skill file with this format:

```markdown
---
name: <kebab-case-name>
applies-to: <agent-id> [ + <agent-id> ]
related-design-doc: docs/design/NN-name.md (optional)
---

## What this is

One-paragraph problem statement.

## When to use

Bullet list of triggers.

## Procedure

1. Step one.
2. Step two.
…

## Worked example

A real example from this project (with file paths, line numbers, code).

## Common variations / gotchas

…

## Cross-references

- [other-skill.md] when X
- design doc section
```

Some skills are directories (`<name>/SKILL.md`) rather than a single
`.md` file — use that shape when the skill ships supporting files.

## Skill index

| Skill | Owner agent(s) | Purpose |
|---|---|---|
| [add-pdl-step/](add-pdl-step/SKILL.md) | pdl-agent | Add a built-in step or grammar scope to the Titan PDL (`titan-pipeline.yml`) |
| [dao-skill.md](dao-skill.md) | engine-agent | Adding a new DAO + Row + test |
| [dao-test-pattern.md](dao-test-pattern.md) | test-agent | In-memory H2 DAO test pattern |
| [spi-shell.md](spi-shell.md) | engine-agent | Adding a new ServiceLoader-discovered SPI provider |
| [schema-migration.md](schema-migration.md) | engine-agent | Adding a Flyway V&lt;n&gt;__*.sql migration |
| [queue-processor.md](queue-processor.md) | engine-agent | Working in the durable queue / processor loop |
| [protobuf-message.md](protobuf-message.md) | worker-agent | Adding/changing a worker protobuf message |
| [sse-broadcast.md](sse-broadcast.md) | server-agent | Streaming SSE events from server-side code |
| [i18n-key.md](i18n-key.md) | frontend-agent | Adding an i18n key + translation entry |
| [chaos-test.md](chaos-test.md) | test-agent | Authoring a chaos / fault-injection test |
| [operational-runbook.md](operational-runbook.md) | bes-agent | Symptoms / Diagnosis / Fix / Prevention runbook template |
| [github-project-management.md](github-project-management.md) | po-agent | Managing the GitHub Project board |
| [next-sprint/](next-sprint/SKILL.md) | po-agent | Plan + dispatch the next Forge-Loop sprint |
| [smoke-tour/](smoke-tour/SKILL.md) | frontend-agent | Click-through crash-test of the live UI |
| [ux-tour/](ux-tour/SKILL.md) | frontend-agent | Adversarial UX review of the live UI |

## Index hygiene

When you add a skill, also add a row to the table above. When you
delete a skill, remove the row. The index is the discovery mechanism.
