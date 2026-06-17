# Timer Subsystem — Phase 1 (Substrate) Implementation Plan

> **Exemplar.** A representative AI-authored implementation plan, kept verbatim
> as evidence of the build method. Written in 2026-05 against the repo's
> then-current layout — a historical artifact, not maintained documentation.
> Paired with [exemplar-spec-timer-subsystem.md](exemplar-spec-timer-subsystem.md).

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the durable-timer substrate for the Titan engine — a `titan.timers` table, its DAO, a `TimerService` façade, and a `TimerSweeper` periodic job — with no user-facing feature yet, fully testable on its own.

**Architecture:** A *timer* is a durable row "at `fire_at`, re-evaluate `build_id`/`node_id`". A timer moves `ARMED → CLAIMED → FIRED` (a stale `CLAIMED` is reclaimed to `ARMED`), mirroring the proven `DiscoveryEvent` claim state machine — a portable `UPDATE … WHERE id IN (SELECT … LIMIT)` claim, no `SKIP LOCKED`. The `TimerSweeper` (a `PeriodicWork`) claims due timers, enqueues an `ORCHESTRATE/ADVANCE` task per timer, and marks it `FIRED`. The kind-specific logic belongs to later phases' orchestrator handlers — the sweeper itself is kind-agnostic.

**Tech Stack:** Java 21, JDBI 3 (`@RegisterFieldMapper`/`@SqlQuery`/`@SqlUpdate`), Flyway, H2 (PostgreSQL mode) for tests, JUnit 5, Maven (`./mvnw`). Spec: `docs/superpowers/specs/2026-05-18-titan-timer-subsystem-design.md`. Work stays on branch `feat/timer-subsystem`.

## File Structure

- Create: `titan-db-core/src/main/resources/io/adaptiq/titan/db/migration/V8__timers.sql` — the `titan.timers` table.
- Create: `titan-plugin/src/main/java/io/adaptiq/titan/store/rows/TimerRow.java` — the row POJO.
- Create: `titan-plugin/src/main/java/io/adaptiq/titan/store/TimerDao.java` — JDBI DAO.
- Create: `titan-plugin/src/test/java/io/adaptiq/titan/store/TimerDaoTest.java` — DAO tests.
- Modify: `titan-plugin/src/main/java/io/adaptiq/titan/store/TitanStores.java` — wire the DAO into the façade.
- Create: `titan-plugin/src/main/java/io/adaptiq/titan/timer/TimerService.java` — the consumer-facing façade.
- Create: `titan-plugin/src/test/java/io/adaptiq/titan/timer/TimerServiceTest.java` — service tests.
- Create: `titan-plugin/src/main/java/io/adaptiq/titan/timer/TimerSweeper.java` — the `PeriodicWork`.
- Create: `titan-plugin/src/test/java/io/adaptiq/titan/timer/TimerSweeperTest.java` — sweeper tests.

**Verified codebase facts (templates to copy):**
- Migration head is `V7__discovery.sql`; next is `V8`. DDL style: `BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY`, unbounded `VARCHAR` (no `TEXT`), `TIMESTAMP` (no `TIMESTAMPTZ`), `CURRENT_TIMESTAMP` default, named constraints/indexes, `--` comments, `titan.` schema prefix, `CHECK (col IN (…))` for closed sets.
- Row POJO: mutable public fields, no getters/constructor; timestamps are `java.time.Instant`; nullable columns get `@Nullable` and a reference type; non-null primitives are bare `long`/`int`. Copy the `@Nullable`/`@NonNull` imports from `DiscoveryEventRow.java`.
- DAO: `@RegisterFieldMapper(Row.class)` on the interface; `@SqlQuery`/`@SqlUpdate` with `@Bind("x")`; `@Transaction` on `default` methods; portable claim = `UPDATE … WHERE id IN (SELECT id … ORDER BY … LIMIT :limit)` then `SELECT … WHERE claim_token = :token`. Copy imports from `DiscoveryEventDao.java`.
- `TitanStores`: each DAO is `translating(jdbi.onDemand(Dao.class), Dao.class)`; a public accessor returns it. Test factories: `TitanStores.daoFor(ds, Dao.class)` (single DAO) and `TitanStores.forDataSource(ds)` (full façade).
- DAO test harness: per-test in-memory H2 (`jdbc:h2:mem:<unique>;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE`), Flyway `.locations("classpath:io/adaptiq/titan/db/migration")`, pure JUnit 5 (`@BeforeEach`/`@AfterEach`), no host-framework test harness.
- `PeriodicWork`: `@Extension` class `extends PeriodicWork`; `getRecurrencePeriod()` returns millis; `protected void doRun()`; `AtomicBoolean running` guard with `compareAndSet`; logger `[titan]`-prefixed.
- Enqueue an ADVANCE: `new TaskQueueRow()` with `type="ORCHESTRATE"`, `queueName="default"`, `status="QUEUED"`, `priority=0`, `payloadJson="{\"action\":\"ADVANCE\",\"buildId\":<id>}"`, `attempts=0`, `maxAttempts=3`, `visibilityTimeoutSeconds=3600`, `buildId=<id>`, `availableAt=Instant.now()`, then `taskQueue().insert(row)`.

---

### Task 1: The `titan.timers` DB layer — migration, row, DAO

**Files:**
- Create: `titan-db-core/src/main/resources/io/adaptiq/titan/db/migration/V8__timers.sql`
- Create: `titan-plugin/src/main/java/io/adaptiq/titan/store/rows/TimerRow.java`
- Create: `titan-plugin/src/main/java/io/adaptiq/titan/store/TimerDao.java`
- Test: `titan-plugin/src/test/java/io/adaptiq/titan/store/TimerDaoTest.java`

- [ ] **Step 1: Write the failing test**

