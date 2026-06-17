package io.adaptiq.titan.scm.reconcile;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-process counter/gauge surface for the reconcile loop (issue #1118).
 *
 * <p>Kept as a small concrete class (not a Micrometer wiring) so the scheduler stays unit-testable
 * and so we can assert on the counters in tests. The CDI-wired adapter in {@code
 * observability/TitanMetrics} reads these getters and exposes them as Prometheus meters at scrape
 * time. Dual-surface so the engine path never has to know about MeterRegistry.
 *
 * <p>Bounded label cardinality (CONSTITUTION §6): {@code provider} is bound by {@link
 * ScmProvider#values()}; {@code (provider, repo)} on the gauge is bounded by the count of
 * configured repos — operator-controlled, not user-input.
 */
public final class ReconcileMetrics {

  /** {@code titan_scm_reconcile_events_recovered_total{provider}} — counter. */
  private final Map<ScmProvider, AtomicLong> recovered = new ConcurrentHashMap<>();

  /** {@code titan_scm_reconcile_lag_seconds{provider,repo}} — gauge. */
  private final Map<RepoKey, AtomicLong> lagSeconds = new ConcurrentHashMap<>();

  /** {@code titan_scm_reconcile_failures_total{provider}} — counter (per-tick API errors). */
  private final Map<ScmProvider, AtomicLong> failures = new ConcurrentHashMap<>();

  public void incrementRecovered(@NonNull ScmProvider provider) {
    recovered.computeIfAbsent(provider, p -> new AtomicLong()).incrementAndGet();
  }

  public void incrementFailures(@NonNull ScmProvider provider) {
    failures.computeIfAbsent(provider, p -> new AtomicLong()).incrementAndGet();
  }

  public void setLagSeconds(@NonNull ScmProvider provider, @NonNull String repo, long lagSeconds) {
    this.lagSeconds
        .computeIfAbsent(new RepoKey(provider, repo), k -> new AtomicLong())
        .set(lagSeconds);
  }

  public long recovered(@NonNull ScmProvider provider) {
    AtomicLong v = recovered.get(provider);
    return v == null ? 0 : v.get();
  }

  public long failures(@NonNull ScmProvider provider) {
    AtomicLong v = failures.get(provider);
    return v == null ? 0 : v.get();
  }

  public long lagSeconds(@NonNull ScmProvider provider, @NonNull String repo) {
    AtomicLong v = lagSeconds.get(new RepoKey(provider, repo));
    return v == null ? 0 : v.get();
  }

  private static final class RepoKey {
    final ScmProvider provider;
    final String repo;

    RepoKey(ScmProvider provider, String repo) {
      this.provider = provider;
      this.repo = repo;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof RepoKey other)) return false;
      return provider == other.provider && repo.equals(other.repo);
    }

    @Override
    public int hashCode() {
      return provider.hashCode() * 31 + repo.hashCode();
    }
  }
}
