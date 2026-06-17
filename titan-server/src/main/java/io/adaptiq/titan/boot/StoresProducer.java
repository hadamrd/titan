package io.adaptiq.titan.boot;

import io.adaptiq.titan.store.TitanStores;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import javax.sql.DataSource;

/**
 * CDI producer that constructs a {@link TitanStores} from the Agroal-managed {@link DataSource}.
 *
 * <p>Quarkus ARC wires the {@link DataSource} (configured via {@code quarkus.datasource.*} in
 * {@code application.properties}) into this producer at startup. The resulting {@link TitanStores}
 * is {@code @ApplicationScoped} — one instance for the lifetime of the application.
 *
 * <p>Constructor-injection discipline: API resources take {@link TitanStores} via constructor;
 * Quarkus CDI calls this producer to satisfy that dependency. No {@code @Inject} field injection
 * anywhere in the HTTP layer.
 */
@ApplicationScoped
public class StoresProducer {

  private final DataSource dataSource;

  /** Quarkus ARC injects the Agroal DataSource via this single-arg constructor. */
  StoresProducer(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  /**
   * Produce the application-scoped {@link TitanStores} bean.
   *
   * <p>Flyway migrations are run by the {@code quarkus-flyway} extension before the first
   * datasource use, so the schema is guaranteed to exist before this producer fires.
   */
  @Produces
  @ApplicationScoped
  public TitanStores titanStores() {
    return TitanStores.forDataSource(dataSource);
  }
}
