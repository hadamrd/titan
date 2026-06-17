# Python app

A Python service pipeline: a `lint` gate, then a `test` stage that fans out
across a **matrix** of Python versions with `pytest`.

## What it demonstrates

- `matrix:` fan-out — one parallel cell per Python version.
- The per-cell `MATRIX_PYTHON` environment variable (axis name upper-cased)
  and the `${matrix.python}` substitution token.
- `maxParallel` and `fail_fast: false` so every version's result is visible
  in one build.
- `junit:` to publish the per-cell test report.

## How to run

Drop `titan-pipeline.yml` at the root of your Python repo, adapt the install
line to your toolchain (uv / pyenv / tox), register the repo as a pipeline in
the Titan UI, and trigger a build. See
[`docs/getting-started.md`](../../docs/getting-started.md) for the local rig.
