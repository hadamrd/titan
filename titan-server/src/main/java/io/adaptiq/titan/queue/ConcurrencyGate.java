package io.adaptiq.titan.queue;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.flow.model.ConcurrencyConfig;
import io.adaptiq.titan.flow.parser.ConcurrencyScope;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.JobRow;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Per-job concurrency gate (issue #1101). Evaluated at the head of {@link SynthesizeHandler} —
 * BEFORE the worker synthesis dispatch — so a build that hits the cap is held / cancelled before it
 * consumes worker capacity.
 *
 * <p>Reads the {@code concurrency:} block tolerantly from the job's {@code pipelineScript} via
 * {@link ConcurrencyScope#parseTolerant(String)}: a missing block (or any parse failure) means "no
 * gate" — backwards compatible.
 *
 * <p>Decision logic lives in pure {@link #decide(int, int, List, ConcurrencyConfig)} — that
 * function is what the unit suite exercises. {@link #evaluate(TitanStores, BuildRow, JobRow)} is
 * the thin IO wrapper that fetches the RUNNING / QUEUED rows and applies side-effects.
 */
final class ConcurrencyGate {

  private static final Logger LOGGER = Logger.getLogger(ConcurrencyGate.class.getName());

  /** Reason recorded on the failure_summary of cancellations issued by this gate. */
  static final String CANCELLED_BY_GATE_REASON =
      "cancelled by concurrency policy — another build of this job took the slot (#1101)";

  /** The two possible verdicts the gate returns to its caller. */
  enum Verdict {
    /** No cap, or cap not reached — proceed with synthesis. */
    PROCEED,
    /** Cap reached, policy says wait — re-poll on the next tick. */
    DEFER
  }

  /**
   * Pure decision record returned by {@link #decide(int, int, List, ConcurrencyConfig)} — the
   * verdict plus the (possibly empty) list of build ids to cancel as a side-effect. Side-effects
   * (marking builds failed) are the caller's job, not the decider's — keeps the unit test pure.
   */
  static final class Decision {
    final Verdict verdict;

    @NonNull final List<Long> cancellations;

    Decision(@NonNull Verdict verdict, @NonNull List<Long> cancellations) {
      this.verdict = verdict;
      this.cancellations = List.copyOf(cancellations);
    }
  }

  private final QueueHandlerSupport support;

  ConcurrencyGate(@NonNull QueueHandlerSupport support) {
    this.support = support;
  }

  /**
   * The IO wrapper — fetches RUNNING / QUEUED rows for the job, calls {@link #decide} and applies
   * the cancellations. Never throws; on any DAO failure, falls back to {@link Verdict#PROCEED}
   * (fail-open — concurrency is an optimisation, not a safety check).
   */
  @NonNull
  Verdict evaluate(@NonNull TitanStores daos, @NonNull BuildRow build, @NonNull JobRow job) {
    ConcurrencyConfig cfg = readConcurrency(job);
    if (cfg == null) {
      return Verdict.PROCEED; // legacy unlimited
    }

    List<BuildRow> running;
    try {
      running = daos.builds().listByJobAndStatus(job.id, "RUNNING", build.id);
    } catch (RuntimeException e) {
      LOGGER.log(
          Level.WARNING,
          "[titan] ConcurrencyGate: listByJobAndStatus(RUNNING) failed for job {0} — fail-open",
          new Object[] {job.id, e});
      return Verdict.PROCEED;
    }

    List<Long> queuedIds = Collections.emptyList();
    if (cfg.getOnOverflow() == ConcurrencyConfig.OnOverflow.CANCEL_PENDING
        && running.size() >= cfg.getMax()) {
      try {
        List<BuildRow> queued = daos.builds().listByJobAndStatus(job.id, "QUEUED", build.id);
        queuedIds = new ArrayList<>(queued.size());
        for (BuildRow b : queued) {
          queuedIds.add(b.id);
        }
      } catch (RuntimeException e) {
        LOGGER.log(
            Level.WARNING,
            "[titan] ConcurrencyGate: listByJobAndStatus(QUEUED) failed for job {0} — "
                + "deferring current build without cancellations",
            new Object[] {job.id, e});
        return Verdict.DEFER;
      }
    }

    List<Long> runningIds = new ArrayList<>(running.size());
    for (BuildRow b : running) {
      runningIds.add(b.id);
    }

    Decision decision = decide(build.id, job.id, runningIds, queuedIds, cfg);
    for (Long victimId : decision.cancellations) {
      LOGGER.log(
          Level.INFO,
          "[titan] ConcurrencyGate: cancelling build {0} of job {1} (policy={2}, current={3})",
          new Object[] {victimId, job.id, cfg.getOnOverflow(), build.id});
      support.markBuildFailed(daos, victimId, CANCELLED_BY_GATE_REASON);
    }
    return decision.verdict;
  }

  /**
   * Pure decision logic — visible for testing. Given the count of RUNNING builds (oldest-first),
   * the count of QUEUED builds (for {@code cancel_pending}), and the policy, returns the verdict
   * plus the list of victim build ids to cancel.
   *
   * <p>The four-arity convenience overload below preserves a simpler call signature when the caller
   * does not need to test {@code cancel_pending}.
   */
  @NonNull
  static Decision decide(
      long currentBuildId,
      long jobId,
      @NonNull List<Long> runningOldestFirst,
      @NonNull List<Long> queuedOthers,
      @NonNull ConcurrencyConfig cfg) {
    int max = cfg.getMax();
    int n = runningOldestFirst.size();
    if (n < max) {
      return new Decision(Verdict.PROCEED, List.of());
    }
    switch (cfg.getOnOverflow()) {
      case QUEUE -> {
        return new Decision(Verdict.DEFER, List.of());
      }
      case CANCEL_OLDEST -> {
        int toCancel = Math.min(n, n - max + 1);
        return new Decision(Verdict.PROCEED, runningOldestFirst.subList(0, toCancel));
      }
      case CANCEL_PENDING -> {
        // Cancel every other QUEUED build of this job; the current build itself still has to
        // wait for the RUNNING ones to drain (we never cancel RUNNING under this policy).
        return new Decision(Verdict.DEFER, queuedOthers);
      }
    }
    return new Decision(Verdict.PROCEED, List.of());
  }

  /** Convenience overload — three-arity variant used by callers that don't need cancel_pending. */
  @NonNull
  static Decision decide(
      long currentBuildId,
      long jobId,
      @NonNull List<Long> runningOldestFirst,
      @NonNull ConcurrencyConfig cfg) {
    return decide(currentBuildId, jobId, runningOldestFirst, List.of(), cfg);
  }

  /** Tolerantly read the concurrency block off a job's pipeline script. */
  @Nullable
  private static ConcurrencyConfig readConcurrency(@NonNull JobRow job) {
    if (job.pipelineScript == null || job.pipelineScript.isBlank()) {
      return null;
    }
    return ConcurrencyScope.parseTolerant(job.pipelineScript);
  }
}