Create `TimerDaoTest.java`:

```java
package io.adaptiq.titan.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.store.rows.TimerRow;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link TimerDao} — the durable-timer DB layer (timer subsystem, Phase 1). */
class TimerDaoTest {

    private HikariDataSource ds;
    private TimerDao dao;

    @BeforeEach
    void setUp() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:h2:mem:timerdao-" + System.nanoTime()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE");
        cfg.setDriverClassName("org.h2.Driver");
        cfg.setMaximumPoolSize(2);
        ds = new HikariDataSource(cfg);

        Flyway.configure(getClass().getClassLoader())
                .dataSource(ds)
                .schemas("titan")
                .defaultSchema("titan")
                .createSchemas(true)
                .locations("classpath:io/adaptiq/titan/db/migration")
                .load()
                .migrate();

        dao = TitanStores.daoFor(ds, TimerDao.class);
    }

    @AfterEach
    void tearDown() {
        if (ds != null) {
            ds.close();
        }
    }

    // ── arm ───────────────────────────────────────────────────────────────────

    @Test
    void armInsertsAnArmedTimer() {
        Instant fireAt = Instant.now().plus(1, ChronoUnit.HOURS);
        assertEquals(1, dao.armIfAbsent(7L, "node-a", "SLEEP", fireAt, null));

        List<TimerRow> rows = dao.listByBuild(7L);
        assertEquals(1, rows.size());
        TimerRow t = rows.get(0);
        assertEquals(7L, t.buildId);
        assertEquals("node-a", t.nodeId);
        assertEquals("SLEEP", t.kind);
        assertEquals("ARMED", t.status);
        assertNotNull(t.createdAt);
        assertNull(t.firedAt);
        assertNull(t.claimToken);
    }

    @Test
    void armIsIdempotentWhileATimerIsArmedForTheSameTriple() {
        Instant fireAt = Instant.now().plus(1, ChronoUnit.HOURS);
        assertEquals(1, dao.armIfAbsent(7L, "node-a", "SLEEP", fireAt, null));
        // Same (build,node,kind), still ARMED → no second row.
        assertEquals(0, dao.armIfAbsent(7L, "node-a", "SLEEP", fireAt, null));
        assertEquals(1, dao.listByBuild(7L).size());
    }

    @Test
    void armDistinguishesKindAndNodeAndBuild() {
        Instant fireAt = Instant.now().plus(1, ChronoUnit.HOURS);
        assertEquals(1, dao.armIfAbsent(7L, "node-a", "SLEEP", fireAt, null));
        assertEquals(1, dao.armIfAbsent(7L, "node-a", "TIMEOUT", fireAt, null), "different kind");
        assertEquals(1, dao.armIfAbsent(7L, "node-b", "SLEEP", fireAt, null), "different node");
        assertEquals(1, dao.armIfAbsent(9L, "node-a", "SLEEP", fireAt, null), "different build");
        assertEquals(3, dao.listByBuild(7L).size());
    }

    @Test
    void armAgainIsAllowedOnceThePriorTimerIsNoLongerArmed() {
        Instant past = Instant.now().minus(1, ChronoUnit.MINUTES);
        assertEquals(1, dao.armIfAbsent(7L, "node-a", "SLEEP", past, null));
        // Claim + fire it, leaving it FIRED — a fresh arm must then be allowed.
        List<TimerRow> claimed = dao.claimDue(10);
        assertEquals(1, claimed.size());
        dao.markFired(claimed.get(0).id);
        assertEquals(1, dao.armIfAbsent(7L, "node-a", "SLEEP", past, null),
                "a FIRED timer must not block a new arm of the same triple");
        assertEquals(2, dao.listByBuild(7L).size());
    }

    @Test
    void armStoresThePayload() {
        Instant fireAt = Instant.now().plus(1, ChronoUnit.HOURS);
        dao.armIfAbsent(7L, "node-a", "RETRY_BACKOFF", fireAt, "{\"attempt\":2}");
        assertEquals("{\"attempt\":2}", dao.listByBuild(7L).get(0).payloadJson);
    }

    // ── claim ─────────────────────────────────────────────────────────────────

    @Test
    void claimDuePicksUpOnlyTimersWhoseFireAtHasPassed() {
        dao.armIfAbsent(7L, "past", "SLEEP", Instant.now().minus(1, ChronoUnit.MINUTES), null);
        dao.armIfAbsent(7L, "future", "SLEEP", Instant.now().plus(1, ChronoUnit.HOURS), null);

        List<TimerRow> claimed = dao.claimDue(10);
        assertEquals(1, claimed.size(), "only the due timer is claimed");
        assertEquals("past", claimed.get(0).nodeId);
        assertEquals("CLAIMED", claimed.get(0).status);
        assertNotNull(claimed.get(0).claimToken);
        assertNotNull(claimed.get(0).claimedAt);
    }

    @Test
    void claimDueReturnsNothingWhenNoTimerIsDue() {
        dao.armIfAbsent(7L, "future", "SLEEP", Instant.now().plus(1, ChronoUnit.HOURS), null);
        assertTrue(dao.claimDue(10).isEmpty());
    }

    @Test
    void aClaimedTimerIsNotClaimedAgainByASecondSweep() {
        dao.armIfAbsent(7L, "past", "SLEEP", Instant.now().minus(1, ChronoUnit.MINUTES), null);
        assertEquals(1, dao.claimDue(10).size());
        assertTrue(dao.claimDue(10).isEmpty(), "a CLAIMED timer must not be re-claimed");
    }

    @Test
    void claimDueRespectsTheBatchLimit() {
        for (int i = 0; i < 5; i++) {
            dao.armIfAbsent(7L, "n" + i, "SLEEP", Instant.now().minus(1, ChronoUnit.MINUTES), null);
        }
        assertEquals(2, dao.claimDue(2).size());
        assertEquals(3, dao.claimDue(10).size(), "the rest are claimed on the next sweep");
    }

    // ── fire ──────────────────────────────────────────────────────────────────

    @Test
    void markFiredTransitionsAClaimedTimerToFired() {
        dao.armIfAbsent(7L, "past", "SLEEP", Instant.now().minus(1, ChronoUnit.MINUTES), null);
        TimerRow claimed = dao.claimDue(10).get(0);
        dao.markFired(claimed.id);

        TimerRow fired = dao.findById(claimed.id).orElseThrow();
        assertEquals("FIRED", fired.status);
        assertNotNull(fired.firedAt);
        assertNull(fired.claimToken, "the claim token is cleared on fire");
    }

    // ── cancel ────────────────────────────────────────────────────────────────

    @Test
    void cancelFlipsAnArmedTimerToCancelled() {
        dao.armIfAbsent(7L, "node-a", "SLEEP", Instant.now().plus(1, ChronoUnit.HOURS), null);
        assertEquals(1, dao.cancel(7L, "node-a", "SLEEP"));
        assertEquals("CANCELLED", dao.listByBuild(7L).get(0).status);
        // A cancelled timer is never claimed.
        assertTrue(dao.claimDue(10).isEmpty());
    }

    @Test
    void cancelAllCancelsEveryArmedTimerOfABuildAndLeavesOtherBuildsAlone() {
        dao.armIfAbsent(7L, "n1", "SLEEP", Instant.now().plus(1, ChronoUnit.HOURS), null);
        dao.armIfAbsent(7L, "n2", "TIMEOUT", Instant.now().plus(1, ChronoUnit.HOURS), null);
        dao.armIfAbsent(9L, "n1", "SLEEP", Instant.now().plus(1, ChronoUnit.HOURS), null);

        assertEquals(2, dao.cancelAll(7L));
        assertTrue(dao.listByBuild(7L).stream().allMatch(t -> t.status.equals("CANCELLED")));
        assertEquals("ARMED", dao.listByBuild(9L).get(0).status, "build 9 is untouched");
    }

    // ── reclaim ───────────────────────────────────────────────────────────────

    @Test
    void reclaimStaleReturnsAStuckClaimedTimerToArmed() {
        dao.armIfAbsent(7L, "past", "SLEEP", Instant.now().minus(1, ChronoUnit.MINUTES), null);
        long id = dao.claimDue(10).get(0).id;
        // Backdate the claim so it looks like a sweep that died 1h ago.
        exec("UPDATE titan.timers SET claimed_at = TIMESTAMP '2000-01-01 00:00:00' WHERE id = " + id);

        assertEquals(1, dao.reclaimStale(Instant.now().minus(5, ChronoUnit.MINUTES)));
        TimerRow row = dao.findById(id).orElseThrow();
        assertEquals("ARMED", row.status);
        assertNull(row.claimToken);
        assertNull(row.claimedAt);
        // Reclaimed → claimable again.
        assertEquals(1, dao.claimDue(10).size());
    }

    @Test
    void reclaimStaleLeavesAFreshlyClaimedTimerAlone() {
        dao.armIfAbsent(7L, "past", "SLEEP", Instant.now().minus(1, ChronoUnit.MINUTES), null);
        dao.claimDue(10); // claimed_at = now
        assertEquals(0, dao.reclaimStale(Instant.now().minus(5, ChronoUnit.MINUTES)));
        assertEquals("CLAIMED", dao.listByBuild(7L).get(0).status);
    }

    // ── raw-SQL helper ────────────────────────────────────────────────────────

    private void exec(String sql) {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate(sql);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl titan-plugin -am test -Dtest=TimerDaoTest -q`
