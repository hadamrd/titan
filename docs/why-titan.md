# Why Titan

Most CI engines fuse two jobs that want opposite properties: turning a pipeline
definition into a plan, and running that plan. Titan splits them. The result is
a control plane that holds **all** execution state in PostgreSQL, never runs
user code on the controller, and recovers by reconciliation instead of resume.

This page is the honest version: each design bet, what it actually buys you, and
where other tools are genuinely stronger. Every claim here is backed by the
[architecture docs](architecture/) and the [ADRs](decisions/).

## The design bets

### 1. Declarative YAML compiled to a validated static DAG

A Titan pipeline is plain YAML. It is parsed and **synthesised** into a
`PipelineModel`, then **baked** into immutable `flow_nodes` rows — a static
directed acyclic graph — before a single step runs. Cycles, missing
dependencies, duplicate ids and self-dependencies are rejected at bake time.

**What it buys you:** a bad pipeline fails in seconds, before the build, not 40
minutes into a run. The graph the engine executes is fully known up front — no
shape-shifting plan, nothing to reconstruct after a restart.

> See: [architecture/synthesis.md](architecture/synthesis.md),
> [ADR-0010](decisions/ADR-0010-fan-out-at-parse-time.md).

### 2. The controller never runs arbitrary code

Synthesis runs as the build's **first task, on a worker**, like any other unit
of work. The controller only orchestrates: it parses nothing user-authored and
executes nothing user-authored. There is no scripting engine, no sandbox to
escape, and no pipeline program suspended in the controller's heap waiting to be
resumed.

**What it buys you:** no script-security tar-pit, no "the running pipeline state
won't serialize" class of failures, and a controller you can kill and restart at
any instant without losing in-flight builds — because it was never holding the
program.

> See: [architecture/overview.md](architecture/overview.md),
> [architecture/synthesis.md](architecture/synthesis.md).

### 3. Reconcile, not resume — durable Postgres state

A build's entire execution state is four durable facts in PostgreSQL: the
immutable baked DAG, per-node progress, in-flight work + leases, and step
outputs. `TitanOrchestrator.advance()` is a pure function of those rows. Nothing
in any process heap is authoritative.

**What it buys you:** recovery is the Kubernetes-controller model — observe
desired vs actual, act, repeat. Kill any process; a survivor (or the restarted
one) reads the database and continues. There is no heap snapshot to corrupt and
no event history to replay (and therefore no replay-determinism constraint on
pipeline code).

> See: [architecture/recovery-and-failure.md](architecture/recovery-and-failure.md),
> [ADR-0001](decisions/ADR-0001-database-backed-engine-state.md),
> [ADR-0002](decisions/ADR-0002-reconcile-not-resume.md),
> [ADR-0003](decisions/ADR-0003-no-event-history-replay.md).

### 4. Pull-based workers over a `SKIP LOCKED` queue

`task_queue` is the only coordination channel. The controller never pushes to a
worker. Both sides **pull**: a claim is `SELECT … FOR UPDATE SKIP LOCKED` that
stamps a `claim_token` lease, commits immediately, and runs the work outside the
transaction. Completion is rejected if the token no longer matches; a dead
claimant's lease is reclaimed by a visibility-timeout reaper.

**What it buys you:** add a worker by giving it a connection string — no
inbound ports, no controller registration handshake, no service mesh. Two
workers never run the same task; a zombie worker can never double-write a
completion. Scale horizontally off one queue.

> See: [architecture/execution-and-queue.md](architecture/execution-and-queue.md),
> [ADR-0004](decisions/ADR-0004-pull-based-workers.md),
> [ADR-0005](decisions/ADR-0005-at-least-once-idempotent-steps.md).

### 5. A strict, typed PDL with a generated schema

The grammar (`TitanGrammar`) is declared once as typed data; the published JSON
Schema is a **generated projection** of it, not hand-authored. The parser reads
every value through a grammar-typed accessor: a type mismatch is a located,
value-echoing error, never a silent coercion. `requiresApproval: "yes"` is
rejected, not quietly read as `true`.

**What it buys you:** the editor's schema and the parser agree by construction —
no silent drift where the tooling lies about a key. Fat-fingered values fail
loudly with a clear message instead of executing something you never wrote.

> See: [architecture/synthesis.md](architecture/synthesis.md),
> [ADR-0008](decisions/ADR-0008-single-source-grammar.md),
> [ADR-0009](decisions/ADR-0009-strict-typed-parser.md),
> [ADR-0011](decisions/ADR-0011-discriminated-union-config.md).

### 6. ServiceLoader SPIs for every extension point

