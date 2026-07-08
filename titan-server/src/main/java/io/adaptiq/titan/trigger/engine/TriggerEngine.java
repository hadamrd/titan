package io.adaptiq.titan.trigger.engine;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.trigger.SchedulerSettings;
import io.adaptiq.titan.trigger.TriggerEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The trigger firing engine — one Quarkus {@code @Scheduled} bean that drives every registered
 * {@link TriggerSubsystem} (design/50, Tier 3; design/51; design/52).
 *
 * <p>This class is intentionally thin: it supplies the wall clock, the registered subsystems and
 * filters, and the bounded {@link TriggerEvaluator} — then hands off to {@link TriggerDispatch},
 * where the testable algorithm lives. Two ingress paths feed it:
 *
 * <ul>
 *   <li>{@link #tick()} — the periodic poll, at {@code quarkus.scheduler.titan.trigger.every}
 *       (default {@code 5s}; a 60 s cadence is too long for a 1-minute cron-grained check, so the
 *       Quarkus default is shorter — set the property to override);
 *   <li>{@link #deliver(TriggerEvent)} — an on-demand evaluation when a push event arrives from a
 *       {@link TriggerSource} or a webhook endpoint (design/51 D1).
 * </ul>
 *
 * <p>Both honour the {@link SchedulerSettings#paused() pause} kill-switch. Per-subsystem failures
 * are isolated so one bad subsystem cannot silence the others.
 */
@ApplicationScoped
public class TriggerEngine {

  private static final Logger LOGGER = Logger.getLogger(TriggerEngine.class.getName());

  @Inject Instance<TriggerSubsystem> subsystems;

  @Inject Instance<TriggerFilter> filterBeans;

  private final AtomicBoolean running = new AtomicBoolean(false);

  /**
   * Bounds every {@code Trigger.evaluate()} so a hung trigger cannot freeze a pass (design/52 D3).
   */
  private final TriggerEvaluator evaluator =
      new BoundedTriggerEvaluator(() -> SchedulerSettings.current().triggerEvaluationTimeout());

  /**
   * Periodic poll. Cadence is overridable via {@code quarkus.scheduler.titan.trigger.every}.
   * Defensive {@code SKIP} concurrent-execution policy + an internal guard against re-entrant
   * programmatic calls.
   */
  @Scheduled(
      every = "{quarkus.scheduler.titan.trigger.every:5s}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  public void tick() {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    try {
      if (SchedulerSettings.current().paused()) {
        return;
      }
      tickAt(Instant.now());
    } catch (RuntimeException e) {
      LOGGER.log(Level.SEVERE, "[trigger] engine tick failed", e);
    } finally {
      running.set(false);
    }
  }

  /**
   * Run one periodic evaluation pass at the given instant. Package-visible so a test can drive a
   * tick with a controlled clock; production calls it from {@link #tick()} with {@code now()}.
   */
  void tickAt(@NonNull Instant now) {
    List<TriggerFilter> filters = toList(filterBeans);
    for (TriggerSubsystem subsystem : subsystems) {
      try {
        TriggerDispatch.evaluate(subsystem, filters, now, null, evaluator);
      } catch (RuntimeException e) {
        LOGGER.log(
            Level.SEVERE, "[trigger] subsystem " + subsystem.getClass().getName() + " failed", e);
      }
    }
  }

  /**
   * Run an on-demand evaluation pass for a pushed event (design/51). Every owner is evaluated with
   * the event in scope; a trigger that does not recognise it returns {@code SKIP}. A no-op while
   * paused.
   */
  public void deliver(@NonNull TriggerEvent event) {
    if (SchedulerSettings.current().paused()) {
      LOGGER.log(Level.FINE, "[trigger] engine paused — dropping event {0}", event.kind());
      return;
    }
    List<TriggerFilter> filters = toList(filterBeans);
    for (TriggerSubsystem subsystem : subsystems) {
      try {
        TriggerDispatch.evaluate(subsystem, filters, Instant.now(), event, evaluator);
      } catch (RuntimeException e) {
        LOGGER.log(
            Level.SEVERE,
            "[trigger] subsystem "
                + subsystem.getClass().getName()
                + " failed handling event "
                + event.kind(),
            e);
      }
    }
  }

  private static <T> List<T> toList(Instance<T> instance) {
    List<T> out = new ArrayList<>();
    for (T t : instance) {
      out.add(t);
    }
    return out;
  }
}
