package io.adaptiq.titan.worker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Shared loader for the engine's Flyway {@code V*.sql} baseline against the Testcontainers Postgres
 * the worker ITs spin up. Centralised so every IT in this module loads the schema the same way and
 * a future schema-loading bug only needs one fix.
 *
 * <p>Two non-obvious behaviours are load-bearing — the worker ITs that hand-rolled this previously
 * tripped on both:
 *
 * <ul>
 *   <li><b>Numeric version ordering.</b> {@code V10__} sorts BEFORE {@code V1__} lexicographically,
 *       so a naive {@code Comparator.comparing(getFileName)} runs V10 first against an empty
 *       database and dies with "schema titan does not exist". We sort by the numeric version.
 *   <li><b>One statement per {@code execute()}.</b> pgjdbc parses a multi-statement string against
 *       the catalog before executing <em>any</em> of it, so {@code CREATE SCHEMA titan; CREATE
 *       TABLE titan.jobs(...)} as one string fails to resolve {@code titan.jobs} even though the
 *       schema is created earlier in the same string. We split each migration on the top-level
 *       {@code ;} (none of our V*.sql files use PL/pgSQL or dollar-quoting, so the naive splitter
 *       is sufficient — and it matches Flyway's own one-statement-per-execute behaviour, which is
 *       the contract the migrations were authored against).
 * </ul>
 */
final class TestMigrations {

  private TestMigrations() {}

  /**
   * Drop and re-create the {@code titan} schema by replaying every {@code V*.sql} migration found
   * under {@code migrationsDir}, in numeric version order.
   */
  static void resetAndApply(Connection c, Path migrationsDir) throws Exception {
    List<Path> scripts;
    try (var files = Files.list(migrationsDir)) {
      scripts =
          files
              .filter(p -> p.getFileName().toString().matches("V\\d+__.*\\.sql"))
              .sorted(Comparator.comparingInt(TestMigrations::versionOf))
              .toList();
    }
    try (Statement st = c.createStatement()) {
      st.execute("DROP SCHEMA IF EXISTS titan CASCADE");
      for (Path script : scripts) {
        for (String stmt : splitSqlStatements(Files.readString(script))) {
          if (!stmt.isBlank()) {
            st.execute(stmt);
          }
        }
      }
    }
  }

  /** Extract the Flyway version number N from a {@code V<N>__name.sql} path. */
  private static int versionOf(Path p) {
    String name = p.getFileName().toString();
    int us = name.indexOf("__");
    return Integer.parseInt(name.substring(1, us));
  }

  /** Split a SQL file into individual statements on the top-level {@code ;}. */
  private static List<String> splitSqlStatements(String sql) {
    List<String> out = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    for (String line : sql.split("\n")) {
      String stripped = line.replaceFirst("--.*$", "");
      cur.append(stripped).append('\n');
      if (stripped.contains(";")) {
        String s = cur.toString().trim();
        if (!s.isEmpty()) {
          out.add(s);
        }
        cur.setLength(0);
      }
    }
    String tail = cur.toString().trim();
    if (!tail.isEmpty()) {
      out.add(tail);
    }
    return out;
  }
}
