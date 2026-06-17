/*
 * Titan — Gradle settings (single-build, standalone).
 *
 * Phase 3 deleted titan-plugin and removed Maven entirely; #328 dropped the
 * last legacy plugin-host coupling (titan-db-core's DatabaseConfig). The
 * legacy maven repo and the plugin-host/credentials deps went with it.
 */

pluginManagement {
    includeBuild("build-logic")
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        mavenLocal()
    }
    // gradle/libs.versions.toml is auto-discovered as the "libs" catalog.
}

rootProject.name = "titan"

// ── Core product modules ──────────────────────────────────────────────────────
include(
    "titan-step-api",
    "titan-trigger-api",
    "titan-secrets-api",
    "titan-pipeline-model",
    "titan-db-core",
    "titan-worker",
    "titan-server",
    "e2e",
)

// ── Pluggable extensions ──────────────────────────────────────────────────────
// First-party extension modules live under titan-extensions/. Each is a
// ServiceLoader-discoverable jar — drop on the classpath, configure, done. New
// extensions go here; out-of-tree ones follow the same layout in their own repo.
include(
    "titan-extensions:titan-artifact-s3",
    "titan-extensions:titan-artifact-nexus",
    "titan-extensions:titan-keyprovider-infisical",
    "titan-extensions:titan-secrets-vault",
)
// Gradle's default project-path → directory mapping puts e.g. titan-artifact-s3
// at ./titan-extensions/titan-artifact-s3 already; no explicit projectDir
// overrides needed.

// ── Build cache ───────────────────────────────────────────────────────────────
//
// The CI / shared-dev story: an HTTP remote build cache fans out task outputs
// across machines, so a branch you've never touched is warm because someone
// else (or CI on trunk) already built it. Until the S3 / Develocity backend is
// provisioned, the remote cache is wired but disabled — flip the env var to
// turn it on without touching this file.
//
//   TITAN_BUILD_CACHE_URL=https://build-cache.example.com/
//   TITAN_BUILD_CACHE_USERNAME=...  (optional)
//   TITAN_BUILD_CACHE_PASSWORD=...  (optional)
//
buildCache {
    local {
        isEnabled = true
    }
    val remoteUrl = System.getenv("TITAN_BUILD_CACHE_URL")
    if (!remoteUrl.isNullOrBlank()) {
        remote<HttpBuildCache> {
            url = uri(remoteUrl)
            isEnabled = true
            isPush = System.getenv("CI") == "true"  // only CI pushes; devs pull-only
            val user = System.getenv("TITAN_BUILD_CACHE_USERNAME")
            val pass = System.getenv("TITAN_BUILD_CACHE_PASSWORD")
            if (!user.isNullOrBlank() && !pass.isNullOrBlank()) {
                credentials {
                    username = user
                    password = pass
                }
            }
        }
    }
}
