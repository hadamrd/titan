package io.adaptiq.titan.trigger;

import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.UUID;

/**
 * A scheduling trigger — the unit of "when should this owner act" (design/50, Tier 2).
 *
 * <p>Concrete trigger types subclass this and ship a companion {@link TriggerDescriptor} discovered
 * via JDK {@link java.util.ServiceLoader}.
 *
 * <p>Each trigger carries a stable, minted {@link #getId() id} so its runtime state (last-fired
 * time) survives config round-trips even as other triggers are added or removed.
 */
public abstract class Trigger {

  private final String id;

  /**
   * @param id the stable trigger id; a blank or {@code null} value mints a fresh UUID — a trigger
   *     newly added in the config UI has no id until its first save.
   */
  protected Trigger(@CheckForNull String id) {
    this.id = (id == null || id.isBlank()) ? UUID.randomUUID().toString() : id;
  }

  /** The stable trigger id — the key under which runtime state is persisted (design/50 D6). */
  @NonNull
  public final String getId() {
    return id;
  }

  /**
   * The DTO discriminator persisted into {@code config_json} and dispatched on by {@link
   * TriggerCodec} (e.g. {@code "cron"}). Must be unique across concrete trigger types.
   */
  @NonNull
  public abstract String getType();

  /**
   * Evaluate this trigger against the given context — a pure function of the trigger's config and
   * {@code ctx}. The firing engine ({@code TriggerEngine}) calls this once per tick.
   */
  @NonNull
  public abstract TriggerOutcome evaluate(@NonNull TriggerContext ctx);

  /**
   * Write this trigger's type-specific fields into {@code node} for persistence (design/50 D6). The
   * {@code type} and {@code id} keys are written by {@link TriggerCodec}; an implementation adds
   * only its own fields. The inverse is {@link TriggerDescriptor#readState}. A trigger with no
   * state of its own leaves {@code node} untouched.
   */
  public abstract void writeState(@NonNull ObjectNode node);
}