Expected: COMPILE FAILURE — `TimerDao` / `TimerRow` do not exist, and `V8__timers.sql` is missing.

- [ ] **Step 3: Write the migration**

Create `titan-db-core/src/main/resources/io/adaptiq/titan/db/migration/V8__timers.sql`:

```sql
-- Titan durable timer subsystem — controller-native scheduled wake-ups.
-- A timer is the persisted fact "at fire_at, re-evaluate build_id/node_id".
-- The TimerSweeper claims due ARMED rows, enqueues an ORCHESTRATE/ADVANCE task,
-- and marks the timer FIRED. The kind-specific work is the orchestrator's job.
-- See docs/superpowers/specs/2026-05-18-titan-timer-subsystem-design.md.
--
-- Portable DDL: VARCHAR for unbounded text, TIMESTAMP for times, snake_case
-- names, BIGINT GENERATED ALWAYS AS IDENTITY surrogate keys, named constraints.

CREATE TABLE titan.timers (
    id           BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    build_id     BIGINT       NOT NULL,
    node_id      VARCHAR(255) NOT NULL,
    kind         VARCHAR(16)  NOT NULL
                 CHECK (kind IN ('SLEEP','TIMEOUT','RETRY_BACKOFF','GATE_RESUME')),
    fire_at      TIMESTAMP    NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'ARMED'
                 CHECK (status IN ('ARMED','CLAIMED','FIRED','CANCELLED')),
    payload_json VARCHAR,
    claim_token  VARCHAR(64),
    claimed_at   TIMESTAMP,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    fired_at     TIMESTAMP
);

-- The sweeper's claim query: due ARMED rows, oldest fire_at first.
CREATE INDEX idx_timers_due ON titan.timers(status, fire_at, claimed_at);
-- Idempotent arm + build-wide cancel.
CREATE INDEX idx_timers_target ON titan.timers(build_id, node_id, kind);
```

- [ ] **Step 4: Write the row POJO**

