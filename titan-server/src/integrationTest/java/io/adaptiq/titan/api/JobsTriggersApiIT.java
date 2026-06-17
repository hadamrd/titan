package io.adaptiq.titan.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.api.dto.JobTriggerDto;
import io.adaptiq.titan.job.JobServiceImpl;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.JobRow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
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
 * Postgres-backed IT for {@link JobTriggersApi} — closes #725.
 *
 * <p>Bypasses the JAX-RS layer (unit-tested elsewhere) and pins the load-bearing contract: the
 * endpoint joins parsed trigger definitions from {@code titan.jobs.config_json} with per-trigger
 * runtime state from {@code titan.job_triggers}, returning a closed-discriminator typed list.
 *
 * <p>Adversarial coverage:
 *
 * <ul>
 *   <li>job with no triggers → empty list
 *   <li>cron trigger, never fired → expression present, lastFiredAt absent, nextFireAt computed
 *   <li>cron trigger that fired yesterday → lastFiredAt populated
 *   <li>cron trigger that errored → lastError populated
 *   <li>github trigger → type=github, expression absent, nextFireAt absent
 *   <li>unknown job id → 404 ({@link ApiNotFoundException})
 * </ul>
 */
@Testcontainers
class JobsTriggersApiIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private HikariDataSource ds;
  private TitanStores stores;
  private JobTriggersApi api;

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
    api = new JobTriggersApi(new JobServiceImpl(stores), stores);
  }

  @AfterEach
  void tearDown() {
    if (ds != null) {
      ds.close();
    }
  }

  // ── helpers ──────────────────────────────────────────────────────────────────

  private long seedJob(String fullName, String configJson) {
    JobRow row = new JobRow();
    row.fullName = fullName;
    row.displayName = fullName;
    row.pipelineScript = "stages: []\n";
    row.configJson = configJson;
    row.enabled = true;
    return stores.jobs().insert(row);
  }

  private void seedTriggerState(long jobId, String triggerId, Instant firedAt, String lastError)
      throws Exception {
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO titan.job_triggers (job_id, trigger_id, last_fired_at, last_error) "
                    + "VALUES (?, ?, ?, ?)")) {
      ps.setLong(1, jobId);
      ps.setString(2, triggerId);
      ps.setTimestamp(3, firedAt == null ? null : Timestamp.from(firedAt));
      ps.setString(4, lastError);
      ps.executeUpdate();
    }
  }

  private static String cronConfig(String triggerId, String spec) {
    return "{\"triggers\":[{\"type\":\"cron\",\"id\":\""
        + triggerId
        + "\",\"spec\":\""
        + spec
        + "\"}]}";
  }

  // ── tests ───────────────────────────────────────────────────────────────────

  @Test
  void noTriggers_returnsEmptyList() {
    long jobId = seedJob("acme/bare-" + System.nanoTime(), "{}");

    List<JobTriggerDto> out = api.listTriggers(Long.toString(jobId));

    assertNotNull(out);
    assertTrue(out.isEmpty(), "job with no triggers must return []");
  }

  @Test
  void cronTrigger_neverFired_expressionPresent_lastFiredAtAbsent() {
    String triggerId = "trig-" + System.nanoTime();
    long jobId =
        seedJob("acme/cron-fresh-" + System.nanoTime(), cronConfig(triggerId, "0 */6 * * *"));

    List<JobTriggerDto> out = api.listTriggers(Long.toString(jobId));

    assertEquals(1, out.size());
    JobTriggerDto t = out.get(0);
    assertEquals(triggerId, t.id());
    assertEquals(JobTriggerDto.Type.cron.name(), t.type());
    assertEquals("0 */6 * * *", t.expression());
    assertNull(t.lastFiredAt(), "never-fired trigger must have lastFiredAt absent");
    assertNull(t.lastError(), "never-fired trigger must have lastError absent");
    assertNotNull(t.nextFireAt(), "cron nextFireAt must be computed for a valid spec");
    assertTrue(
        t.nextFireAt().isAfter(Instant.now().minusSeconds(60)),
        "nextFireAt must be in the future or near-future");
  }

  @Test
  void cronTrigger_firedYesterday_lastFiredAtIsPopulated() throws Exception {
    String triggerId = "trig-" + System.nanoTime();
    long jobId =
        seedJob("acme/cron-fired-" + System.nanoTime(), cronConfig(triggerId, "0 0 * * *"));
    Instant yesterday = Instant.now().minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
    seedTriggerState(jobId, triggerId, yesterday, null);

    List<JobTriggerDto> out = api.listTriggers(Long.toString(jobId));

    assertEquals(1, out.size());
    JobTriggerDto t = out.get(0);
    assertNotNull(t.lastFiredAt(), "lastFiredAt must be surfaced from titan.job_triggers");
    assertEquals(yesterday, t.lastFiredAt().truncatedTo(ChronoUnit.SECONDS));
    assertNull(t.lastError());
  }

  @Test
  void cronTrigger_withError_lastErrorIsPopulated() throws Exception {
    String triggerId = "trig-" + System.nanoTime();
    long jobId = seedJob("acme/cron-err-" + System.nanoTime(), cronConfig(triggerId, "0 0 * * *"));
    seedTriggerState(jobId, triggerId, null, "queue dispatch refused: worker pool drained");

    List<JobTriggerDto> out = api.listTriggers(Long.toString(jobId));

    assertEquals(1, out.size());
    JobTriggerDto t = out.get(0);
    assertNull(t.lastFiredAt(), "errored trigger that never successfully fired stays null");
    assertEquals("queue dispatch refused: worker pool drained", t.lastError());
  }

  @Test
  void githubTrigger_typeGithub_noExpression_noNextFire() {
    String triggerId = "trig-gh-" + System.nanoTime();
    String configJson =
        "{\"triggers\":[{\"type\":\"github\",\"id\":\""
            + triggerId
            + "\",\"branches\":[\"trunk\"],\"events\":[\"push\"],\"credentialsId\":\"gh-secret\"}]}";
    long jobId = seedJob("acme/gh-" + System.nanoTime(), configJson);

    List<JobTriggerDto> out = api.listTriggers(Long.toString(jobId));

    assertEquals(1, out.size());
    JobTriggerDto t = out.get(0);
    assertEquals(JobTriggerDto.Type.github.name(), t.type());
    assertNull(t.expression(), "github triggers do not carry a cron expression");
    assertNull(t.nextFireAt(), "github triggers fire on inbound webhook, not on a wall clock");
  }

  @Test
  void unknownJob_throws404() {
    assertThrows(ApiNotFoundException.class, () -> api.listTriggers(Long.toString(999_999_999L)));
  }
}
