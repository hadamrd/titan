plugins {
    `kotlin-dsl`
    // Spotless here so the repo's pre-commit gate (`:build-logic:spotlessCheck`)
    // resolves once build-logic carries first-party Kotlin sources (the Flyway
    // migration-version guard added for #1158).
    alias(libs.plugins.spotless)
}

dependencies {
    implementation(libs.plugins.spotless.get().let { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" })
    implementation(libs.plugins.spotbugs.get().let { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" })

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// Format only first-party Kotlin (`io/adaptiq/titan/gradle/**`). The precompiled
// convention scripts (`*.gradle.kts`) predate this and use a different style — we
// deliberately leave them untouched to keep this PR scoped to #1158.
spotless {
    kotlin {
        target("src/main/kotlin/io/adaptiq/titan/**/*.kt", "src/test/kotlin/io/adaptiq/titan/**/*.kt")
        ktfmt().googleStyle()
        endWithNewline()
        trimTrailingWhitespace()
    }
}
