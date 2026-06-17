package io.adaptiq.titan.trigger.engine;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.store.TitanStores;
import java.util.function.Consumer;

/**
 * Db-backed {@link TriggerStore} — runs the engine's per-owner work inside a locked Titan
 * transaction (design/50, Tier 3).
 *
 * <p>The {@code FOR UPDATE} lock on the {@code titan.jobs} row is the multi-controller coalescing
 * mutex (design/50 D4): a second controller's engine tick blocks here until the first commits, then
 * sees the advanced last-fired time and finds nothing due. If the job row has been deleted since
 * enumeration, {@code work} is not invoked.
 */
public final class DbTriggerStore implements TriggerStore {

  private final TitanStores stores;

  public DbTriggerStore(@NonNull TitanStores stores) {
    this.stores = stores;
  }

  @Override
  public void inLockedScope(@NonNull TriggerOwner owner, @NonNull Consumer<TriggerScope> work) {
    DbTriggerOwner dbOwner = (DbTriggerOwner) owner;
    long jobId = dbOwner.job().id();
    if (jobId == 0) {
      return;
    }
    stores.withTransaction(
        conn -> {
          if (stores.jobs().lockForUpdate(conn, jobId)) {
            work.accept(new DbTriggerScope(conn, stores, jobId));
          }
          return null;
        });
  }
}
