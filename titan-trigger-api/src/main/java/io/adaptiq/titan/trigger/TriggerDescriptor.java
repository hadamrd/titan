package io.adaptiq.titan.trigger;

import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Descriptor base for {@link Trigger}s (design/50, Tier 2).
 *
 * <p>Discovery uses JDK {@link ServiceLoader}: each concrete descriptor is registered in {@code
 * META-INF/services/io.adaptiq.titan.trigger.TriggerDescriptor}.
 *
 * <p>A descriptor carries the trigger type's <em>persistence</em> half: {@link #triggerType} is the
 * JSON discriminator and {@link #readState} reconstructs an instance from stored JSON. A new
 * trigger type is therefore fully self-describing — {@link TriggerCodec} dispatches through the
 * descriptor registry, so a third-party trigger plugin round-trips with no change to the trigger
 * module (design/50 — pluggable trigger types).
 */
public abstract class TriggerDescriptor {

  /** Every registered {@link Trigger} type — discovered via JDK {@link ServiceLoader}. */
  @NonNull
  public static List<TriggerDescriptor> all() {
    List<TriggerDescriptor> out = new ArrayList<>();
    for (TriggerDescriptor d : ServiceLoader.load(TriggerDescriptor.class)) {
      out.add(d);
    }
    return out;
  }

  /**
   * The JSON discriminator for this trigger type — must equal the {@link Trigger#getType()} of the
   * triggers this descriptor describes, and be unique across registered descriptors.
   */
  @NonNull
  public abstract String triggerType();

  /**
   * Reconstruct a trigger from its persisted JSON — the inverse of {@link Trigger#writeState}. The
   * {@code node} is the same object {@code writeState} wrote into (it still carries the {@code
   * type}/{@code id} keys, which an implementation ignores).
   *
   * @param id the persisted stable id; may be {@code null} for legacy/hand-written config, in which
   *     case the trigger mints a fresh one.
   * @param node the stored trigger object.
   */
  @NonNull
  public abstract Trigger readState(@CheckForNull String id, @NonNull JsonNode node);
}
