package io.adaptiq.titan.scm.webhook;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.scm.reconcile.ScmProvider;
import io.adaptiq.titan.store.ScmWebhookEventDao;
import io.adaptiq.titan.store.rows.ScmWebhookEventRow;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Durable retry sweeper for SCM webhook ingestion (issue #1129).
 *
 * <p>Lifecycle (single tick):
 *
 * <ol>
 *   <li>{@link ScmWebhookEventDao#findDuePending} returns up to {@link #batchSize} {@code PENDING}
 *       rows whose {@code next_attempt_at} is at or before {@code now}.
 *   <li>For each row, {@link WebhookHandler#handle} is invoked.
 *   <li>Outcome → DAO state transition:
 *       <ul>
 *         <li>{@code SUCCESS} → {@link ScmWebhookEventDao#markProcessed}
 *         <li>{@code RETRY}: if {@code attempts + 1 >= maxAttempts}, {@link
 *             ScmWebhookEventDao#markFailedTerminal}; otherwise {@link
 *             ScmWebhookEventDao#markFailedWithRetry} with exponential back-off ({@code baseBackoff
 *             × 2^attempts}, capped at {@link #MAX_BACKOFF}).
 *         <li>{@code TERMINAL} → {@link ScmWebhookEventDao#markFailedTerminal}
 *       </ul>
 * </ol>
 *
 * <p>Wired to the timer subsystem at boot: a {@code TimerService} entry calls {@link #tick} every
 * {@link #DEFAULT_INTERVAL}. The service is stateless and tick-idempotent — multiple controllers
 * running concurrent ticks is safe because each row update is conditional.
 *
 * <p>This service does NOT decide what "the SCM provider" is — that's an {@link ScmProvider} value
 * derived from {@link ScmWebhookEventRow#provider}. String-to-enum mapping fails LOUD (throws
 * {@code IllegalArgumentException}) so a corrupt row is surfaced rather than silently retried with
 * the wrong provider — per CONSTITUTION §"No stringly-typed cross-module discriminators".
 */
public final class WebhookRetryService {

  private static final Logger LOGGER = Logger.getLogger(WebhookRetryService.class.getName());

  /** Default sweeper cadence — operators tune via Settings when wired through boot. */
  public static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(30);

  /** Cap on the exponential-backoff delay; keeps a "stuck" event from sliding to never-retry. */
  static final Duration MAX_BACKOFF = Duration.ofMinutes(15);

  private final ScmWebhookEventDao dao;
  private final WebhookHandler handler;
  private final int maxAttempts;
  private final int batchSize;
  private final Duration baseBackoff;

  /**
   * @param maxAttempts terminal-fail after this many attempts (must be ≥ 1). Below the cap the
   *     sweeper re-schedules with exponential back-off.
   * @param batchSize max rows per tick — bounds DB load on a backlog burst.
   * @param baseBackoff first retry delay; subsequent delays double up to {@link #MAX_BACKOFF}.
   */
  public WebhookRetryService(
      @NonNull ScmWebhookEventDao dao,
      @NonNull WebhookHandler handler,
      int maxAttempts,
      int batchSize,
      @NonNull Duration baseBackoff) {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be >= 1, got " + maxAttempts);
    }
    if (batchSize < 1) {
      throw new IllegalArgumentException("batchSize must be >= 1, got " + batchSize);
    }
    if (baseBackoff.isNegative() || baseBackoff.isZero()) {
      throw new IllegalArgumentException("baseBackoff must be positive, got " + baseBackoff);
    }
    this.dao = dao;
    this.handler = handler;
    this.maxAttempts = maxAttempts;
    this.batchSize = batchSize;
    this.baseBackoff = baseBackoff;
  }

  /**
   * Single sweeper tick. Returns the per-outcome counters so callers (e.g. {@code
   * ReconcileMetrics}) can publish a Prometheus delta.
   */
  @NonNull
  public TickStats tick(@NonNull Instant now) {
    List<ScmWebhookEventRow> due = dao.findDuePending(now, batchSize);
    int processed = 0;
    int retried = 0;
    int terminal = 0;
    for (ScmWebhookEventRow row : due) {
      ScmProvider provider;
      try {
        provider = ScmProvider.valueOf(row.provider.toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException badProvider) {
        // Don't endlessly retry a row we can't even classify. Mark terminal so an operator sees it.
        LOGGER.log(
            Level.WARNING,
            "[webhook-retry] row {0} has unknown provider {1} — marking terminal",
            new Object[] {row.id, row.provider});
        dao.markFailedTerminal(row.id, "unknown provider: " + row.provider);
        terminal++;
        continue;
      }

      WebhookHandler.Result result;
      try {
        result = handler.handle(provider, row);
      } catch (RuntimeException unexpected) {
        // Manifesto: handler MUST NOT throw, but we belt-and-brace the sweeper so one rogue
        // handler can't kill the loop. Treat as RETRY with the message preserved.
        LOGGER.log(
            Level.WARNING,
            "[webhook-retry] handler threw for row " + row.id + " — treating as RETRY",
            unexpected);
        result =
            WebhookHandler.Result.retry(
                unexpected.getMessage() == null
                    ? unexpected.getClass().getSimpleName()
                    : unexpected.getMessage());
      }

      switch (result.outcome()) {
        case SUCCESS -> {
          dao.markProcessed(row.id);
          processed++;
        }
        case TERMINAL -> {
          dao.markFailedTerminal(row.id, result.error());
          terminal++;
        }
        case RETRY -> {
          int nextAttempt = row.attempts + 1;
          if (nextAttempt >= maxAttempts) {
            dao.markFailedTerminal(
                row.id, "retry-budget-exhausted after " + nextAttempt + ": " + result.error());
            terminal++;
          } else {
            Duration backoff = backoffFor(nextAttempt);
            dao.markFailedWithRetry(row.id, result.error(), now.plus(backoff));
            retried++;
          }
        }
      }
    }
    return new TickStats(due.size(), processed, retried, terminal);
  }

  /** Exponential back-off: {@code base × 2^(attempt-1)}, capped at {@link #MAX_BACKOFF}. */
  @NonNull
  Duration backoffFor(int nextAttempt) {
    long ms = baseBackoff.toMillis();
    // Shift cap: 2^30 ms ≈ 12 days; the MAX_BACKOFF cap clamps long before overflow.
    int shift = Math.min(nextAttempt - 1, 30);
    long scaled = ms << shift;
    if (scaled < 0 || scaled > MAX_BACKOFF.toMillis()) {
      return MAX_BACKOFF;
    }
    return Duration.ofMillis(scaled);
  }

  /** Per-tick counters surfaced for metrics. */
  public record TickStats(int considered, int processed, int retried, int terminal) {}
}
