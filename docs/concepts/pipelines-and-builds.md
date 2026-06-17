# Pipelines and builds

A pipeline is a static DAG declared in YAML; a build is one run of that DAG.

## The one rule

> The controller never executes Turing-complete user code.

The controller deserialises YAML and evaluates a small, non-Turing-complete
expression language (for `when:` conditions). That is all it runs. Every line
of real work — every shell command, every test, every deploy — runs on a
worker, as an ordinary process, start to finish.

Everything else in Titan is downstream of this rule. It is what makes the
engine stateless, multi-controller, and free of the script-security and
restart-survival problems that come from running orchestration logic as live,
suspendable code on the controller.

## A pipeline is data, not a program

A Titan pipeline is a declarative document, not a script. The body lives at
the document root:

```yaml
agent: linux

stages:
  - stage: Build
    steps:
      - sh: ./gradlew build

  - stage: Test
    when: "params.runTests == true"
    dependsOn: [Build]
    steps:
      - sh: ./gradlew test

  - stage: Package
    dependsOn: [Test]
    steps:
      - sh: ./gradlew jar
```

Stages declare their dependencies with `dependsOn`; the dependency edges are
the DAG. A stage runs once all of its dependencies have succeeded. Stages with
no path between them run concurrently — concurrency is expressed by the graph,
not by a `parallel` wrapper.

Because the definition is data, it is diffable, lintable, and what-you-see-is-
what-you-run: the pipeline you read is the graph that executes. That property
answers the most common CI question — "why did (or didn't) stage X run?" — by
inspection.

### Conditions

`when:` skips a stage based on a condition. The condition is a restricted
expression — boolean, comparison, string and arithmetic operators over
`params` — not arbitrary code. It is evaluated against the build's parameters
when the graph is constructed, so a skipped stage is simply absent from the
DAG.

## The DAG is fixed before the build runs

The graph is frozen before any step executes. There is no runtime branching on
a step's result — you cannot inspect the output of stage A and decide at
runtime whether to add stage B. The graph cannot grow a node mid-run.

What you can do instead:

- **Conditions** (`when:`) — include or exclude a stage based on parameters
  known up front.
- **Fan-out** — repeat a stage over a list, expanded into sibling nodes before
  the run.

This is a deliberate trade. Titan is strictly less expressive than an
imperative pipeline language, and in exchange the engine is small: there is no
program counter to checkpoint, no replay machinery, no determinism checker.

## Computed pipelines: synthesis

Some pipelines are too large or too repetitive to hand-write. For these, the
pipeline definition can be *generated* by a synthesis program whose return
value is the same pipeline model that plain YAML produces. Synthesis is real
code — loops, helpers, abstraction — so it can derive structure from a module
graph, a service catalog, or a parameter cube.

The key constraint preserves the one rule: synthesis runs as the build's first
task, **on a worker**, not on the controller. It runs to completion and emits a
finished, static graph; the controller then consumes that graph exactly as it
consumes parsed YAML. Plain YAML is just the trivial synthesis program — the
identity function.

Synthesis may branch on anything known before the build (parameters, files in
the repo). It cannot branch on a step's runtime result, because that result
does not exist yet — that is what conditions and DAG edges are for, in the
emitted graph.

## A build

A build is one execution of a pipeline. It is a row in Postgres
(`titan.builds`) with a sequential build number per job, a status, the
parameters it was given, and how it was triggered.

A build moves through a small lifecycle:

1. **Synthesize** — a worker runs the synthesis program (or the YAML identity
   case) and produces the pipeline model.
2. **Bake** — the controller writes the model once to the build row
   (`pipeline_model_json`), immutable for the life of the build, and
   materialises it into `flow_nodes` rows — one per stage and step.
3. **Advance** — the controller walks the DAG: a node whose dependencies have
   all succeeded becomes eligible, and leaf steps are dispatched to workers as
   tasks.

Build status is one of `QUEUED`, `RUNNING`, `SUCCESS`, `FAILED`, `ABORTED`, or
`UNSTABLE`. Each node in the graph carries its own status — `PENDING`,
`QUEUED`, `RUNNING`, `SUCCESS`, `FAILED`, `ABORTED`, or `SKIPPED` — so the
state of a build is fully visible at the granularity of individual steps.

## Step outputs

Steps pass data forward through an immutable, producer-keyed store, not a
shared mutable scratchpad. A step publishes a small output (an id, a version, a
flag); a downstream step reads it. Outputs are written in the same update that
completes the producing node, so there is no torn read and no race between
parallel branches. Large data (artifacts, archives) goes to artifact storage —
only its handle travels through the output store.
