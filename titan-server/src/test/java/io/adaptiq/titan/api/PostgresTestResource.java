package io.adaptiq.titan.api;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Quarkus test resource that boots a Testcontainers PostgreSQL container and configures the Agroal
 * datasource properties so the Quarkus application under test connects to it.
 *
 * <p>Quarkus calls {@link #start()} before the application context starts, so the datasource URL is
 * available for injection into {@code application.properties} before Flyway runs migrations.
 */
public class PostgresTestResource implements QuarkusTestResourceLifecycleManager {

  @SuppressWarnings("resource") // lifecycle managed by Quarkus test infrastructure
  private static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("titan_api_it");

  @Override
  public Map<String, String> start() {
    PG.start();
    return Map.of(
        "quarkus.datasource.jdbc.url", PG.getJdbcUrl(),
        "quarkus.datasource.username", PG.getUsername(),
        "quarkus.datasource.password", PG.getPassword());
  }

  @Override
  public void stop() {
    PG.stop();
  }
}
