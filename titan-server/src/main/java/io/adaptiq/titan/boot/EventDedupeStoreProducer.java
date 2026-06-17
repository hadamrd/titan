package io.adaptiq.titan.boot;

import io.adaptiq.titan.scm.reconcile.EventDedupeStore;
import io.adaptiq.titan.scm.reconcile.JdbiEventDedupeStore;
import io.adaptiq.titan.store.TitanStores;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/**
 * CDI producer for the single, application-scoped {@link EventDedupeStore} — the durable, DB-backed
 * idempotency boundary over {@code titan.scm_event_seen} (#1118).
 *
 * <p>One instance for the lifetime of the application so the Pulsar poll scanner (#T2) and the
 * webhook hot-path share the SAME claim table: a change dispatched once never re-enqueues on the
 * next scheduled tick. A fresh per-tick store would re-build the same change forever — this
 * producer is what makes the scheduled scanner safe.
 */
@ApplicationScoped
public class EventDedupeStoreProducer {

  @Produces
  @ApplicationScoped
  public EventDedupeStore eventDedupeStore(TitanStores stores) {
    return new JdbiEventDedupeStore(stores);
  }
}
