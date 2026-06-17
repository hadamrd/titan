package io.adaptiq.titan.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.adaptiq.titan.store.rows.AuditRetentionPolicyRow;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link AuditRetentionPolicy} — the in-memory resolver behind the nightly
 * retention purge (#1104).
 *
 * <p>The one behaviour that matters and could regress: <strong>kind-specific &gt; default</strong>,
 * plus the adversarial 0-day misconfig guard. No DB — pure resolution logic.
 */
class AuditRetentionPolicyTest {

  private static AuditRetentionPolicyRow row(String kind, int days) {
    AuditRetentionPolicyRow r = new AuditRetentionPolicyRow();
    r.kind = kind;
    r.maxAgeDays = days;
    r.updatedAt = Instant.parse("2026-01-01T00:00:00Z");
    return r;
  }

  @Test
  void resolveKindSpecificWinsOverDefault() {
    AuditRetentionPolicy policy =
        AuditRetentionPolicy.of(
            List.of(row(AuditRetentionPolicy.DEFAULT_KEY, 90), row("PAT_CREATE", 365)));

    assertEquals(365, policy.resolve("PAT_CREATE"), "kind-specific override must win");
    assertEquals(90, policy.resolve("JOB_CREATE"), "uncovered kind falls back to the '*' default");
  }

  @Test
  void resolveFallsBackToDefaultWhenNoKindSpecificRow() {
    AuditRetentionPolicy policy =
        AuditRetentionPolicy.of(List.of(row(AuditRetentionPolicy.DEFAULT_KEY, 45)));
    assertEquals(45, policy.resolve("BUILD_TRIGGER"));
    assertEquals(45, policy.defaultMaxAgeDays());
  }

  @Test
  void resolveFallsBackToHardDefaultWhenTableIsEmpty() {
    // Adversarial: the policy table has no rows at all (fresh DB before seed, or a wiped table).
    // The feature must degrade to a sane horizon, never to "no retention" or a crash.
    AuditRetentionPolicy policy = AuditRetentionPolicy.of(List.of());
    assertEquals(
        AuditRetentionPolicy.DEFAULT_MAX_AGE_DAYS,
        policy.resolve("JOB_CREATE"),
        "empty table → hard-coded 90-day fallback, not 0/disabled");
  }

  @Test
  void resolveZeroDayKindSpecificPolicyThrowsTyped() {
    // Adversarial misconfig: a 0-day policy would purge the kind's entire history on the next
    // sweep. The resolver must raise a typed error so the job skips the kind loudly.
    AuditRetentionPolicy policy =
        AuditRetentionPolicy.of(
            List.of(row(AuditRetentionPolicy.DEFAULT_KEY, 90), row("RBAC_CHECK", 0)));

    InvalidRetentionPolicyException ex =
        assertThrows(InvalidRetentionPolicyException.class, () -> policy.resolve("RBAC_CHECK"));
    assertEquals("RBAC_CHECK", ex.kind());
    assertEquals(0, ex.maxAgeDays());
    // A sibling kind with a valid policy still resolves — the bad row is isolated.
    assertEquals(90, policy.resolve("JOB_CREATE"));
  }

  @Test
  void resolveZeroDayDefaultThrowsForUncoveredKind() {
    // The '*' default itself set to 0 is just as dangerous: every uncovered kind would resolve to
    // 0. The guard must fire on the default path too.
    AuditRetentionPolicy policy =
        AuditRetentionPolicy.of(List.of(row(AuditRetentionPolicy.DEFAULT_KEY, 0)));
    assertThrows(InvalidRetentionPolicyException.class, () -> policy.resolve("JOB_CREATE"));
  }

  @Test
  void resolveNegativeDayPolicyThrows() {
    AuditRetentionPolicy policy =
        AuditRetentionPolicy.of(
            List.of(row(AuditRetentionPolicy.DEFAULT_KEY, 90), row("PAT_REVOKE", -7)));
    assertThrows(InvalidRetentionPolicyException.class, () -> policy.resolve("PAT_REVOKE"));
  }

  @Test
  void invalidPolicyExceptionIsRuntimeForBestEffortSkip() {
    // The job catches it per-kind; assert the type stays unchecked so callers are not forced to
    // declare it (keeps the best-effort skip path simple).
    InvalidRetentionPolicyException ex = new InvalidRetentionPolicyException("X", 0);
    assertSame(RuntimeException.class, ex.getClass().getSuperclass());
  }
}
