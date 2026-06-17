package io.adaptiq.titan.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.JobRow;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BuildServiceImpl}. Uses the H2-backed {@link TitanStores} produced by
 * {@code H2StoresProducer} — no Docker, no Postgres. Each test allocates a fresh job row so runs do
 * not collide with each other (the {@code @ApplicationScoped} stores bean is shared).
 *
 * <p>These hunt the sad paths: missing-id lookups, the {@code BuildNotFoundException} contract on
 * {@code update}, the {@code MAX+1} numbering invariant across two inserts for the same job, and
 * the domain-mapping round-trip.
 */
@QuarkusTest
class BuildServiceImplTest {

  @Inject TitanStores stores;

  // ── findById ─────────────────────────────────────────────────────────────

  @Test
  void findById_unknownReturnsEmpty() {
    BuildService svc = new BuildServiceImpl(stores);
    assertTrue(svc.findById(999_999_999L).isEmpty());
  }

  @Test
  void findById_knownReturnsDomainRecord() {
    BuildService svc = new BuildServiceImpl(stores);
    long jobId = freshJob();
    Build created = svc.create(newReq(jobId, "alice", "manual"));

    Optional<Build> found = svc.findById(created.id());
    assertTrue(found.isPresent());
    Build b = found.get();
    assertEquals(jobId, b.jobId());
    assertEquals("QUEUED", b.status());
    assertEquals("alice", b.triggeredBy());
    assertEquals("manual", b.triggerType());
    assertEquals(created.buildNumber(), b.buildNumber());
    assertNotNull(b.queuedAt());
  }

  // ── findByJobIdAndNumber ─────────────────────────────────────────────────

  @Test
  void findByJobIdAndNumber_resolvesToSameRow() {
    BuildService svc = new BuildServiceImpl(stores);
    long jobId = freshJob();
    Build first = svc.create(newReq(jobId, null, "api"));

    Optional<Build> byPair = svc.findByJobIdAndNumber(jobId, first.buildNumber());
    assertTrue(byPair.isPresent());
    assertEquals(first.id(), byPair.get().id());
  }

  @Test
  void findByJobIdAndNumber_unknownReturnsEmpty() {
    BuildService svc = new BuildServiceImpl(stores);
    assertTrue(svc.findByJobIdAndNumber(freshJob(), 9999).isEmpty());
  }

  // ── create: MAX+1 numbering ──────────────────────────────────────────────

  @Test
  void create_allocatesMonotonicallyPerJob() {
    BuildService svc = new BuildServiceImpl(stores);
    long jobId = freshJob();
    Build b1 = svc.create(newReq(jobId, null, "api"));
    Build b2 = svc.create(newReq(jobId, null, "api"));
    Build b3 = svc.create(newReq(jobId, null, "api"));

    assertEquals(1, b1.buildNumber());
    assertEquals(2, b2.buildNumber());
    assertEquals(3, b3.buildNumber());
  }

  @Test
  void create_numbersAreScopedToJob() {
    BuildService svc = new BuildServiceImpl(stores);
    long jobA = freshJob();
    long jobB = freshJob();

    Build a1 = svc.create(newReq(jobA, null, "api"));
    Build b1 = svc.create(newReq(jobB, null, "api"));

    // Different jobs each start at 1 — numbering is scoped, not global.
    assertEquals(1, a1.buildNumber());
    assertEquals(1, b1.buildNumber());
  }

  // ── listByJobId ──────────────────────────────────────────────────────────

  @Test
  void listByJobId_returnsOnlyOwnedBuilds() {
    BuildService svc = new BuildServiceImpl(stores);
    long jobA = freshJob();
    long jobB = freshJob();
    svc.create(newReq(jobA, null, "api"));
    svc.create(newReq(jobA, null, "api"));
    svc.create(newReq(jobB, null, "api"));

    List<Build> ofA = svc.listByJobId(jobA);
    List<Build> ofB = svc.listByJobId(jobB);
    assertEquals(2, ofA.size());
    assertEquals(1, ofB.size());
    assertTrue(ofA.stream().allMatch(b -> b.jobId() == jobA));
    assertTrue(ofB.stream().allMatch(b -> b.jobId() == jobB));
  }

  // ── update ───────────────────────────────────────────────────────────────

  @Test
  void update_writesStatusAndTiming() {
    BuildService svc = new BuildServiceImpl(stores);
    long jobId = freshJob();
    Build created = svc.create(newReq(jobId, null, "api"));

    Instant started = Instant.parse("2026-05-20T10:00:00Z");
    Instant finished = Instant.parse("2026-05-20T10:05:00Z");
    Build updated =
        svc.update(created.id(), new BuildUpdate("SUCCESS", started, finished, 300_000L, null));

    assertEquals("SUCCESS", updated.status());
    assertEquals(started, updated.startedAt());
    assertEquals(finished, updated.finishedAt());
    assertEquals(Long.valueOf(300_000L), updated.durationMs());
  }

  @Test
  void update_unknownIdThrowsBuildNotFound() {
    BuildService svc = new BuildServiceImpl(stores);
    BuildNotFoundException ex =
        assertThrows(
            BuildNotFoundException.class,
            () -> svc.update(999_999_999L, new BuildUpdate("FAILED", null, null, null, "boom")));
    assertTrue(ex.getMessage().contains("999999999"));
  }

  // ── delete ───────────────────────────────────────────────────────────────

  @Test
  void delete_dropsRow() {
    BuildService svc = new BuildServiceImpl(stores);
    long jobId = freshJob();
    Build created = svc.create(newReq(jobId, null, "api"));
    assertTrue(svc.findById(created.id()).isPresent());

    svc.delete(created.id());
    assertFalse(svc.findById(created.id()).isPresent());
  }

  @Test
  void delete_unknownIdIsNoOp() {
    BuildService svc = new BuildServiceImpl(stores);
    // Must not throw — the underlying DELETE matches zero rows on an absent id.
    svc.delete(999_999_999L);
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private long freshJob() {
    JobRow row = new JobRow();
    row.fullName = "build-service-test/" + System.nanoTime();
    row.pipelineScript = "";
    row.configJson = "{}";
    row.enabled = true;
    return stores.jobs().insert(row);
  }

  private static NewBuildRequest newReq(long jobId, String triggeredBy, String triggerType) {
    return new NewBuildRequest(jobId, null, triggeredBy, triggerType, null, null, null);
  }
}