Create `titan-plugin/src/main/java/io/adaptiq/titan/store/rows/TimerRow.java`. Use the exact `@Nullable` import that `DiscoveryEventRow.java` (same package) uses:

```java
package io.adaptiq.titan.store.rows;

import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/**
 * One row of {@code titan.timers} — a durable, controller-native scheduled wake-up.
 *
 * <p>Lifecycle: {@code ARMED → CLAIMED} (a sweep picked it up) {@code → FIRED}. A {@code CLAIMED}
 * row whose sweep died is reclaimed to {@code ARMED}. {@code cancel} flips {@code ARMED → CANCELLED}.
 * Mutable public-field POJO, mapped by JDBI's field mapper — no getters, no constructor.
 */
public class TimerRow {

    public long id;
    public long buildId;
    public String nodeId;
    public String kind; // SLEEP | TIMEOUT | RETRY_BACKOFF | GATE_RESUME
    public Instant fireAt;
    public String status; // ARMED | CLAIMED | FIRED | CANCELLED

    @Nullable public String payloadJson;
    @Nullable public String claimToken;
    @Nullable public Instant claimedAt;

    public Instant createdAt;

    @Nullable public Instant firedAt;
}
```

(If `DiscoveryEventRow` imports `@Nullable` from a different package, match that import instead — the row POJOs in this directory must be consistent.)

- [ ] **Step 5: Write the DAO**

Create `titan-plugin/src/main/java/io/adaptiq/titan/store/TimerDao.java`. Match the JDBI import set of `DiscoveryEventDao.java` in the same package:

```java
package io.adaptiq.titan.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.store.rows.TimerRow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.sqlobject.config.RegisterFieldMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.sqlobject.transaction.Transaction;

/**
 * DAO for {@code titan.timers} — the durable-timer substrate (timer subsystem, Phase 1).
 *
 * <p>The claim is the portable two-step pattern shared with {@code DiscoveryEventDao}: an
 * {@code UPDATE … WHERE id IN (SELECT … LIMIT)} flips due {@code ARMED} rows to {@code CLAIMED}
 * under a fresh token, then a {@code SELECT} by that token returns exactly the claimed rows. No
 * {@code FOR UPDATE SKIP LOCKED} — runs identically on H2 (PostgreSQL mode) and PostgreSQL, and
 * is multi-controller-safe because each sweep claims under its own token.
 */
@RegisterFieldMapper(TimerRow.class)
public interface TimerDao {

    String COLS = "id, build_id, node_id, kind, fire_at, status, payload_json, "
            + "claim_token, claimed_at, created_at, fired_at";

    // ── queries ───────────────────────────────────────────────────────────────

    @SqlQuery("SELECT " + COLS + " FROM titan.timers WHERE id = :id")
    @NonNull
    Optional<TimerRow> findById(@Bind("id") long id);

    @SqlQuery("SELECT " + COLS + " FROM titan.timers WHERE build_id = :buildId ORDER BY id")
    @NonNull
    List<TimerRow> listByBuild(@Bind("buildId") long buildId);

    @SqlQuery("SELECT " + COLS + " FROM titan.timers WHERE claim_token = :claimToken ORDER BY fire_at")
    @NonNull
    List<TimerRow> selectClaimed(@Bind("claimToken") @NonNull String claimToken);

    // ── arm ───────────────────────────────────────────────────────────────────

    /**
     * Arm a timer, idempotent on {@code (buildId,nodeId,kind)}: inserts an {@code ARMED} row only
     * if no {@code ARMED} or {@code CLAIMED} timer already exists for that triple. Returns 1 if a
     * row was inserted, 0 if an active timer already covered it.
     */
    @SqlUpdate("INSERT INTO titan.timers (build_id, node_id, kind, fire_at, status, payload_json) "
            + "SELECT :buildId, :nodeId, :kind, :fireAt, 'ARMED', :payloadJson "
            + "WHERE NOT EXISTS (SELECT 1 FROM titan.timers "
            + "WHERE build_id = :buildId AND node_id = :nodeId AND kind = :kind "
            + "AND status IN ('ARMED','CLAIMED'))")
    int armIfAbsent(
            @Bind("buildId") long buildId,
            @Bind("nodeId") @NonNull String nodeId,
            @Bind("kind") @NonNull String kind,
            @Bind("fireAt") @NonNull Instant fireAt,
            @Bind("payloadJson") @Nullable String payloadJson);

    // ── claim ─────────────────────────────────────────────────────────────────

    /** Claim up to {@code limit} due {@code ARMED} timers, flipping them to {@code CLAIMED}. */
    @Transaction
    @NonNull
    default List<TimerRow> claimDue(int limit) {
        String token = UUID.randomUUID().toString();
        if (markDueAsClaimed(token, limit) == 0) {
            return List.of();
        }
        return selectClaimed(token);
    }

    @SqlUpdate("UPDATE titan.timers "
            + "SET status = 'CLAIMED', claim_token = :claimToken, claimed_at = CURRENT_TIMESTAMP "
            + "WHERE id IN (SELECT id FROM titan.timers "
            + "WHERE status = 'ARMED' AND fire_at <= CURRENT_TIMESTAMP "
            + "ORDER BY fire_at LIMIT :limit)")
    int markDueAsClaimed(@Bind("claimToken") @NonNull String claimToken, @Bind("limit") int limit);

    /** Return a stale {@code CLAIMED} timer (a sweep that died) to {@code ARMED}. */
    @SqlUpdate("UPDATE titan.timers SET status = 'ARMED', claim_token = NULL, claimed_at = NULL "
            + "WHERE status = 'CLAIMED' AND claimed_at < :cutoff")
    int reclaimStale(@Bind("cutoff") @NonNull Instant cutoff);

    // ── fire ──────────────────────────────────────────────────────────────────

    @SqlUpdate("UPDATE titan.timers "
            + "SET status = 'FIRED', fired_at = CURRENT_TIMESTAMP, claim_token = NULL, claimed_at = NULL "
            + "WHERE id = :id AND status = 'CLAIMED'")
    void markFired(@Bind("id") long id);

    // ── cancel ────────────────────────────────────────────────────────────────

    @SqlUpdate("UPDATE titan.timers SET status = 'CANCELLED' "
            + "WHERE build_id = :buildId AND node_id = :nodeId AND kind = :kind AND status = 'ARMED'")
    int cancel(
            @Bind("buildId") long buildId,
            @Bind("nodeId") @NonNull String nodeId,
            @Bind("kind") @NonNull String kind);

    @SqlUpdate("UPDATE titan.timers SET status = 'CANCELLED' "
            + "WHERE build_id = :buildId AND status = 'ARMED'")
    int cancelAll(@Bind("buildId") long buildId);
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./mvnw -pl titan-plugin -am test -Dtest=TimerDaoTest -q`
Expected: PASS — 14 tests.

