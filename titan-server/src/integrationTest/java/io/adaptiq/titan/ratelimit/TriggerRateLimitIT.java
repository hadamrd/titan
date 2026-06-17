package io.adaptiq.titan.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.audit.AuditAction;
import io.adaptiq.titan.audit.AuditTargetType;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.AuditLogRow;
import io.adaptiq.titan.store.rows.JobRow;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Testcontainers IT for the manual-trigger rate limiter (closes #739).
 *
 * <p>Drives {@link TriggerRateLimiter} with a controllable {@link Clock} and verifies that
 * rejections can be recorded into {@code titan.audit_log} as {@link
 * AuditAction#TRIGGER_RATE_LIMITED} — the same row {@link io.adaptiq.titan.api.JobBuildsApi} writes
 * via {@code AuditService} on the 429 path.
 *
 * <p>The JAX-RS surface is unit-tested by {@code BuildsApiTest} ({@code @QuarkusTest} / H2); here
 * we pin the load-bearing stateful contract — token-bucket arithmetic, per-key independence, refill
 * semantics, audit row shape — against a real Postgres.
 *
 * <p>Cases:
 *
 * <ul>
 *   <li>6 rapid acquires same (user, job) → first 5 allowed, 6th denied with {@code retryAfter}
 *       &gt;= 1
 *   <li>different user, same job → independent bucket
 *   <li>same user, different job → independent bucket
 *   <li>audit row for the denial lands in {@code titan.audit_log} with the right action+target
 *   <li>clock advances 61s → token replenishes
 * </ul>
 */
@Testcontainers
class TriggerRateLimitIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private HikariDataSource ds;
  private TitanStores stores;

  @BeforeEach
  void setUp() throws Exception {
    HikariConfig cfg = new HikariConfig();
    cfg.setJdbcUrl(POSTGRES.getJdbcUrl());
    cfg.setUsername(POSTGRES.getUsername());
    cfg.setPassword(POSTGRES.getPassword());
    cfg.setDriverClassName("org.postgresql.Driver");
    cfg.setMaximumPoolSize(8);
    ds = new HikariDataSource(cfg);

    try (Connection c = ds.getConnection();
        Statement st = c.createStatement()) {
      st.execute("DROP SCHEMA IF EXISTS titan CASCADE");
      st.execute("CREATE SCHEMA titan");
    }
    Flyway.configure(getClass().getClassLoader())
        .dataSource(ds)
        .schemas("titan")
        .defaultSchema("titan")
        .locations(
            "classpath:io/adaptiq/titan/db/migration",
            "classpath:io/adaptiq/titan/db/migration-postgresql")
        .load()
        .migrate();
    stores = TitanStores.forDataSource(ds);
  }

  @AfterEach
  void tearDown() {
    if (ds != null) {
      ds.close();
    }
  }

  // ── helpers ──────────────────────────────────────────────────────────────────

  private long seedJob(String fullName) {
    JobRow row = new JobRow();
    row.fullName = fullName;
    row.displayName = fullName;
    row.pipelineScript = "stages: []\n";
    row.configJson = "{}";
    row.enabled = true;
    return stores.jobs().insert(row);
  }

  private static TriggerRateLimiter limiter(Clock clock) {
    return new TriggerRateLimiter(TriggerRateLimitConfig.DEFAULT, clock);
  }

  /** Emit an audit row the same way {@code AuditService.record} would — but without CDI. */
  private void writeAuditRow(
      String actor,
      AuditAction action,
      AuditTargetType targetType,
      String targetId,
      String detailsJson) {
    AuditLogRow row = new AuditLogRow();
    row.actor = actor;
    row.action = action.name();
    row.targetType = targetType.name();
    row.targetId = targetId;
    row.detailsJson = detailsJson;
    stores.auditLog().insert(row);
  }

  // ── tests ───────────────────────────────────────────────────────────────────

  @Test
  void sixRapidAcquires_sameUserSameJob_fiveAllowedSixthDenied() {
    Clock fixed = Clock.fixed(Instant.parse("2026-05-24T10:00:00Z"), ZoneOffset.UTC);
    TriggerRateLimiter rl = limiter(fixed);
    long jobId = seedJob("acme/rl-burst-" + System.nanoTime());

    for (int i = 1; i <= 5; i++) {
      RateLimitDecision d = rl.acquire("alice-sub", jobId);
      assertInstanceOf(
          RateLimitDecision.Allowed.class, d, "call #" + i + " must be allowed within burst=5");
    }

    RateLimitDecision sixth = rl.acquire("alice-sub", jobId);
    RateLimitDecision.Denied denied =
        assertInstanceOf(
            RateLimitDecision.Denied.class, sixth, "6th call must be denied — burst exhausted");
    assertTrue(
        denied.retryAfterSeconds() >= 1 && denied.retryAfterSeconds() <= 60,
        "retryAfterSeconds must be in [1, 60], got " + denied.retryAfterSeconds());
  }

  @Test
  void differentUser_sameJob_independentBucket() {
    Clock fixed = Clock.fixed(Instant.parse("2026-05-24T10:00:00Z"), ZoneOffset.UTC);
    TriggerRateLimiter rl = limiter(fixed);
    long jobId = seedJob("acme/rl-multiuser-" + System.nanoTime());

    // Alice drains her bucket on jobId.
    for (int i = 0; i < 5; i++) {
      assertInstanceOf(RateLimitDecision.Allowed.class, rl.acquire("alice-sub", jobId));
    }
    assertInstanceOf(
        RateLimitDecision.Denied.class,
        rl.acquire("alice-sub", jobId),
        "alice's bucket must be empty");

    // Bob fires on the same job — independent bucket, full burst available.
    for (int i = 1; i <= 5; i++) {
      assertInstanceOf(
          RateLimitDecision.Allowed.class,
          rl.acquire("bob-sub", jobId),
          "bob call #" + i + " must be independent of alice's bucket");
    }
    assertInstanceOf(
        RateLimitDecision.Denied.class,
        rl.acquire("bob-sub", jobId),
        "bob's bucket exhausts independently after 5");
  }

  @Test
  void sameUser_differentJobs_independentBuckets() {
    Clock fixed = Clock.fixed(Instant.parse("2026-05-24T10:00:00Z"), ZoneOffset.UTC);
    TriggerRateLimiter rl = limiter(fixed);
    long jobA = seedJob("acme/rl-jobA-" + System.nanoTime());
    long jobB = seedJob("acme/rl-jobB-" + System.nanoTime());

    for (int i = 0; i < 5; i++) {
      assertInstanceOf(RateLimitDecision.Allowed.class, rl.acquire("alice-sub", jobA));
    }
    assertInstanceOf(RateLimitDecision.Denied.class, rl.acquire("alice-sub", jobA));

    for (int i = 1; i <= 5; i++) {
      assertInstanceOf(
          RateLimitDecision.Allowed.class,
          rl.acquire("alice-sub", jobB),
          "alice call #" + i + " on jobB must be independent of jobA");
    }
  }

  @Test
  void deniedAcquire_emittedAsAuditEvent() {
    Clock fixed = Clock.fixed(Instant.parse("2026-05-24T10:00:00Z"), ZoneOffset.UTC);
    TriggerRateLimiter rl = limiter(fixed);
    long jobId = seedJob("acme/rl-audit-" + System.nanoTime());

    for (int i = 0; i < 5; i++) {
      rl.acquire("carol-sub", jobId);
    }
    RateLimitDecision sixth = rl.acquire("carol-sub", jobId);
    RateLimitDecision.Denied denied = assertInstanceOf(RateLimitDecision.Denied.class, sixth);

    // Mirror JobBuildsApi.triggerBuild's emission shape.
    writeAuditRow(
        "carol-sub",
        AuditAction.TRIGGER_RATE_LIMITED,
        AuditTargetType.JOB,
        Long.toString(jobId),
        "{\"retryAfterSeconds\":" + denied.retryAfterSeconds() + "}");

    List<AuditLogRow> rows =
        stores
            .auditLog()
            .findRecent(null, AuditAction.TRIGGER_RATE_LIMITED.name(), null, null, 50, 0);
    AuditLogRow hit =
        rows.stream().filter(r -> Long.toString(jobId).equals(r.targetId)).findFirst().orElse(null);
    assertNotNull(hit, "TRIGGER_RATE_LIMITED row must be persisted for the throttled call");
    assertEquals("carol-sub", hit.actor);
    assertEquals(AuditTargetType.JOB.name(), hit.targetType);
    assertTrue(
        hit.detailsJson != null && hit.detailsJson.contains("retryAfterSeconds"),
        "details must surface retryAfterSeconds, got: " + hit.detailsJson);
  }

  @Test
  void clockAdvancePastRefillWindow_tokenReplenishes() {
    Instant t0 = Instant.parse("2026-05-24T10:00:00Z");
    MutableClock clock = new MutableClock(t0);
    TriggerRateLimiter rl = new TriggerRateLimiter(TriggerRateLimitConfig.DEFAULT, clock);
    long jobId = seedJob("acme/rl-refill-" + System.nanoTime());

    for (int i = 0; i < 5; i++) {
      assertInstanceOf(RateLimitDecision.Allowed.class, rl.acquire("dave-sub", jobId));
    }
    assertInstanceOf(RateLimitDecision.Denied.class, rl.acquire("dave-sub", jobId));

    // Advance 61s — refill=1/min, so one token must have re-materialised.
    clock.advance(61, ChronoUnit.SECONDS);
    assertInstanceOf(
        RateLimitDecision.Allowed.class,
        rl.acquire("dave-sub", jobId),
        "token must replenish after the refill window elapses");

    // Immediately deny again — only one token replenished.
    assertInstanceOf(RateLimitDecision.Denied.class, rl.acquire("dave-sub", jobId));
  }

  // ── tiny mutable-clock seam ─────────────────────────────────────────────────

  private static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant start) {
      this.now = start;
    }

    void advance(long amount, ChronoUnit unit) {
      this.now = this.now.plus(amount, unit);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
