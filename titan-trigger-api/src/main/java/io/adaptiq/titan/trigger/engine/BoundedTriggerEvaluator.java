package io.adaptiq.titan.trigger.engine;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.trigger.Trigger;
import io.adaptiq.titan.trigger.TriggerContext;
import io.adaptiq.titan.trigger.TriggerOutcome;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A {@link TriggerEvaluator} that bounds {@code Trigger.evaluate()} with a timeout (design/52 D3).
 *
 * <p>The review's worst failure mode: a trigger whose {@code evaluate()} hangs runs inside the
 * {@code FOR UPDATE} job-row lock, freezing the whole tick and holding the lock. This evaluator
 * runs the call on a worker thread and waits only {@code timeout}; on overrun it returns a {@code
 * SKIP}, interrupts the abandoned call, and lets the dispatch thread — and the lock — go free.
 *
 * <p>The timeout is read per call from a {@link Supplier} so a live config change applies without a
 * restart. A trigger that ignores interruption keeps its worker thread until it finishes on its
 * own; that thread leak is bounded only by fixing the offending trigger — the point here is to
 * protect the <em>engine</em>, not to forcibly kill arbitrary code.
 */
public final class BoundedTriggerEvaluator implements TriggerEvaluator {

  private static final Logger LOGGER = Logger.getLogger(BoundedTriggerEvaluator.class.getName());

  private final ExecutorService pool;
  private final Supplier<Duration> timeout;

  public BoundedTriggerEvaluator(@NonNull Supplier<Duration> timeout) {
    this.timeout = timeout;
    this.pool = Executors.newCachedThreadPool(daemonThreadFactory());
  }

  @Override
  @NonNull
  public TriggerOutcome evaluate(@NonNull Trigger trigger, @NonNull TriggerContext ctx) {
    Duration budget = timeout.get();
    Future<TriggerOutcome> future = pool.submit(() -> trigger.evaluate(ctx));
    try {
      return future.get(budget.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      future.cancel(true);
      LOGGER.log(
          Level.WARNING,
          "[trigger] trigger {0} evaluation exceeded {1} — treated as skip",
          new Object[] {trigger.getId(), budget});
      return TriggerOutcome.skip("evaluation exceeded the " + budget + " budget");
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException re) {
        throw re;
      }
      throw new IllegalStateException("trigger " + trigger.getId() + " evaluation failed", cause);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      future.cancel(true);
      return TriggerOutcome.skip("evaluation interrupted");
    }
  }

  @NonNull
  private static ThreadFactory daemonThreadFactory() {
    AtomicLong counter = new AtomicLong();
    return runnable -> {
      Thread thread = new Thread(runnable, "titan-trigger-eval-" + counter.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }
}
