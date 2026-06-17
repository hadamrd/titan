package io.adaptiq.titan.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.api.dto.BuildDto;
import io.adaptiq.titan.build.BuildServiceImpl;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.JobGithubLinkRow;
import jakarta.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Postgres-backed integration test for the GitHub-App provenance projection on the build-detail
 * endpoint (issue #892). Exercises the real cross-module seam: a {@code github-app}-triggered build
 * row → {@code JobDao.findGithubLinkage} (a real JOIN across {@code jobs} ⋈ {@code
 * github_repositories} on live Postgres) → {@link BuildDto#from(Build, JobGithubLinkRow)} → JSON.
 *
 * <p>Drives the request through the <b>real</b> {@link BuildDetailApi#getBuild} handler (not a
 * replica of its body), so a regression in the handler's conditional dispatch — calling the wrong
 * {@code BuildDto.from} overload, the wrong DAO method, or dropping the linkage lookup — is caught
 * here, in addition to regressions in the DAO query or the mapper. The full Quarkus HTTP stack is
 * deliberately not booted: both {@code @QuarkusTest} harnesses are currently unusable for this seam
 * (the default H2 profile cannot run V34's Postgres-only {@code tsvector} migration; the {@code
 * postgres-it} profile fails augmentation on {@code titan.public-url}), and the conditional
 * dispatch the reviewer flagged lives in {@code getBuild}, which this test invokes directly on live
 * Postgres.
 *
 * <ul>
 *   <li>App build with a resolvable linkage → DTO carries provenance / repoFullName / commitUrl and
 *       the serialized JSON contains all three.
 *   <li>Manual build → DTO omits provenance entirely; the JSON does NOT contain the key
 *       (backward-compat: prior-schema clients deserialize unchanged).
 *   <li>App build whose Job linkage was deleted (orphan) → no crash, provenance suppressed.
 * </ul>
 */
@Testcontainers
class BuildDetailProvenanceIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          // Loaded CI boxes (concurrent Testcontainers + a long-lived dev rig) can starve the
          // ephemeral PG of CPU; the default 60s readiness wait then flakes. Give it headroom.
          .withStartupTimeout(Duration.ofMinutes(3));

  private static final ObjectMapper MAPPER =
      new ObjectMapper().registerModule(new JavaTimeModule());
  private static final String SHA = "abc123def456abc123def456abc123def456abcd";

  private HikariDataSource ds;
  private TitanStores stores;
  private BuildServiceImpl builds;

  @BeforeEach
  void setUp() throws Exception {
    HikariConfig cfg = new HikariConfig();
    cfg.setJdbcUrl(POSTGRES.getJdbcUrl());
    cfg.setUsername(POSTGRES.getUsername());
    cfg.setPassword(POSTGRES.getPassword());
    cfg.setDriverClassName("org.postgresql.Driver");
    cfg.setMaximumPoolSize(4);
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
    builds = new BuildServiceImpl(stores);
  }

  @AfterEach
  void tearDown() {
    if (ds != null) {
      ds.close();
    }
  }

  /**
   * Invokes the <b>real</b> {@link BuildDetailApi#getBuild} handler against the live stores — not a
   * hand-rolled replica of its body. This is what makes the IT load-bear on the handler's actual
   * conditional dispatch ({@code isGithubAppTrigger ? from(build, linkage) : from(build)}): a
   * regression that calls the wrong overload, the wrong DAO method, or drops the linkage lookup is
   * caught here (reviewer's sev2 on PR #1176).
   *
   * <p>The handler's only other collaborators in this path are {@code AuditService} and the {@code
   * BuildStateChangedEvent} channel, neither of which {@code getBuild} touches — so {@code null} is
   * safe for both. {@link io.adaptiq.titan.api.Etag#respond} is null-tolerant on the {@code
   * HttpHeaders} argument (no {@code If-None-Match} to honour), so a null header yields a plain
   * 200.
   */
  private BuildDetailApi handler() {
    return new BuildDetailApi(stores, builds, null, null);
  }

  private BuildDto assembleDetailDto(long buildId) {
    Response resp = handler().getBuild(Long.toString(buildId), null);
    assertEquals(200, resp.getStatus(), "getBuild must return 200 for an existing build");
    return (BuildDto) resp.getEntity();
  }

  @Test
  void appTriggeredBuild_detailDtoCarriesProvenance_andSerializes() throws Exception {
    long installId = 5001L;
    long repoId = 9001L;
    try (Connection c = ds.getConnection()) {
      insertInstall(c, installId, "hadamrd");
      insertRepo(c, installId, repoId, "hadamrd", "titan");
      long jobId = insertLinkedJob(c, "hadamrd/titan", installId, repoId);
      insertBuild(c, jobId, 4711, "github-app:push", buildMetaJson());
    }

    long buildId = latestBuildId();
    BuildDto dto = assembleDetailDto(buildId);

    BuildDto.TriggerMetaDto meta = dto.triggerMeta();
    assertNotNull(meta);
    assertEquals("github-app", meta.provenance());
    assertEquals("hadamrd/titan", meta.repoFullName());
    assertEquals("https://github.com/hadamrd/titan/commit/" + SHA, meta.commitUrl());

    // The wire payload actually carries the three new fields.
    String json = MAPPER.writeValueAsString(dto);
    assertTrue(
        json.contains("\"provenance\":\"github-app\""), "json must carry provenance: " + json);
    assertTrue(json.contains("\"repoFullName\":\"hadamrd/titan\""), json);
    assertTrue(json.contains("/commit/" + SHA), json);

    // And the payload re-parses cleanly into the DTO (no required-field break).
    BuildDto restored = MAPPER.readValue(json, BuildDto.class);
    assertEquals("github-app", restored.triggerMeta().provenance());
  }

  @Test
  void getBuildHandler_appTriggered_responseBodyJsonCarriesProvenance() throws Exception {
    // The BuildsApiIT the #892 test matrix asks for: exercise the endpoint handler end-to-end and
    // assert the JSON payload — not just the in-memory DTO. Guards the HTTP-boundary contract a
    // pure-mapper test cannot (200 status, the linkage-resolving branch was taken, the three new
    // fields survive serialization).
    long installId = 7001L;
    long repoId = 9501L;
    try (Connection c = ds.getConnection()) {
      insertInstall(c, installId, "hadamrd");
      insertRepo(c, installId, repoId, "hadamrd", "titan");
      long jobId = insertLinkedJob(c, "hadamrd/titan", installId, repoId);
      insertBuild(c, jobId, 8080, "github-app:push", buildMetaJson());
    }

    long buildId = latestBuildId();
    Response resp = handler().getBuild(Long.toString(buildId), null);

    assertEquals(200, resp.getStatus());
    assertNotNull(resp.getEntity(), "200 must carry the BuildDto entity");
    assertNotNull(resp.getHeaderString("ETag"), "the real handler routes through Etag.respond");

    String json = MAPPER.writeValueAsString(resp.getEntity());
    assertTrue(
        json.contains("\"provenance\":\"github-app\""), "json must carry provenance: " + json);
    assertTrue(json.contains("\"repoFullName\":\"hadamrd/titan\""), json);
    assertTrue(
        json.contains("\"commitUrl\":\"https://github.com/hadamrd/titan/commit/" + SHA + "\""),
        json);
  }

  @Test
  void getBuildHandler_manualBuild_responseBodyOmitsProvenanceKeys() throws Exception {
    // Backward-compat at the HTTP boundary: a manual build's serialized payload must not mention
    // any of the three #892 keys (NON_NULL stripping), so prior-schema clients parse it unchanged.
    try (Connection c = ds.getConnection()) {
      long jobId = insertJob(c, "plain/handler-job");
      insertBuild(c, jobId, 1, "manual", buildMetaJson());
    }

    long buildId = latestBuildId();
    Response resp = handler().getBuild(Long.toString(buildId), null);
    assertEquals(200, resp.getStatus());

    String json = MAPPER.writeValueAsString(resp.getEntity());
    assertFalse(
        json.contains("provenance"), "manual build JSON must not mention provenance: " + json);
    assertFalse(json.contains("repoFullName"), json);
    assertFalse(json.contains("commitUrl"), json);
  }

  @Test
  void manualBuild_detailDtoOmitsProvenance_priorSchemaUnchanged() throws Exception {
    try (Connection c = ds.getConnection()) {
      long jobId = insertJob(c, "plain/job");
      insertBuild(c, jobId, 1, "manual", buildMetaJson());
    }

    long buildId = latestBuildId();
    BuildDto dto = assembleDetailDto(buildId);

    BuildDto.TriggerMetaDto meta = dto.triggerMeta();
    assertNotNull(meta, "manual builds still carry the webhook meta when present");
    assertNull(meta.provenance());
    assertNull(meta.repoFullName());
    assertNull(meta.commitUrl());

    // Backward-compat: NON_NULL stripping means the three keys never appear on the wire.
    String json = MAPPER.writeValueAsString(dto);
    assertFalse(
        json.contains("provenance"), "manual build JSON must not mention provenance: " + json);
    assertFalse(json.contains("repoFullName"), json);
    assertFalse(json.contains("commitUrl"), json);
  }

  @Test
  void appTriggeredBuild_butLinkageDeleted_doesNotCrash_andSuppressesBadge() throws Exception {
    // A job that has NO github linkage columns set → findGithubLinkage returns empty (orphan).
    try (Connection c = ds.getConnection()) {
      long jobId = insertJob(c, "orphan/job");
      insertBuild(c, jobId, 1, "github-app:push", buildMetaJson());
    }

    long buildId = latestBuildId();
    BuildDto dto = assembleDetailDto(buildId); // must not throw

    BuildDto.TriggerMetaDto meta = dto.triggerMeta();
    assertNotNull(meta);
    assertNull(meta.provenance(), "orphaned App build → provenance suppressed, badge hidden");
    assertNull(meta.commitUrl());
  }

  @Test
  void getBuildHandler_paramBuild_responseBodyJsonCarriesParametersUsed() throws Exception {
    // Ticket #1266 test matrix: GET /api/v1/builds/{id} for a build whose parameters_json holds
    // {"GREETING":"world","MODE":"dev"} returns a JSON body where parametersUsed equals those
    // pairs. Locks the Map serialization through the real handler in the fast Testcontainers gate
    // rather than only in the live-rig e2e.
    try (Connection c = ds.getConnection()) {
      long jobId = insertJob(c, "params/handler-job");
      insertBuildWithParams(c, jobId, 1, "manual", "{\"GREETING\":\"world\",\"MODE\":\"dev\"}");
    }

    long buildId = latestBuildId();
    BuildDto dto = assembleDetailDto(buildId);
    assertNotNull(dto.parametersUsed(), "detail DTO must carry the persisted params");
    assertEquals("world", dto.parametersUsed().get("GREETING"));
    assertEquals("dev", dto.parametersUsed().get("MODE"));

    String json = MAPPER.writeValueAsString(dto);
    assertTrue(json.contains("\"parametersUsed\""), "json must carry parametersUsed: " + json);
    assertTrue(json.contains("\"GREETING\":\"world\""), json);
    assertTrue(json.contains("\"MODE\":\"dev\""), json);
  }

  @Test
  void getBuildHandler_paramLessBuild_responseBodyOmitsParametersUsed() throws Exception {
    // Backward-compat at the HTTP boundary: a build with no parameters_json must omit
    // parametersUsed entirely (JsonInclude.NON_NULL), so prior-schema clients parse it unchanged.
    try (Connection c = ds.getConnection()) {
      long jobId = insertJob(c, "noparams/handler-job");
      insertBuild(c, jobId, 1, "manual", buildMetaJson()); // no parameters_json
    }

    long buildId = latestBuildId();
    BuildDto dto = assembleDetailDto(buildId);
    assertNull(dto.parametersUsed(), "param-less build → parametersUsed null");

    String json = MAPPER.writeValueAsString(dto);
    assertFalse(
        json.contains("parametersUsed"),
        "param-less build JSON must not mention parametersUsed: " + json);
  }

  // ── seed helpers ─────────────────────────────────────────────────────────────

  private static String buildMetaJson() {
    return "{\"branch\":\"main\",\"commitSha\":\"" + SHA + "\",\"actor\":\"octocat\"}";
  }

  private long latestBuildId() throws Exception {
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT MAX(id) FROM titan.builds")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private static void insertInstall(Connection c, long installId, String login) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.github_installations "
                + "(install_id, account_login, account_type, target_type) "
                + "VALUES (?, ?, 'User', 'User')")) {
      ps.setLong(1, installId);
      ps.setString(2, login);
      ps.executeUpdate();
    }
  }

  private static void insertRepo(
      Connection c, long installId, long repoId, String owner, String name) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.github_repositories (install_id, repo_id, owner, name) "
                + "VALUES (?, ?, ?, ?)")) {
      ps.setLong(1, installId);
      ps.setLong(2, repoId);
      ps.setString(3, owner);
      ps.setString(4, name);
      ps.executeUpdate();
    }
  }

  private static long insertJob(Connection c, String fullName) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.jobs (full_name, pipeline_script, config_json) VALUES (?, '', '{}')",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, fullName);
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        keys.next();
        return keys.getLong(1);
      }
    }
  }

  private static long insertLinkedJob(Connection c, String fullName, long installId, long repoId)
      throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.jobs (full_name, pipeline_script, config_json, "
                + "github_installation_id, github_repo_id) VALUES (?, '', '{}', ?, ?)",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, fullName);
      ps.setLong(2, installId);
      ps.setLong(3, repoId);
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        keys.next();
        return keys.getLong(1);
      }
    }
  }

  private static void insertBuildWithParams(
      Connection c, long jobId, int buildNumber, String triggerType, String parametersJson)
      throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.builds (job_id, build_number, status, queued_at, "
                + "trigger_meta_json, triggered_by, trigger_type, parameters_json) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
      ps.setLong(1, jobId);
      ps.setInt(2, buildNumber);
      ps.setString(3, "SUCCESS");
      ps.setTimestamp(4, Timestamp.from(Instant.now()));
      ps.setString(5, buildMetaJson());
      ps.setString(6, "octocat");
      ps.setString(7, triggerType);
      ps.setString(8, parametersJson);
      ps.executeUpdate();
    }
  }

  private static void insertBuild(
      Connection c, long jobId, int buildNumber, String triggerType, String triggerMetaJson)
      throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.builds (job_id, build_number, status, queued_at, "
                + "trigger_meta_json, triggered_by, trigger_type) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
      ps.setLong(1, jobId);
      ps.setInt(2, buildNumber);
      ps.setString(3, "SUCCESS");
      ps.setTimestamp(4, Timestamp.from(Instant.now()));
      if (triggerMetaJson == null) {
        ps.setNull(5, Types.VARCHAR);
      } else {
        ps.setString(5, triggerMetaJson);
      }
      ps.setString(6, "octocat");
      ps.setString(7, triggerType);
      ps.executeUpdate();
    }
  }
}
