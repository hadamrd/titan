/*
 * titan-secrets-vault — SecretsBackend extension that resolves credentials
 * from HashiCorp Vault.
 *
 * Phase 1 (#475): SPI skeleton + ServiceLoader discovery.
 * Phase 2 (#549, this build): AppRole auth + KV v2 resolve via JDK HttpClient.
 *   Token auth + Kubernetes service-account auth are deferred follow-ups.
 *
 * Binds against the slim :titan-secrets-api module (#548 closed
 * the SPI-extraction follow-up) — SecretsBackend, Credential,
 * NewCredentialRequest, CredentialUpdate, CredentialNotFoundException — so
 * the extension is decoupled from titan-server's Quarkus runtime.
 */

plugins {
    id("titan.java-library")
}

group = "io.adaptiq.titan"
version = "1.0.0-rc1"

dependencies {
    // SPI surface — slim, JDK-only. SecretsBackend, Credential,
    // NewCredentialRequest, CredentialUpdate, CredentialNotFoundException all
    // live here since #548.
    implementation(project(":titan-secrets-api"))
    compileOnly(libs.spotbugs.annotations)

    // Jackson — parse Vault JSON responses (login token, KV v2 data). The
    // host (titan-server) already ships Jackson, so this is implementation,
    // not a new runtime dep on the controller classpath.
    implementation(libs.jackson.databind)

    testImplementation(project(":titan-secrets-api"))
    testImplementation(libs.junit.jupiter)

    // Integration test stack — Vault dev-mode in a GenericContainer.
    "integrationTestImplementation"(project(":titan-secrets-api"))
    // titan-server (compileOnly): VaultBackendSpiDiscoveryIT asserts the
    // in-tree DbEnvelopeBackend (which stays in titan-server) is discovered
    // alongside the Vault backend. Compile-only so the heavy Quarkus runtime
    // is not pulled into the integrationTest runtime classpath.
    "integrationTestCompileOnly"(project(":titan-server"))
    "integrationTestImplementation"(libs.junit.jupiter)
    "integrationTestImplementation"(libs.testcontainers.core)
    "integrationTestImplementation"(libs.testcontainers.junit)
    "integrationTestImplementation"(libs.jackson.databind)
}
