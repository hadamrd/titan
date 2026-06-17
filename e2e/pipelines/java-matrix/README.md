# java-matrix fixture (issue #1127)

End-to-end fixture proving the `matrix:` PDL scope fans a stage out across a
parameter grid, surfaces every cell to the UI, and rolls up the parent
verdict correctly under `fail_fast: false`.

The matrix is `{jdk: [17, 21], profile: [unit, it]}` = 4 cells. One cell
(`jdk=21, profile=it`) is deliberately rigged to fail so the spec can assert:

- 4 cell stages render with axis-tuple labels.
- 3 cells reach `SUCCESS` (the others), 1 reaches `FAILED`.
- Parent (matrix group) verdict rolls up as `FAILED` overall.
- Per-cell log streams contain the cell's `MATRIX_JDK` / `MATRIX_PROFILE`
  bindings, proving env injection threads to the worker.

Driven by `e2e/specs/matrix-fixture.spec.ts`.
