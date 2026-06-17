/*
 * titan-server — standalone Titan HTTP server (Pivot M2).
 *
 * HTTP layer: Quarkus 3 (REST + CDI + SmallRye OpenAPI/Health + Agroal + Flyway).
 * Data layer: JDBI 3 (unchanged — this is an HTTP-layer migration only).
 *
 * Source sets:
 *   src/main/       — production code
 *   src/test/       — @QuarkusTest unit tests (H2 via H2StoresProducer) +
 *                     @QuarkusTest ITs (Postgres via PostgresTestResource) +
 *                     plain @Testcontainers engine smoke tests (EngineSmokIT)
 *   src/integrationTest/  — reserved for @QuarkusIntegrationTest (packaged artifact tests);
 *                           owned by the Quarkus Gradle plugin.
 *
 * No JaCoCo floor set yet — coverage baselines are still being established.
 */

import java.time.Instant

plugins {
    // Quarkus MUST apply first — it creates the `integrationTest` source set,
    // and titan.java-library guards against double-creation (see convention plugin).
    alias(libs.plugins.quarkus)
    id("titan.java-library")
}

// SpotBugs exclusions for pre-existing NP findings in TitanOrchestrator
// (false positives: @Nullable JDBI params). Not touched by the API surface PR.
spotbugs {
    excludeFilter.set(file("spotbugs-exclude.xml"))
}

group = "io.adaptiq.titan"
version = "1.0.0-rc1"

dependencies {
    // Quarkus BOM — pins ALL quarkus extension versions consistently.
    implementation(enforcedPlatform(libs.quarkus.bom))

    // ── Quarkus extensions ────────────────────────────────────────────────────
    implementation(libs.quarkus.arc)
    implementation(libs.quarkus.rest)
    implementation(libs.quarkus.rest.jackson)
    implementation(libs.quarkus.smallrye.openapi)
    implementation(libs.quarkus.smallrye.health)
    implementation(libs.quarkus.hibernate.validator)
    implementation(libs.quarkus.agroal)
    implementation(libs.quarkus.jdbc.postgresql)
    implementation(libs.quarkus.flyway)
    implementation(libs.quarkus.oidc)
    implementation(libs.quarkus.scheduler)
    // Structured JSON console logger — see docs/ops/logging.md (PR 1 of logging split).
    implementation(libs.quarkus.logging.json)
    // OpenTelemetry — traces+metrics export, gated on TITAN_OTEL_ENDPOINT (#314, PR 2). When the
    // env var is unset, the extension is on the classpath but no exporter is configured (no-op);
    // the OTel SDK is also responsible for the W3C traceparent propagation we stamp onto
    // task_queue rows so workers can re-attach to the originating span.
    implementation(libs.quarkus.opentelemetry)
    // Quarkus Cache (Caffeine-backed) — see io.adaptiq.titan.cache for the three hot-path caches.
    implementation(libs.quarkus.cache)
    // Elytron BcryptUtil — password-style hashing of personal access tokens (#434). Ships the
    // canonical BCrypt impl + verify helper; no new third-party crypto dep needed.
    implementation(libs.quarkus.elytron.security.common)
    // Micrometer + Prometheus exposition on /q/metrics (#649). Bound to the
    // CONSTITUTION §6 observability contract: discriminated `status` labels, no
    // plaintext-secret labels. Toggle via `quarkus.micrometer.export.prometheus.enabled`.
    implementation(libs.quarkus.micrometer.registry.prometheus)

    // ── Serialization (Jackson modules beyond what quarkus-rest-jackson provides) ──
    implementation(libs.jackson.databind)
    // YAML binding for the externally-configurable failure-signature set (#1105).
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.spotbugs.annotations)

    // ── Data access (JDBI stays — data layer is NOT being migrated) ───────────
    implementation(libs.jdbi3.core)
    implementation(libs.jdbi3.sqlobject)

    // ── Internal modules ──────────────────────────────────────────────────────
    // titan-trigger-api: pure-Java trigger SPI (Trigger, TriggerCodec, CronSchedule, the
    // vendored cron grammar, …). The ANTLR runtime + Jackson are exposed as
    // `api` from that module so titan-server picks them up transitively.
    implementation(project(":titan-trigger-api"))

    // titan-secrets-api: SecretsBackend SPI surface (#548) — moved out of
    // titan-server so extensions (titan-secrets-vault, AWS/GCP/Azure) bind
    // against a slim JDK-only jar instead of dragging in the Quarkus runtime.
    // `api` so the host's CredentialsService — which exposes these types in
    // its method signatures — re-exports them to anyone depending on
    // titan-server.
    api(project(":titan-secrets-api"))

    // titan-db-core: TitanDataException + Database substrate. No external
    // transitive coupling since #328 removed DatabaseConfig.
    implementation(project(":titan-db-core"))
    implementation(project(":titan-pipeline-model"))

    // ── External: GitHub API client (discovery/GitHubRepoSource, GitHub App) ──
    // kohsuke github-api wraps every REST surface Titan needs for the GitHub App
    // golden path (#874, replaces the hand-rolled HTTP client). Its JWTTokenProvider
    // signs the App-as-itself JWT internally using jjwt — three jjwt artifacts must
    // be on the runtime classpath (API + impl + jackson binding) or kohsuke fails
    // with NoClassDefFoundError: io/jsonwebtoken/Jwts.
    implementation(libs.github.api)
    runtimeOnly(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)

    // ── Test ──────────────────────────────────────────────────────────────────
    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.rest.assured)
    testImplementation(libs.quarkus.test.security)
    testImplementation(libs.quarkus.test.security.oidc)
    testImplementation(libs.quarkus.test.keycloak.server)
    testImplementation(libs.jackson.datatype.jsr310)
    // H2 for unit-test CDI alternative (FakeTitanStores / H2StoresProducer)
    testImplementation(libs.h2)
    // Quarkus H2 JDBC extension — needed so Quarkus can boot the datasource
    // against H2 in test mode (application.properties sets db-kind=h2 in tests)
    testImplementation(libs.quarkus.jdbc.h2)
    // Hikari: used by FakeTitanStores (H2 pool) and EngineSmokIT (direct Postgres pool)
    testImplementation(libs.hikaricp.server)
    // Testcontainers: ApiSurfaceIT (via PostgresTestResource) + EngineSmokIT
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    // Property-based tests for ExpressionEvaluator (relocated from titan-plugin, Wave 2a).
    testImplementation(libs.jqwik)
    // WireMock — GitHub API stubbing for GithubAppService / GithubAppApi tests (#832).
    testImplementation(libs.wiremock)

    // ── integrationTest (relocated chaos rig + engine ITs from titan-plugin, Wave 2a) ──
    // The integrationTestImplementation configuration extendsFrom testImplementation per the
    // titan.java-library convention plugin, so testcontainers/hikari/jackson/junit are already
    // visible here. We only add the toxiproxy testcontainer module + flyway runtime artifacts
    // that the relocated chaos rig and flow ITs need.
    "integrationTestImplementation"(libs.testcontainers.toxiproxy)
    "integrationTestImplementation"(libs.flyway.core)
    "integrationTestImplementation"(libs.flyway.postgresql)
    // titan-keyprovider-infisical: InfisicalBackendIT (#1227) drives the InfisicalSecretsBackend
    // through the real CredentialResolver with a fake SecretProvider, proving a pipeline
    // credentials: binding resolves an Infisical-sourced secret. Test-scope only.
    "integrationTestImplementation"(project(":titan-extensions:titan-keyprovider-infisical"))
    // Reuse unit-test helpers (e.g. FakeTitanStores — an H2-backed TitanStores) from src/test so
    // ITs that need a real DAO layer without a Postgres container can build one inline (#1287).
    "integrationTestImplementation"(sourceSets["test"].output)
}

