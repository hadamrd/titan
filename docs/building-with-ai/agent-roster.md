# The specialist agent roster

Work isn't dispatched to a generic "coding assistant." It's routed to one of
twelve specialist agents, each with a narrow charter, a hard file-ownership
boundary, a reading list it must consult, and a set of pattern files it
mirrors. The definitions are real and live in
[`.claude/agents/`](../../.claude/agents/) — one `*.md` file per agent, each
with frontmatter declaring its name, description, and allowed tools, and a
body that is the system prompt the agent runs under.

The specialization is what makes parallelism safe. Two agents can run at the
same time precisely because their file-ownership boundaries don't overlap.
The boundary is the collision-detection table.

## The twelve

| Agent | Owns | Stays out of |
|---|---|---|
| **po-agent** | The backlog: triages vision into themed tickets, tags them, sets acceptance criteria, dispatches to the right specialist, and guards scope against the design contract. Edits a small set of design docs only. | Any code, any test. |
| **engine-agent** | The server-side Java engine — orchestrator, queue processor, durable timers, build/job services, DAOs, the runtime state machine — across `titan-server` and the core engine modules. | The HTTP DTO surface, the UI, the worker, the PDL grammar, the rig. |
| **server-agent** | The HTTP/SSE API layer and boot-time wiring of `titan-server`: routes, DTOs, OIDC auth, RBAC enforcement, error mapping, SSE log streaming. | Engine logic, frontend, the worker. |
| **storage-agent** | The persistence layer — Flyway migrations, JDBC DAOs, Row POJOs, the `Daos` façade, the appliers that sink discovery output into the DB. The only agent that writes SQL or designs schemas. | Engine runtime services, parsers, UI, tests. |
| **pdl-agent** | The Titan pipeline YAML language — grammar, parser, scopes, and the generated JSON schema in `titan-pipeline-model`. | Step handler logic, UI editor features, the trigger framework's worker side. |
| **worker-agent** | The pull-based worker — protobuf message definitions, the gRPC service, the polling loop, workspace manager, heartbeat thread, the claim-then-work query pattern. | Any host-coupled abstractions; the worker is a standalone process with zero external runtime dependencies. |
| **frontend-agent** | The entire React UI in `titan-ui` — Vite, React 19, TypeScript strict, TanStack Router/Query/Table, Tailwind, shadcn, OIDC via `oidc-client-ts`, SSE log streaming — plus the Playwright suite. | Java, REST DTO definitions, the rig compose files. |
| **test-agent** | Unit, integration, and Playwright E2E tests across the engine, UI, and PDL parser. Owns every `src/test/`, `src/integrationTest/`, and `e2e/`. | Production source — if it finds a bug, it files an issue; it never slips a production fix into a test PR. |
| **cqc-agent** | Code Quality Control — Spotless, SpotBugs, Error Prone, CSP, accessibility, dead-code review. The "would this pass review at a serious shop?" voice. Applies only mechanical fixes directly. | Behavioral changes — those go back to the originating agent. |
| **infra-agent** | Standalone-product infrastructure — Dockerfiles, the `rig/local` and `rig/k3s` stacks, the root `Taskfile.yml`, ops runbooks, the Keycloak realm seed, nginx/CSP config, and the observability stack. | Any Java, any `.tsx`, the design docs. |
| **bes-agent** | Break-glass / incident response — failing boots, regressions, andon-pull triage, emergency overrides. Owns `docs/operations/runbooks/` (how-tos for known failure modes) and `docs/operations/post-mortems/` (retrospectives). | Routine review nits, coverage gaps, non-urgent misconfig. |
| **layer2-agent** | One-time test-rig prep — terraform skeleton, ansible roles, a sample Helm chart, sample team repos. Stops at "an operator with credentials can apply cleanly." (Retiring.) | Long-running infra and core code. |

> A note on accuracy: a couple of agent definitions still carry vocabulary
> from Titan's pre-standalone era (legacy package names, references to a now-
> deleted plugin module). That drift is real and visible in the files — kept
> here for honesty rather than airbrushed. The *ownership boundaries* are
> current and load-bearing; the historical phrasing is being groomed out on
> the documentation cadence.

## How work is routed

The flow is human-to-agent, mediated by the product-owner agent
([`.claude/agents/po-agent.md`](../../.claude/agents/po-agent.md)):

1. A human expresses a vision, feature, or fix.
2. The **po-agent** breaks it into coherent tickets, each tagged with a theme,
   a target agent, and a size estimate, and writes explicit acceptance
   criteria and a `cites:` reference to a design doc.
3. The loop picks the top-priority Ready ticket, runs the vision oracle, and
   if it passes, **assembles a brief** — the relevant constitution excerpts,
   the relevant slice of the target agent's skill book, a summary of the cited
   design doc, the verbatim ticket, and the acceptance checkboxes — and
   dispatches the named specialist into an isolated worktree.
4. The specialist ships a PR; the gates run; the human merges.

Routing is by `agent:` label, mapped one-to-one onto the roster. The loop
never invents an agent — new agent types are added by a human via PR, never
spun up on the fly (per the loop charter §10).

## Why the boundaries are hard, not advisory

The file-ownership matrix is the parallelism contract. Two agents editing the
same file is a guaranteed collision; two agents in different files (even in
the same package) are parallel-safe. The loop uses this to decide what can run
concurrently. Some pairs are *always* collisions and must be sequenced — two
agents editing the same `package.json`, the same migration log, or the same
single Java class.

The boundaries also enforce *separation of concerns* in the output. The
storage-agent is deliberately split from the engine-agent so the persistence
layer gets its own design rigor — schema decisions, index choices, and
migration-immutability rules that an engine-focused agent would shortcut. The
test-agent is forbidden from touching production source so a test PR can never
smuggle a behavioral change past review. The cqc-agent reviews everyone else's
output but only applies mechanical fixes itself.

## Skills — shared procedure, not re-derived each time

Agents don't re-figure-out recurring procedures. They pull from a **skill
library** at [`.claude/skills/`](../../.claude/skills/) — worked procedures,
each with a real example from this codebase: adding a DAO, writing a Flyway
migration, building an SPI shell, adding a PDL step, authoring a chaos test,
broadcasting an SSE event, writing an operational runbook, managing the GitHub
project board, planning the next sprint. When an agent notices it's doing the
same thing for the third time, it distills the pattern into a new skill file,
and the next invocation references it instead of re-deriving it. This is how
the dataplane gets *smarter* over time without the loop code changing at all.

The skill library and the agent roster together are the "what and how"; the
constitution and design docs are the "why and when." The agents read both on
every task — see [method-and-discipline.md](method-and-discipline.md) for how
that reading list keeps the output at a consistent bar.
