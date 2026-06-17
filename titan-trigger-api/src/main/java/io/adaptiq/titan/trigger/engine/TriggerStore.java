package io.adaptiq.titan.trigger.engine;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Consumer;

/**
 * The persistence + locking SPI of the trigger engine (design/50, Tier 3).
 *
 * <p>A consumer implements this over its own storage. The single method runs {@code work} inside a
 * scope that is:
 *
 * <ul>
 *   <li><strong>locked</strong> — concurrent firing of the same owner, including from another
 *       controller, is serialized (Titan uses a {@code SELECT … FOR UPDATE} on the job row);
 *   <li><strong>transactional</strong> — every {@link TriggerScope} operation the engine performs
 *       inside the callback commits atomically, or rolls back together on error.
 * </ul>
 *
 * <p>If the owner has vanished (deleted between enumeration and locking), the implementation simply
 * does not invoke {@code work}.
 */
public interface TriggerStore {

  /** Run {@code work} against a freshly-opened locked, transactional scope for {@code owner}. */
  void inLockedScope(@NonNull TriggerOwner owner, @NonNull Consumer<TriggerScope> work);
}
