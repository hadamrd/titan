---
name: github-project-management
applies-to: po-agent
related-design-doc: design/16-execution-plan.md
---

## What this is

The PO agent's interface to a GitHub Project (Projects v2) board.
The board mirrors `design/16-execution-plan.md` — phases A through M
become items on the board with status, owning agent, and progress
notes.

## Project shape (what gets created on github.com)

**Project:** `Release Flow — v1.0 Roadmap`

**Custom fields:**
- `Phase` — single-select: A, B, C, D, E, F, G, H, I, J, K, L, M
- `Sub-phase` — text (e.g. "A.1", "B.7", "K.2")
- `Status` — single-select: Queued, Active, Blocked, Done
- `Owning agent` — single-select: PO, Engine, Frontend, PDL, Test, CQC, Layer-2, DevOps, Boot-verifier
- `Last update` — date
- `Notes` — text (PO writes the running narrative here)

**Items at v1 seed:**
One issue per phase A–M, plus one issue per known sub-phase that has
non-trivial scope (B.4, B.5, B.7, etc).

## The CTO → PO → Agent → Board flow

```
┌────────────────────────┐
│ CTO (you, main session)│  Vision / feature / fix in plain English.
└───────────┬────────────┘
            │
            ▼
┌────────────────────────┐
│ PO Agent               │  - reads design/* + open issues
│                        │  - breaks request into themed tickets
│                        │  - tags each (theme + phase + agent + priority)
│                        │  - gh issue create + project item-add
│                        │  - Status=Queued, Owning agent=<role>
│                        │  - asks orchestrator to dispatch
└───────────┬────────────┘
            │
            ▼
┌────────────────────────┐
│ Orchestrator (main)    │  Spawns the named agent via Agent tool with
│                        │  the ticket body + agent's standing prompt.
└───────────┬────────────┘
            │
            ▼
┌────────────────────────┐
│ Specialist agent       │  Reads ticket → reads relevant skills →
│ (engine/pdl/test/…)    │  ships → reports back with files + tests +
│                        │  deviations.
└───────────┬────────────┘
            │
            ▼
┌────────────────────────┐
│ Orchestrator           │  Integrates, runs compile/boot/tests.
│                        │  On green, posts the agent's report as a
│                        │  comment on the ticket and closes it (or
│                        │  flips Status=Done in the project).
└────────────────────────┘
```

CTO writes. PO tickets. Agent ships. Orchestrator integrates. Board reflects.

## How the PO agent uses it

After every meaningful event (an agent ships, a phase moves status,
a decision lands), the PO agent:

1. Resolves the affected item(s).
2. Updates the `Status` field.
3. Sets `Last update` to today.
4. Appends one line to `Notes` summarizing what shipped (with a commit
   SHA).
5. Closes the corresponding issue when status flips to Done.

Example:
```
gh project item-edit \
  --project-id <pid> \
  --id <item-id> \
  --field-id <status-field-id> \
  --single-select-option-id <done-option-id>
```

## CLI cheatsheet

Owner is the GitHub user / org under which the project lives.

```bash
# List projects
gh project list --owner hadamrd

# Create project (returns project number)
gh project create --owner hadamrd --title "Release Flow — v1.0 Roadmap"

# Add a custom field
gh project field-create <project-number> --owner hadamrd \
  --name "Phase" --data-type SINGLE_SELECT \
  --single-select-options "A,B,C,D,E,F,G,H,I,J,K,L,M"

# Add an item from an existing issue
gh project item-add <project-number> --owner hadamrd \
  --url https://github.com/hadamrd/dashboard-plugin/issues/N

# Edit a field on an item
gh project item-edit --id <item-id> \
  --project-id <project-id> \
  --field-id <field-id> \
  --single-select-option-id <option-id>

# Close an issue (item auto-moves to Done if Status field tracks state)
gh issue close N --reason completed --comment "Phase A done; see commit SHA…"
```

## Setup procedure

The first-time setup is in `.claude/skills/github-project-management.sh`
(if present) or run manually:

1. Refresh token: `gh auth refresh -s project`.
2. Create the project: `gh project create --owner <USER> --title "Release Flow — v1.0 Roadmap"`.
3. Add the 6 custom fields.
4. Create the 13 milestone issues (one per phase).
5. Add each issue to the project.
6. Set initial Status field per phase (look at execution-plan.md).
7. PO agent maintains from then on.

## Ongoing maintenance

Every commit that closes a sub-phase: the PO agent (or the orchestrator
on PO's behalf) updates the corresponding item. Pattern in commit
messages: `Refs: ProjectItem-<id>` so the GH Action (post-v1) can
auto-update.

## Out of scope (for v1)

- A custom GH Action that auto-closes issues from commit footers.
- Cross-project rollups.
- Calendar view of phases.
- Burndown / velocity charts.

## Cross-references

- `design/16-execution-plan.md` — the source of truth the project
  mirrors.
- `.claude/agents/po-agent.md` — the agent that drives this.
- `design/13-decision-log.md` — what's locked on the project's items.
