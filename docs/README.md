# Titan documentation

Titan is a standalone, cloud-native CI/CD pipeline execution engine: a
controller that turns declarative YAML pipelines into a validated static
DAG, and a fleet of pull-based workers that execute it, with all state
held durably in Postgres.

New here? Start with **[Getting started](getting-started.md)**, then read
the [concepts](concepts/).

## Sections

| Section | What's in it |
|---|---|
| [Getting started](getting-started.md) | Install, run the local rig, ship your first pipeline. |
| [Concepts](concepts/) | The mental model: pipelines and builds, the engine, triggers and discovery. |
| [Guides](guides/) | Task-oriented how-tos: writing a step, credentials, artifacts, SCM integration, extending Titan, testing, security. |
| [Reference](reference/) | The PDL grammar, built-in steps, parameters, configuration, database schema, and SPIs. |
| [Architecture](architecture/) | How the engine works internally: the queue, recovery model, and pipeline synthesis. |
| [Operations](operations/) | Deploying and running Titan: runbooks and post-mortems. |
| [Decisions](decisions/) | Architecture Decision Records — why Titan is built the way it is. |
| [Building with AI](building-with-ai/) | How Titan was designed and built by autonomous AI coding loops — the method, the agent roster, and the discipline. |
| [Maintaining the docs](maintaining-docs.md) | The curation rules for this tree — what earns a place, where each kind of doc lives, and the altitude rule. |

## Contributing

See [CONTRIBUTING.md](../CONTRIBUTING.md) for the workflow and
[repo-layout.md](repo-layout.md) for the source-tree map.
