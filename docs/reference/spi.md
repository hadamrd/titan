# Extension SPIs

The interfaces for extending Titan — steps, triggers, secrets backends, and artifact stores — and how the engine discovers implementations.

Discovery is uniform: the JDK `java.util.ServiceLoader` reads `META-INF/services/<fully-qualified-interface-name>` files. There is no annotation scanning. Built-in implementations dogfood the same SPI path — there is no privileged code path. When more than one implementation is discovered, selection is by a configured name/kind or by a typed discriminator (noted per SPI). Two exceptions use Quarkus CDI rather than ServiceLoader, called out below.

## Step SPI

**Module:** `titan-step-api` · package `io.adaptiq.titan.worker.step`

An extension author registers a `StepHandlerProvider`, which returns a family of `StepHandler`s. Discovery is on the *provider* (one jar may ship many steps, and handlers need not have no-arg constructors).

```java
public interface StepHandlerProvider {
  List<StepHandler> handlers(StepHandlerContext context);
  String describe();                          // logged at startup
  default int apiVersion() { return StepApi.VERSION; }   // version gate (currently 1)
}

public interface StepHandler {
  String descriptorId();        // the step type, e.g. "sh"
  StepDescriptor descriptor();  // declarative metadata
  StepResult execute(StepRequest request) throws Exception;
}
```

The `StepDescriptor` *is* the step's grammar: `(descriptorId, displayName, help, List<ParamSpec> parameters, scalarShorthandKey)`. `ParamSpec` is `(name, type, required, help, choices)`. `scalarShorthandKey` names the argument a bare scalar folds into (e.g. `sh: echo hi`). The descriptor drives the JSON Schema, the authoring palette, and worker-side argument validation.

`StepRequest` (immutable input) carries `descriptorId`, `arguments`, `workDir`, `env`, `buildId`, `nodeId`, `image`, plus collaborators:

| Type | Role |
|---|---|
| `StepExecutor` | runs commands in the execution environment — `int run(command, workDir, env, LogSink)` |
| `LogSink` | `void line(String stream, String text)` — `stream` ∈ stdout/stderr/system |
| `OutputSink` | `void put(String key, Object value)` — publish small JSON outputs |
| `ArtifactSink` | `void archive(String name, Path file, boolean fingerprint)` |
| `TestResultSink` | sink for parsed test cases |

`StepResult` is `(Status status, Integer exitCode, String message)` with `Status` ∈ `SUCCESS`/`FAILED`. `StepHandlerContext` handed to the provider is deliberately narrow: `(libraryCacheRoot, apiVersion, logger)` — no DB, no controller channel, no credential store.

**Version handshake:** `StepApi.VERSION` is `1`. The worker rejects providers above the floor or below the current version and logs a warning; the step then fails cleanly as an unknown step.

**Discovery:** `META-INF/services/io.adaptiq.titan.worker.step.StepHandlerProvider`. The worker also scans `TITAN_STEPS_DIR` (default `./steps/`) for external jars (loaded in child classloaders) and `*.titanstep.yaml` Tier-1 container-step manifests. Built-ins are registered through `SocleStepHandlerProvider`.

### Execution augmenters

`ExecutionAugmenter` (same package, same ServiceLoader discovery) decorates step execution — adding environment, materialising files, wrapping the command, registering cleanup. All discovered augmenters run, ordered by `order()`. The built-ins are credentials unsealing and ssh-agent setup.

```java
public interface ExecutionAugmenter {
  void augment(StepExecutionContext ctx);
  default int order() { return 0; }   // lower runs first
}
```

## Trigger SPI

**Module:** `titan-trigger-api` · package `io.adaptiq.titan.trigger`

A trigger type is a pair: a `Trigger` (config + evaluation logic) and a `TriggerDescriptor` (the ServiceLoader-discovered factory / codec).

```java
public abstract class Trigger {
  protected Trigger(String id);                       // blank id → minted UUID
  public abstract String getType();                   // JSON discriminator, e.g. "cron"
  public abstract TriggerOutcome evaluate(TriggerContext ctx);   // pure function of config + context
  public abstract void writeState(ObjectNode node);   // serialize own fields
}

public abstract class TriggerDescriptor {
  public static List<TriggerDescriptor> all();        // ServiceLoader.load(...)
  public abstract String triggerType();               // == Trigger.getType()
  public abstract Trigger readState(String id, JsonNode node);   // inverse of writeState
}
```

Supporting types: `TriggerContext` (`now`, `lastFiredAt`, `hashSeed`, `event`); `TriggerEvent` (`kind`, `key`, `attributes`, `instant`); `TriggerOutcome` (`FIRE` / `SKIP` / `DEFER`); `TriggerCodec` (JSON ⇄ Trigger, building its reader registry from `TriggerDescriptor.all()` — unknown types are skipped with a warning); `SchedulerConfig` (engine poll policy: `pollIntervalSeconds`, `catchUpHours`, `triggerEvaluationTimeoutSeconds`, `paused`).

**Adding a type:** subclass `Trigger`, add a `TriggerDescriptor`, and list the descriptor in `META-INF/services/io.adaptiq.titan.trigger.TriggerDescriptor`. The built-ins are `cron`, `github`, `gitlab`, `bitbucket`. No core change is needed.