// titan-plugin (Maven) resolves :titan-server from ~/.m2, which means we publish a regular
// jar of titan-server's compile classpath. Gradle's publication validator rightly flags
// `enforcedPlatform(quarkus.bom)` as a "shouldn't leak to consumers" smell — but here the
// Quarkus BOM is part of the runtime contract we want titan-plugin to see (it gets the same
// pinned versions for any Quarkus transitive), so we suppress the validation explicitly.
tasks.withType<GenerateModuleMetadata>().configureEach {
    suppressedValidationErrors.add("enforced-platform")
}

// ── Build-info properties (Forge-loop tick #48) ───────────────────────────────
// Writes META-INF/titan-build-info.properties to the production resources at
// compile time. InfoApi reads it off the classpath to serve GET /api/v1/info.
// Falls back to "unknown" when the file is missing (IDE / non-Gradle classpath).
val titanBuildInfoFile =
    layout.buildDirectory.file("generated/resources/build-info/META-INF/titan-build-info.properties")

val writeTitanBuildInfo = tasks.register("writeTitanBuildInfo") {
    val versionProp = project.version.toString()
    val outFile = titanBuildInfoFile
    outputs.file(outFile)
    outputs.upToDateWhen { false } // git SHA can change between builds without source changes
    doLast {
        val commit =
            try {
                val proc =
                    ProcessBuilder("git", "rev-parse", "--short=7", "HEAD")
                        .directory(rootDir)
                        .redirectErrorStream(true)
                        .start()
                proc.waitFor()
                if (proc.exitValue() == 0) proc.inputStream.bufferedReader().readText().trim()
                else "unknown"
            } catch (e: Exception) {
                "unknown"
            }
        val builtAt = Instant.now().toString()
        val file = outFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            "# Generated by writeTitanBuildInfo — DO NOT EDIT\n" +
                "version=$versionProp\n" +
                "commit=$commit\n" +
                "builtAt=$builtAt\n"
        )
    }
}

sourceSets {
    named("main") {
        resources.srcDir(
            files(titanBuildInfoFile.map { it.asFile.parentFile.parentFile }).builtBy(writeTitanBuildInfo)
        )
    }
}

tasks.named("processResources") { dependsOn(writeTitanBuildInfo) }

// Dev Services for Keycloak manages the container lifecycle — no manual image-tag wiring needed.
// Override the Keycloak image used by Dev Services via the standard Quarkus property if needed:
//   quarkus.keycloak.devservices.image-name=quay.io/keycloak/keycloak:26.0
tasks.withType<Test> {
    val keycloakImage =
        System.getenv("KEYCLOAK_DOCKER_IMAGE") ?: "quay.io/keycloak/keycloak:26.0"
    systemProperty("quarkus.keycloak.devservices.image-name", keycloakImage)
}

// The Quarkus plugin compiles its generated sources (gRPC stubs, config) in a
// separate `compileQuarkusGeneratedSourcesJava` task whose output `compileJava` and
// the test compile consume, but it does not declare that ordering. Under
// `org.gradle.parallel=true` Gradle's implicit-dependency validation fails a clean
// `gradle check`. Declare the ordering explicitly so a fresh checkout builds.
listOf("compileJava", "compileTestJava").forEach { name ->
    tasks.named(name) { mustRunAfter("compileQuarkusGeneratedSourcesJava") }
}
