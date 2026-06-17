/*
 * titan-keyprovider-infisical — CredentialKeyProvider that fetches the
 * credential-sealing AES key from Infisical (design/39 §3.1).
 * Uses JDK HttpClient; only non-test dep is Jackson.
 */

plugins {
    id("titan.java-library")
}

group = "io.adaptiq.titan"
version = "1.0.0-rc1"

dependencies {
    implementation(project(":titan-pipeline-model"))
    // titan-secrets-api: the SecretsBackend SPI surface (SecretsBackend, Credential,
    // NewCredentialRequest, CredentialUpdate). InfisicalSecretsBackend (#1227) exposes the
    // existing InfisicalSecretProvider as a pipeline credential source. Slim, JDK-only — no
    // Quarkus runtime is dragged in, exactly like titan-secrets-vault binds it.
    implementation(project(":titan-secrets-api"))
    implementation(libs.jackson.databind)
    compileOnly(libs.spotbugs.annotations)

    testImplementation(project(":titan-secrets-api"))
    testImplementation(libs.junit.jupiter)
}
