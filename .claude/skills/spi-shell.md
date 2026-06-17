---
name: spi-shell
applies-to: engine-agent
related-design-doc: docs/decisions/ADR-0012-serviceloader-spis.md
---

## What this is

Adding a new pluggable extension point to Titan. Every extension point is a
plain-Java interface (a **provider**) discovered at runtime via the JDK
`ServiceLoader` (`META-INF/services`) — no annotation processor, no plugin
runtime, no global singleton (ADR-0012). A built-in default ships in-tree; a
third-party backend is a self-contained module dropped on the classpath.

The current SPIs are `StepHandlerProvider` and `ExecutionAugmenter` (worker),
`TriggerDescriptor`, `ArtifactStoreProvider`, `SecretsBackend`,
`CredentialKeyProvider`, and `SecretProvider`.

## When to use

- A new pluggable mechanism is needed (artifact backend, secrets backend, KEK
  provider, trigger source, step handler family, ...).
- The mechanism has a built-in default + a clear extensibility story so a user
  can add their own backend without a core change.

## Procedure

### 1. Choose the package + the discriminator

Put the provider interface beside its consumer in the module that owns the
abstraction (e.g. `io.adaptiq.titan.flow.artifact` for artifact stores,
`io.adaptiq.titan.flow.crypto` for secret/credential providers). The provider
exposes a `kind()` / `type()` discriminator that the config selects and the
runtime matches on.

### 2. Write the provider interface

Pattern (from `ArtifactStoreProvider`):

```java
package io.adaptiq.titan.flow.artifact;

import java.util.Map;

/**
 * Constructs one {@link ArtifactStore} backend from configuration — the
 * discovery seam that makes the backend a deployment choice. Providers are
 * discovered by {@link java.util.ServiceLoader} via a {@code META-INF/services}
 * entry, so a new backend is a self-contained module: one provider
 * implementation plus its services entry, dropped on the classpath. Nothing in
 * Titan's core changes.
 */
public interface ArtifactStoreProvider {

  /** The backend discriminator this provider builds — {@code "fs"}, {@code "s3"}, … */
  String kind();

  /**
   * Build the store from backend-specific configuration.
   *
   * @throws IllegalArgumentException if the configuration is missing or invalid
   */
  ArtifactStore create(Map<String, String> config);
}
```

### 3. Built-in default implementation

Ship one in-tree default (e.g. the filesystem artifact store, the
`db-envelope` secrets backend). It is an ordinary class — no annotation.

```java
public final class FilesystemArtifactStoreProvider implements ArtifactStoreProvider {
  @Override public String kind() { return "fs"; }
  @Override public ArtifactStore create(Map<String, String> config) { ... }
}
```

### 4. Register it via `META-INF/services`

Create (or append to) the services file named after the interface's FQN:

```
src/main/resources/META-INF/services/io.adaptiq.titan.flow.artifact.ArtifactStoreProvider
```

with one fully-qualified implementation class per line:

```
io.adaptiq.titan.flow.artifact.FilesystemArtifactStoreProvider
```

### 5. Discover + select at runtime

A registry walks every provider via `ServiceLoader` and selects by
discriminator. Pattern (from `ArtifactStoreRegistry`):

```java
public ArtifactStore forKind(String kind, Map<String, String> config) {
  for (ArtifactStoreProvider p : ServiceLoader.load(ArtifactStoreProvider.class)) {
    if (kind.equals(p.kind())) {
      return p.create(config);
    }
  }
  throw new IllegalArgumentException("no artifact store provider for kind=" + kind);
}
```

### 6. Boot check

Discovery is classpath-dependent — a misconfigured classpath means a backend
silently isn't loaded. Add/extend a boot check that asserts the expected
providers are present (ADR-0012, "Consequences").

## Worked example

The first-party extension modules prove the SPI is real, each its own module
with one provider + one services file:

- `titan-extensions/titan-artifact-s3` — `S3ArtifactStoreProvider` (`kind = "s3"`).
- `titan-extensions/titan-artifact-nexus` — `NexusArtifactStoreProvider`.
- `titan-extensions/titan-keyprovider-infisical` — `InfisicalSecretProvider`,
  `InfisicalCredentialKeyProvider`, `InfisicalSecretsBackend`.
- `e2e/fixtures/spi-step` — a `StepHandlerProvider` dropped on the worker's
  step classpath, exercised by `e2e/scenarios/spi-step.e2e.yaml`.

## Common variations / gotchas

- **One module, one interface, one services file.** Adding a backend must NOT
  touch the core — if it does, the seam is in the wrong place.
- **`kind()` must be stable.** It is persisted (e.g. `titan.artifact.storage`)
  and selected by config; renaming it is a migration.
- **Worker-side step descriptors are not visible to the controller** — keep the
  controller schema open for unknown step args (ADR-0008).
- **Caching:** `ServiceLoader.load` re-scans per call; cache the registry once
  if the lookup is on a hot path.

## Cross-references

- docs/decisions/ADR-0012-serviceloader-spis.md — the SPI decision
- docs/reference/spi.md — the full SPI reference
- `ArtifactStoreProvider` / `ArtifactStoreRegistry`, `CredentialKeyProvider`,
  `SecretProvider` as in-tree reference implementations
