package io.adaptiq.titan.api;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.db.MigrationScripts;
import io.adaptiq.titan.store.TitanStores;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * Test helper that produces a {@link TitanStores} backed by an in-memory H2 database with the Titan
 * schema applied. Each call to {@link #create()} gives a fresh isolated store.
 *
 * <p>H2 is schema-compatible enough with PostgreSQL for the DAO layer (JDBI SqlObject + field
 * mapper): simple INSERT/SELECT/UPDATE/DELETE work without modification. The PostgreSQL-specific
 * {@code FOR UPDATE SKIP LOCKED} in {@code TaskQueueDao.selectClaimableId} is not exercised by the
 * API unit tests (it's only called by the QueueProcessor), so H2 mode is sufficient here.
 *
 * <p>This is intentionally a factory, not a subclass — {@link TitanStores} is {@code final}.
 */
public final class FakeTitanStores {

  private FakeTitanStores() {}

  /** Create a fresh TitanStores backed by a new in-memory H2 database. */
  public static TitanStores create() {
    HikariConfig hc = new HikariConfig();
    // Unique DB name per call = full isolation between tests
    hc.setJdbcUrl(
        "jdbc:h2:mem:titan_api_test_"
            + System.nanoTime()
            + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=true;DEFAULT_NULL_ORDERING=HIGH");
    hc.setUsername("sa");
    hc.setPassword("");
    hc.setMaximumPoolSize(5);
    hc.setPoolName("titan-api-test-" + System.nanoTime());
    HikariDataSource ds = new HikariDataSource(hc);

    try {
      applyMigrations(ds);
    } catch (Exception e) {
      ds.close();
      throw new RuntimeException("FakeTitanStores: migration failed", e);
    }

    return TitanStores.forDataSource(ds);
  }

  // ── migration runner (same approach as EngineSmokIT) ──────────────────────

  // Auto-discovered V<N>__*.sql via classpath scan — see MigrationScripts (#369).
  // Hardcoded list went stale 3 times this sprint (#366, #377, #369). Note: the H2 path
  // intentionally uses the portable migration/ dir, NOT migration-postgresql/ (PR #362 overrides
  // that are incompatible with H2's PG-mode subset).
  private static final String MIGRATION_BASE = "io/adaptiq/titan/db/migration";

  private static void applyMigrations(DataSource ds) throws Exception {
    // Register gen_random_uuid() alias in a separate autocommit connection
    // before the migration transaction. H2 aliases are per-database but
    // DDL errors must not pollute the subsequent transactional migration.
    try (Connection ac = ds.getConnection();
        Statement st = ac.createStatement()) {
      try {
        st.execute("CREATE ALIAS gen_random_uuid FOR \"java.util.UUID.randomUUID\"");
      } catch (Exception ignore) {
        // Already registered from a previous call in this JVM — safe to ignore.
      }
    }

    try (Connection conn = ds.getConnection()) {
      conn.setAutoCommit(false);
      try (Statement st = conn.createStatement()) {
        st.execute("CREATE SCHEMA IF NOT EXISTS titan");
      }
      conn.commit();
      for (String script : MigrationScripts.listInOrder(MIGRATION_BASE)) {
        String path = MIGRATION_BASE + "/" + script;
        URL url = FakeTitanStores.class.getClassLoader().getResource(path);
        if (url == null) {
          throw new IllegalStateException("migration not found on classpath: " + path);
        }
        String sql;
        try (InputStream in = url.openStream()) {
          sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Statement st = conn.createStatement()) {
          for (String stmt : splitStatements(sql)) {
            if (!stmt.isBlank()) {
              try {
                st.execute(stmt);
              } catch (Exception e) {
                // H2 doesn't support every PG-specific DDL; skip known-incompatible
                // statements silently. The tables themselves are always created first
                // so the DAO layer works fine.
                String msg = e.getMessage();
                String lower = msg == null ? "" : msg.toLowerCase(java.util.Locale.ROOT);
                if (msg != null
                    && (lower.contains("syntax error")
                        || lower.contains("not supported")
                        || lower.contains("unknown")
                        || msg.contains("COMMENT")
                        // PG-only sequence helpers (setval/nextval/currval) used by id-reseed
                        // migrations — H2 has no equivalent; the test DAOs don't rely on the
                        // reserved high-id range.
                        || msg.contains("not found"))) {
                  // ignore — H2 quirks
                } else {
                  throw e;
                }
              }
            }
          }
        }
        conn.commit();
      }
    }
  }

  private static List<String> splitStatements(String sql) {
    List<String> stmts = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inSingleQuote = false;
    boolean inDollarQuote = false;
    String dollarTag = null;
    int i = 0;
    while (i < sql.length()) {
      char c = sql.charAt(i);
      if (!inSingleQuote
          && !inDollarQuote
          && c == '-'
          && i + 1 < sql.length()
          && sql.charAt(i + 1) == '-') {
        int eol = sql.indexOf('\n', i);
        if (eol == -1) break;
        current.append(sql, i, eol + 1);
        i = eol + 1;
        continue;
      }
      if (!inDollarQuote && c == '\'') {
        inSingleQuote = !inSingleQuote;
        current.append(c);
        i++;
        continue;
      }
      if (!inSingleQuote && !inDollarQuote && c == '$') {
        int end = sql.indexOf('$', i + 1);
        if (end != -1) {
          inDollarQuote = true;
          dollarTag = sql.substring(i, end + 1);
          current.append(sql, i, end + 1);
          i = end + 1;
          continue;
        }
      } else if (inDollarQuote && dollarTag != null && sql.startsWith(dollarTag, i)) {
        current.append(sql, i, i + dollarTag.length());
        i += dollarTag.length();
        inDollarQuote = false;
        dollarTag = null;
        continue;
      }
      if (c == ';' && !inSingleQuote && !inDollarQuote) {
        String stmt = current.toString().trim();
        if (!stmt.isBlank()) stmts.add(stmt);
        current.setLength(0);
      } else {
        current.append(c);
      }
      i++;
    }
    String remainder = current.toString().trim();
    if (!remainder.isBlank()) stmts.add(remainder);
    return stmts;
  }
}
