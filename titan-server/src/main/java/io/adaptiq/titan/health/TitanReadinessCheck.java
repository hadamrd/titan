package io.adaptiq.titan.health;

import io.adaptiq.titan.store.TitanStores;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * SmallRye readiness check — verifies DB connectivity via a lightweight probe. Exposed at {@code
 * /q/health/ready} by the Quarkus SmallRye Health extension.
 *
 * <p>Constructor-injection only — {@link TitanStores} wired by Quarkus ARC.
 */
@Readiness
@ApplicationScoped
public class TitanReadinessCheck implements HealthCheck {

  private final TitanStores stores;

  TitanReadinessCheck(TitanStores stores) {
    this.stores = stores;
  }

  @Override
  public HealthCheckResponse call() {
    try {
      // Lightweight probe: list jobs with limit 0 — exercises the connection pool
      // without reading any data.
      stores.jobs().listAll();
      return HealthCheckResponse.named("titan-readiness").up().build();
    } catch (Exception e) {
      return HealthCheckResponse.named("titan-readiness")
          .down()
          .withData("error", e.getMessage())
          .build();
    }
  }
}
