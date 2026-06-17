package io.adaptiq.titan.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.flow.artifact.FilesystemArtifactStore;
import io.adaptiq.titan.worker.step.LogSink;
import io.adaptiq.titan.worker.step.OutputSink;
import io.adaptiq.titan.worker.step.StepRequest;
import io.adaptiq.titan.worker.step.StepResult;
import io.adaptiq.titan.worker.step.builtin.ArchiveArtifactsStepHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for the artifact write-path against real PostgreSQL (design/41 32E-2 / 32E-3) —
 * {@link DbArtifactSink} → {@link FilesystemArtifactStore} → {@code titan.artifact} + {@code
 * titan.fingerprint}, and the {@link ArchiveArtifactsStepHandler} end to end.
 *
 * <p>The {@code titan} schema is loaded from the plugin module's real {@code V*.sql} migrations, so
 * this test also proves {@code V3} applies on PostgreSQL and that the {@code ON CONFLICT} upserts
 * behave — H2 alone could not show either.
 */
@Testcontainers
class ArtifactArchivingTest {

  // SHA-256 of the ASCII string "hello" — the well-known vector.
  private static final String HELLO_SHA256 =
      "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private WorkerDb db;
  private FilesystemArtifactStore store;

  @TempDir private Path artifactRoot;
  @TempDir private Path workspace;

  @BeforeEach
  void setUp() throws Exception {
    Path migrations = Path.of("../titan-db-core/src/main/resources/io/adaptiq/titan/db/migration");
    assertTrue(Files.isDirectory(migrations), "engine migrations not found");
    try (Connection c = conn()) {
      TestMigrations.resetAndApply(c, migrations);
    }
    WorkerConfig cfg =
        new WorkerConfig(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword(),
            "test-worker",
            "Test Worker",
            "linux",
            1,
            "/titan",
            "NORMAL",
            "default",
            "synthesis",
            Path.of("."),
            "",
            Path.of("."),
            1000,
            10000,
            "",
            Map.of());
    db = new WorkerDb(cfg);
    store = new FilesystemArtifactStore(artifactRoot);
  }