- [ ] **Step 7: Commit**

```bash
git add titan-db-core/src/main/resources/io/adaptiq/titan/db/migration/V8__timers.sql \
        titan-plugin/src/main/java/io/adaptiq/titan/store/rows/TimerRow.java \
        titan-plugin/src/main/java/io/adaptiq/titan/store/TimerDao.java \
        titan-plugin/src/test/java/io/adaptiq/titan/store/TimerDaoTest.java
git commit -m "feat(titan): add the titan.timers table, row, and DAO"
```

---

### Task 2: Wire `TimerDao` into the `TitanStores` façade

**Files:**
- Modify: `titan-plugin/src/main/java/io/adaptiq/titan/store/TitanStores.java`

- [ ] **Step 1: Add the field, construction, and accessor**

In `TitanStores.java`, follow the existing per-DAO pattern exactly. Three edits:

1. Add a private final field next to the other DAO fields (e.g. near `discoveryEventDao`):

```java
    private final TimerDao timerDao;
```

2. In the private `TitanStores(DataSource ds)` constructor, next to the other `translating(...)` lines:

```java
        this.timerDao = translating(jdbi.onDemand(TimerDao.class), TimerDao.class);
```

3. Add a public accessor next to the other accessors (e.g. after `discoveryEvents()`):

```java
    /** DAO for {@code titan.timers} — the durable-timer substrate. */
    @NonNull
    public TimerDao timers() {
        return timerDao;
    }
```

`TimerDao` is in the same package (`io.adaptiq.titan.store`), so no import is needed.

- [ ] **Step 2: Verify it compiles**

Run: `./mvnw -pl titan-plugin -am test-compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Re-run the DAO test through the façade path**

Run: `./mvnw -pl titan-plugin -am test -Dtest=TimerDaoTest -q`
Expected: PASS — still 14 tests (unaffected; this confirms nothing regressed).

- [ ] **Step 4: Commit**

```bash
git add titan-plugin/src/main/java/io/adaptiq/titan/store/TitanStores.java
git commit -m "feat(titan): expose TimerDao on the TitanStores facade"
```

---

### Task 3: `TimerService` — the consumer-facing façade

**Files:**
- Create: `titan-plugin/src/test/java/io/adaptiq/titan/timer/TimerServiceTest.java`
- Create: `titan-plugin/src/main/java/io/adaptiq/titan/timer/TimerService.java`

- [ ] **Step 1: Write the failing test**

Create `TimerServiceTest.java`:

```java
package io.adaptiq.titan.timer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.store.TimerDao;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.TimerRow;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link TimerService} — the consumer-facing arm/cancel façade. */
class TimerServiceTest {

    private HikariDataSource ds;
    private TimerDao dao;
    private TimerService service;

