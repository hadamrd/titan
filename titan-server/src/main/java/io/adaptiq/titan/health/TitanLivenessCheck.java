package io.adaptiq.titan.health;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

/**
 * SmallRye liveness check — reports UP as long as the JVM is running. Exposed at {@code
 * /q/health/live} by the Quarkus SmallRye Health extension.
 *
 * <p>Replaces the former manual {@code /healthz} route that simply returned {@code
 * {"status":"ok"}}.
 */
@Liveness
@ApplicationScoped
public class TitanLivenessCheck implements HealthCheck {

  @Override
  public HealthCheckResponse call() {
    return HealthCheckResponse.named("titan-liveness").up().build();
  }
}
