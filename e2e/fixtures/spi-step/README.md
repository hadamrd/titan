# titan-e2e-spi-step — Tier-2 StepHandler fixture

A minimal, self-contained Maven module that builds a **Tier-2 step jar** for the
Titan E2E harness. It exists to prove one thing end to end: the worker discovers
a `StepHandler` jar from `TITAN_STEPS_DIR` via the Java `ServiceLoader` SPI and
runs it (design/42 §4.2/§4.3).

## What's in it

- `GreetingStepHandler` — a `StepHandler` contributing the step id `e2eSpiGreeting`.
- `GreetingStepHandlerProvider` — the `StepHandlerProvider` returning that handler.
- `META-INF/services/io.adaptiq.titan.worker.step.StepHandlerProvider` —
  the `ServiceLoader` registration.

Its only Titan dependency is `titan-step-api`, `provided` scope — the worker
supplies that on the parent classloader at runtime (design/42 §5.3).

## Build + install into the rig

```sh
# 1. titan-step-api must be in the local ~/.m2 first:
mvn -f titan-step-api/pom.xml install -DskipTests

# 2. build this fixture jar:
mvn -f e2e/fixtures/spi-step/pom.xml package

# 3. copy the jar into the worker's steps dir (bind-mounted into the container):
cp e2e/fixtures/spi-step/target/titan-e2e-spi-step.jar rig/local/worker-steps/
```

`rig/local/worker-steps/` is bind-mounted to `/opt/titan/steps`
(`TITAN_STEPS_DIR`) on the `titan-worker` service. After dropping the jar in,
restart the worker (`pwsh rig/local/dev.ps1` restarts `titan-worker`, or
`docker compose restart titan-worker`); the worker logs an audit line:

```
step discovery: loaded provider class=...GreetingStepHandlerProvider ... steps=[e2eSpiGreeting]
```

The built jar is committed under `rig/local/worker-steps/` so the rig is
reproducible without a build step; rebuild + recopy only when this source changes.
