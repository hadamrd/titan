package io.adaptiq.titan.db;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the lazy-initialization contract of {@link Database} — the fix for the latent controller
 * DB-config bug where a throwaway embedded H2 "bootstrap pool" opened before the real datasource
 * config had been applied, only to be closed and replaced moments later.
 *
 * <p>The contract under test:
 *
 * <ol>
 *   <li>{@link Database#dataSource()} does NOT lazily boot a pool — calling it before any {@link
 *       Database#reconfigure} is a hard error, never a silent H2 fallback.
 *   <li>Exactly ONE pool exists after a single {@code reconfigure}, and it is the configured one.
 *   <li>An idempotent re-{@code reconfigure} against the same URL reuses the same pool object — no
 *       close/reopen churn (which is what produced the spurious {@code has been closed} race).
 *   <li>The embedded-default fallback is reachable, but only via an EXPLICIT call.
 * </ol>
 */
class DatabaseLazyInitTest {

  @BeforeEach
  void reset() {
    Database.shutdown();
    Database.clearRegistryForTests();
  }

  @AfterEach
  void tearDown() {
    Database.shutdown();
    Database.clearRegistryForTests();
  }

  private static String h2Url(Path dir, String name) {
    return "jdbc:h2:file:" + dir.resolve(name).toAbsolutePath() + ";MODE=PostgreSQL";
  }

  @Test
  void dataSourceBeforeConfigThrowsInsteadOfBootingH2() {
    // The core of the bug: nothing must spin up a pool just because a caller
    // reached for the DataSource early. It must fail loud, not fall back silently.
    assertFalse(Database.isConfigured(), "no pool should exist before reconfigure");
    IllegalStateException ex = assertThrows(IllegalStateException.class, Database::dataSource);
    assertTrue(
        ex.getMessage().contains("no database pool configured"),
        "error must explain the ordering contract, got: " + ex.getMessage());
    assertFalse(Database.isConfigured(), "a failed dataSource() call must not have opened a pool");
  }

  @Test
  void connectionBeforeConfigThrows() {
    assertThrows(IllegalStateException.class, Database::connection);
  }

  @Test
  void exactlyOnePoolOpensAfterReconfigure(@TempDir Path tmp) {
    Database.reconfigure(Database.Config.of(h2Url(tmp, "one")));

    assertTrue(Database.isConfigured(), "pool must exist after reconfigure");
    DataSource ds = Database.dataSource();
    assertNotNull(ds);
    assertTrue(ds instanceof HikariDataSource);
    HikariDataSource hds = (HikariDataSource) ds;
    assertFalse(hds.isClosed(), "the one pool must be open");
    assertTrue(hds.getJdbcUrl().startsWith("jdbc:h2:file:"), "pool is the configured datasource");
  }

  @Test
  void idempotentReconfigureReusesTheSamePoolObject(@TempDir Path tmp) {
    Database.Config cfg = Database.Config.of(h2Url(tmp, "stable"));

    Database.reconfigure(cfg);
    DataSource first = Database.dataSource();

    // Re-applying the SAME config must NOT close and reopen the pool — that
    // close/reopen is exactly what logged the benign "HikariDataSource
    // (titan-db) has been closed" SQLException during the swap.
    Database.reconfigure(cfg);
    DataSource second = Database.dataSource();

    assertSame(
        first, second, "an idempotent reconfigure must reuse the existing pool, not swap it");
    assertFalse(((HikariDataSource) second).isClosed());
  }

  @Test
  void switchingUrlReplacesThePoolWithExactlyOneOpenPool(@TempDir Path tmp) {
    Database.reconfigure(Database.Config.of(h2Url(tmp, "first")));
    HikariDataSource firstPool = (HikariDataSource) Database.dataSource();

    Database.reconfigure(Database.Config.of(h2Url(tmp, "second")));
    HikariDataSource secondPool = (HikariDataSource) Database.dataSource();

    // The first pool is closed, the second is the only live one — never two at once.
    assertTrue(firstPool.isClosed(), "the superseded pool must be closed");
    assertFalse(secondPool.isClosed(), "the new pool must be the live one");
    assertTrue(secondPool.getJdbcUrl().contains("second"));
  }

  @Test
  void embeddedDefaultFallbackIsReachableOnlyExplicitly(@TempDir Path tmp) {
    // dataSource() never reaches the embedded default on its own (asserted above).
    // The deliberate, named fallback DOES open it — that is the legitimate
    // "no external database configured" path.
    Database.reconfigureWithEmbeddedDefault();
    assertTrue(Database.isConfigured());
    HikariDataSource ds = (HikariDataSource) Database.dataSource();
    assertTrue(ds.getJdbcUrl().startsWith("jdbc:h2:file:"), "explicit fallback opens embedded H2");
    assertFalse(ds.isClosed());
  }

  @Test
  void reconfigureWithBlankJdbcUrlFailsClosedInsteadOfFallingBackToH2() {
    // Issue #926 / V1 bar 5: a non-null Config with a blank jdbcUrl used to silently
    // substitute embedded H2 inside resolveJdbcUrl. That was the dev-only escape
    // hatch — a half-populated Config in production-shaped code would never reach
    // its intended database. The guard now fails loud.
    Database.Config halfPopulated = new Database.Config(); // jdbcUrl left null
    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> Database.reconfigure(halfPopulated));
    assertTrue(
        ex.getMessage().contains("Config.jdbcUrl is required"),
        "error must name the missing field and the #926 contract, got: " + ex.getMessage());
    assertFalse(Database.isConfigured(), "the guard must NOT have opened any pool");

    // Same for an explicitly-blank URL.
    Database.Config blank = Database.Config.of("   ");
    assertThrows(IllegalStateException.class, () -> Database.reconfigure(blank));
    assertFalse(Database.isConfigured());

    // Sanity: the legitimate explicit fallback still works (regression guard).
    Database.reconfigureWithEmbeddedDefault();
    assertTrue(Database.isConfigured(), "named embedded fallback must remain functional");
  }

  @Test
  void embeddedDefaultEmitsBootWarnLog() {
    // Issue #934 / V1 bar 5 audit row 6: when the embedded H2 path is selected — whether via
    // the explicit reconfigureWithEmbeddedDefault() or via reconfigure(null) — Database must
    // emit a WARN-level boot log so a production-shaped deployment that lands on this path
    // is loud in operator logs (rather than silently booting against a throwaway H2 file).
    Logger logger = Logger.getLogger(Database.class.getName());
    Level prev = logger.getLevel();
    logger.setLevel(Level.ALL);
    CapturingHandler handler = new CapturingHandler();
    logger.addHandler(handler);
    try {
      Database.reconfigureWithEmbeddedDefault();
      assertTrue(
          handler.records.stream()
              .anyMatch(
                  r ->
                      r.getLevel() == Level.WARNING
                          && r.getMessage() != null
                          && r.getMessage().contains("embedded H2 selected")),
          "embedded fallback must emit a WARNING-level boot log; got: " + handler.records);

      Database.shutdown();
      handler.records.clear();

      Database.reconfigure(null);
      assertTrue(
          handler.records.stream()
              .anyMatch(
                  r ->
                      r.getLevel() == Level.WARNING
                          && r.getMessage() != null
                          && r.getMessage().contains("embedded H2 selected")),
          "reconfigure(null) must also emit the WARN log; got: " + handler.records);
    } finally {
      logger.removeHandler(handler);
      logger.setLevel(prev);
    }
  }

  @Test
  void nonEmbeddedReconfigureDoesNotEmitTheEmbeddedWarn(@TempDir Path tmp) {
    // Negative case: a real (non-embedded) configured path must NOT trip the WARN — otherwise
    // the signal becomes noise and operators learn to ignore it.
    Logger logger = Logger.getLogger(Database.class.getName());
    Level prev = logger.getLevel();
    logger.setLevel(Level.ALL);
    CapturingHandler handler = new CapturingHandler();
    logger.addHandler(handler);
    try {
      Database.reconfigure(Database.Config.of(h2Url(tmp, "real")));
      assertFalse(
          handler.records.stream()
              .anyMatch(
                  r ->
                      r.getLevel() == Level.WARNING
                          && r.getMessage() != null
                          && r.getMessage().contains("embedded H2 selected")),
          "non-embedded reconfigure must NOT emit the embedded WARN; got: " + handler.records);
    } finally {
      logger.removeHandler(handler);
      logger.setLevel(prev);
    }
  }

  private static final class CapturingHandler extends Handler {
    final List<LogRecord> records = new ArrayList<>();

    @Override
    public void publish(LogRecord record) {
      records.add(record);
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}
  }

  @Test
  void shutdownLeavesNoPoolAndDataSourceThrowsAgain(@TempDir Path tmp) {
    Database.reconfigure(Database.Config.of(h2Url(tmp, "down")));
    assertTrue(Database.isConfigured());

    Database.shutdown();

    assertFalse(Database.isConfigured(), "shutdown must drop the pool");
    assertThrows(IllegalStateException.class, Database::dataSource);
  }
}
