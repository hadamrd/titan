/*
 * titan-db-core — schema-neutral JDBC pool + Flyway migration runner.
 *
 * Database configuration is held by Database.Config (a plain holder).
 */

plugins {
    id("titan.java-library")
}

group = "io.adaptiq"
version = "1.0.0-rc1"

dependencies {
    implementation(libs.h2)
    implementation(libs.hikaricp.server)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    // SpotBugs @NonNull/@Nullable annotations — declared explicitly.
    implementation(libs.spotbugs.annotations)

    testImplementation(libs.junit.jupiter)
}
