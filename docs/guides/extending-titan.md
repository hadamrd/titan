# Extending Titan

How to ship a third-party extension — a step, trigger, artifact backend, or
secrets provider — without forking the engine.

Titan is extensible through service-provider interfaces (SPIs). There are two
delivery mechanisms:

- **Steps** can be dropped into a watched directory at runtime — a declarative
  manifest or a jar — with no engine change. This is the common case.
- **All other SPIs** (artifact backends, key/secret providers, trigger sources)
  are discovered from the JVM classpath. You package the jar onto the
  server/worker classpath; there is no separate watched directory for them yet.

For the exact interface signatures, see [the SPI reference](../reference/spi.md).

## Extension points

| Extends | SPI | Module |
|---|---|---|
| A pipeline step | `StepHandler` (+ `StepHandlerProvider`) | `titan-step-api` |
| A build trigger | `Trigger` / `TriggerDescriptor` | `titan-trigger-api` |
| A push listener | `TriggerSource` | `titan-trigger-api` |
| An artifact backend | `ArtifactStore` / `ArtifactStoreProvider` | `titan-pipeline-model` |
| The credential KEK source | `CredentialKeyProvider` | `titan-pipeline-model` |
| The secrets store | `SecretsBackend` | `titan-secrets-api` |

## Shipping a step extension

Steps are the only SPI with a runtime drop-in directory: `TITAN_STEPS_DIR`
(default `./steps/`), scanned by the worker at boot. Two tiers:

### Tier 1 — declarative manifest (the default)

A `*.titanstep.yaml` file describes a container or composite step — pure data,
no jar, no JVM cost. The step runs as an OS process.

```yaml
step: trivy-scan                       # the descriptorId (YAML keyword)
displayName: Trivy scan
help: Scans an image for vulnerabilities.
image: aquasec/trivy:latest
command: ['trivy', 'image', '${{ args.image }}']
params:
  - { name: image, type: string, required: true }
```

Drop it in `TITAN_STEPS_DIR` and restart the worker.

### Tier 2 — SPI jar

For logic-heavy or proprietary steps that run in-process. Implement
`StepHandler` and `StepHandlerProvider` (see [Writing a step](writing-a-step.md)
for the handler shape), then package a jar:

1. Depend on `titan-step-api` only (as `compileOnly`/`provided`); bundle your
   own transitive dependencies.
2. Add a `ServiceLoader` entry —
   `META-INF/services/io.adaptiq.titan.worker.step.StepHandlerProvider`
   containing the fully-qualified name of your provider class.
3. Drop the jar in `TITAN_STEPS_DIR`.

Each jar loads in its own isolated classloader, so two extensions with
conflicting dependencies do not collide.

### Discovery safety

At boot the worker logs every provider it loads and enforces:

- an **API-version gate** — a provider built against a newer or below-floor step
  API is refused;
- **fail-fast on a duplicate `descriptorId`** — the worker refuses to start;
- a **reserved-namespace gate** — a drop-in jar that bundles any class under
  `io.adaptiq.titan.` is refused. Your code must live in its own package
  namespace.

A throwing provider is skipped with a warning rather than crashing the worker.

## Shipping an artifact, secrets, or trigger extension

These follow the same module shape but are discovered from the classpath, not
from `TITAN_STEPS_DIR`. The pattern (using an artifact backend as the example):

1. Create a Gradle module depending on the SPI's home module (here
   `titan-pipeline-model`).
2. Implement the provider and the store (`kind()` + `create()`, then the store
   methods).
3. Add the `ServiceLoader` entry —
   `META-INF/services/io.adaptiq.titan.flow.artifact.ArtifactStoreProvider` with
   one line naming your provider class.
4. Place the jar on the server/worker classpath at package time.

The first-party extensions under `titan-extensions/` are the canonical worked
examples: `titan-artifact-s3` and `titan-artifact-nexus` (one provider + one
store class each), and `titan-keyprovider-infisical` (a single jar shipping
three services files — a key provider, a secret provider, and a secrets
backend). These ride on the worker classpath as bundled dependencies; an
out-of-tree extension uses the identical layout.

A backend is then selected at runtime by its kind — e.g.
`TITAN_ARTIFACT_STORE=<kind>` for artifact backends (see [Artifacts](artifacts.md))
and `TITAN_SECRETS_BACKEND=<kind>` for secrets backends (see
[Credentials & secrets](credentials.md)).

## See also

- [Writing a step](writing-a-step.md) — adding a built-in step to the engine.
- [The SPI reference](../reference/spi.md) — full interface contracts.
