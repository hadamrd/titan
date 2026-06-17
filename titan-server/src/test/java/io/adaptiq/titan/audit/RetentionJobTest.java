package io.adaptiq.titan.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.api.FakeTitanStores;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.AuditRetentionPolicyRow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RetentionJob}'s best-effort per-kind isolation guarantee (#1104).
 *
 * <p>{@code RetentionJob.purge()} documents that "a poison kind cannot block the rest of the sweep"
 * and has a per-kind try/catch. This proves that contract directly: a {@link
 * RetentionJob.BatchDeleter} that throws for one kind must NOT stop the others, and the failure
 * must be recorded (not silently swallowed). Driven through the package-private {@code purge(now,
 * policy, kinds, deleter)} seam so no database is needed — the store-wired overload just supplies
 * the real collaborators.
 */
class RetentionJobTest {

  private static final Instant NOW = Instant.parse("2026-06-04T00:00:00Z");

  /** A fake deleter that throws for one named kind and records every other (kind) it drains. */
  private static final class RecordingDeleter implements RetentionJob.BatchDeleter {
    private final String poisonKind;
    private final List<String> drained = new ArrayList<>();

    RecordingDeleter(String poisonKind) {
      this.poisonKind = poisonKind;
    }

    @Override
    public int deleteOlderThan(String kind, Instant cutoff, int limit) {
      if (kind.equals(poisonKind)) {
        throw new RuntimeException("simulated DB failure for kind " + kind);
      }
      drained.add(kind);
      return 1; // one row deleted, then "drained" (1 < batchSize) — single batch per kind
    }
  }

  private static AuditRetentionPolicy policyWithDefault(int defaultDays) {
    AuditRetentionPolicyRow row = new AuditRetentionPolicyRow();
    row.kind = AuditRetentionPolicy.DEFAULT_KEY;
    row.maxAgeDays = defaultDays;
    return AuditRetentionPolicy.of(List.of(row));
  }

  @Test
  void aPoisonKindDoesNotBlockTheRestOfTheSweep() {
    // batchSize is irrelevant to the seam path; any value works.
    RetentionJob job = new RetentionJob(stubStores(), 10_000);
    RecordingDeleter deleter = new RecordingDeleter("PAT_CREATE"); // the poison kind throws

    RetentionJob.PurgePass pass =
        job.purge(
            NOW,
            policyWithDefault(90),
            List.of("JOB_CREATE", "PAT_CREATE", "BUILD_TRIGGER"),
            deleter);

    // The two healthy kinds were still purged despite PAT_CREATE blowing up mid-sweep.
    assertEquals(
        List.of("JOB_CREATE", "BUILD_TRIGGER"),
        deleter.drained,
        "the kinds after the poison kind must still be drained — best-effort isolation");
    assertEquals(2, pass.rowsDeleted(), "one row each from the two healthy kinds");
    assertEquals(2, pass.kindsPurged(), "exactly the two healthy kinds count as purged");
    assertEquals(1, pass.kindsFailed(), "the poison kind is recorded as a failure, not swallowed");
    assertEquals(
        0, pass.kindsSkipped(), "a delete failure is a failure, not an invalid-policy skip");
  }

  @Test
  void invalidPolicyIsSkippedNotFailed_andDistinctFromDeleteFailures() {
    // A 0-day policy for one kind makes resolve() throw InvalidRetentionPolicyException; that kind
    // is *skipped* (loudly) and never handed to the deleter — distinct from a delete-time failure.
    AuditRetentionPolicyRow def = new AuditRetentionPolicyRow();
    def.kind = AuditRetentionPolicy.DEFAULT_KEY;
    def.maxAgeDays = 90;
    AuditRetentionPolicyRow zero = new AuditRetentionPolicyRow();
    zero.kind = "JOB_CREATE";
    zero.maxAgeDays = 0; // operator misconfig — resolve() rejects it
    AuditRetentionPolicy policy = AuditRetentionPolicy.of(List.of(def, zero));

    RetentionJob job = new RetentionJob(stubStores(), 10_000);
    RecordingDeleter deleter = new RecordingDeleter("__none__");

    RetentionJob.PurgePass pass =
        job.purge(NOW, policy, List.of("JOB_CREATE", "BUILD_TRIGGER"), deleter);

    assertEquals(
        List.of("BUILD_TRIGGER"),
        deleter.drained,
        "the 0-day JOB_CREATE is skipped before any delete — it must never reach the deleter");
    assertEquals(1, pass.kindsSkipped(), "invalid policy counts as skipped");
    assertEquals(0, pass.kindsFailed(), "no delete threw, so nothing failed");
    assertEquals(1, pass.kindsPurged());
  }

  @Test
  void nullAndDuplicateKindsAreToleratedDuringTheSweep() {
    RetentionJob job = new RetentionJob(stubStores(), 10_000);
    RecordingDeleter deleter = new RecordingDeleter("__none__");
    List<String> kinds = new ArrayList<>();
    kinds.add("JOB_CREATE");
    kinds.add(null); // legacy/garbage row — must be skipped without blowing up
    kinds.add("BUILD_TRIGGER");

    RetentionJob.PurgePass pass = job.purge(NOW, policyWithDefault(90), kinds, deleter);

    assertTrue(deleter.drained.contains("JOB_CREATE") && deleter.drained.contains("BUILD_TRIGGER"));
    assertEquals(2, pass.kindsPurged(), "the null kind is silently skipped, the two real ones run");
  }

  /**
   * The store collaborators are never touched on the seam path ({@code purge(now, policy, kinds,
   * deleter)} bypasses {@code stores} entirely) — we only need a non-null instance to satisfy the
   * constructor. {@link FakeTitanStores#create()} is the established (H2-backed) way to get one
   * without subclassing the {@code final} {@link TitanStores}.
   */
  private static TitanStores stubStores() {
    return FakeTitanStores.create();
  }
}
