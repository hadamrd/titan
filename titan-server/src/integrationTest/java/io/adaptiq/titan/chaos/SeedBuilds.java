package io.adaptiq.titan.chaos;

import io.adaptiq.titan.flow.TitanFlowExecution;
import io.adaptiq.titan.store.TitanStores;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/** Seeds the deterministic chaos build mix and records each build's known terminal state. */
final class SeedBuilds {

  /** One seeded build: its id, the fixture used, and its pre-computed terminal state. */
  record Seeded(long buildId, String fixture, String expectedTerminal) {}

  /** The full seeded plan — the oracle's source of truth. */
  record Plan(List<Seeded> builds) {
    Map<Long, String> expectations() {
      Map<Long, String> m = new LinkedHashMap<>();
      for (Seeded s : builds) {
        m.put(s.buildId(), s.expectedTerminal());
      }
      return m;
    }
  }

  private SeedBuilds() {}

  /** Seed the fixed mix: 8 diamond, 2 sleep/timeout, 2 flaky-retry. */
  static Plan seedAll(DataSource ds, TitanStores stores) throws Exception {
    var builds = new java.util.ArrayList<Seeded>();
    for (int i = 0; i < 8; i++) {
      builds.add(seedOne(ds, stores, "diamond.yml", "SUCCESS"));
    }
    for (int i = 0; i < 2; i++) {
      builds.add(seedOne(ds, stores, "sleep-timeout-retry.yml", "FAILED"));
    }
    for (int i = 0; i < 2; i++) {
      builds.add(seedOne(ds, stores, "flaky-retry.yml", "FAILED"));
    }
    return new Plan(List.copyOf(builds));
  }

  static Seeded seedOne(DataSource ds, TitanStores stores, String fixture, String expectedTerminal)
      throws Exception {
    String yaml = ChaosFixtures.load(fixture);
    long buildId;
    try (Connection c = ds.getConnection()) {
      long jobId;
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO titan.jobs (full_name, pipeline_script, config_json)"
                  + " VALUES (?, ?, '{}')",
              Statement.RETURN_GENERATED_KEYS)) {
        ps.setString(1, "chaos/" + fixture + "-" + System.nanoTime());
        ps.setString(2, yaml);
        ps.executeUpdate();
        try (var keys = ps.getGeneratedKeys()) {
          keys.next();
          jobId = keys.getLong(1);
        }
      }
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO titan.builds (job_id, build_number, status)"
                  + " VALUES (?, 1, 'QUEUED')",
              Statement.RETURN_GENERATED_KEYS)) {
        ps.setLong(1, jobId);
        ps.executeUpdate();
        try (var keys = ps.getGeneratedKeys()) {
          keys.next();
          buildId = keys.getLong(1);
        }
      }
    }
    new TitanFlowExecution(stores, buildId).bake(yaml);
    // Enqueue the initial ORCHESTRATE/ADVANCE task so a real QueueProcessor.tick() loop
    // has something to claim — the cluster is genuinely queue-driven from this point.
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO titan.task_queue "
                    + "(type, queue_name, status, priority, payload_json, attempts, "
                    + "max_attempts, visibility_timeout_seconds, build_id) "
                    + "VALUES ('ORCHESTRATE', 'default', 'QUEUED', 0, ?, 0, 3, 3600, ?)")) {
      ps.setString(1, "{\"action\":\"ADVANCE\",\"buildId\":" + buildId + "}");
      ps.setLong(2, buildId);
      ps.executeUpdate();
    }
    return new Seeded(buildId, fixture, expectedTerminal);
  }
}
