package io.adaptiq.titan.api;

import io.quarkus.test.junit.QuarkusTestProfile;

/**
 * Quarkus test profile for {@link ApiSurfaceIT}.
 *
 * <p>Activates the {@code postgres-it} config profile, which sets {@code
 * quarkus.datasource.db-kind=postgresql} at augmentation time (build-time fixed property). Without
 * this, all {@code @QuarkusTest} classes share the default test augmentation that bakes in {@code
 * db-kind=h2}, and {@link PostgresTestResource}'s runtime URL override is rejected by the H2
 * driver.
 *
 * <p>Quarkus re-augments the application once per distinct profile, so unit tests (default {@code
 * test} profile, H2) and {@link ApiSurfaceIT} ({@code postgres-it} profile, PostgreSQL) run against
 * independently augmented applications.
 */
public class PostgresItProfile implements QuarkusTestProfile {

  @Override
  public String getConfigProfile() {
    return "postgres-it";
  }
}
