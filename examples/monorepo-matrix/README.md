# Monorepo matrix / each fan-out

Builds and tests every package in a monorepo in parallel, then fans back in to
a single `release` stage.

## What it demonstrates

- `each:` — fan a stage over a list of packages (`${each.package}`).
- `matrix:` — fan over the same packages, with a `MATRIX_PACKAGE` env var and
  the `${matrix.package}` token.
- **Fan-in**: a `dependsOn` that names a fanned-out stage resolves to *all* of
  its cells, so `release` waits for every package's build and test.
- `maxParallel` to cap concurrency, `allowEmptyArchive` for optional outputs.

`each` vs `matrix`: use `each` for a single list (no env injected); use
`matrix` when you want the cartesian product of several axes and/or the
auto-injected `MATRIX_<AXIS>` environment variables.

## How to run

Adapt the package list and `make` targets to your repo layout, register the
repo as a pipeline in the Titan UI, and trigger a build. See
[`docs/getting-started.md`](../../docs/getting-started.md) for the local rig.
