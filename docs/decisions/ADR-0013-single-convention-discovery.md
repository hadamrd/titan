# ADR-0013: Single-convention discovery; the file IS the pipeline

**Status:** Accepted

**Context** — Early Titan let pipelines live at multiple paths (root files, alternate dirs) plus a manual "add pipeline" UI, and required a separate "Enable" click before a discovered file became runnable. That produced a large documentation surface, fuzzy discovery semantics, and the recurring user question "I added the YAML, why no build?". Every leader in the space (GitHub Actions, GitLab CI, CircleCI) made the opposite call.

**Decision** — Titan pipelines live at exactly one path: `.titan/pipelines/*.yml` (and `.yaml`). No alternate paths, no root-level files, no manual-add UI. A file that parses cleanly on the default branch IS a Titan job the moment the scanner sees it; the job disappears when the file disappears. There is no Enable step.

**Consequences**
- The path is the contract: every pipeline's location is predictable, and discovery is one rule, not a resolution table.
- Onboarding cost is a one-time `git mv` into the convention — bounded and the same trade GitHub Actions made.
- A brand-new repo builds on first push; a deleted file stops being a job, instead of lingering and still firing.
- No per-job path state to store or reconcile.
