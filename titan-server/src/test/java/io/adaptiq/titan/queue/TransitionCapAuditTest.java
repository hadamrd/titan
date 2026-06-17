package io.adaptiq.titan.queue;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.audit.AuditAction;
import io.adaptiq.titan.store.TitanStores;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Issue #1074 — structured {@code transition_cap_warn} / {@code transition_cap_halt} audit events.
 *
 * <p>{@link TransitionCapGuardTest} already pins the in-memory counter semantics (warn at soft,
 * halt at hard, eviction, per-(build, kind) isolation, Prometheus emission). This suite pins the
 * NEW surface added by #1074: the {@link QueueHandlerSupport#recordTransition(TitanStores, long,
 * String)} wrapper that fans a cap crossing out to a structured {@link AuditAction} audit event —
 * and the exactly-once-per-edge invariant that keeps the spam guard from spamming its own audit
 * trail.
 *
 * <p>The DB-backed emit (real {@code audit_log} INSERT against Postgres) is covered by {@code
 * TransitionSpamIT}; here we capture emissions via an overridden {@link
 * QueueHandlerSupport#emitCapEvent} so the wiring is testable without a live DAO.
 */
class TransitionCapAuditTest {

  /** A captured {@link QueueHandlerSupport#emitCapEvent} invocation. */
  private record CapEvent(AuditAction action, long buildId, String kind, long count, int cap) {}

  /** {@link QueueHandlerSupport} that records audit emissions instead of hitting a DAO. */
  private static final class CapturingSupport extends QueueHandlerSupport {
    final List<CapEvent> events = new ArrayList<>();

    CapturingSupport(TransitionCapGuard guard) {
      super(() -> null, guard);
    }

    @Override
    void emitCapEvent(
        TitanStores daos, AuditAction action, long buildId, String kind, long count, int cap) {
      events.add(new CapEvent(action, buildId, kind, count, cap));
    }
  }

  // ── happy path: no crossing → no events ─────────────────────────────────────

  @Test
  void belowSoftCap_emitsNoAuditEvents() {
    CapturingSupport support = new CapturingSupport(new TransitionCapGuard(5, 10));
    for (int i = 0; i < 5; i++) {
      assertEquals(
          TransitionCapGuard.Outcome.OK, support.recordTransition(null, 1L, "ADVANCE"), "i=" + i);
    }
    assertTrue(support.events.isEmpty(), "no cap crossed → no audit events: " + support.events);
  }

  // ── @golden headroom: a healthy build at PRODUCTION caps emits no audit events ──

  /**
   * Issue #1074 acceptance criterion: <em>"A passing @golden suite run MUST NOT hit even the soft
   * cap."</em> The full Playwright @golden run is the end-to-end proof, but it depends on the live
   * rig. This deterministic, CI-runnable proxy locks the same guarantee at the actual production
   * caps (200/1000): a generously long healthy build — driven through the exact {@link
   * QueueHandlerSupport#recordTransition} wiring the guard adds, incrementing BOTH the ORCHESTRATE
   * umbrella and the per-action kind on every tick — emits <b>zero</b> structured audit events. A
   * misconfigured or prematurely-tripped guard (the failure mode the reviewer flagged) would
   * surface here as a spurious warn/halt long before @golden ever ran.
   *
   * <p>150 productive ticks is far above any real golden pipeline's transition count in a single
   * kind, yet stays comfortably under the 200 soft cap — proving the headroom is real, not assumed.
   */
  @Test
  void goldenHeadroom_healthyBuildAtProductionCaps_emitsNoAuditEvents() {
    CapturingSupport support =
        new CapturingSupport(
            new TransitionCapGuard(
                TransitionCapGuard.DEFAULT_SOFT_CAP, TransitionCapGuard.DEFAULT_HARD_CAP));

    for (int tick = 0; tick < 150; tick++) {
      // Production wiring: each ORCHESTRATE/ADVANCE dispatch records the umbrella AND the action.
      assertEquals(
          TransitionCapGuard.Outcome.OK,
          support.recordTransition(null, 1234L, "ORCHESTRATE"),
          "umbrella tick " + tick + " must stay within budget");
      assertEquals(
          TransitionCapGuard.Outcome.OK,
          support.recordTransition(null, 1234L, "ADVANCE"),
          "advance tick " + tick + " must stay within budget");
    }

    assertTrue(
        support.events.isEmpty(),
        "a healthy 150-tick build at production caps must not trip the soft cap: "
            + support.events);
  }

  // ── soft cap → exactly one warn event on the crossing edge ──────────────────

  @Test
  void crossingSoftCap_emitsExactlyOneWarnEvent_withSoftCapAndCount() {
    CapturingSupport support = new CapturingSupport(new TransitionCapGuard(3, 100));

    // counts 1,2,3 → OK; count 4 → SOFT_WARN (crossing edge); 5,6 → OK (no further warn).
    for (int i = 0; i < 6; i++) {
      support.recordTransition(null, 42L, "BAKE");
    }

    List<CapEvent> warns =
        support.events.stream().filter(e -> e.action() == AuditAction.TRANSITION_CAP_WARN).toList();
    assertEquals(1, warns.size(), "exactly one warn event on the crossing edge: " + support.events);
    CapEvent warn = warns.get(0);
    assertEquals(42L, warn.buildId());
    assertEquals("BAKE", warn.kind());
    assertEquals(4L, warn.count(), "count at the soft-cap crossing edge is softCap+1");
    assertEquals(3, warn.cap(), "warn event must carry the SOFT cap");
  }

  // ── hard cap → exactly one halt event, then silence ─────────────────────────

  @Test
  void crossingHardCap_emitsExactlyOneHaltEvent_andStaysSilentWhileSpammed() {
    CapturingSupport support = new CapturingSupport(new TransitionCapGuard(2, 4));

    // counts: 1 OK, 2 OK, 3 SOFT_WARN, 4 OK, 5 HARD_HALT, then 6..14 idempotent HALT.
    for (int i = 0; i < 14; i++) {
      support.recordTransition(null, 7L, "ADVANCE");
    }

    List<CapEvent> halts =
        support.events.stream().filter(e -> e.action() == AuditAction.TRANSITION_CAP_HALT).toList();
    assertEquals(
        1,
        halts.size(),
        "the halt audit row must be written ONCE — not once per spammed tick: " + support.events);
    CapEvent halt = halts.get(0);
    assertEquals(7L, halt.buildId());
    assertEquals("ADVANCE", halt.kind());
    assertEquals(5L, halt.count(), "count at the hard-cap crossing edge is hardCap+1");
    assertEquals(4, halt.cap(), "halt event must carry the HARD cap");

    // And exactly one warn earlier (sanity — both edges fire once).
    assertEquals(
        1,
        support.events.stream().filter(e -> e.action() == AuditAction.TRANSITION_CAP_WARN).count());
  }

  // ── per-build isolation of the audit edge ───────────────────────────────────

  @Test
  void haltEdgeIsTrackedPerBuild_notGlobally() {
    CapturingSupport support = new CapturingSupport(new TransitionCapGuard(1, 1));

    // Build 1 crosses hard cap on its 2nd ADVANCE; build 2 independently on its 2nd.
    support.recordTransition(null, 1L, "ADVANCE"); // count 1
    support.recordTransition(null, 1L, "ADVANCE"); // count 2 → halt build 1
    support.recordTransition(null, 2L, "ADVANCE"); // count 1
    support.recordTransition(null, 2L, "ADVANCE"); // count 2 → halt build 2

    List<CapEvent> halts =
        support.events.stream().filter(e -> e.action() == AuditAction.TRANSITION_CAP_HALT).toList();
    assertEquals(2, halts.size(), "each build gets its own one-shot halt event");
    assertTrue(halts.stream().anyMatch(e -> e.buildId() == 1L));
    assertTrue(halts.stream().anyMatch(e -> e.buildId() == 2L));
  }

  @Test
  void haltEventReEmitsAfterBuildTerminal_freshBudgetFreshEdge() {
    CapturingSupport support = new CapturingSupport(new TransitionCapGuard(1, 1));
    support.recordTransition(null, 9L, "ADVANCE"); // 1
    support.recordTransition(null, 9L, "ADVANCE"); // 2 → halt #1
    support.capGuard().onBuildTerminal(9L); // evicts counters + halted bit

    support.recordTransition(null, 9L, "ADVANCE"); // 1 (fresh)
    support.recordTransition(null, 9L, "ADVANCE"); // 2 → halt #2 (new edge)

    long halts =
        support.events.stream().filter(e -> e.action() == AuditAction.TRANSITION_CAP_HALT).count();
    assertEquals(2, halts, "a terminal-then-reused build id re-arms the halt edge");
  }

  // ── adversarial: audit sink failure (null DAO) must not break the guard ──────

  @Test
  void recordTransition_withNullDaos_swallowsAuditFailure_andStillReturnsOutcome() {
    // The REAL emitCapEvent runs here (no override) — a null DAO bundle NPEs inside the emit,
    // which must be swallowed so the spam guard's fail-close decision is never lost to an audit
    // write failure. This is the adversarial path the testing manifesto demands.
    QueueHandlerSupport support = new QueueHandlerSupport(() -> null, new TransitionCapGuard(1, 2));

    assertDoesNotThrow(
        () -> {
          assertEquals(
              TransitionCapGuard.Outcome.OK, support.recordTransition(null, 5L, "SYNTHESIZE"));
          assertEquals(
              TransitionCapGuard.Outcome.SOFT_WARN,
              support.recordTransition(null, 5L, "SYNTHESIZE"));
          assertEquals(
              TransitionCapGuard.Outcome.HARD_HALT,
              support.recordTransition(null, 5L, "SYNTHESIZE"));
        },
        "an audit-sink failure must never propagate out of recordTransition");
    assertTrue(support.capGuard().isHalted(5L), "the guard still recorded the halt despite no DAO");
  }
}
