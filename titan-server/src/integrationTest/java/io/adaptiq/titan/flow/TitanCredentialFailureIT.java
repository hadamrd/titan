package io.adaptiq.titan.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.credentials.Credential;
import io.adaptiq.titan.credentials.CredentialResolver;
import io.adaptiq.titan.credentials.CredentialsService;
import io.adaptiq.titan.credentials.CredentialsServiceImpl;
import io.adaptiq.titan.credentials.DbEnvelopeBackend;
import io.adaptiq.titan.credentials.NewCredentialRequest;
import io.adaptiq.titan.flow.crypto.CredentialKeyProvider;
import io.adaptiq.titan.flow.crypto.EnvCredentialKeyProvider;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for the design/45 failure model's <strong>credential-side</strong> paths —
 * build step 45-T. The orchestrator resolves a step's {@code credentials:} bindings via the
 * injected {@link CredentialResolver} (backed by the Titan {@link CredentialsService}) at dispatch
 * time; a resolution failure (or a fail-closed encryption-key check) fails the step at dispatch
 * with the structured field set on the node.
 *
 * <p>Credentials are seeded via {@link CredentialsService#create} against the {@link
 * DbEnvelopeBackend}. The other failure categories that do not touch credentials are still covered
 * by the lighter {@code TitanFailureModelIT}.
 *
 * <p>Asserts (design/45 §3):
 *
 * <ul>
 *   <li>a {@code credentials:} binding naming an id that is not in the store → {@code
 *       failure_category = CREDENTIAL}, a {@code failure_reason} naming the credential;
 *   <li>a binding whose id <em>is</em> in the store but with no {@code TITAN_CREDENTIAL_KEY}
 *       configured → {@code CREDENTIAL} + the credential-encryption-key reason (fail-closed,
 *       design/39).
 * </ul>
 *
 * <p><strong>Docker:</strong> Testcontainers needs a Docker daemon — run under WSL on a Windows
 * host without Docker Desktop, as the other Titan ITs document.
 */
