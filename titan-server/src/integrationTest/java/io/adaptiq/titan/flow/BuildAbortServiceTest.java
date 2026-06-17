package io.adaptiq.titan.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.store.TitanStores;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link BuildAbortService} — pure-JDBC, in-memory H2 + Flyway, no container. */
class BuildAbortServiceTest {

  private HikariDataSource ds;
  private TitanStores stores;
  private long buildId;

  @BeforeEach
  void setUp() throws Exception {
    HikariConfig cfg = new HikariConfig();
    cfg.setJdbcUrl(
        "jdbc:h2:mem:buildabort-"
            + System.nanoTime()
            + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE");
    cfg.setDriverClassName("org.h2.Driver");
    cfg.setMaximumPoolSize(2);
    ds = new HikariDataSource(cfg);

    Flyway.configure(getClass().getClassLoader())
        .dataSource(ds)
        .locations("classpath:io/adaptiq/titan/db/migration")
        .load()
        .migrate();

    stores = TitanStores.forDataSource(ds);

    try (Connection c = ds.getConnection()) {
      long jobId;
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO titan.jobs (full_name, pipeline_script, config_json) VALUES (?, ?, '{}')",
              Statement.RETURN_GENERATED_KEYS)) {
        ps.setString(1, "abort/job");
        ps.setString(2, "echo test");
        ps.executeUpdate();
        try (ResultSet keys = ps.getGeneratedKeys()) {
          keys.next();
          jobId = keys.getLong(1);
        }
      }
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO titan.builds (job_id, build_number, status) VALUES (?, 1, 'QUEUED')",
              Statement.RETURN_GENERATED_KEYS)) {
        ps.setLong(1, jobId);
        ps.executeUpdate();
        try (ResultSet keys = ps.getGeneratedKeys()) {
          keys.next();
          buildId = keys.getLong(1);
        }
      }
    }
  }

  @AfterEach
  void tearDown() {
    if (ds != null) {
      ds.close();
    }
  }

  @Test
  void abortCancelsArmedTimers() {
    stores.timers().armIfAbsent(buildId, "sleeper", "SLEEP", Instant.now().plusSeconds(3600), null);

    BuildAbortService.AbortOutcome outcome = BuildAbortService.abort(stores, buildId, "tester");

    assertTrue(outcome.aborted(), "the build must abort");
    assertEquals(
        "CANCELLED",
        stores.timers().listByBuild(buildId).get(0).status,
        "an aborted build's durable timers must be cancelled");
  }
}