    @BeforeEach
    void setUp() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:h2:mem:timersvc-" + System.nanoTime()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE");
        cfg.setDriverClassName("org.h2.Driver");
        cfg.setMaximumPoolSize(2);
        ds = new HikariDataSource(cfg);

        Flyway.configure(getClass().getClassLoader())
                .dataSource(ds)
                .schemas("titan")
                .defaultSchema("titan")
                .createSchemas(true)
                .locations("classpath:io/adaptiq/titan/db/migration")
                .load()
                .migrate();

        dao = TitanStores.daoFor(ds, TimerDao.class);
        service = new TimerService(dao);
    }

    @AfterEach
    void tearDown() {
        if (ds != null) {
            ds.close();
        }
    }

    @Test
    void armCreatesAnArmedTimerOfTheGivenKind() {
        Instant fireAt = Instant.now().plus(1, ChronoUnit.HOURS);
        assertTrue(service.arm(TimerService.Kind.SLEEP, 7L, "node-a", fireAt, null));

        List<TimerRow> rows = dao.listByBuild(7L);
        assertEquals(1, rows.size());
        assertEquals("SLEEP", rows.get(0).kind);
        assertEquals("ARMED", rows.get(0).status);
    }

    @Test
    void armReturnsFalseWhenATimerForTheTripleAlreadyExists() {
        Instant fireAt = Instant.now().plus(1, ChronoUnit.HOURS);
        assertTrue(service.arm(TimerService.Kind.TIMEOUT, 7L, "node-a", fireAt, null));
        assertFalse(service.arm(TimerService.Kind.TIMEOUT, 7L, "node-a", fireAt, null),
                "a duplicate arm of the same (build,node,kind) is a no-op");
        assertEquals(1, dao.listByBuild(7L).size());
    }

    @Test
    void armCarriesThePayloadThrough() {
        service.arm(TimerService.Kind.RETRY_BACKOFF, 7L, "node-a",
                Instant.now().plus(1, ChronoUnit.HOURS), "{\"attempt\":3}");
        assertEquals("{\"attempt\":3}", dao.listByBuild(7L).get(0).payloadJson);
    }

    @Test
    void cancelFlipsAMatchingArmedTimerToCancelled() {
        service.arm(TimerService.Kind.SLEEP, 7L, "node-a",
                Instant.now().plus(1, ChronoUnit.HOURS), null);
        assertTrue(service.cancel(TimerService.Kind.SLEEP, 7L, "node-a"));
        assertEquals("CANCELLED", dao.listByBuild(7L).get(0).status);
    }

    @Test
    void cancelReturnsFalseWhenThereIsNothingToCancel() {
        assertFalse(service.cancel(TimerService.Kind.SLEEP, 7L, "absent"));
    }

    @Test
    void cancelAllCancelsEveryArmedTimerOfTheBuild() {
        service.arm(TimerService.Kind.SLEEP, 7L, "n1", Instant.now().plus(1, ChronoUnit.HOURS), null);
        service.arm(TimerService.Kind.TIMEOUT, 7L, "n2", Instant.now().plus(1, ChronoUnit.HOURS), null);
        assertEquals(2, service.cancelAll(7L));
        assertTrue(dao.listByBuild(7L).stream().allMatch(t -> t.status.equals("CANCELLED")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl titan-plugin -am test -Dtest=TimerServiceTest -q`
Expected: COMPILE FAILURE — `TimerService` does not exist.

- [ ] **Step 3: Write `TimerService`**

Create `titan-plugin/src/main/java/io/adaptiq/titan/timer/TimerService.java`:

```java
package io.adaptiq.titan.timer;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.store.TimerDao;
import io.adaptiq.titan.store.TitanStores;
import java.time.Instant;

/**
 * The consumer-facing façade for the durable-timer subsystem — the API the orchestrator calls to
 * arm and cancel timers. A thin, typed layer over {@link TimerDao}: it owns the {@link Kind} enum
 * so callers never pass a raw string, and it names the idempotent-arm contract.
 *
 * <p>Arming is idempotent on {@code (buildId, nodeId, kind)} — a re-run of the orchestrator's
 * {@code advance()} never produces a duplicate timer.
 */
public final class TimerService {

    /** What a fired timer means — resolved by the orchestrator, not the sweeper. */
    public enum Kind {
        SLEEP,
        TIMEOUT,
        RETRY_BACKOFF,
        GATE_RESUME
    }

    private final TimerDao dao;

    public TimerService(@NonNull TimerDao dao) {
        this.dao = dao;
    }

    /** The production instance, bound to the live {@link TitanStores} façade. */
    @NonNull
    public static TimerService get() {
        return new TimerService(TitanStores.get().timers());
    }

    /**
     * Arm a timer that fires at {@code fireAt}. Idempotent on {@code (buildId,nodeId,kind)}:
     * returns {@code true} if a new timer was armed, {@code false} if an active timer already
     * covered that triple.
     */
    public boolean arm(
            @NonNull Kind kind,
            long buildId,
            @NonNull String nodeId,
            @NonNull Instant fireAt,
            @Nullable String payloadJson) {
        return dao.armIfAbsent(buildId, nodeId, kind.name(), fireAt, payloadJson) == 1;
    }

    /** Cancel the {@code ARMED} timer for {@code (buildId,nodeId,kind)}; {@code true} if one was cancelled. */
    public boolean cancel(@NonNull Kind kind, long buildId, @NonNull String nodeId) {
        return dao.cancel(buildId, nodeId, kind.name()) > 0;
    }

    /** Cancel every {@code ARMED} timer of a build (used when the build is aborted). Returns the count. */
    public int cancelAll(long buildId) {
        return dao.cancelAll(buildId);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl titan-plugin -am test -Dtest=TimerServiceTest -q`
Expected: PASS — 6 tests.

- [ ] **Step 5: Commit**

```bash
git add titan-plugin/src/main/java/io/adaptiq/titan/timer/TimerService.java \
        titan-plugin/src/test/java/io/adaptiq/titan/timer/TimerServiceTest.java
git commit -m "feat(titan): add TimerService arm/cancel facade"
```

---

### Task 4: `TimerSweeper` — the periodic firing job

**Files:**
- Create: `titan-plugin/src/test/java/io/adaptiq/titan/timer/TimerSweeperTest.java`
- Create: `titan-plugin/src/main/java/io/adaptiq/titan/timer/TimerSweeper.java`

- [ ] **Step 1: Write the failing test**

Create `TimerSweeperTest.java`. It builds the full `TitanStores` façade against H2 (so both `timers()` and `taskQueue()` work) and drives `sweep(TitanStores)` directly — no scheduler runtime needed:

```java
package io.adaptiq.titan.timer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.store.TimerDao;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.TimerRow;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link TimerSweeper} — the periodic timer-firing job. */
class TimerSweeperTest {

    private HikariDataSource ds;
    private TitanStores stores;
    private TimerDao dao;
    private TimerSweeper sweeper;

    @BeforeEach
    void setUp() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:h2:mem:timersweep-" + System.nanoTime()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE");
        cfg.setDriverClassName("org.h2.Driver");
        cfg.setMaximumPoolSize(2);
        ds = new HikariDataSource(cfg);

        Flyway.configure(getClass().getClassLoader())
                .dataSource(ds)
                .schemas("titan")
                .defaultSchema("titan")
                .createSchemas(true)
                .locations("classpath:io/adaptiq/titan/db/migration")
                .load()
                .migrate();

        stores = TitanStores.forDataSource(ds);
        dao = stores.timers();
        sweeper = new TimerSweeper();
    }

    @AfterEach
    void tearDown() {
        if (ds != null) {
            ds.close();
        }
    }

    @Test
    void aDueTimerIsFiredAndAnAdvanceTaskIsEnqueued() {
        dao.armIfAbsent(7L, "node-a", "SLEEP", Instant.now().minus(1, ChronoUnit.MINUTES), null);

        sweeper.sweep(stores);

        assertEquals("FIRED", dao.listByBuild(7L).get(0).status);
        assertEquals(1, advanceTasksFor(7L), "exactly one ORCHESTRATE/ADVANCE task is enqueued");
    }

    @Test
    void aFutureTimerIsLeftArmedAndEnqueuesNothing() {
        dao.armIfAbsent(7L, "node-a", "SLEEP", Instant.now().plus(1, ChronoUnit.HOURS), null);

        sweeper.sweep(stores);

        assertEquals("ARMED", dao.listByBuild(7L).get(0).status);
        assertEquals(0, advanceTasksFor(7L));
    }

    @Test
    void aTimerIsFiredExactlyOnceAcrossTwoSweeps() {
        dao.armIfAbsent(7L, "node-a", "SLEEP", Instant.now().minus(1, ChronoUnit.MINUTES), null);

        sweeper.sweep(stores);
        sweeper.sweep(stores); // second sweep must find nothing to do

        assertEquals(1, advanceTasksFor(7L), "no duplicate ADVANCE from a second sweep");
    }

    @Test
    void aCancelledTimerIsNeverFired() {
        dao.armIfAbsent(7L, "node-a", "SLEEP", Instant.now().minus(1, ChronoUnit.MINUTES), null);
        dao.cancel(7L, "node-a", "SLEEP");

        sweeper.sweep(stores);

        assertEquals("CANCELLED", dao.listByBuild(7L).get(0).status);
        assertEquals(0, advanceTasksFor(7L));
    }

    @Test
    void aStaleClaimIsReclaimedAndFiredOnTheNextSweep() {
        dao.armIfAbsent(7L, "node-a", "SLEEP", Instant.now().minus(1, ChronoUnit.MINUTES), null);
        // Simulate a sweep that claimed the timer and then died before firing.
        long id = dao.claimDue(10).get(0).id;
        exec("UPDATE titan.timers SET claimed_at = TIMESTAMP '2000-01-01 00:00:00' WHERE id = " + id);

        sweeper.sweep(stores); // reclaim phase returns it to ARMED, then fires it

        assertEquals("FIRED", dao.findById(id).orElseThrow().status);
        assertEquals(1, advanceTasksFor(7L));
    }

    @Test
    void multipleDueTimersAcrossBuildsEachGetTheirOwnAdvance() {
        dao.armIfAbsent(7L, "n1", "SLEEP", Instant.now().minus(1, ChronoUnit.MINUTES), null);
        dao.armIfAbsent(8L, "n1", "TIMEOUT", Instant.now().minus(1, ChronoUnit.MINUTES), null);

        sweeper.sweep(stores);

        assertEquals(1, advanceTasksFor(7L));
        assertEquals(1, advanceTasksFor(8L));
        assertTrue(dao.listByBuild(7L).get(0).status.equals("FIRED"));
        assertTrue(dao.listByBuild(8L).get(0).status.equals("FIRED"));
    }

    // ── raw-SQL helpers ───────────────────────────────────────────────────────

    /** Count ORCHESTRATE/ADVANCE task_queue rows for a build. */
    private int advanceTasksFor(long buildId) {
        return scalar("SELECT COUNT(*) FROM titan.task_queue "
                + "WHERE type = 'ORCHESTRATE' AND build_id = " + buildId
                + " AND payload_json LIKE '%\"action\":\"ADVANCE\"%'");
    }

    private void exec(String sql) {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate(sql);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private int scalar(String sql) {
        try (Connection c = ds.getConnection();
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl titan-plugin -am test -Dtest=TimerSweeperTest -q`
Expected: COMPILE FAILURE — `TimerSweeper` does not exist.

- [ ] **Step 3: Write `TimerSweeper`**

Create `titan-plugin/src/main/java/io/adaptiq/titan/timer/TimerSweeper.java`:

```java
package io.adaptiq.titan.timer;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import io.adaptiq.titan.store.rows.TimerRow;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The durable-timer firing job — a {@link PeriodicWork} that, every ~5 s, reclaims any timer left
 * {@code CLAIMED} by a dead sweep, claims the due {@code ARMED} timers, and fires each.
 *
 * <p>Firing is deliberately kind-agnostic: it enqueues one {@code ORCHESTRATE/ADVANCE} task for the
 * timer's build, then marks the timer {@code FIRED}. The kind-specific work is the orchestrator's
 * job on the next {@code advance()} — the sweeper only pokes the build. The ADVANCE enqueue is done
 * before {@code markFired}, so a crash between the two re-fires (a duplicate ADVANCE is harmless;
 * a lost ADVANCE would not be) — at-least-once delivery.
 */
@ApplicationScoped
public class TimerSweeper {

    private static final Logger LOGGER = Logger.getLogger(TimerSweeper.class.getName());

    /** Timers claimed per tick. */
    private static final int BATCH = 100;

    /** A {@code CLAIMED} timer older than this is assumed orphaned by a dead sweep. */
    private static final Duration STALE_CLAIM = Duration.ofMinutes(5);

    private final AtomicBoolean running = new AtomicBoolean(false);

    /** Driven by the Quarkus scheduler every ~5 s; tests call {@link #sweep} directly. */
    @Scheduled(every = "5s")
    void doRun() {
        if (!running.compareAndSet(false, true)) {
            LOGGER.fine("[titan] previous timer sweep still running — skipping");
            return;
        }
        try {
            sweep(TitanStores.get());
        } catch (RuntimeException e) {
            LOGGER.log(Level.SEVERE, "[titan] timer sweep failed", e);
        } finally {
            running.set(false);
        }
    }

    /** One sweep cycle. Package-visible and {@code TitanStores}-injected so tests can drive a tick. */
    void sweep(@NonNull TitanStores daos) {
        int reclaimed = daos.timers().reclaimStale(Instant.now().minus(STALE_CLAIM));
        if (reclaimed > 0) {
            LOGGER.log(Level.INFO, "[titan] timer sweep reclaimed {0} stale timer(s)", reclaimed);
        }
        for (TimerRow timer : daos.timers().claimDue(BATCH)) {
            fire(daos, timer);
        }
    }

    /** Fire one timer: poke the build's orchestrator, then mark the timer {@code FIRED}. */
    private static void fire(@NonNull TitanStores daos, @NonNull TimerRow timer) {
        enqueueAdvance(daos, timer.buildId);
        daos.timers().markFired(timer.id);
        LOGGER.log(Level.INFO, "[titan] timer {0} ({1}) fired for build {2}",
                new Object[] {timer.id, timer.kind, timer.buildId});
    }

    /** Enqueue an {@code ORCHESTRATE/ADVANCE} task so the orchestrator re-evaluates the build. */
    private static void enqueueAdvance(@NonNull TitanStores daos, long buildId) {
        TaskQueueRow advance = new TaskQueueRow();
        advance.type = "ORCHESTRATE";
        advance.queueName = "default";
        advance.status = "QUEUED";
        advance.priority = 0;
        advance.payloadJson = "{\"action\":\"ADVANCE\",\"buildId\":" + buildId + "}";
        advance.attempts = 0;
        advance.maxAttempts = 3;
        advance.visibilityTimeoutSeconds = 3600;
        advance.buildId = buildId;
        advance.availableAt = Instant.now();
        daos.taskQueue().insert(advance);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl titan-plugin -am test -Dtest=TimerSweeperTest -q`
Expected: PASS — 6 tests.

Note: if `new TimerSweeper()` cannot be constructed in a plain test, the only adaptation needed is to annotate `TimerSweeperTest` with the project's standard test harness (check how other periodic-work tests are set up) — the `sweep(TitanStores)` call itself is unchanged. Do not weaken the assertions.

- [ ] **Step 5: Commit**

```bash
git add titan-plugin/src/main/java/io/adaptiq/titan/timer/TimerSweeper.java \
        titan-plugin/src/test/java/io/adaptiq/titan/timer/TimerSweeperTest.java
git commit -m "feat(titan): add TimerSweeper periodic timer-firing job"
```

---

### Task 5: Final verification

- [ ] **Step 1: Run the full Phase 1 test set together**

Run: `./mvnw -pl titan-plugin -am test -Dtest=TimerDaoTest,TimerServiceTest,TimerSweeperTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: PASS — 26 tests total (14 + 6 + 6).

- [ ] **Step 2: Apply Spotless formatting**

`titan-plugin` and `titan-db-core` are Spotless-governed (`titan-worker` is not — irrelevant here, all Phase 1 Java is in `titan-plugin`).

Run: `./mvnw -pl titan-plugin spotless:apply -q`
Expected: BUILD SUCCESS. (If it reformats only the new files, good; if it also touches unrelated pre-existing files, commit those separately as a `style(titan):` commit, as was done on the prior branch.)

- [ ] **Step 3: Commit any formatting changes**

```bash
git add titan-plugin/src/main/java/io/adaptiq/titan/timer/ \
        titan-plugin/src/test/java/io/adaptiq/titan/timer/ \
        titan-plugin/src/main/java/io/adaptiq/titan/store/TimerDao.java \
        titan-plugin/src/test/java/io/adaptiq/titan/store/TimerDaoTest.java \
        titan-plugin/src/main/java/io/adaptiq/titan/store/rows/TimerRow.java \
        titan-plugin/src/main/java/io/adaptiq/titan/store/TitanStores.java
git diff --cached --quiet || git commit -m "style(titan): spotless format the timer substrate"
```

Do NOT run the full test suite locally (20+ min — forbidden by repo policy).

---

## Self-Review notes

- **Spec coverage (Phase 1 rows of the spec only):** `titan.timers` table → Task 1 (`V8__timers.sql`); `TimerDao`/`TimerRow` → Task 1; `TitanStores` wiring → Task 2; `TimerService` (idempotent `arm`, `cancel`, `cancelAll`) → Task 3; `TimerSweeper` (3-phase: reclaim → claim → fire; enqueues ADVANCE) → Task 4. Phases 2–4 (consumers, grammar, orchestrator handlers) are out of scope for this plan by design.
- **Deviations from the spec, deliberate and consistent with the codebase:** `TIMESTAMP` not `timestamptz`; portable `UPDATE … WHERE id IN (SELECT … LIMIT)` claim not `FOR UPDATE SKIP LOCKED`; a `CLAIMED` status added to the lifecycle so the claim mirrors `DiscoveryEvent`'s proven state machine exactly; `BIGINT IDENTITY` PK not `uuid`; `VARCHAR` payload not `jsonb`. The spec's "DB clock is the only clock" invariant still holds — every `fire_at` comparison uses SQL `CURRENT_TIMESTAMP`.
- **Type consistency:** `TimerRow` field names ↔ `TimerDao.COLS` snake_case columns ↔ `V8__timers.sql` columns all align; `TimerService.Kind.name()` produces the exact strings in the migration's `CHECK (kind IN …)`; `TimerSweeper.enqueueAdvance` matches the verified `QueueProcessor.enqueueAdvance` shape.
- **No grammar/JSON-schema touched** — Phase 1 is pure persistence + a periodic job; `TitanSchemaGenerationTest`/`GrammarSchemaContractTest` are unaffected.
