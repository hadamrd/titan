# ADR-0012: ServiceLoader SPIs for all extension points

**Status:** Accepted

**Context** — Titan must be extensible — step handlers, trigger sources, artifact backends, secrets backends, KEK providers — without an annotation-driven plugin-manager model, and without coupling the core to any specific third-party tool. The engine is plain Java on Quarkus, not a plugin host.

**Decision** — Every extension point is a plain-Java interface discovered at runtime via the JDK `ServiceLoader` (`META-INF/services`). The current SPIs are `StepHandlerProvider` and `ExecutionAugmenter` (worker), `TriggerDescriptor`, `ArtifactStoreProvider`, `SecretsBackend`, `CredentialKeyProvider`, and `SecretProvider`. A built-in default ships for each (e.g. the filesystem artifact store, `db-envelope` secrets); third-party backends are self-contained modules dropped on the classpath.

**Consequences**
- Adding a backend is one module with one interface implementation and one services file — no core change, no annotation processor, no plugin runtime.
- First-party extensions (S3/Nexus artifact stores, Infisical/Vault secrets) live in their own modules and prove the SPI is real.
- Worker-side step descriptors are not visible to the controller, which keeps the controller schema honestly open for unknown step args (ADR-0008).
- Discovery is classpath-dependent; a misconfigured classpath means a backend silently isn't loaded, so boot checks assert the expected providers are present.