Steps, triggers, artifact stores, secrets backends and KEK providers are all
plain-Java interfaces discovered via the JDK `ServiceLoader`. A built-in default
ships for each; a third-party backend is a self-contained module dropped on the
classpath — no annotation processor, no plugin runtime, no core change.

**What it buys you:** extend Titan without forking it. First-party S3/Nexus
artifact stores and an Infisical key provider prove the SPI is real, not
aspirational.

> See: [ADR-0012](decisions/ADR-0012-serviceloader-spis.md).

### 7. API-first auth: OIDC + PKCE for humans, PATs for machines

Humans authenticate via OIDC Authorization Code + PKCE against an external IdP;
machines use Personal Access Tokens with per-PAT scopes. No session cookies, no
API password auth. Every endpoint accepts both paths uniformly.

**What it buys you:** identity, SSO and user lifecycle are delegated to your
IdP — Titan stores roles and grants, not passwords. The browser never holds a
long-lived secret; one endpoint serves both an operator and a scripted caller,
so there is no parallel auth surface to drift.

> See: [ADR-0015](decisions/ADR-0015-oidc-pkce-humans-pat-machines.md),
> [ADR-0016](decisions/ADR-0016-envelope-encrypted-secrets.md).

### 8. The UI is the product, not an afterthought

`titan-ui` is a standalone React 19 + Vite + TypeScript (strict) SPA on the
TanStack stack, consuming the server's REST/SSE API — a dense, calm daily-driver
instrument for on-call SREs, with live log streaming over SSE.

**What it buys you:** a real operator console (overview, queue, builds, build
detail with logs + flow nodes + artifacts, workers, pipelines) instead of a
server-rendered admin page.

> See: [ADR-0017](decisions/ADR-0017-react-vite-tanstack-ui.md).

## Honest comparison

This table is high-level and fair. Where Titan is younger or narrower, it says
so. Competitor cells are kept defensible and general; for exact behaviour of any
other tool, consult its own docs.

| Dimension | **Titan** | GitHub Actions | GitLab CI | Buildkite |
|---|---|---|---|---|
| **Where execution state lives** | PostgreSQL — the single source of truth; processes are stateless | GitHub-hosted service state | GitLab service DB | Buildkite-hosted control plane |
| **Recovery model** | Reconcile against DB rows; kill any process and continue (no resume) | Managed by the platform | Managed by the platform | Managed by the platform |
| **What executes the pipeline definition** | Controller runs **no** user code; synthesis runs on a worker → static DAG | Workflow YAML interpreted by the runner | YAML config interpreted by runners | YAML/dynamic pipeline uploaded by agents |
| **Worker → control-plane coupling** | Pull-based; worker needs only a DB connection string (no inbound ports) | Runners poll GitHub | Runners poll GitLab | Agents poll Buildkite |
| **Self-hosting** | Fully self-hosted: your Postgres, your IdP, your workers | Self-hosted runners; control plane is GitHub's | Self-managed or SaaS | Hybrid: self-hosted agents, SaaS control plane |
| **Pipeline-definition strictness** | Strict typed parser; schema generated from one grammar source; no value coercion | YAML schema, generally lenient | YAML schema | YAML schema |
| **Extensibility** | ServiceLoader SPIs (steps, triggers, artifacts, secrets, KEK) — jars on classpath | Reusable Actions marketplace (huge) | Components/templates + integrations | Plugins + marketplace |
| **Auth** | OIDC+PKCE (humans) / PATs with scopes (machines); no API passwords | GitHub identity / OIDC | GitLab identity / OIDC | Buildkite identity / SSO |
| **Operator UI** | First-party React/TanStack SPA; live SSE logs | GitHub web UI (mature) | GitLab web UI (mature) | Buildkite web UI (polished) |
| **Maturity / ecosystem** | **Young** (1.0.0-rc1); small ecosystem; single-tenant today | Very mature, vast ecosystem | Mature | Mature |

### Where the others are genuinely stronger

- **Ecosystem & maturity.** GitHub Actions and GitLab have years of
  production hardening and enormous libraries of reusable actions/components.
  Titan's SPI model is real but its catalog is small.
- **Zero-ops SaaS.** GitHub Actions and Buildkite hand you a managed control
  plane. Titan is something you run — you operate the Postgres and the IdP.
- **Scope.** Titan is **single-tenant** today, with no plugin marketplace UI and
  a narrower trigger set (GitHub + Bitbucket webhooks, cron). See the
  [README's "out of scope"](../README.md) list for the precise boundary.

## The one-line version

> Titan keeps the pipeline as **data**, the state in **Postgres**, and the
> controller **dumb** — so it scales horizontally, recovers by reconciliation,
> and never runs your code where it can't survive a restart.
