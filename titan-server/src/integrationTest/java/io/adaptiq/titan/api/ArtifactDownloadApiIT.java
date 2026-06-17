package io.adaptiq.titan.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.artifact.ArtifactStoreResolver;
import io.adaptiq.titan.flow.artifact.ArtifactKey;
import io.adaptiq.titan.flow.artifact.FilesystemArtifactStore;
import io.adaptiq.titan.flow.artifact.StoredBlob;
import io.adaptiq.titan.store.TitanStores;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Postgres-backed integration test for {@link ArtifactDownloadApi} (closes the #393 loop with PR
 * #391's e2e spec). Exercises the streaming path through a real {@link FilesystemArtifactStore}
 * rooted at a JUnit {@link TempDir} — no environment-driven {@code TITAN_ARTIFACT_*} config
 * required, since this test calls the endpoint's handler directly through a hand-built {@link
 * ArtifactStoreResolver} stand-in.
 *
 * <p>The contract pinned down here is the SQL + the streamed bytes path on real PostgreSQL —
 * matching the {@code StatsApiIT} pattern. The JAX-RS / OIDC glue is unit-test territory (covered
 * by {@link ArtifactDownloadApiTest}).
 */
@Testcontainers
class ArtifactDownloadApiIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private HikariDataSource ds;
  private TitanStores stores;

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
  }

  @AfterEach
  void tearDown() {
    if (ds != null) {
      ds.close();
    }
  }

  @Test
  void downloadEndpoint_servesFilesystemBackedBytes_endToEnd(@TempDir Path artifactRoot)
      throws Exception {
    // 1) Seed the filesystem store with real bytes.
    FilesystemArtifactStore fs = new FilesystemArtifactStore(artifactRoot);
    byte[] payload = ("titan-payload-" + System.nanoTime()).getBytes(StandardCharsets.UTF_8);
    long buildId;
    long artifactId;
    StoredBlob blob;
    try (Connection c = ds.getConnection()) {
      long jobId = insertJob(c, "art-dl-it/job-" + System.nanoTime());
      buildId =
          insertBuild(
              c, jobId, 1, "SUCCESS", Instant.now(), Instant.now(), Instant.now().plusSeconds(1));

      blob =
          fs.put(
              new ArtifactKey(buildId, ArtifactKey.ARTIFACT, "out/report.json"),
              new ByteArrayInputStream(payload));
      artifactId =
          insertArtifact(
              c,
              buildId,
              "ARTIFACT",
              "out/report.json",
              blob.sizeBytes(),
              blob.sha256(),
              "fs",
              blob.storageRef());
    }

    // 2) Round-trip the row through the DAO + the endpoint's handler — exercising the same code
    //    path the JAX-RS layer drives, with the real DB lookup and the real backend.
    ArtifactDownloadApi api =
        ArtifactDownloadApi.forStreamingTestsOnly(
            stores,
            kind -> {
              if (!"fs".equals(kind)) {
                throw new IllegalArgumentException("unexpected kind: " + kind);
              }
              return fs;
            });
    // The 2-arg ctor is the test-only seam (signer + identity = null) — auth is bypassed and
    // we exercise the streaming path directly with no token / no bearer.
    jakarta.ws.rs.core.Response response = api.download(Long.toString(artifactId), null);

    assertEquals(200, response.getStatus(), "download must succeed");
    assertEquals(
        String.valueOf(payload.length),
        response.getHeaderString("Content-Length"),
        "Content-Length must equal row size_bytes");
    assertEquals(
        "attachment; filename=\"report.json\"",
        response.getHeaderString("Content-Disposition"),
        "Content-Disposition uses the basename of the workspace-relative path");

    // 3) Stream the JAX-RS entity through its StreamingOutput to compare bytes.
    java.io.ByteArrayOutputStream sink = new java.io.ByteArrayOutputStream();
    ((jakarta.ws.rs.core.StreamingOutput) response.getEntity()).write(sink);
    assertArrayEquals(payload, sink.toByteArray(), "streamed body must equal the stored bytes");
  }

  @Test
  void downloadEndpoint_missingRow_throws404() {
    ArtifactDownloadApi api =
        ArtifactDownloadApi.forStreamingTestsOnly(
            stores, kind -> new FilesystemArtifactStore(Path.of("/tmp")));
    org.junit.jupiter.api.Assertions.assertThrows(
        ApiNotFoundException.class, () -> api.download("9999999", null));
  }

  @Test
  void downloadEndpoint_rowExistsButBytesGone_throws404(@TempDir Path artifactRoot)
      throws Exception {
    long artifactId;
    try (Connection c = ds.getConnection()) {
      long jobId = insertJob(c, "art-dl-it/orphan-" + System.nanoTime());
      long buildId =
          insertBuild(
              c, jobId, 1, "SUCCESS", Instant.now(), Instant.now(), Instant.now().plusSeconds(1));
      artifactId =
          insertArtifact(
              c,
              buildId,
              "ARTIFACT",
              "vanished.bin",
              42L,
              "0000000000000000000000000000000000000000000000000000000000000000",
              "fs",
              buildId + "/ARTIFACT/vanished.bin"); // never written to disk
    }
    // sanity: the storageRef does not exist on disk
    org.junit.jupiter.api.Assertions.assertFalse(
        Files.exists(artifactRoot.resolve("vanished.bin")));

    ArtifactDownloadApi api =
        ArtifactDownloadApi.forStreamingTestsOnly(
            stores, kind -> new FilesystemArtifactStore(artifactRoot));
    org.junit.jupiter.api.Assertions.assertThrows(
        ApiNotFoundException.class, () -> api.download(Long.toString(artifactId), null));
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static long insertJob(Connection c, String fullName) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.jobs (full_name, pipeline_script, config_json) "
                + "VALUES (?, '', '{}')",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, fullName);
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        keys.next();
        return keys.getLong(1);
      }
    }
  }

  private static long insertBuild(
      Connection c,
      long jobId,
      int buildNumber,
      String status,
      Instant queuedAt,
      Instant startedAt,
      Instant finishedAt)
      throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.builds (job_id, build_number, status, queued_at, "
                + "started_at, finished_at, duration_ms) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setLong(1, jobId);
      ps.setInt(2, buildNumber);
      ps.setString(3, status);
      ps.setTimestamp(4, Timestamp.from(queuedAt));
      ps.setTimestamp(5, Timestamp.from(startedAt));
      ps.setTimestamp(6, Timestamp.from(finishedAt));
      ps.setLong(7, finishedAt.toEpochMilli() - startedAt.toEpochMilli());
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        keys.next();
        return keys.getLong(1);
      }
    }
  }

  private static long insertArtifact(
      Connection c,
      long buildId,
      String kind,
      String name,
      long sizeBytes,
      String sha256,
      String storage,
      String storageRef)
      throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.artifact "
                + "(build_id, node_id, kind, name, size_bytes, sha256, storage, storage_ref) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setLong(1, buildId);
      ps.setString(2, "n1");
      ps.setString(3, kind);
      ps.setString(4, name);
      ps.setLong(5, sizeBytes);
      ps.setString(6, sha256);
      ps.setString(7, storage);
      ps.setString(8, storageRef);
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        keys.next();
        return keys.getLong(1);
      }
    }
  }
}
