package io.adaptiq.titan.chaos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import io.adaptiq.titan.flow.TitanOrchestrator;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import io.adaptiq.titan.timer.TimerSweepWorker;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.ToxiproxyContainer;

class TitanChaosRigIT {

  // Per-test-method containers (instance fields, not static) — every @Test gets its own
  // PostgreSQL + Toxiproxy + Docker Network, so nothing leaks between methods. Costs ~5s of
  // container startup per test (×3 = +15s total); the alternative — sharing one PG across tests
  // — caused intermittent cross-test contamination via lingering Hikari pools or Toxiproxy
  // toxics that survived a DROP SCHEMA CASCADE.
  // Containers are managed manually in @BeforeEach/@AfterEach (not @Container) so each test
  // method gets a brand-new PG + Toxiproxy + Network instance.
  private Network net;
  private PostgreSQLContainer<?> postgres;
  private ToxiproxyContainer toxiproxy;

  /** Toxiproxy proxy fronting Postgres — used by {@link #engineConvergesUnderChaos()}. */
  private Proxy dbProxy;

  @BeforeEach
  void createProxy() throws Exception {
    net = Network.newNetwork();
    postgres =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withNetwork(net)
            .withNetworkAliases("postgres");
    toxiproxy = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.9.0").withNetwork(net);
    postgres.start();
    toxiproxy.start();
    ToxiproxyClient client = new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort());
    // Proxy listens on 8666 inside the Toxiproxy container, forwarding to Postgres on the
    // shared Docker network. ToxiproxyContainer pre-exposes the 8666-8697 port range.
    dbProxy = client.createProxy("postgres", "0.0.0.0:8666", "postgres:5432");
  }

  @AfterEach
  void closeNetwork() {
    if (toxiproxy != null) {
      toxiproxy.stop();
    }
    if (postgres != null) {
      postgres.stop();
    }
    if (net != null) {
      net.close();
    }
  }

  private HikariDataSource dataSource(String jdbcUrl) {
    HikariConfig cfg = new HikariConfig();
    cfg.setJdbcUrl(jdbcUrl);
    cfg.setUsername(postgres.getUsername());
    cfg.setPassword(postgres.getPassword());
    cfg.setDriverClassName("org.postgresql.Driver");
    cfg.setMaximumPoolSize(8);
    cfg.setConnectionTimeout(10_000L);
    return new HikariDataSource(cfg);
  }

  /** Wipes and re-creates the {@code titan} schema, then runs every Flyway migration. */
  private static void migrate(HikariDataSource ds) throws Exception {
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement()) {
      st.execute("DROP SCHEMA IF EXISTS titan CASCADE");
      st.execute("CREATE SCHEMA titan");
    }
    Flyway.configure(TitanChaosRigIT.class.getClassLoader())
        .dataSource(ds)
        .schemas("titan")
        .defaultSchema("titan")
        .locations(
            "classpath:io/adaptiq/titan/db/migration",
            "classpath:io/adaptiq/titan/db/migration-postgresql")
        .load()
        .migrate();
  }

  @Test
  void singleBuildConvergesSingleThreaded() throws Exception {
    try (HikariDataSource ds = dataSource(postgres.getJdbcUrl())) {
      migrate(ds);
      TitanStores stores = TitanStores.forDataSource(ds);

      long buildId = SeedBuilds.seedOne(ds, stores, "diamond.yml", "SUCCESS").buildId();

      TitanOrchestrator orchestrator = new TitanOrchestrator(stores, buildId);
      TitanOrchestrator.AdvanceResult result = null;
      // generous cap; the diamond converges in well under 10 passes
      for (int pass = 0; pass < 60; pass++) {
        result = orchestrator.advance();
        if (result.buildFinished()) {
          break;
        }
        new TimerSweepWorker().sweep(stores);
        Optional<TaskQueueRow> claimed;
        while ((claimed = stores.taskQueue().claim("skeleton-worker", "chaos", UUID.randomUUID()))
            .isPresent()) {
          TaskQueueRow t = claimed.get();
          int exit = ChaosWorker.runRealSubprocess(t.payloadJson);
          stores
              .taskQueue()
              .complete(t.id, t.claimToken, "COMPLETED", "{\"exitCode\":" + exit + "}");
        }
      }
      if (result == null || !result.buildFinished()) {
        throw new AssertionError("diamond build did not finish within 60 advance passes");
      }
      assertEquals("SUCCESS", result.buildResult(), "diamond build must converge to SUCCESS");
      assertEquals(
          "SUCCESS",
          stores.builds().findById(buildId).orElseThrow().status,
          "persisted build row must be SUCCESS");
      assertEquals(
          0, stores.flowNodes().countNonTerminal(buildId), "every flow node must be terminal");
    }
  }

  @Test
  void clusterConvergesWithoutChaos() throws Exception {
    try (HikariDataSource workerDs = dataSource(postgres.getJdbcUrl())) {
      migrate(workerDs);
      TitanStores workerStores = TitanStores.forDataSource(workerDs);
      SeedBuilds.Plan plan = SeedBuilds.seedAll(workerDs, workerStores);
      List<Long> buildIds = plan.builds().stream().map(SeedBuilds.Seeded::buildId).toList();

      ChaosLedger ledger = new ChaosLedger(Files.createTempDirectory("chaos-ledger"));
      List<HikariDataSource> ctlPools = new ArrayList<>();
      ChaosRig rig =
          new ChaosRig(
              3,
              4,
              20,
              ledger,
              () -> {
                HikariDataSource d = dataSource(postgres.getJdbcUrl());
                ctlPools.add(d);
                return TitanStores.forDataSource(d);
              },
              workerStores);
      boolean converged = false;
      try {
        rig.start();
        long deadline = System.currentTimeMillis() + 120_000L;
        while (System.currentTimeMillis() < deadline) {
          boolean allDone =
              buildIds.stream()
                  .allMatch(
                      id -> isTerminal(workerStores.builds().findById(id).orElseThrow().status));
          if (allDone) {
            converged = true;
            break;
          }
          Thread.sleep(500L);
        }
      } finally {
        rig.stop();
        ctlPools.forEach(HikariDataSource::close);
      }
      assertTrue(converged, "cluster did not converge within 120s");
      new ConvergenceOracle(workerStores, workerDs, plan.expectations(), ledger).assertConverged();
    }
  }

  @Test
  void engineConvergesUnderChaos() throws Exception {
    long seed = Long.getLong("chaos.seed", 20260519L);

    String proxiedJdbcUrl =
        "jdbc:postgresql://"
            + toxiproxy.getHost()
            + ":"
            + toxiproxy.getMappedPort(8666)
            + "/"
            + postgres.getDatabaseName();

    // Migrate over a DIRECT connection — never chaos the schema migration.
    try (HikariDataSource directDs = dataSource(postgres.getJdbcUrl())) {
      migrate(directDs);
    }

    try (HikariDataSource workerDs = dataSource(proxiedJdbcUrl)) {
      TitanStores workerStores = TitanStores.forDataSource(workerDs);
      SeedBuilds.Plan plan;
      try (HikariDataSource seedDs = dataSource(postgres.getJdbcUrl())) {
        plan = SeedBuilds.seedAll(seedDs, TitanStores.forDataSource(seedDs));
      }
      List<Long> buildIds = plan.builds().stream().map(SeedBuilds.Seeded::buildId).toList();

      ChaosLedger ledger = new ChaosLedger(Files.createTempDirectory("chaos-ledger"));
      List<HikariDataSource> ctlPools = new ArrayList<>();
      ChaosRig rig =
          new ChaosRig(
              3,
              4,
              20,
              ledger,
              () -> {
                HikariDataSource d = dataSource(proxiedJdbcUrl);
                ctlPools.add(d);
                return TitanStores.forDataSource(d);
              },
              workerStores);
      ChaosMonkey monkey =
          new ChaosMonkey(
              seed,
              dbProxy,
              rig.controllerPauseFlags(),
              rig.workerPauseFlags(),
              rig.workerCrashSignals());
      try {
        rig.start();
        monkey.runChaosWindow(45_000L);
        // Liveness: with chaos off, the engine must make measurable progress unaided.
        long before = countNonTerminal(buildIds, workerStores);
        // Must be > reapTimeoutSeconds (20s) so crash-abandoned CLAIMED tasks are reaped
        // and re-queued before we sample 'after'. 25s gives a 5s margin.
        Thread.sleep(25_000L); // bounded settle
        long after = countNonTerminal(buildIds, workerStores);
        ConvergenceOracle.assertMakingProgress(before, after);
        rig.quiesce(35_000L); // remaining calm period (60s total minus the 25s liveness sample)
      } finally {
        rig.stop();
        ctlPools.forEach(HikariDataSource::close);
      }

      try (HikariDataSource verifyDs = dataSource(postgres.getJdbcUrl())) {
        new ConvergenceOracle(
                TitanStores.forDataSource(verifyDs), verifyDs, plan.expectations(), ledger)
            .assertConverged();
      }
    }
  }

  private static boolean isTerminal(String buildStatus) {
    return "SUCCESS".equals(buildStatus)
        || "FAILED".equals(buildStatus)
        || "ABORTED".equals(buildStatus);
  }

  private static long countNonTerminal(List<Long> buildIds, TitanStores stores) {
    return buildIds.stream()
        .filter(id -> !isTerminal(stores.builds().findById(id).orElseThrow().status))
        .count();
  }
}
