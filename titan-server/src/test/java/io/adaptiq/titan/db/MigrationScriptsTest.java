package io.adaptiq.titan.db;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link MigrationScripts}. Guards against the bug class of #366 / #377 / #369 —
 * stale hardcoded migration arrays in test helpers. If this test goes red, the auto-discovery
 * helper is broken; if a future PR adds V18__foo.sql and EngineSmokIT/FakeTitanStores stop picking
 * it up, this test will still pass but the real ITs will explode — which is the point (loud fail at
 * IT time, not silent drift).
 */
class MigrationScriptsTest {

  private static final Pattern MIGRATION = Pattern.compile("^V\\d+__.*\\.sql$");

  @Test
  void discoversAtLeastTheKnownSeventeenMigrations() {
    List<String> scripts = MigrationScripts.listInOrder("io/adaptiq/titan/db/migration");
    assertTrue(
        scripts.size() >= 17,
        "expected at least 17 migration scripts (V1..V17 baseline), found "
            + scripts.size()
            + ": "
            + scripts);
  }

  @Test
  void allEntriesMatchMigrationPattern() {
    List<String> scripts = MigrationScripts.listInOrder("io/adaptiq/titan/db/migration");
    for (String s : scripts) {
      assertTrue(MIGRATION.matcher(s).matches(), "entry does not match V<N>__*.sql pattern: " + s);
    }
  }

  @Test
  void sortedAscendingByNumericVersion() {
    List<String> scripts = MigrationScripts.listInOrder("io/adaptiq/titan/db/migration");
    int prev = -1;
    for (String s : scripts) {
      int v = Integer.parseInt(s.substring(1, s.indexOf("__")));
      assertTrue(
          v > prev,
          "scripts not in strictly-ascending version order: "
              + s
              + " (v="
              + v
              + ") came after v="
              + prev
              + " — full list: "
              + scripts);
      prev = v;
    }
  }

  @Test
  void firstScriptIsV1Init() {
    List<String> scripts = MigrationScripts.listInOrder("io/adaptiq/titan/db/migration");
    assertEquals("V1__init.sql", scripts.get(0), "V1__init.sql must be the first migration");
  }

  @Test
  void unknownPackageFailsLoudly() {
    assertThrows(
        IllegalStateException.class,
        () -> MigrationScripts.listInOrder("io/adaptiq/titan/db/no_such_migration_pkg"),
        "missing package must throw, not return empty — silent failure is the bug class we fix");
  }
}
