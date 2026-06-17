# ADR-0014: Quarkus, Gradle, and JDBI as the server stack

**Status:** Accepted

**Context** — As a standalone product, Titan needed to pick its own server runtime, build tool, and persistence access layer from first principles. The goals were fast boot, low reflection overhead, a native-compile path, a modern multi-module build, and direct control over SQL.

**Decision** — The server is built on Quarkus 3 (compile-time CDI, native-image path), the reactor is Gradle 8.x with no Maven anywhere, and persistence uses JDBI over plain SQL rather than a full ORM. An in-process Quarkus Cache (Caffeine) backs three hot-path caches (`pipeline-model`, `job-lookup`, `cron-compile`); there is no Redis.

**Consequences**
- Compile-time CDI and a native path keep startup fast and the runtime reflection surface small.
- JDBI keeps the engine in direct control of its queries and indexes, which matters for the `SKIP LOCKED` queue and other hot paths — at the cost of writing SQL by hand.
- Maven is forbidden engine-wide (no `pom.xml`, `mvnw`, `.mvn/`); a stray Maven artifact is a flagged anti-pattern.
- Caching is in-process and bounded; no external cache to operate. Sealed/credential values are never cached.
