/*
 * titan-secrets-api — slim SPI for pluggable Titan secrets backends (#548).
 *
 * Mirrors the titan-step-api / titan-trigger-api pattern: a JDK-only library
 * holding the SecretsBackend interface + its companion records / exception so
 * out-of-tree extensions (titan-secrets-vault, AWS/GCP/Azure backends) can
 * bind against the SPI without pulling in titan-server's Quarkus runtime.
 *
 * Production code MUST stay dependency-free (JDK + spotbugs annotations only).
 */

plugins {
    id("titan.java-library")
}

group = "io.adaptiq.titan"
version = "1.0.0-rc1"

dependencies {
    // SpotBugs @NonNull / @Nullable — used on the SPI surface (constitution
    // §style: edu.umd.cs.findbugs.annotations, NOT javax.annotation). The
    // annotations are CLASS-retention so they don't need to ship at runtime,
    // but consumers re-compiling against the SPI do see them, hence `api`.
    api(libs.spotbugs.annotations)

    testImplementation(libs.junit.jupiter)
}

java {
    withSourcesJar()
}