@Testcontainers
class TitanCredentialFailureIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private HikariDataSource ds;
  private TitanStores stores;
  private long buildId;
  private CredentialsService credentialsService;
  private byte[] seedKey;
  private String savedSystemProperty;

  @BeforeEach
  void setUp() throws Exception {
    // Save and clear the optional system-property fallback the env provider reads — the
    // anUnconfiguredCredentialKey test needs CredentialKeyProvider.active() to return null.
    savedSystemProperty = System.getProperty(EnvCredentialKeyProvider.SYSTEM_PROPERTY);
    System.clearProperty(EnvCredentialKeyProvider.SYSTEM_PROPERTY);

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

    // A fixed, deterministic key for the seed path only — used by DbEnvelopeBackend to seal
    // credentials when the test pre-populates the store. The orchestrator's fail-closed check
    // calls the static CredentialKeyProvider.active() which falls through to the env provider
    // (TITAN_CREDENTIAL_KEY / titan.credentialKey), so cleared above.
    seedKey = new byte[32];
    CredentialKeyProvider seedProvider =
        new CredentialKeyProvider() {
          @Override
          public byte[] credentialKey() {
            return seedKey.clone();
          }

          @Override
          public String describe() {
            return "test:fixed";
          }
        };
    credentialsService = new CredentialsServiceImpl(new DbEnvelopeBackend(stores, seedProvider));
  }

  @AfterEach
  void tearDown() {
    if (savedSystemProperty != null) {
      System.setProperty(EnvCredentialKeyProvider.SYSTEM_PROPERTY, savedSystemProperty);
    } else {
      System.clearProperty(EnvCredentialKeyProvider.SYSTEM_PROPERTY);
    }
    if (ds != null) {
      ds.close();
    }
  }

  private void bootstrap(String fixture) throws Exception {
    try (Connection c = ds.getConnection()) {
      long jobId = insertJob(c, Fixtures.load(fixture));
      buildId = insertBuild(c, jobId);
    }
    new TitanFlowExecution(stores, buildId).bake(Fixtures.load(fixture));
  }

  private TitanOrchestrator orchestrator() {
    return new TitanOrchestrator(stores, buildId, new CredentialResolver(credentialsService));
  }

  /**
   * A {@code credentials:} binding naming an id absent from the Titan credentials store fails the
   * step at dispatch: {@code failure_category = CREDENTIAL}, and the {@code failure_reason} names
   * the missing credential id (design/45 §3, design/39 §5).
   */
  @Test
  void aMissingCredentialFailsTheStepWithCredentialCategory() throws Exception {
    bootstrap("missing-credential.yml");
    // Store is empty — 'e2e-ssh-key' is not resolvable.
    orchestrator().advance();

    FlowNodeRow step = node("deploy-s0");
    assertEquals("FAILED", step.status, "an unresolved credential fails the step at dispatch");
    assertEquals("CREDENTIAL", step.failureCategory);
    assertNotNull(step.failureReason, "a CREDENTIAL failure carries the only copy of the reason");
    assertTrue(
        step.failureReason.contains("e2e-ssh-key"),
        "the reason names the credential; was: " + step.failureReason);
  }

  /**
   * A binding whose credential <em>is</em> in the store, but with no {@code TITAN_CREDENTIAL_KEY}
   * configured, fails closed at dispatch: {@code CREDENTIAL} + the credential-encryption-key reason
   * — Titan refuses to dispatch a step's secrets to the database un-encrypted (design/39).
   */
  @Test
  void anUnconfiguredCredentialKeyFailsTheStepClosed() throws Exception {
    // 'deploy-token' resolves; the test JVM has no TITAN_CREDENTIAL_KEY / titan.credentialKey set
    // (cleared in setUp), so the orchestrator's static CredentialKeyProvider.active() returns
    // null and the fail-closed key check fires.
    credentialsService.create(
        new NewCredentialRequest(Credential.KIND_STRING, "default", "deploy-token", "tok-abc-123"));
    // Sanity: confirm the env provider does return null right now — if a CI runner has
    // TITAN_CREDENTIAL_KEY set in its environment this test would false-pass, so we make the
    // precondition visible.
    org.junit.jupiter.api.Assumptions.assumeTrue(
        new EnvCredentialKeyProvider().credentialKey() == null,
        "TITAN_CREDENTIAL_KEY must be unset in the test environment");

    bootstrap("present-credential.yml");
    orchestrator().advance();

    FlowNodeRow step = node("deploy-s0");
    assertEquals("FAILED", step.status);
    assertEquals(
        "CREDENTIAL", step.failureCategory, "the fail-closed key check is a CREDENTIAL failure");
    assertNotNull(step.failureReason);
    assertTrue(
        step.failureReason.contains("TITAN_CREDENTIAL_KEY"),
        "the reason names the missing key; was: " + step.failureReason);
  }

  /** The structured failure is in place by the time the node is FAILED — ready to render. */
  @Test
  void theFailureFieldIsWrittenBeforeTheNodeIsFailed() throws Exception {
    bootstrap("missing-credential.yml");
    orchestrator().advance();

    FlowNodeRow step = node("deploy-s0");
    assertEquals("FAILED", step.status);
    // updateFailure() runs before the QUEUED->FAILED compare-and-set — both are set together.
    assertNotNull(step.failureCategory);
    assertNotNull(step.failureReason);
  }

  private FlowNodeRow node(String nodeId) {
    return stores.flowNodes().findByBuildAndNode(buildId, nodeId).orElseThrow();
  }

  private static long insertJob(Connection c, String yaml) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.jobs (full_name, pipeline_script, config_json) VALUES (?, ?, '{}')",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, "cred/job-" + System.nanoTime());
      ps.setString(2, yaml);
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        keys.next();
        return keys.getLong(1);
      }
    }
  }

  private static long insertBuild(Connection c, long jobId) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.builds (job_id, build_number, status) VALUES (?, 1, 'QUEUED')",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setLong(1, jobId);
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        keys.next();
        return keys.getLong(1);
      }
    }
  }
}
