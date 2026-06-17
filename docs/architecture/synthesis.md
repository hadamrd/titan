# Synthesis

Titan separates turning a pipeline definition into a plan (synthesis) from running that plan (execution) — and keeps the grammar and its published schema in lockstep by generating one from the other.

## The reframe — synthesis is not execution

A pipeline system does two jobs that want opposite properties:

| Phase | Job | Wants to be |
|---|---|---|
| **Synthesis** | turn intent into a plan | flexible — could be loops, helpers, abstraction |
| **Execution** | run the plan | dumb — a static graph, stateless, restartable |

Engines that fuse them — running pipeline-construction logic *during* the build,
on the controller — inherit every pathology of that fusion: builds that do not
survive a controller restart, script-security tar-pits, in-memory program state
to snapshot. Titan un-fuses the two phases.

A Titan pipeline definition is the *output* of synthesis. Plain YAML is the
trivial synthesis: `parse(text) → PipelineModel`. Whatever produces it, the
result is data — a static DAG. Execution consumes data and runs nothing the DAG
does not already contain.

## Synthesis is a task

The move that makes this native: synthesis does not run on the controller. It
runs as the build's first task, on a worker, like every other unit of work.

```
build scheduled
  → SYNTHESIZE   worker parses the definition → emits a PipelineModel
  → BAKE         controller turns the model into flow_nodes        (static DAG)
  → ADVANCE      the controller reconciles the DAG; workers run nodes
```

This is not even a new storage seam: `builds.pipeline_model_json` already holds
the materialised model. The `SYNTHESIZE` task writes it; `BakeHandler` consumes
it. Synthesis is not agent-pinned — any worker may run it, so it polls the shared
`synthesis` queue.

What this buys:

- **The controller never runs user code.** Synthesis is a worker process. The
  invariant holds for pipeline *construction*, not only step bodies.
- **The DAG stays fully static.** Synthesis completes before bake; execution only
  ever sees a finished graph.
- **Crash-safety is free.** Synthesis is a task; re-running a pure synthesis
  yields the identical model, so ordinary task re-delivery covers it.
- **A bad pipeline fails in seconds, before the build** — at synthesis, with a
  `SYNTHESIS` failure category, not 40 minutes into execution.

The handlers: `SynthesizeHandler` (controller-side dispatch/poll) and the
worker-side synthesis task that runs `TitanYamlParser` to produce a
`PipelineModel`. `BakeHandler` then materialises that model into `flow_nodes`,
validates the DAG, and enqueues the first `ADVANCE`.

> Current synthesis is YAML-only — the identity synthesis. A code/builder
> front-end (a synthesis program whose return value is a `PipelineModel`) is the
> architecture's intended generalisation, but it is not part of the engine
> today.

## The one irreducible boundary

Synthesis runs *before* the build. It may branch on anything known then — the
pipeline's parameters, files in the repo, a checked-in catalog. It cannot branch
on a step's *runtime result*, because that result does not exist yet. Runtime
branching is what `when`, `precondition`, gates, and DAG edges are for — in the
emitted graph. Synthesis names which side of that line each piece of logic
belongs on; it does not move the line.

## Grammar and schema co-evolution

The pipeline grammar (`titan-pipeline-model`) is declared once, as typed data,
and the published JSON Schema is a generated projection of it — never authored by
hand.

The problem this solves: the grammar would otherwise live in two places that must
agree by hand — the parser's key sets and the JSON Schema the editor/tooling
contract relies on. Add a key and forget the schema, and nothing fails: the
parser still accepts the YAML, the editor just lies about it. A silent drift
surface that grows with every grammar change.

The design:

- **`TitanGrammar` is the single source.** In
  `io.adaptiq.titan.flow.parser.grammar`, it declares every grammar context —
  root, stage skeleton, gate, precondition, parameter, script — as an ordered
  `List<GrammarKey>`. Each `GrammarKey` carries its name, whether it is required,
  a human description, and a JSON Schema fragment for its value.
- **Scopes own their own schema.** A `StepScope` declares its key's description
  and value schema next to its parse code, so one scope class is one change to
  both parser and schema, by construction.
- **The parser projects, it does not own.** `TitanYamlParser`'s key-set constants
  derive from `TitanGrammar`. Parse *logic* — the same key sets, the same
  unknown-key rejection, the same errors — is untouched; only the source of the
  sets moves.
- **`TitanSchemaGenerator` emits the canonical schema.** It walks `TitanGrammar`
  plus the scopes and writes `titan-pipeline.schema.json`
  (`titan-pipeline-model/src/main/resources/.../schemas/`). The generated file is
  the canonical one; it stays committed so it is reviewable in diffs and
  greppable.
- **A drift guard, not a build coupling.** `TitanSchemaGenerationTest` regenerates
  the schema in memory and asserts it equals the committed resource. Drift fails
  the build with a one-line fix: re-run the generator. Generation is *not* wired
  into the build phase — the test is the enforcement.

This takes the single-source principle without the reflection mechanism some
config binders use: the source of truth is an explicit, typed, intentional
grammar model, not whatever happens to be on the classpath. The per-step
descriptor catalog stays deliberately open (the `step` definition keeps a curated
hint list with `additionalProperties` open) because the step SPI is extensible.

To add a step or scope, see the `add-pdl-step` workflow: it is ~3 files and never
touches the grammar core or schema by hand.