**CDI-based (not ServiceLoader):**
- `TriggerSource` (`io.adaptiq.titan.trigger.engine`) — push ingress (Kafka, k8s-watch, AMQP) with `start()` / `stop()`, discovered as Quarkus CDI beans. HTTP webhooks are not `TriggerSource`s.
- `StatusReporter` — posts build state back to SCM (`triggerTypePrefix()`, `report(BuildStatusEvent)`), discovered as CDI beans observing build-state events.

## Secrets SPIs

Three distinct interfaces, all in extensions that may implement one or more.

### `SecretsBackend` — the pluggable secrets store

**Module:** `titan-secrets-api` · package `io.adaptiq.titan.credentials`

```java
public interface SecretsBackend {
  String name();                                              // selector, e.g. "db-envelope"
  Optional<Credential> findById(long id);
  Optional<Credential> findByScopeAndKey(String scope, String key);
  List<Credential> listAll();
  List<Credential> listByScope(String scope);
  Credential create(NewCredentialRequest request);
  Credential update(long id, CredentialUpdate update);
  void delete(long id);                                       // idempotent
  Optional<String> resolvePlaintext(String scope, String key);
  int rotateKek();
}
```

**Discovery:** ServiceLoader. **Selection:** `TITAN_SECRETS_BACKEND` (default `db-envelope`) matches a backend's `name()`; exactly one is active per controller.

### `CredentialKeyProvider` — the KEK source

**Module:** `titan-pipeline-model` · package `io.adaptiq.titan.flow.crypto`

Supplies the 32-byte AES-256 key-encryption key for envelope encryption.

```java
public interface CredentialKeyProvider {
  byte[] credentialKey();                       // null → fail closed
  String describe();
  default int credentialKeyVersion() { return 1; }
  static CredentialKeyProvider active();        // ServiceLoader chain + env fallback
}
```

**Discovery:** ServiceLoader. `active()` builds a chain (the first link returning a non-null key wins) and always appends the built-in `EnvCredentialKeyProvider` as a last resort.

### `SecretProvider` — synthesis-time secret resolution

**Module:** `titan-pipeline-model` · package `io.adaptiq.titan.flow.crypto`

Resolves secrets during pipeline synthesis (e.g. for `library()` calls).

```java
public interface SecretProvider {
  String secret(String name);   // null → fail synthesis closed
  String describe();
  static SecretProvider active();  // first discovered, else NoopSecretProvider
}
```

**Discovery:** ServiceLoader; `active()` is the first discovered implementation, else the built-in no-op.

The Infisical extension implements all three; Vault provides a `SecretsBackend`.

## ArtifactStore SPI

**Module:** `titan-pipeline-model` · package `io.adaptiq.titan.flow.artifact`

An author implements a store and a provider (the discovered factory).

```java
public interface ArtifactStore extends Closeable {
  String kind();                                                       // "fs"/"s3"/"nexus" — matches the DB row
  StoredBlob put(ArtifactKey key, InputStream content) throws IOException;   // computes SHA-256
  Optional<InputStream> open(String storageRef) throws IOException;
  boolean delete(String storageRef) throws IOException;
  void pruneStashes(long buildId) throws IOException;
  void deleteBuild(long buildId) throws IOException;
}

public interface ArtifactStoreProvider {
  String kind();                                   // must equal the store's kind()
  ArtifactStore create(Map<String,String> config); // IllegalArgumentException on bad config
}
```

**Discovery and selection:** `ArtifactStoreRegistry` is the single entry point for both worker and controller. `resolve(kind, config)` iterates ServiceLoader providers, matches by `kind()`, and calls `create()`, throwing if no provider declares that kind. It loads providers with an explicit SPI classloader so bundled built-ins are always found on a hermetic worker. The active kind comes from configuration (`TITAN_ARTIFACT_STORE`) and the stored `storage` column, not a per-store discriminator scan. The built-in store is `fs` (filesystem); `s3` and `nexus` ship as extensions.

## Summary

| SPI | Author implements | Discovery | Selection |
|---|---|---|---|
| Step | `StepHandlerProvider` → `StepHandler` | ServiceLoader (+ `TITAN_STEPS_DIR`) | version gate; type by `descriptorId()` |
| Execution augmenter | `ExecutionAugmenter` | ServiceLoader | all run, ordered by `order()` |
| Trigger | `Trigger` + `TriggerDescriptor` | ServiceLoader | by `type` discriminator |
| Trigger push-source | `TriggerSource` | Quarkus CDI | all started |
| SCM status reporter | `StatusReporter` | Quarkus CDI | by `triggerTypePrefix()` |
| Secrets store | `SecretsBackend` | ServiceLoader | `TITAN_SECRETS_BACKEND` vs `name()` |
| KEK source | `CredentialKeyProvider` | ServiceLoader | chain, first non-null wins; env fallback |
| Synthesis secret | `SecretProvider` | ServiceLoader | first found, else no-op |
| Artifact store | `ArtifactStoreProvider` → `ArtifactStore` | ServiceLoader | by `kind()` via the registry |

See [steps.md](steps.md) for the built-in step library and [configuration.md](configuration.md) for the environment variables that select backends.
