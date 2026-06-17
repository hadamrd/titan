/*
 * titan-worker — standalone pull-based execution agent.
 *
 * Packaged as a runnable fat jar via the shadow plugin (replaces maven-shade).
 * JaCoCo floors from Maven baseline (2026-05-18): LINE 58%, BRANCH 56%.
 * Set to baseline minus ~5 points per the Maven config (floor at 0.50).
 */

plugins {
    id("titan.java-library")
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

// SpotBugs exclusions for the Groovy-closure UMAC false positives in
// GroovyStepBinding and the forward-compatibility UC in StepHandlerDiscovery
// (see spotbugs-exclude.xml for the per-rule justification). Mirrors the
// :titan-server wiring — the exclude file was on disk but unloaded on T73 #815.
spotbugs {
    excludeFilter.set(file("spotbugs-exclude.xml"))
}

group = "io.adaptiq.titan"
version = "1.0.0-rc1"

// JaCoCo coverage floors (preserved from pom.xml)
ext["jacocoMinLineCoverage"]   = "0.50"
ext["jacocoMinBranchCoverage"] = "0.50"

dependencies {
    implementation(project(":titan-step-api"))
    implementation(project(":titan-pipeline-model"))
    implementation(project(":titan-extensions:titan-keyprovider-infisical"))
    implementation(project(":titan-extensions:titan-artifact-s3"))
    implementation(project(":titan-extensions:titan-artifact-nexus"))

    implementation(libs.postgresql.worker)
    implementation(libs.hikaricp.worker)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.slf4j.api)
    implementation(libs.groovy)
    implementation(libs.ant)
    implementation(libs.docker.java.core)
    implementation(libs.docker.java.transport)

    // ── Logging — Logback JSON via Logstash encoder (#315). Replaces the
    // slf4j-simple binding. logback-classic IS the SLF4J binding at runtime;
    // any other binding (slf4j-simple, slf4j-jdk14, slf4j-jcl, slf4j-log4j12)
    // on the classpath would race for primary and pick one at random. Hence
    // the explicit excludes below.
    runtimeOnly(libs.logback.classic)
    runtimeOnly(libs.logstash.logback.encoder)

    // ── OpenTelemetry SDK — manual autoconfigure boot (#315). Server uses
    // the Quarkus OTel extension; worker has no Quarkus runtime so it embeds
    // the SDK directly. Span continuation reads task_queue.trace_parent
    // (populated by the server in PR #382 / closes #314) and re-attaches the
    // claimed task's execution to the originating trace.
    implementation(libs.opentelemetry.api)
    implementation(libs.opentelemetry.sdk)
    implementation(libs.opentelemetry.sdk.extension.autoconfigure)
    runtimeOnly(libs.opentelemetry.exporter.otlp)

    testImplementation(libs.junit.jupiter)
    // OTel SDK explicitly on the test classpath so WorkerTracingTest can
    // build a programmatic OpenTelemetrySdk + SdkTracerProvider for the
    // span-continuation assertions (the SDK is otherwise pulled only via
    // implementation, which test sources also see — listed explicitly here
    // for clarity).
    testImplementation(libs.opentelemetry.sdk)
    // TCK (StepHandlerTck) + shared fixtures live under titan-step-api's
    // testFixtures source set — consumed via the standard java-test-fixtures
    // plugin (Gradle 8.x+). Replaces the older cross-project sourceSets
    // access that breaks under Gradle 9.
    testImplementation(testFixtures(project(":titan-step-api")))
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
    // logback-classic is the runtime SLF4J binding (above); pulling it onto the test
    // compile classpath lets StepHandlerDiscoveryTest attach an in-memory ListAppender to
    // assert the per-directory summary log line (#474).
    testImplementation(libs.logback.classic)

    // ITs moved to integrationTest source set
    "integrationTestImplementation"(libs.testcontainers.postgresql)
    "integrationTestImplementation"(libs.testcontainers.junit)
}

// ── Shadow / fat-jar ──────────────────────────────────────────────────────────
tasks.shadowJar {
    archiveBaseName.set("titan-worker")
    archiveClassifier.set("")
    manifest {
        attributes["Main-Class"] = "io.adaptiq.titan.worker.TitanWorker"
    }
    // Drop stale JAR signatures from signed deps (Bouncy Castle via docker-java)
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    // Merge ServiceLoader registries so JDBC drivers and SPI providers are found
    mergeServiceFiles()
}

tasks.named("assemble") {
    dependsOn(tasks.shadowJar)
}
