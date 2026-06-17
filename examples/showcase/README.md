# Showcase — the annotated PDL tour

The "everything" pipeline. It exercises most of the Titan grammar in one
heavily-commented file so you can see how the pieces fit together. The shell
commands are illustrative shims — adapt them to a real project — but the
pipeline is **valid against the published schema**.

## What it demonstrates

| Feature | Where |
|---|---|
| `parameters:` (choice + boolean), `${{ params.* }}` | root + `env`, `when:` |
| Pipeline-wide `env:` | root |
| `triggers:` — cron + github webhook | root |
| `matrix:` with `maxParallel` + `fail_fast` | `test` stage |
| `${matrix.<axis>}` substitution + `MATRIX_<AXIS>` env | `test` stage |
| `retry:` with backoff | `test` step |
| `junit:` test report | `test` stage |
| `archiveArtifacts:` with `fingerprint` | `build` stage |
| `gate:` — manual approval node | `deploy-approval` |
| Typed `credentials:` (`usernamePassword`, `string`) | `deploy` stage |
| Stage-level `when:` (CEL) + step-level `when: { branch }` | `deploy` |
| `httpRequest:` smoke check with `validResponseCodes` | `smoke` stage |
| `onFailure:` cleanup handler | `cleanup-on-failure` stage |
| `notify:` terminal webhook | root |

## How to run

This is best read, not run as-is. To try it, replace the `./scripts/*.sh`
shims with your real commands and provision the referenced credential ids
(`registry-creds`, `deploy-token`, etc.) in the Titan credentials store. See
[`docs/reference/pdl.md`](../../docs/reference/pdl.md) for the full grammar and
[`docs/getting-started.md`](../../docs/getting-started.md) for the local rig.
