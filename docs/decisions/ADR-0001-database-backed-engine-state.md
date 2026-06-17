# ADR-0001: Database-backed engine state

**Status:** Accepted

**Context** — Filesystem-backed CI engines store a build's authoritative execution state on the controller's local disk (per-build XML files, an in-process heap snapshot). That ties every build to one host, makes "show me X" queries walk the filesystem, and makes state non-recoverable across a controller move. Titan targets stateless, horizontally-scalable controllers and a queryable audit surface.

**Decision** — All durable engine state lives in a relational database: jobs, builds, per-node progress (`flow_nodes`), the task queue, leases, and step outputs. No process holds authoritative execution state in memory.

**Consequences**
- Any controller can be killed and restarted without losing in-flight work — the survivor reads the DB and continues.
- Every operator query ("active builds", "queue depth", audit history) is an indexed SQL query, not a filesystem walk.
- The DB is the single point of contention and the scaling ceiling; hot paths must be indexed and transactions kept short.
- Schema is now a first-class versioned artifact (see ADR-0006).
