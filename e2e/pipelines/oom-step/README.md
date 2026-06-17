# oom-step fixture (#1175)

Drives a **real** out-of-memory kill on the rig and lets
`e2e/specs/golden-path-oom.spec.ts` assert the failure is **OOM-classified at the
build-detail UI layer** — not a bare `exit 137`.

## Shape

| stage | does | expected |
|-------|------|----------|
| `hog` | `python3 -c 'bytearray(512 MiB)'` inside a 64 MiB cgroup | reaped by the OOM-killer → SIGKILL → `exit 137` → step **FAILED**, build **FAILURE** |

512 MiB inside 64 MiB is an ~8x over-commit: the bytearray is zero-filled
eagerly, so the pages become resident and the kernel OOM-killer fires
deterministically.

## Why the UI spec is `pending`

This fixture **asserts** worker-side OOM detection; it does not implement it.
Two engine capabilities (both **#1116**) must ship first:

1. **cgroup enforcement** of `resources.memoryLimitMb` — without a real RSS cap
   the 512 MiB allocation simply succeeds on a multi-GB host and the build goes
   green.
2. **OOM classification** — the worker supervisor inspects cgroup
   `memory.events` / the SIGKILL termination cause and emits a typed
   `StepTerminationReason.OOM` that the orchestrator maps onto the node's
   `failureReason`, surfaced in the UI as `killed: OOM`.

`golden-path-oom.spec.ts` runs live the moment `TITAN_OOM_DETECTION=1` (set once
#1116 ships); until then it self-skips with the exact blocking gap named.

## Sibling

`e2e/scenarios/oom-step.e2e.yaml` is the generic-scenario-runner counterpart —
it asserts the **log surfaces** (`killed: OOM` present, `exit 137 (no reason)`
absent). This fixture + spec add the assertion the scenario runner cannot make:
the **build-detail UI DOM** (`data-status`, the failed-step reason chip).