  private Connection conn() throws Exception {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  /** Insert a job + a QUEUED build, return the build id. */
  private long insertBuild() throws Exception {
    try (Connection c = conn()) {
      long jobId;
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO titan.jobs (full_name, pipeline_script) VALUES (?, 'x') RETURNING id")) {
        ps.setString(1, "art/job-" + System.nanoTime());
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          jobId = rs.getLong(1);
        }
      }
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO titan.builds (job_id, build_number, status) "
                  + "VALUES (?, 1, 'QUEUED') RETURNING id")) {
        ps.setLong(1, jobId);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          return rs.getLong(1);
        }
      }
    }
  }

  private Path workspaceFile(String relativePath, String content) throws Exception {
    Path file = workspace.resolve(relativePath);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content, StandardCharsets.UTF_8);
    return file;
  }

  // ── DbArtifactSink → store + titan.artifact ───────────────────────────

  @Test
  void archiveStoresTheBlobAndWritesTheArtifactRow() throws Exception {
    long buildId = insertBuild();
    Path file = workspaceFile("target/app.jar", "hello");

    new DbArtifactSink(store, db, buildId, "node-7").archive("target/app.jar", file, false);

    try (Connection c = conn();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT node_id, kind, size_bytes, sha256, storage, storage_ref "
                    + "FROM titan.artifact WHERE build_id=? AND name=?")) {
      ps.setLong(1, buildId);
      ps.setString(2, "target/app.jar");
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next(), "a titan.artifact row must exist");
        assertEquals("node-7", rs.getString("node_id"));
        assertEquals("ARTIFACT", rs.getString("kind"));
        assertEquals(5, rs.getLong("size_bytes"));
        assertEquals(HELLO_SHA256, rs.getString("sha256"));
        assertEquals("fs", rs.getString("storage"));
        // The blob the row points at is readable through the store.
        assertTrue(store.open(rs.getString("storage_ref")).isPresent());
      }
    }
  }

  @Test
  void reArchivingTheSameNameUpsertsOneRowWithTheLatestContent() throws Exception {
    long buildId = insertBuild();
    DbArtifactSink sink = new DbArtifactSink(store, db, buildId, "node-1");

    sink.archive("out/data", workspaceFile("out/data", "first"), false);
    sink.archive("out/data", workspaceFile("out/data", "second-longer"), false);

    try (Connection c = conn();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT COUNT(*) AS n, MAX(size_bytes) AS sz FROM titan.artifact "
                    + "WHERE build_id=? AND kind='ARTIFACT' AND name='out/data'")) {
      ps.setLong(1, buildId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        assertEquals(1, rs.getInt("n"), "re-archive must upsert, not duplicate");
        assertEquals("second-longer".length(), rs.getLong("sz"));
      }
    }
  }

  // ── fingerprinting ────────────────────────────────────────────────────

  @Test
  void fingerprintTrueWritesTheFingerprintAndAProducedEdge() throws Exception {
    long buildId = insertBuild();
    Path file = workspaceFile("dist/app.bin", "hello");

    new DbArtifactSink(store, db, buildId, "node-2").archive("dist/app.bin", file, true);

    try (Connection c = conn()) {
      try (PreparedStatement ps =
          c.prepareStatement(
              "SELECT first_build_id, file_name FROM titan.fingerprint WHERE hash=?")) {
        ps.setString(1, HELLO_SHA256);
        try (ResultSet rs = ps.executeQuery()) {
          assertTrue(rs.next(), "a titan.fingerprint row must exist");
          assertEquals(buildId, rs.getLong("first_build_id"));
          assertEquals("dist/app.bin", rs.getString("file_name"));
        }
      }
      try (PreparedStatement ps =
          c.prepareStatement(
              "SELECT role FROM titan.fingerprint_ref WHERE hash=? AND build_id=?")) {
        ps.setString(1, HELLO_SHA256);
        ps.setLong(2, buildId);
        try (ResultSet rs = ps.executeQuery()) {
          assertTrue(rs.next(), "a fingerprint_ref edge must exist");
          assertEquals("PRODUCED", rs.getString("role"));
        }
      }
    }
  }

  @Test
  void fingerprintFirstBuildWinsAndEdgesAccumulate() throws Exception {
    long firstBuild = insertBuild();
    long secondBuild = insertBuild();
    // Both builds archive byte-identical content — same hash, two PRODUCED edges, one origin.
    new DbArtifactSink(store, db, firstBuild, "n1")
        .archive("a/same", workspaceFile("a/same", "hello"), true);
    new DbArtifactSink(store, db, secondBuild, "n2")
        .archive("b/same", workspaceFile("b/same", "hello"), true);

    try (Connection c = conn()) {
      try (PreparedStatement ps =
          c.prepareStatement("SELECT first_build_id FROM titan.fingerprint WHERE hash=?")) {
        ps.setString(1, HELLO_SHA256);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          assertEquals(
              firstBuild,
              rs.getLong("first_build_id"),
              "the first build to produce the content keeps the origin");
        }
      }
      try (PreparedStatement ps =
          c.prepareStatement(
              "SELECT COUNT(*) FROM titan.fingerprint_ref WHERE hash=? AND role='PRODUCED'")) {
        ps.setString(1, HELLO_SHA256);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          assertEquals(2, rs.getInt(1), "both builds leave a PRODUCED edge");
        }
      }
    }
  }

  // ── the handler, end to end ───────────────────────────────────────────

  @Test
  void theArchiveArtifactsHandlerWritesRowsThroughTheRealSink() throws Exception {
    long buildId = insertBuild();
    workspaceFile("target/one.jar", "hello");
    workspaceFile("target/two.jar", "world");
    workspaceFile("target/skip.txt", "not a jar");

    LogSink silentLog = (stream, text) -> {};
    OutputSink silentOutputs = (key, value) -> {};
    StepRequest request =
        new StepRequest(
            "archiveArtifacts",
            Map.of("artifacts", "target/*.jar", "fingerprint", true),
            workspace,
            Map.of(),
            buildId,
            "build-node",
            null,
            null,
            silentLog,
            silentOutputs,
            new DbArtifactSink(store, db, buildId, "build-node"));

    StepResult result = new ArchiveArtifactsStepHandler().execute(request);
    assertTrue(result.isSuccess(), result.message());

    try (Connection c = conn();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT COUNT(*) FROM titan.artifact WHERE build_id=? AND kind='ARTIFACT'")) {
      ps.setLong(1, buildId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        assertEquals(2, rs.getInt(1), "the two .jar files are archived, the .txt is not");
      }
    }
    try (Connection c = conn();
        PreparedStatement ps =
            c.prepareStatement("SELECT COUNT(*) FROM titan.fingerprint_ref WHERE build_id=?")) {
      ps.setLong(1, buildId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        assertEquals(2, rs.getInt(1), "fingerprint:true records both jars");
      }
    }
  }
}
