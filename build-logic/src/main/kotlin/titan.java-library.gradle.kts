/*
 * titan.java-library — convention plugin applied by every Gradle module.
 *
 * Provides:
 *   - Java 21 toolchain
 *   - Three source sets: main / test (unit, cached) / integrationTest (Testcontainers, not cached)
 *   - Three tasks:    test / integrationTest / check (depends on both)
 *   - Spotless (Google Java Format 1.22.0, same as titan-plugin Maven config)
 *   - SpotBugs (effort=MAX, threshold=HIGH — same as Maven config)
 *   - JaCoCo (report + configurable floor via ext property jacocoMinLineCoverage / jacocoMinBranchCoverage)
 *   - maven-publish so each module publishes to mavenLocal (Gradle→Maven bridge)
 */

plugins {
    `java-library`
    id("com.diffplug.spotless")
    id("com.github.spotbugs")
    `maven-publish`
    jacoco
}

// ── Java toolchain ────────────────────────────────────────────────────────────
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
}

// ── Source sets ───────────────────────────────────────────────────────────────
// Guard: if the Quarkus plugin has already created the integrationTest source
// set (it does this unconditionally in QuarkusPlugin.createSourceSets), reuse it
// rather than creating a duplicate and crashing.
val integrationTest: SourceSet =
    if (sourceSets.findByName("integrationTest") != null) {
        sourceSets["integrationTest"].also {
            it.compileClasspath += sourceSets.main.get().output
            it.runtimeClasspath += sourceSets.main.get().output
        }
    } else {
        sourceSets.create("integrationTest") {
            compileClasspath += sourceSets.main.get().output
            runtimeClasspath += sourceSets.main.get().output
        }
    }

configurations["integrationTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

// ── Tasks ─────────────────────────────────────────────────────────────────────
val integrationTestTask = tasks.register<Test>("integrationTest") {
    description = "Runs Testcontainers integration tests (not cached)."
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnitPlatform()
    // ITs spin up Docker containers — disable the build cache for this task.
    outputs.upToDateWhen { false }
    shouldRunAfter(tasks.test)
}

tasks.test {
    useJUnitPlatform()
}

// Gradle 8.x quirk: the JUnit Jupiter engine depends on a JUnit Platform launcher
// at a specific version; the version bundled with Gradle itself can drift from
// the Jupiter version we declare, producing "OutputDirectoryCreator not available"
// at test discovery time. Forcing junit-platform-launcher on the test runtime
// classpath lets Gradle resolve them to a single aligned version.
dependencies {
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    "integrationTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

// NOTE: `check` deliberately does NOT depend on integrationTest. Per the
// three-tier design in docs/PIVOT.md, `./gradlew check` is the fast feedback
// loop (unit tests + spotless + spotbugs + jacoco). Testcontainers integration
// tests run explicitly via `./gradlew integrationTest` — wiring them into
// `check` would force every dev to boot Docker for every change.

// ── Spotless ──────────────────────────────────────────────────────────────────
spotless {
    java {
        targetExclude("build/**")
        removeUnusedImports()
        googleJavaFormat("1.22.0").style("GOOGLE")
        endWithNewline()
        trimTrailingWhitespace()
    }
}

// Make check depend on spotless
tasks.named("check") {
    dependsOn("spotlessCheck")
}

// ── Flyway migration version guard ────────────────────────────────────────────
// Fails the build if two Flyway migrations share a version. Flyway refuses to
// start on a duplicate ("Found more than one migration with version N"), which
// CrashLooped titan-server on the rig and blocked a deploy (#1157). Wired into
// `check` so a duplicate can never merge. The task is a no-op (SkipWhenEmpty)
// for modules without a migration directory, so applying it convention-wide is
// free — any future module that ships migrations is protected automatically.
val checkMigrationVersions =
    tasks.register<io.adaptiq.titan.gradle.CheckMigrationVersionsTask>("checkMigrationVersions") {
        migrationFiles.from(
            fileTree("src/main/resources") {
                // Match every Flyway location: `…/migration/` and PG-specific
                // `…/migration-postgresql/`. Versioned (`V…`) files only —
                // repeatable (`R__…`) migrations have no version.
                include("**/migration/V*.sql", "**/migration-postgresql/V*.sql")
            }
        )
    }

tasks.named("check") {
    dependsOn(checkMigrationVersions)
}

// ── SpotBugs ──────────────────────────────────────────────────────────────────
spotbugs {
    effort.set(com.github.spotbugs.snom.Effort.MAX)
    reportLevel.set(com.github.spotbugs.snom.Confidence.HIGH)
    ignoreFailures.set(false)
}

// Only analyse main sources (not test sources)
tasks.named("spotbugsTest") { enabled = false }
tasks.named("spotbugsIntegrationTest") { enabled = false }

// ── JaCoCo ────────────────────────────────────────────────────────────────────
tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

// Per-module coverage floors — modules override via:
//   extensions.extraProperties["jacocoMinLineCoverage"]   = "0.75"
//   extensions.extraProperties["jacocoMinBranchCoverage"] = "0.65"
// Default: no floor (0.0) so modules without explicit config don't fail.
val jacocoMinLine   = (project.findProperty("jacocoMinLineCoverage")   as String? ?: "0.0").toBigDecimal()
val jacocoMinBranch = (project.findProperty("jacocoMinBranchCoverage") as String? ?: "0.0").toBigDecimal()

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value   = "COVEREDRATIO"
                minimum = jacocoMinLine
            }
            limit {
                counter = "BRANCH"
                value   = "COVEREDRATIO"
                minimum = jacocoMinBranch
            }
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

// ── maven-publish (Gradle → Maven cross-build bridge) ─────────────────────────
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
    repositories {
        mavenLocal()
    }
}
