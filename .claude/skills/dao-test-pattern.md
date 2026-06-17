---
name: dao-test-pattern
applies-to: test-agent
related-design-doc: design/16-execution-plan.md §A.8
---

## What this is

Container-free DAO test pattern: in-memory H2 + Hikari + Flyway.
Used by all 20 DAO test classes; runs in 2-3 seconds per class.

## When to use

- Writing tests for a new `<Entity>Dao`.
- Testing a new method added to an existing DAO.

## Procedure

### 1. Test class setup

Path: `src/test/java/io/adaptiq/titan/db/dao/<Entity>DaoTest.java`

Skeleton:

```java
package io.adaptiq.titan.db.dao;

import static org.junit.jupiter.api.Assertions.*;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.db.dao.rows.<Entity>Row;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class <Entity>DaoTest {

    private HikariDataSource ds;
    private <Entity>Dao dao;

    @BeforeEach
    void setUp() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:h2:mem:<entity>-" + System.nanoTime()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE");
        cfg.setDriverClassName("org.h2.Driver");
        cfg.setMaximumPoolSize(2);
        ds = new HikariDataSource(cfg);

        Flyway.configure(getClass().getClassLoader())
                .dataSource(ds)
                .locations("classpath:io/adaptiq/titan/db/migration")
                .load()
                .migrate();

        dao = new <Entity>Dao(ds);  // package-private constructor — same package as test
    }

    @AfterEach
    void tearDown() {
        if (ds != null) ds.close();
    }

    @Test
    void insertThenFind() {
        <Entity>Row row = new <Entity>Row();
        // ... populate fields ...
        long id = dao.upsert(row);

        <Entity>Row found = dao.findById(id).orElseThrow();
        assertEquals(row.something, found.something);
    }

    // ... more tests ...
}
```

### 2. Foreign-key parents

If the entity has FKs, seed parents in `setUp` or in each test:

```java
@BeforeEach
void setUp() {
    // ... pool / Flyway setup ...
    dao = new <Entity>Dao(ds);

    // Seed parent rows (e.g. for ComponentDaoTest, you need an apps row first)
    AppRow app = new AppRow();
    app.id = "test-app";
    app.displayName = "Test";
    new AppDao(ds).upsert(app);
    parentAppId = "test-app";
}
```

### 3. Coverage targets per DAO

At minimum:
- `insert` happy path.
- `findById` returns `Optional.empty()` for missing id.
- `findByXxx` (every alternate lookup).
- `list*` returns empty list when no rows.
- `list*` returns expected rows in expected order.
- `upsert` updates existing (verify version bump or content change).
- `delete` removes the row.
- `delete` is idempotent (calling twice doesn't throw).

For DAOs with special semantics:
- **Locks** (e.g. DeploymentLockDao): test `tryAcquire` returns false on second call.
- **Insert-or-skip** (e.g. VersionObservationDao): test duplicate insert returns -1.
- **Bulk replace** (e.g. AclRuleDao): test that previous rows are gone after replaceAll.
- **Single-row** (e.g. AndonDao): test currentState returns the single row even with no prior writes.

### 4. Assertions

JUnit 5: `org.junit.jupiter.api.Assertions.*`. NOT JUnit 4. NOT
Hamcrest. Stick to `assertEquals`, `assertTrue`, `assertFalse`,
`assertThrows`, `assertNull`, `assertNotNull`.

## Worked example

`AppDaoTest.java`:
- 7 tests covering: insertThenFindById, upsertBumpsVersion,
  listActiveExcludesArchived, archiveIsIdempotent, unarchive,
  findMissingReturnsEmpty, deleteHardRemoves.
- Each test takes ~0.2s after JVM warmup.
- Total class runs in ~2.4s.

## Common variations / gotchas

- **`MODE=PostgreSQL` matters.** Without it, H2 uses its own SQL
  dialect and some queries (e.g. `INSERT … RETURNING`) parse
  differently. The production database is Postgres-compatible; tests
  must run in Postgres mode.
- **`DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE`**
  matches our production settings. Don't drop these.
- **Each test gets a fresh in-memory schema** via the unique
  `nano:Time()` URL suffix. Don't share state across tests.
- **Parallel test execution**: each test class gets its own Hikari
  pool; H2's in-memory mode is per-JVM-classloader, so different
  test classes don't see each other's data.

## Cross-references

- [dao-skill.md](dao-skill.md) — writing the DAO under test.
- design/16-execution-plan.md §A.8 — Phase A acceptance gate.
