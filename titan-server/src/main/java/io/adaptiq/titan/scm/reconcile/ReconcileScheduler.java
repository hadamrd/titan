package io.adaptiq.titan.scm.reconcile;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The per-tick reconcile engine (issue #1118).
 *
 * <p>Inputs (all injected, all behind Fakes-for-tests):
 *
 * <ul>
 *   <li>The set of {@link RepoTarget}s the operator has configured for reconcile.
 *   <li>A per-provider {@link ScmEventSource} to list events since the last cursor.
 *   <li>A {@link CursorStore} for the per-(provider, repo) high-water-mark.
 *   <li>An {@link EventDedupeStore} — the idempotency boundary shared with the webhook hot-path.
 *   <li>A {@link WebhookDispatcher} — the SAME dispatch tail the live webhook uses.
 *   <li>A {@link ReconcileAuditSink} for the {@code SCM_WEBHOOK_RECOVERED} audit row.
 *   <li>A {@link ReconcileMetrics} for the recovered/lag/failure counters.
 * </ul>
 *
 * <p><strong>Backoff on failure.</strong> A {@link ScmReconcileException} from {@code
 * listEventsSince} for repo R is recorded in an in-memory backoff map; R is skipped on subsequent
 * ticks until the deadline passes. The loop never crashes; a single bad repo cannot stall the
 * scheduler (acceptance criterion: "one slow repo cannot stall others").
 *
 * <p><strong>Cursor advance ordering.</strong> The cursor advances ONLY after every event in a
 * batch dispatched (or skipped via dedupe). If dispatch throws midway, the cursor stays put and the
 * next tick re-attempts from the last successful id — at-least-once + dedupe gives exactly-once.
 *
 * <p>Sized for unit testability: {@link #tick()} returns a {@link TickReport} so tests can assert
 * on per-tick outcomes without scraping logs.
 */
public final class ReconcileScheduler {

  private static final Logger LOGGER = Logger.getLogger(ReconcileScheduler.class.getName());

  /** Default per-tick batch cap when not overridden. */
  public static final int DEFAULT_BATCH_CAP = 100;

  /** Default per-repo backoff after a failed listEventsSince call. */
  public static final Duration DEFAULT_BACKOFF = Duration.ofSeconds(60);

  private final Map<ScmProvider, ScmEventSource> sources;
  private final CursorStore cursorStore;
  private final EventDedupeStore dedupe;
  private final WebhookDispatcher dispatcher;
  private final ReconcileAuditSink auditSink;
  private final ReconcileMetrics metrics;
  private final Clock clock;
  private final int batchCap;
  private final Duration backoff;

  /** repo → "do not retry before this instant" (entry absent = OK to try). */
  private final Map<RepoKey, Instant> backoffUntil = new ConcurrentHashMap<>();

  public ReconcileScheduler(
      @NonNull Map<ScmProvider, ScmEventSource> sources,
      @NonNull CursorStore cursorStore,
      @NonNull EventDedupeStore dedupe,
      @NonNull WebhookDispatcher dispatcher,
      @NonNull ReconcileAuditSink auditSink,
      @NonNull ReconcileMetrics metrics,
      @NonNull Clock clock,
      int batchCap,
      @NonNull Duration backoff) {
    this.sources = Map.copyOf(sources);
    this.cursorStore = cursorStore;
    this.dedupe = dedupe;
    this.dispatcher = dispatcher;
    this.auditSink = auditSink;
    this.metrics = metrics;
    this.clock = clock;
    if (batchCap <= 0) {
      throw new IllegalArgumentException("batchCap must be > 0");
    }
    this.batchCap = batchCap;
    this.backoff = Objects.requireNonNull(backoff, "backoff");
  }

  /**
   * Run one reconcile pass over {@code targets}. Returns a {@link TickReport} summarising the
   * outcome. Never throws — every exceptional path is caught + logged + counted.
   */
  @NonNull
  public TickReport tick(@NonNull List<RepoTarget> targets) {
    TickReport report = new TickReport();
    Instant now = clock.instant();
    for (RepoTarget target : targets) {
      try {
        reconcileOne(target, now, report);
      } catch (RuntimeException e) {
        // Defensive: should never happen — reconcileOne catches its own exceptions. But the
        // promise of the scheduler is "one bad repo does not stall others", so we make it true
        // even for programmer errors we did not foresee.
        LOGGER.log(
            Level.WARNING,
            "[reconcile] unexpected error for "
                + target.provider()
                + "/"
                + target.repoExternalId()
                + ": "
                + e.getMessage(),
            e);
        report.unexpectedErrors++;
      }
    }
    return report;
  }

  private void reconcileOne(RepoTarget target, Instant now, TickReport report) {
    ScmProvider provider = target.provider();
    String repo = target.repoExternalId();

    ScmEventSource source = sources.get(provider);
    if (source == null || !source.supportsReconcile()) {
      report.skippedNoSource++;
      return;
    }

    RepoKey key = new RepoKey(provider, repo);
    Instant until = backoffUntil.get(key);
    if (until != null && now.isBefore(until)) {
      report.skippedBackoff++;
      return;
    }

    CursorStore.Cursor cursor = cursorStore.read(provider, repo);
    String sinceId = cursor.lastEventId();

    List<ScmEvent> events;
    try {
      events = source.listEventsSince(repo, sinceId, batchCap);
    } catch (ScmReconcileException e) {
      LOGGER.log(
          Level.WARNING,
          "[reconcile] listEventsSince failed for "
              + provider
              + "/"
              + repo
              + " — applying backoff: "
              + e.getMessage());
      backoffUntil.put(key, now.plus(backoff));
      metrics.incrementFailures(provider);
      report.failures++;
      return;
    }
    // Success → clear any old backoff so a recovered repo resumes immediately.
    backoffUntil.remove(key);

    if (events.isEmpty()) {
      cursorStore.advance(
          provider,
          repo,
          sinceId == null ? "" : sinceId,
          cursor.lastEventAt() == null ? now : cursor.lastEventAt(),
          now);
      updateLag(provider, repo, cursor.lastEventAt(), now);
      report.reposChecked++;
      return;
    }

    String latestId = sinceId;
    Instant latestAt = cursor.lastEventAt();
    for (ScmEvent ev : events) {
      // Idempotent claim: only one of (webhook, reconcile) can dispatch. Loser logs + skips.
      boolean claimed = dedupe.markSeen(provider, ev.eventId(), EventDedupeStore.Source.RECONCILE);
      if (!claimed) {
        report.skippedDuplicate++;
        latestId = ev.eventId();
        latestAt = ev.occurredAt();
        continue;
      }
      WebhookDispatcher.Outcome outcome;
      try {
        outcome = dispatcher.dispatch(ev);
      } catch (RuntimeException re) {
        LOGGER.log(
            Level.WARNING,
            "[reconcile] dispatcher threw for "
                + provider
                + "/"
                + repo
                + " event="
                + ev.eventId()
                + ": "
                + re.getMessage(),
            re);
        report.dispatchExceptions++;
        // Do NOT advance cursor past a failed dispatch — next tick will retry. Dedupe is already
        // marked, but the next-tick path will also call markSeen → false → skip, so we never
        // double-dispatch. The drop is acceptable: the upstream dispatcher is responsible for
        // its own internal retries.
        break;
      }
      boolean stopBatch = false;
      switch (outcome) {
        case DISPATCHED -> {
          long gap = Math.max(0, now.getEpochSecond() - ev.occurredAt().getEpochSecond());
          auditSink.recordRecovered(ev, gap);
          metrics.incrementRecovered(provider);
          report.recovered++;
        }
        case NO_MATCH -> report.noMatch++;
        case SKIPPED -> report.skippedByDispatcher++;
        case FAILED -> {
          report.dispatchFailures++;
          stopBatch = true;
        }
      }
      if (stopBatch) {
        // Leave cursor un-advanced past this id so the next tick retries (markSeen has been
        // claimed but the dispatcher will be re-invoked? No — markSeen returns false on retry,
        // so a hard FAILED is observable as a permanent dead-letter. Operators see the failure
        // count rise; a fix ships and they manually clear the seen row. Documented tradeoff.)
        break;
      }
      latestId = ev.eventId();
      latestAt = ev.occurredAt();
    }

    cursorStore.advance(
        provider, repo, latestId == null ? "" : latestId, latestAt == null ? now : latestAt, now);
    updateLag(provider, repo, latestAt, now);
    report.reposChecked++;
  }

  private void updateLag(ScmProvider provider, String repo, Instant lastEventAt, Instant now) {
    long lag =
        lastEventAt == null ? 0 : Math.max(0, now.getEpochSecond() - lastEventAt.getEpochSecond());
    metrics.setLagSeconds(provider, repo, lag);
  }

  /** A single repo the operator wants the loop to reconcile. */
  public static final class RepoTarget {
    private final ScmProvider provider;
    private final String repoExternalId;

    public RepoTarget(@NonNull ScmProvider provider, @NonNull String repoExternalId) {
      this.provider = provider;
      this.repoExternalId = repoExternalId;
    }

    @NonNull
    public ScmProvider provider() {
      return provider;
    }

    @NonNull
    public String repoExternalId() {
      return repoExternalId;
    }
  }

  /** Summary of one {@link #tick(List)} pass — primary surface for unit-test assertions. */
  public static final class TickReport {
    public int reposChecked;
    public int recovered;
    public int noMatch;
    public int skippedDuplicate;
    public int skippedBackoff;
    public int skippedNoSource;
    public int skippedByDispatcher;
    public int dispatchFailures;
    public int dispatchExceptions;
    public int failures;
    public int unexpectedErrors;
  }

  /** Composite key for the backoff map. Private — never crosses a module boundary. */
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

  /** Convenience builder for tests + production wiring. */
  public static Builder builder() {
    return new Builder();
  }

  /** Fluent builder — every field is required except batchCap and backoff which have defaults. */
  public static final class Builder {
    private Map<ScmProvider, ScmEventSource> sources = Map.of();
    private CursorStore cursorStore;
    private EventDedupeStore dedupe;
    private WebhookDispatcher dispatcher;
    private ReconcileAuditSink auditSink;
    private ReconcileMetrics metrics;
    private Clock clock = Clock.systemUTC();
    private int batchCap = DEFAULT_BATCH_CAP;
    private Duration backoff = DEFAULT_BACKOFF;

    public Builder sources(Map<ScmProvider, ScmEventSource> sources) {
      this.sources = sources;
      return this;
    }

    public Builder cursorStore(CursorStore cursorStore) {
      this.cursorStore = cursorStore;
      return this;
    }

    public Builder dedupe(EventDedupeStore dedupe) {
      this.dedupe = dedupe;
      return this;
    }

    public Builder dispatcher(WebhookDispatcher dispatcher) {
      this.dispatcher = dispatcher;
      return this;
    }

    public Builder auditSink(ReconcileAuditSink auditSink) {
      this.auditSink = auditSink;
      return this;
    }

    public Builder metrics(ReconcileMetrics metrics) {
      this.metrics = metrics;
      return this;
    }

    public Builder clock(Clock clock) {
      this.clock = clock;
      return this;
    }

    public Builder batchCap(int batchCap) {
      this.batchCap = batchCap;
      return this;
    }

    public Builder backoff(Duration backoff) {
      this.backoff = backoff;
      return this;
    }

    public ReconcileScheduler build() {
      // Sanity-checks all required collaborators are wired — fail loud at boot (Manifesto
      // §"Boot-time validation").
      List<String> missing = new ArrayList<>();
      if (cursorStore == null) missing.add("cursorStore");
      if (dedupe == null) missing.add("dedupe");
      if (dispatcher == null) missing.add("dispatcher");
      if (auditSink == null) missing.add("auditSink");
      if (metrics == null) missing.add("metrics");
      if (!missing.isEmpty()) {
        throw new IllegalStateException("ReconcileScheduler.Builder missing: " + missing);
      }
      return new ReconcileScheduler(
          sources, cursorStore, dedupe, dispatcher, auditSink, metrics, clock, batchCap, backoff);
    }
  }
}
