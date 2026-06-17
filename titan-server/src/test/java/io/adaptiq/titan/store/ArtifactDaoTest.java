package io.adaptiq.titan.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.store.rows.ArtifactRow;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.JobRow;
import java.lang.reflect.Method;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Direct DAO tests for {@link ArtifactDao#countByBuild(long)} and {@link
 * ArtifactDao#findByBuildPaged(long, int, int)}.
 *
 * <p>Backed by the same H2 schema-loader the API tests use ({@code FakeTitanStores}), reached via
 * reflection because {@code FakeTitanStores} lives in the API test source set. Each test gets a
 * fresh, isolated database — no shared state across tests in this class.
 */
class ArtifactDaoTest {

  private TitanStores stores;
  private long buildId;

  @BeforeEach
  void setUp() throws Exception {
    Class<?> fake = Class.forName("io.adaptiq.titan.api.FakeTitanStores");
    Method create = fake.getDeclaredMethod("create");
    create.setAccessible(true);
    stores = (TitanStores) create.invoke(null);

    JobRow j = new JobRow();
    j.fullName = "art/dao-" + System.nanoTime();
    j.enabled = true;
    j.pipelineScript = "";
    j.configJson = "{}";
    long jobId = stores.jobs().insert(j);

    BuildRow b = new BuildRow();
    b.jobId = jobId;
    b.buildNumber = stores.builds().nextBuildNumber(jobId);
    b.status = "SUCCESS";
    b.queuedAt = Instant.now();
    b.triggeredBy = "test";
    b.triggerType = "manual";
    buildId = stores.withTransaction(c -> stores.builds().insert(c, b));
  }

  // ── countByBuild ──────────────────────────────────────────────────────────

  @Test
  void countByBuild_emptyReturnsZero() {
    assertEquals(0, stores.artifacts().countByBuild(buildId));
  }

  @Test
  void countByBuild_excludesStashRows() {
    insertArtifact(buildId, "ARTIFACT", "a.bin");
    insertArtifact(buildId, "ARTIFACT", "b.bin");
    insertArtifact(buildId, "STASH", "intra");

    // 2 ARTIFACTs + 1 STASH — count must report only the 2 visible rows.
    assertEquals(2, stores.artifacts().countByBuild(buildId));
  }

  @Test
  void countByBuild_isolatesByBuildId() {
    insertArtifact(buildId, "ARTIFACT", "mine.bin");

    // Build a second job + build and put 3 artifacts under it; the count for the
    // first build must remain 1.
    JobRow j2 = new JobRow();
    j2.fullName = "art/dao-other-" + System.nanoTime();
    j2.enabled = true;
    j2.pipelineScript = "";
    j2.configJson = "{}";
    long otherJob = stores.jobs().insert(j2);
    BuildRow b2 = new BuildRow();
    b2.jobId = otherJob;
    b2.buildNumber = stores.builds().nextBuildNumber(otherJob);
    b2.status = "SUCCESS";
    b2.queuedAt = Instant.now();
    b2.triggeredBy = "test";
    b2.triggerType = "manual";
    long otherBuild = stores.withTransaction(c -> stores.builds().insert(c, b2));

    insertArtifact(otherBuild, "ARTIFACT", "x.bin");
    insertArtifact(otherBuild, "ARTIFACT", "y.bin");
    insertArtifact(otherBuild, "ARTIFACT", "z.bin");

    assertEquals(1, stores.artifacts().countByBuild(buildId));
    assertEquals(3, stores.artifacts().countByBuild(otherBuild));
  }

  // ── findByBuildPaged ──────────────────────────────────────────────────────

  @Test
  void findByBuildPaged_emptyReturnsEmptyList() {
    List<ArtifactRow> page = stores.artifacts().findByBuildPaged(buildId, 0, 100);
    assertTrue(page.isEmpty());
  }

  @Test
  void findByBuildPaged_orderedByIdDesc() {
    for (int i = 1; i <= 5; i++) {
      insertArtifact(buildId, "ARTIFACT", "f-" + i + ".bin");
    }

    List<ArtifactRow> page = stores.artifacts().findByBuildPaged(buildId, 0, 100);
    assertEquals(5, page.size());
    // Strictly decreasing ids — newest insert is row 0.
    for (int i = 0; i < page.size() - 1; i++) {
      assertTrue(
          page.get(i).id > page.get(i + 1).id,
          "expected id DESC at idx " + i + ": " + page.get(i).id + " > " + page.get(i + 1).id);
    }
    // Last-inserted name appears first.
    assertEquals("f-5.bin", page.get(0).name);
    assertEquals("f-1.bin", page.get(4).name);
  }

  @Test
  void findByBuildPaged_offsetAndLimitWindow() {
    for (int i = 0; i < 25; i++) {
      insertArtifact(buildId, "ARTIFACT", "w-" + i + ".bin");
    }

    List<ArtifactRow> first = stores.artifacts().findByBuildPaged(buildId, 0, 10);
    List<ArtifactRow> second = stores.artifacts().findByBuildPaged(buildId, 10, 10);
    List<ArtifactRow> third = stores.artifacts().findByBuildPaged(buildId, 20, 10);

    assertEquals(10, first.size());
    assertEquals(10, second.size());
    assertEquals(5, third.size(), "tail page must be partial — only 5 rows left after offset 20");

    // No id overlap across the three windows.
    assertTrue(first.get(9).id > second.get(0).id);
    assertTrue(second.get(9).id > third.get(0).id);
  }

  @Test
  void findByBuildPaged_excludesStashRows() {
    insertArtifact(buildId, "ARTIFACT", "v.bin");
    insertArtifact(buildId, "STASH", "intra");
    insertArtifact(buildId, "ARTIFACT", "w.bin");

    List<ArtifactRow> page = stores.artifacts().findByBuildPaged(buildId, 0, 100);
    assertEquals(2, page.size());
    for (ArtifactRow r : page) {
      assertEquals("ARTIFACT", r.kind);
    }
  }

  // ── helper ────────────────────────────────────────────────────────────────

  private void insertArtifact(long build, String kind, String name) {
    stores.withTransaction(
        conn -> {
          try (PreparedStatement ps =
              conn.prepareStatement(
                  "INSERT INTO titan.artifact "
                      + "(build_id, node_id, kind, name, size_bytes, sha256, storage, storage_ref) "
                      + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, build);
            ps.setString(2, "n1");
            ps.setString(3, kind);
            ps.setString(4, name);
            ps.setLong(5, 1L);
            ps.setString(6, "0000000000000000000000000000000000000000000000000000000000000000");
            ps.setString(7, "fs");
            ps.setString(8, "/tmp/" + name);
            ps.executeUpdate();
            return null;
          } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
          }
        });
  }
}
