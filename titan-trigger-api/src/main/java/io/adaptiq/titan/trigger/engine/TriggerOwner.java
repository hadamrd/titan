package io.adaptiq.titan.trigger.engine;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.trigger.Trigger;
import java.util.List;

/**
 * A schedulable thing, as the trigger engine sees it (design/50, Tier 3 SPI).
 *
 * <p>A consumer adapts each of its schedulable entities to this interface — Titan wraps a {@code
 * Job}, a future CD consumer would wrap a deployment pipeline. The engine knows nothing of jobs or
 * pipelines; it only ever sees {@code TriggerOwner}s. Implementations are short-lived value
 * adapters created per engine tick.
 */
public interface TriggerOwner {

  /** A stable identifier for this owner, unique within its {@link TriggerSubsystem}. */
  @NonNull
  String ownerId();

  /** Whether scheduling is currently active for this owner (a disabled job is skipped). */
  boolean schedulingEnabled();

  /**
   * A stable per-owner string seeding {@code H} cron hashing — typically the job's full name, so
   * {@code H}-slots are spread across owners but stable for each (design/50 D3).
   */
  @NonNull
  String hashSeed();

  /** This owner's configured triggers. */
  @NonNull
  List<Trigger> triggers();
}
