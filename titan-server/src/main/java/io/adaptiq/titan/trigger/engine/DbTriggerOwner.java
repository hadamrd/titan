package io.adaptiq.titan.trigger.engine;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.job.Job;
import io.adaptiq.titan.trigger.Trigger;
import java.util.List;

/**
 * Adapts a Titan {@link Job} to the trigger engine's {@link TriggerOwner} SPI (design/50, Tier 3).
 *
 * <p>A short-lived value adapter, created fresh per engine tick by {@link DbTriggerSubsystem}.
 * {@link DbTriggerStore} casts {@code TriggerOwner} back to this type to reach the job — safe
 * because the same subsystem produced both.
 */
public final class DbTriggerOwner implements TriggerOwner {

  private final Job job;
  private final List<Trigger> triggers;

  public DbTriggerOwner(@NonNull Job job, @NonNull List<Trigger> triggers) {
    this.job = job;
    this.triggers = triggers;
  }

  @NonNull
  public Job job() {
    return job;
  }

  @Override
  @NonNull
  public String ownerId() {
    return Long.toString(job.id());
  }

  @Override
  public boolean schedulingEnabled() {
    return job.enabled();
  }

  @Override
  @NonNull
  public String hashSeed() {
    return job.fullName();
  }

  @Override
  @NonNull
  public List<Trigger> triggers() {
    return triggers;
  }
}
