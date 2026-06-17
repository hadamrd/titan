/*
 * titan-step-api — thin step SPI (design/42 §4.1).
 *
 * Production code depends on JDK only.
 * The TCK (StepHandlerTck) + shared test fixtures live under src/testFixtures/
 * so titan-worker and third-party step jars can consume them via
 * testImplementation(testFixtures(project(":titan-step-api"))).
 */

plugins {
    id("titan.java-library")
    `java-test-fixtures`
}

group = "io.adaptiq.titan"
version = "1.0.0-rc1"

dependencies {
    testFixturesImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.jupiter)
}

java {
    withSourcesJar()
}
