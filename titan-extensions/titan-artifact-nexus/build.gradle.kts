/*
 * titan-artifact-nexus — ArtifactStore backend for Nexus 3 raw repos (design/49).
 * Uses JDK HttpClient only — no extra HTTP dependency.
 */

plugins {
    id("titan.java-library")
}

group = "io.adaptiq.titan"
version = "1.0.0-rc1"

dependencies {
    implementation(project(":titan-pipeline-model"))
    compileOnly(libs.spotbugs.annotations)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.junit)
    // The Nexus IT needs jakarta.servlet-api on its runtime classpath.
    "integrationTestRuntimeOnly"(libs.jakarta.servlet.api)
}
