# Concepts

The mental model behind Titan — read these before the reference docs.

Titan is a CI/CD pipeline execution engine. You describe a pipeline as
declarative YAML in your repository; Titan turns each run into a static graph
of work, dispatches that work to workers, and tracks every step in Postgres.

Three pages cover the model end to end:

- [Pipelines and builds](pipelines-and-builds.md) — what a pipeline is (a
  static DAG declared in YAML), what a build is (one run of that DAG), and the
  one rule the whole design hangs off.
- [The engine](the-engine.md) — server vs worker, the pull-based task queue,
  durable state in Postgres, and why recovery is reconciliation rather than
  resume.
- [Triggers and discovery](triggers-and-discovery.md) — how builds get
  started: SCM webhooks, the `.titan/pipelines/` discovery convention, and the
  one-build-per-push policy.

For deep internals (schema, orchestrator, queue mechanics) see the
[architecture docs](../architecture/).
