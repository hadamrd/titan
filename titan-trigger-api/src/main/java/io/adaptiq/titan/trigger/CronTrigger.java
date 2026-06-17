package io.adaptiq.titan.trigger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.trigger.cron.CronSchedule;
import java.time.Duration;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A {@link Trigger} that fires on a cron schedule (design/50, Tier 2) — the build-periodically
 * trigger, and the first concrete trigger type.
 *
 * <p>The cron string is interpreted by {@link CronSchedule}, i.e. by the vendored cron grammar:
 * {@code H} hashing, the {@code @daily}/{@code @hourly} aliases, the {@code TZ=} prefix and
 * multi-line specs are all supported.
 *
 * <p>The catch-up window — how far back a missed occurrence is honoured after a controller outage —
 * comes from {@link SchedulerSettings} (design/52); when a trigger's last fire predates that
 * window, the skipped occurrences are logged rather than dropped silently (design/52 D4).
 */
public class CronTrigger extends Trigger {

  private static final Logger LOGGER = Logger.getLogger(CronTrigger.class.getName());

  /** The DTO discriminator — see {@link TriggerCodec}. */
  public static final String TYPE = "cron";

  private final String spec;

  public CronTrigger(@CheckForNull String id, @CheckForNull String spec) {
    super(id);
    this.spec = spec == null ? "" : spec.trim();
  }

  @NonNull
  public String getSpec() {
    return spec;
  }

  @Override
  @NonNull
  public String getType() {
    return TYPE;
  }

  @Override
  @NonNull
  public TriggerOutcome evaluate(@NonNull TriggerContext ctx) {
    Duration catchUp = SchedulerSettings.current().catchUpWindow();
    CronSchedule schedule;
    try {
      schedule = CronSchedule.of(spec, ctx.hashSeed(), catchUp);
    } catch (IllegalArgumentException e) {
      return TriggerOutcome.skip("invalid cron spec: " + e.getMessage());
    }
    Instant lastFired = ctx.lastFiredAt();
    if (lastFired != null && lastFired.isBefore(ctx.now().minus(catchUp))) {
      LOGGER.log(
          Level.WARNING,
          "[trigger] cron trigger {0}: last fired at {1}, older than the {2} catch-up "
              + "window — scheduled occurrences during the gap were skipped "
              + "(controller outage?)",
          new Object[] {getId(), lastFired, catchUp});
    }
    return schedule.isDue(lastFired, ctx.now())
        ? TriggerOutcome.fire()
        : TriggerOutcome.skip("not due");
  }

  @Override
  public void writeState(@NonNull ObjectNode node) {
    node.put("spec", spec);
  }

  /**
   * {@link Trigger} descriptor for the cron trigger. Discovered via {@link
   * java.util.ServiceLoader}.
   */
  public static class DescriptorImpl extends TriggerDescriptor {

    @Override
    @NonNull
    public String triggerType() {
      return TYPE;
    }

    @Override
    @NonNull
    public Trigger readState(@CheckForNull String id, @NonNull JsonNode node) {
      return new CronTrigger(id, node.path("spec").asText(""));
    }
  }
}
