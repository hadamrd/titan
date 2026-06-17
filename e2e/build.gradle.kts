/*
 * e2e — end-to-end test placeholder (Pivot Phase 1).
 *
 * Real E2E scenarios (spinning up titan-server + workers against a live
 * Postgres instance) will be added in Phase 4 (M4). This module exists
 * so the Gradle include() is wired up and the :e2e:integrationTest task
 * can be exercised in CI from day one.
 */

plugins {
    id("titan.java-library")
}

group = "io.adaptiq.titan"
version = "1.0.0-rc1"

dependencies {
    "integrationTestImplementation"(libs.junit.jupiter)
}
